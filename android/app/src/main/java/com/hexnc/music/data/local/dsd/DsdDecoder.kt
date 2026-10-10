package com.hexnc.music.data.local.dsd

import java.io.InputStream

/**
 * Decimates a 1-bit DSD stream into 16-bit PCM.
 *
 * DSD is a sigma-delta bitstream: the audio lives in the *average* of its bits, and everything above
 * ~20 kHz is shaped quantization noise that must be filtered out before the stream may be resampled.
 * That is what this class does, in two steps:
 *
 *  1. each bit becomes +1 (one) or −1 (zero);
 *  2. a cascade of half-band low-pass filters decimates by 2 per stage, so a DSD64 stream
 *     (2 822 400 bit/s) lands on 176 400 Hz after four stages.
 *
 * Half-band filters are used because every second tap of an ideal one is zero — the response is flat
 * across the audio band, and the taps that do matter need only a short window. The decoder skips the
 * vanishing taps outright, so each output costs about eight multiplies instead of fifteen.
 *
 * Each stage keeps an overlap of the previous block, because a sample's output needs its neighbours
 * on both sides. Without that overlap the block boundaries would drop a sample every few thousand.
 *
 * The class is an [InputStream] over the decoded PCM, so the Windows Java Sound provider can hand it
 * straight to the transport while the Android side copies it into a WAV cache file. Streaming (as
 * opposed to decode-everything-then-return) matters: a five-minute DSD64 stereo track is ~100 MB of
 * PCM, and neither platform benefits from materialising that twice.
 */
internal class DsdPcmInputStream(
    private val info: DsdTrackInfo,
    private val source: InputStream,
    targetRateHz: Int,
) : InputStream() {

    /** Decimation factor: 16 for DSD64 → 176 400 Hz, 32 for DSD128, … */
    val factor: Int = decimationFactor(info.bitRateHz, targetRateHz)

    /** The rate the PCM comes out at, which is [DsdTrackInfo.bitRateHz] divided by [factor]. */
    val outputRateHz: Int = info.bitRateHz / factor

    val channels: Int = info.channels

    /**
     * PCM frames the container header promises.
     *
     * The decoder emits a hair fewer (the last half-window of the stream has no neighbours to filter
     * against), so this is an upper bound — which is exactly what a caller advertising a length
     * wants.
     */
    val frameLength: Long = info.bitsPerChannel / factor

    private val stages: Int = factor.countTrailingZeroBits()
    private val taps: FloatArray = halfBandTaps(HALF_BAND_TAPS)
    private val half: Int = taps.size / 2

    /** Samples a stage must carry into the next block; two windows' worth covers postponed outputs. */
    private val lookback: Int = half * 2

    /** The non-vanishing taps, so the convolution loop never multiplies by zero. */
    private val activeTaps: FloatArray = taps.filter { it != 0f }.toFloatArray()
    private val activeOffsets: IntArray = taps.indices.filter { taps[it] != 0f }.map { it - half }.toIntArray()

    /** DSF hands us whole 4096-byte blocks per channel; DFF is a flat byte-interleaved stream. */
    private val unitBytes: Int =
        if (info.container == DsdContainer.DSF) maxOf(info.blockSizePerChannel, 1) else DFF_UNIT_BYTES

    private val maxBits: Int = unitBytes * 8

    private val raw = ByteArray(unitBytes * channels)
    private val planes = Array(channels) { ByteArray(unitBytes) }
    private val bitValues = FloatArray(maxBits)
    private val stageInput = Array(channels) { Array(stages) { FloatArray(lookback + maxBits) } }
    private val stageOutput = Array(channels) { Array(stages) { FloatArray((lookback + maxBits) / 2 + 16) } }
    private val decoded = Array(channels) { FloatArray(maxBits / maxOf(factor, 1) + 64) }
    private val keepAlive = Array(channels) { Array(stages) { FloatArray(lookback) } }
    private val kept = Array(channels) { Array(stages) { IntArray(1) } }
    private val baseIndex = Array(channels) { Array(stages) { LongArray(1) } }
    private val nextOutput = Array(channels) { Array(stages) { LongArray(1) } }
    private val out = ByteArray(maxOf(16 * 1024, (maxBits / factor + 64) * channels * 2))

    private var bitsRemaining: Long = info.bitsPerChannel
    private var outStart = 0
    private var outEnd = 0
    private var finished = false

    override fun read(): Int {
        if (outStart >= outEnd && !fill()) return -1
        return out[outStart++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (outStart >= outEnd && !fill()) return -1
        val count = minOf(len, outEnd - outStart)
        System.arraycopy(out, outStart, b, off, count)
        outStart += count
        return count
    }

    override fun skip(n: Long): Long {
        var left = n
        while (left > 0L) {
            if (outStart >= outEnd && !fill()) break
            val take = minOf(left, (outEnd - outStart).toLong()).toInt()
            outStart += take
            left -= take
        }
        return n - left
    }

    override fun available(): Int = outEnd - outStart

    override fun close() {
        source.close()
    }

    // ------------------------------------------------------------------ decoding

    /** Decodes one more unit of DSD data into [out]; `false` once the container is exhausted. */
    private fun fill(): Boolean {
        while (!finished && bitsRemaining > 0L) {
            val want = unitBytes * channels
            val got = readUpTo(raw, want)
            if (got <= 0) {
                finished = true
                return false
            }
            // A truncated tail (or a partial block) is zero-filled rather than treated as an error:
            // the header's sample count caps how many bits are actually consumed below, and a file
            // whose last block is padded reads exactly as a well-formed one.
            if (got < want) java.util.Arrays.fill(raw, got, want, 0)

            when (info.container) {
                DsdContainer.DSF -> for (c in 0 until channels) {
                    System.arraycopy(raw, c * unitBytes, planes[c], 0, unitBytes)
                }

                // DFF interleaves whole bytes: frame i holds one byte per channel.
                DsdContainer.DFF -> for (i in 0 until unitBytes) {
                    val base = i * channels
                    for (c in 0 until channels) planes[c][i] = raw[base + c]
                }
            }

            val blockBits =
                if (bitsRemaining >= maxBits.toLong()) maxBits else bitsRemaining.toInt()
            var frames = 0
            for (c in 0 until channels) frames = decodeChannel(c, blockBits)
            bitsRemaining -= blockBits

            if (frames <= 0) continue
            var at = 0
            for (i in 0 until frames) {
                for (c in 0 until channels) {
                    val sample = (decoded[c][i] * 32_767f).toInt().coerceIn(-32_768, 32_767)
                    out[at++] = (sample and 0xFF).toByte()
                    out[at++] = ((sample shr 8) and 0xFF).toByte()
                }
            }
            outStart = 0
            outEnd = at
            return true
        }
        finished = true
        return false
    }

    /** @return how many PCM frames this channel produced for [bitCount] bits of DSD. */
    private fun decodeChannel(channel: Int, bitCount: Int): Int {
        val plane = planes[channel]
        var count = 0
        if (info.lsbFirst) {
            for (i in plane.indices) {
                if (count >= bitCount) break
                val byte = plane[i].toInt()
                for (k in 0 until 8) {
                    if (count >= bitCount) break
                    bitValues[count++] = if ((byte shr k) and 1 == 1) 1f else -1f
                }
            }
        } else {
            for (i in plane.indices) {
                if (count >= bitCount) break
                val byte = plane[i].toInt()
                for (k in 7 downTo 0) {
                    if (count >= bitCount) break
                    bitValues[count++] = if ((byte shr k) and 1 == 1) 1f else -1f
                }
            }
        }
        if (stages == 0) {
            System.arraycopy(bitValues, 0, decoded[channel], 0, count)
            return count
        }

        var input: FloatArray = bitValues
        var length = count
        var produced = 0
        for (stage in 0 until stages) {
            val work = stageInput[channel][stage]
            val carry = keepAlive[channel][stage]
            val keptCount = kept[channel][stage]
            val base = baseIndex[channel][stage]
            val next = nextOutput[channel][stage]
            System.arraycopy(carry, 0, work, 0, keptCount[0])
            System.arraycopy(input, 0, work, keptCount[0], length)
            val total = keptCount[0] + length
            val target = if (stage == stages - 1) decoded[channel] else stageOutput[channel][stage]

            produced = decimate(work, total, target, next, base)

            // Carry the tail of the combined buffer: the next block's outputs reach back into it,
            // and any output this block postponed needs it too.
            val keep = minOf(lookback, total)
            val from = total - keep
            System.arraycopy(work, from, carry, 0, keep)
            keptCount[0] = keep
            base[0] += from.toLong()

            input = target
            length = produced
        }
        return produced
    }

    /**
     * One half-band decimation stage: convolves the non-vanishing taps over [src] and keeps every
     * second output. Outputs whose window would reach past [length] are left for the next block.
     *
     * @param next `[0]` is the absolute index of the first output still owed; updated in place.
     * @param base `[0]` is the absolute index of `src[0]`.
     * @return how many outputs were written
     */
    private fun decimate(
        src: FloatArray,
        length: Int,
        dst: FloatArray,
        next: LongArray,
        base: LongArray,
    ): Int {
        var written = 0
        var n = (next[0] - base[0]).toInt().coerceAtLeast(0)
        while (n + half < length) {
            var sum = 0f
            for (t in activeTaps.indices) {
                val index = n + activeOffsets[t]
                // Only the first `half` samples of the whole stream look back past the start.
                if (index >= 0) sum += activeTaps[t] * src[index]
            }
            dst[written++] = sum
            n += 2
        }
        if (written > 0) next[0] = base[0] + (n - 2) + 2L
        return written
    }

    private fun readUpTo(buffer: ByteArray, count: Int): Int {
        var filled = 0
        while (filled < count) {
            val read = source.read(buffer, filled, count - filled)
            if (read < 0) break
            filled += read
        }
        return filled
    }

    private companion object {
        /** Taps per half-band stage. 15 keeps the stopband around −55 dB at a fraction of the cost. */
        const val HALF_BAND_TAPS = 15

        /** Bytes read per channel per pass for the flat DFF layout. */
        const val DFF_UNIT_BYTES = 4_096
    }
}

/**
 * The largest power-of-two divisor of [bitRateHz] that stays at or above [targetRateHz].
 *
 * Powers of two are what makes the half-band cascade possible; DSD64/128/256 all divide evenly onto
 * 176 400 Hz and 88 200 Hz (16/32/64).
 */
internal fun decimationFactor(bitRateHz: Int, targetRateHz: Int): Int {
    var factor = 1
    while (factor < 1 shl 12 && bitRateHz / (factor * 2) >= targetRateHz) factor *= 2
    return factor
}

/**
 * Windowed-sinc half-band low-pass, normalised so its DC gain is exactly 1.
 *
 * The cutoff sits at a quarter of the input rate — the Nyquist frequency of the decimated output.
 * Every even offset from the centre vanishes (the property that defines a half-band filter), so those
 * taps are set to exact zeros and the decoder skips them.
 */
internal fun halfBandTaps(length: Int = 15): FloatArray {
    require(length >= 3 && length % 2 == 1) { "half-band length must be odd and at least 3" }
    val half = length / 2
    val taps = FloatArray(length)
    var sum = 0.0
    for (i in 0 until length) {
        val offset = i - half
        val x = offset.toDouble()
        val sinc = if (x == 0.0) 0.5 else 0.5 * (Math.sin(Math.PI * 0.5 * x) / (Math.PI * 0.5 * x))
        val window = 0.54 + 0.46 * Math.cos(Math.PI * offset / half)
        val value = if (offset != 0 && offset % 2 == 0) 0.0 else sinc * window
        taps[i] = value.toFloat()
        sum += value
    }
    val normalise = (1.0 / sum).toFloat()
    for (i in 0 until length) taps[i] *= normalise
    return taps
}
