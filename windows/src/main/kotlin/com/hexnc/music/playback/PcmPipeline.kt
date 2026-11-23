package com.hexnc.music.playback

import java.util.Random
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * One way to feed the decoded source to the output device.
 *
 * The engine tries these best-first and opens a real line for each until one is accepted, because
 * `AudioSystem.isLineSupported` is optimistic about what Windows mixers actually render. Ordering is
 * therefore part of the contract, not an implementation detail; see [outputPlans].
 */
internal data class OutputPlan(
    val rate: Float,
    val bits: Int,
    val channels: Int,
)

/**
 * The output ladders to try for [source], best first.
 *
 * Order and rationale:
 *  1. The source's own rate if it is at or below [MAX_DEVICE_RATE_HZ] — no conversion at all, which
 *     is both the least work and the only path that cannot add filter ringing.
 *  2. The usual Windows consumer rates from [DEVICE_RATES] (48 kHz, then 44.1 kHz).
 *  3. Within each rate, the source's bit depth first and a 16-bit fallback second. A 24-bit file
 *     offered 24-bit PCM stays lossless; a device that refuses 24-bit still gets the same rate at
 *     16 bits, dithered by [PcmConverter], before the ladder drops the sample rate as well.
 *  4. For each (rate, bits) pair a mono source also offers a stereo duplicate *after* the
 *     same-channel plan, so a device that only accepts stereo is still reachable without ever
 *     changing the source's loudness.
 *
 * Bit depth is never raised above the source: a 16-bit file is not padded into a 24-bit container.
 * 24 is the highest depth the pipeline produces because that is the widest packed PCM format Windows
 * consumer mixers accept; anything above it stays 24-bit at the source rate and is dithered down on
 * the way out if the device cannot take it. Bit depths below 16 are widened to 16 because Java Sound
 * has no signed 8-bit output path and the device would refuse it.
 */
internal fun outputPlans(source: AudioFormat): List<OutputPlan> {
    val sourceChannels = source.channels.coerceAtLeast(1)
    val topBits = outputBitsFor(source)
    val bitDepths = if (topBits > MIN_OUTPUT_BITS) listOf(topBits, MIN_OUTPUT_BITS) else listOf(topBits)

    val rates = ArrayList<Float>(DEVICE_RATES.size + 1)
    val sourceRate = source.frameRate
    if (sourceRate in MIN_DEVICE_RATE_HZ..MAX_DEVICE_RATE_HZ) rates += sourceRate
    for (rate in DEVICE_RATES) {
        if (rates.none { abs(it - rate) < 0.5f }) rates += rate
    }

    val plans = ArrayList<OutputPlan>(rates.size * bitDepths.size * 2)
    for (rate in rates) {
        for (bits in bitDepths) {
            plans += OutputPlan(rate, bits, sourceChannels)
            if (sourceChannels == 1) plans += OutputPlan(rate, bits, 2)
        }
    }
    return plans
}

/**
 * The [AudioFormat] a device is opened with for [plan].
 *
 * Always little-endian signed PCM: that is the only layout every Windows mixer speaks, and the
 * converter emits exactly this layout, so no second conversion layer is needed.
 */
internal fun outputFormatOf(plan: OutputPlan): AudioFormat {
    require(plan.bits % 8 == 0) { "PCM bit depth must be byte aligned: ${plan.bits}" }
    require(plan.channels >= 1) { "a plan needs at least one channel: ${plan.channels}" }
    return AudioFormat(
        AudioFormat.Encoding.PCM_SIGNED,
        plan.rate,
        plan.bits,
        plan.channels,
        plan.channels * (plan.bits / 8),
        plan.rate,
        false,
    )
}

/**
 * The highest bit depth this pipeline may offer for [source], clamped to a packed 16/24-bit layout.
 *
 * A compressed container reports `sampleSizeInBits = -1` (an MP3 frame has no fixed sample size), so
 * "unknown" has to fall back to 16 rather than be compared as if it were a number — that comparison
 * once let `-1` through and produced a plan asking for a negative-depth PCM format.
 */
private fun outputBitsFor(source: AudioFormat): Int {
    val bits = source.sampleSizeInBits
    if (bits < MIN_OUTPUT_BITS || bits % 8 != 0) return MIN_OUTPUT_BITS
    return if (bits >= HIGH_OUTPUT_BITS) HIGH_OUTPUT_BITS else MIN_OUTPUT_BITS
}

/**
 * A streaming band-limited sample-rate converter.
 *
 * Why not linear interpolation: at 44.1 → 48 kHz, linear interpolation folds the imaging products
 * into the audible band at roughly -35 dB, which is audible as a metallic edge on cymbals. This is a
 * windowed-sinc (Hann) interpolator with 48 taps and a cutoff at the lower of the two Nyquist
 * frequencies, so downsampling is anti-aliased and upsampling is band-limited.
 *
 * The kernel is precomputed as a polyphase table (256 phases + an endpoint row) because evaluating
 * 48 sin/cos pairs per output sample costs a measurable fraction of a core at 192 kHz; the rows are
 * linearly blended across adjacent phases so the phase resolution is effectively continuous. Each row
 * is normalised to unity DC gain: without that, a partial tap span would make the level drift with
 * fractional position.
 *
 * The class is deliberately streaming and resumable: [process] may be called with arbitrarily sized
 * blocks and the fractional read position, filter history and (for [flush]) end padding all persist
 * across calls. That is what lets [PcmConverter] reuse one instance across an entire track.
 */
internal class SincResampler(
    private val sourceRate: Double,
    private val targetRate: Double,
    private val taps: Int = 48,
) {
    init {
        require(sourceRate > 0.0) { "source rate must be positive: $sourceRate" }
        require(targetRate > 0.0) { "target rate must be positive: $targetRate" }
        require(taps >= MIN_TAPS && taps % 2 == 0) { "taps must be even and at least $MIN_TAPS: $taps" }
    }

    /** Source frames consumed per output frame. */
    private val step: Double = sourceRate / targetRate

    /** One half of the filter support, in source frames. */
    private val half: Int = taps / 2

    /** Normalised cutoff: below 1 when downsampling, which is what removes aliasing. */
    private val cutoff: Double = min(1.0, targetRate / sourceRate)

    private val kernel: DoubleArray = buildKernel()

    /**
     * Input samples in flight, shifted so index 0 is the oldest sample still needed.
     *
     * It starts with [half] zeros: those are the "before the beginning" samples a symmetric FIR
     * needs, and they make the first output frame line up with the first input frame instead of
     * delaying the track by half a filter.
     */
    private var buffer = DoubleArray(half)
    private var count = half
    private var pos = half.toDouble()

    private var flushLimit = -1.0
    private var flushed = false

    private fun buildKernel(): DoubleArray {
        val table = DoubleArray((PHASES + 1) * taps)
        for (phase in 0..PHASES) {
            val fraction = phase.toDouble() / PHASES
            val base = phase * taps
            var sum = 0.0
            for (tap in 0 until taps) {
                // Tap `tap` reads input index center - half + 1 + tap. Measured from the
                // fractional output position, its distance in source frames is what the kernel wants.
                val value = kernelAt((tap - half + 1) - fraction)
                table[base + tap] = value
                sum += value
            }
            if (sum != 0.0) {
                for (tap in 0 until taps) table[base + tap] /= sum
            }
        }
        return table
    }

    /** Hann-windowed sinc, in source-frame units. */
    private fun kernelAt(distance: Double): Double {
        if (abs(distance) >= half) return 0.0
        val window = 0.5 * (1.0 + cos(PI * distance / half))
        val angle = PI * cutoff * distance
        val sinc = if (abs(angle) < 1e-12) 1.0 else sin(angle) / angle
        return cutoff * sinc * window
    }

    /**
     * Converts [inputFrames] frames from [input] and writes at most `output.size` frames to [output].
     *
     * Returns the number produced, which can be zero when the block is shorter than the filter's
     * look-ahead. The remaining input is retained, so the next call continues where this one stopped.
     * A final [flush] is required to emit the tail that is still inside the filter.
     */
    fun process(input: FloatArray, inputFrames: Int, output: FloatArray): Int {
        require(inputFrames in 0..input.size) { "inputFrames=$inputFrames outside 0..${input.size}" }
        if (inputFrames == 0 || output.isEmpty()) return 0
        append(input, inputFrames)
        return produce(output, Double.POSITIVE_INFINITY)
    }

    /**
     * Emits the samples still held inside the filter at end of stream, zero-padding the look-ahead.
     *
     * Call until it returns 0; only the first call pads, so calling it repeatedly is harmless. Total
     * output across [process] and [flush] is `ceil(inputFrames * targetRate / sourceRate)` frames,
     * i.e. the resampled length with no extra delay.
     */
    fun flush(output: FloatArray): Int {
        if (flushed) return 0
        if (flushLimit < 0.0) {
            flushLimit = count.toDouble()
            appendZeros(half * 2)
        }
        val produced = produce(output, flushLimit)
        if (produced < output.size) flushed = true
        return produced
    }

    private fun produce(output: FloatArray, limit: Double): Int {
        var produced = 0
        while (produced < output.size) {
            if (pos >= limit) break
            val center = floor(pos).toInt()
            val start = center - half + 1
            // The leading pad and compaction guarantee start >= 1 while real input is being read;
            // after the final flush there is nothing left to do.
            if (start < 0 || center + half >= count) break
            val fraction = pos - center
            val scaled = fraction * PHASES
            var phase = scaled.toInt()
            if (phase >= PHASES) phase = PHASES - 1
            val blend = scaled - phase
            val row = phase * taps
            val next = row + taps
            var value = 0.0
            for (tap in 0 until taps) {
                val weight = kernel[row + tap] + (kernel[next + tap] - kernel[row + tap]) * blend
                value += buffer[start + tap] * weight
            }
            output[produced++] = value.toFloat()
            pos += step
        }
        compact()
        return produced
    }

    private fun compact() {
        val drop = floor(pos).toInt() - half + 1
        if (drop <= 0) return
        if (drop >= count) {
            // Only reachable after the end-of-stream flush has consumed the padded tail.
            count = 0
            pos -= drop
            return
        }
        System.arraycopy(buffer, drop, buffer, 0, count - drop)
        count -= drop
        pos -= drop
    }

    private fun append(input: FloatArray, frames: Int) {
        ensureCapacity(count + frames)
        for (index in 0 until frames) buffer[count + index] = input[index].toDouble()
        count += frames
    }

    private fun appendZeros(frames: Int) {
        ensureCapacity(count + frames)
        buffer.fill(0.0, count, count + frames)
        count += frames
    }

    private fun ensureCapacity(needed: Int) {
        if (needed <= buffer.size) return
        var size = maxOf(buffer.size * 2, needed)
        if (size < MIN_BUFFER) size = MIN_BUFFER
        buffer = buffer.copyOf(size)
    }
}

/**
 * Quantises interleaved float samples to little-endian signed PCM of [bits] bits.
 *
 * [frames] is the frame count; the channel count is inferred from `samples.size / frames`. The float
 * domain is nominally [-1, 1], but resampling overshoots it at transients and a filter can ring past
 * full scale, so every value is clamped to the representable range instead of wrapping around into a
 * full-scale pop. Rounding is to nearest; truncation would bias every sample toward zero.
 *
 * With [dither] enabled, triangular noise of ±1 LSB is added before rounding: the difference of two
 * independent uniform randoms in [-0.5, 0.5] LSB. TPDF has zero mean and a flat spectrum, so the
 * quantisation error is decorrelated from the signal (no idle tones on quiet passages) and the total
 * error stays within ±1.5 LSB. [random] is injected rather than global so tests are reproducible and
 * two conversions can deliberately differ.
 */
internal fun quantize(
    samples: FloatArray,
    frames: Int,
    bits: Int,
    dither: Boolean,
    random: Random,
): ByteArray {
    require(bits in 8..32 && bits % 8 == 0) { "unsupported bit depth: $bits" }
    require(frames >= 0) { "frames must not be negative: $frames" }
    val channels = if (frames == 0) 0 else {
        require(samples.size % frames == 0) {
            "samples.size=${samples.size} is not a whole number of $frames-frame blocks"
        }
        samples.size / frames
    }

    val bytesPerSample = bits / 8
    val output = ByteArray(frames * channels * bytesPerSample)
    val scale = (1L shl (bits - 1)).toDouble()
    val maximum = (1L shl (bits - 1)) - 1L
    val minimum = -(1L shl (bits - 1))

    var write = 0
    for (index in 0 until frames * channels) {
        var value = samples[index].toDouble() * scale
        if (dither) value += random.nextDouble() - random.nextDouble()
        var remaining = Math.round(value).coerceIn(minimum, maximum)
        for (byte in 0 until bytesPerSample) {
            output[write++] = (remaining and 0xFFL).toByte()
            remaining = remaining shr 8
        }
    }
    return output
}

/**
 * Converts a decoded PCM stream into the format a device accepted.
 *
 * This replaces Java Sound's `AudioSystem.getAudioInputStream(target, source)` chain, which always
 * went through 16-bit and used the platform's own rate converter. The converter here:
 *
 *  - decodes PCM_SIGNED 8/16/24/32 (either endianness), PCM_UNSIGNED and PCM_FLOAT;
 *  - resamples with [SincResampler] per channel when the rates differ, keeping the resampler state
 *    across [read] calls so a track is filtered continuously rather than in unrelated blocks;
 *  - duplicates a mono source into stereo at identical level (never +3 dB / never a sum), because the
 *    plan ladder prefers the source channel count and only duplicates when the device demands it;
 *  - applies TPDF dither only when the bit depth is actually reduced (24→16, float→16/24, 32→24),
 *    so a 24-bit passthrough is not needlessly noised;
 *  - rounds and clamps to the target range after filtering, so a resampler overshoot cannot wrap.
 *
 * [read] mirrors `AudioInputStream.read`: it fills up to `buffer.size` bytes, returns -1 once the
 * source and the resampler tail are exhausted, and never returns 0 for a non-empty buffer. The
 * fractional position, filter history and dither sequence persist between calls, which is what makes
 * the result independent of the caller's buffer size. [seed] makes the dither reproducible; the
 * engine passes a fresh seed per track, tests pass a fixed one.
 */
internal class PcmConverter(
    val source: AudioFormat,
    val target: AudioFormat,
    seed: Long = 0L,
) {
    init {
        require(source.sampleRate > 0f) { "source rate must be positive: ${source.sampleRate}" }
        require(target.sampleRate > 0f) { "target rate must be positive: ${target.sampleRate}" }
        require(source.channels >= 1) { "source needs at least one channel: ${source.channels}" }
        require(target.channels >= 1) { "target needs at least one channel: ${target.channels}" }
        require(target.encoding == AudioFormat.Encoding.PCM_SIGNED) {
            "target must be PCM_SIGNED: ${target.encoding}"
        }
        require(target.sampleSizeInBits in 8..32 && target.sampleSizeInBits % 8 == 0) {
            "unsupported target depth: ${target.sampleSizeInBits}"
        }
        require(source.sampleSizeInBits in 8..64 && source.sampleSizeInBits % 8 == 0) {
            "unsupported source depth: ${source.sampleSizeInBits}"
        }
        require(SUPPORTED_ENCODINGS.contains(source.encoding)) {
            "unsupported source encoding: ${source.encoding}"
        }
    }

    private val sourceChannels: Int = source.channels
    private val targetChannels: Int = target.channels
    private val sourceSampleBytes: Int = source.sampleSizeInBits / 8
    private val targetSampleBytes: Int = target.sampleSizeInBits / 8
    private val sourceFrameSize: Int = source.frameSize.takeIf { it > 0 }
        ?: (sourceChannels * sourceSampleBytes)
    private val targetFrameSize: Int = targetChannels * targetSampleBytes

    /** True only when bits are actually lost; a widening or same-depth pass stays clean. */
    private val dither: Boolean = source.sampleSizeInBits > target.sampleSizeInBits

    private val random = Random(seed)

    private val resamplers: Array<SincResampler>? =
        if (abs(source.sampleRate.toDouble() - target.sampleRate.toDouble()) > 1e-3) {
            Array(sourceChannels) {
                SincResampler(source.sampleRate.toDouble(), target.sampleRate.toDouble(), RESAMPLER_TAPS)
            }
        } else {
            null
        }

    private val chunkBytes = ByteArray(CHUNK_FRAMES * sourceFrameSize)
    private val decoded = FloatArray(CHUNK_FRAMES * sourceChannels)
    private val planarIn = Array(sourceChannels) { FloatArray(CHUNK_FRAMES) }
    private val outCapacity: Int = maxOf(
        CHUNK_FRAMES,
        ceil(CHUNK_FRAMES * target.sampleRate.toDouble() / source.sampleRate.toDouble()).toInt(),
    ) + 2
    private val planarOut = Array(sourceChannels) { FloatArray(outCapacity) }
    private val mixed = FloatArray(outCapacity * targetChannels)

    private var pending = EMPTY_BYTES
    private var pendingPos = 0
    private var eof = false
    private var zeroReads = 0
    private var tailDone = false

    /** Bytes of a frame split across two [AudioInputStream.read] calls; kept so no sample is lost. */
    private var leftover = 0

    /**
     * Reads converted bytes into [buffer] (named `source` to mirror `AudioInputStream.read`).
     *
     * The byte count is always a whole number of target frames, except for a buffer smaller than one
     * frame, for which 0 is returned. -1 means the source and the filter tail are both exhausted.
     */
    fun read(source: AudioInputStream, buffer: ByteArray): Int {
        require(buffer.isNotEmpty()) { "buffer must not be empty" }
        val limit = buffer.size - buffer.size % targetFrameSize
        if (limit <= 0) return 0
        var written = 0
        while (written < limit) {
            if (pendingPos >= pending.size) {
                if (!fill(source)) break
            }
            val copy = minOf(limit - written, pending.size - pendingPos)
            System.arraycopy(pending, pendingPos, buffer, written, copy)
            pendingPos += copy
            written += copy
        }
        return if (written > 0) written else -1
    }

    private fun fill(stream: AudioInputStream): Boolean {
        while (true) {
            if (eof) {
                if (tailDone) return false
                tailDone = true
                return emitTail()
            }
            val read = stream.read(chunkBytes, leftover, chunkBytes.size - leftover)
            if (read == 0) {
                // Vorbis' converted stream reports 0 for its first couple of calls while it primes
                // its internal buffer; only -1 means the source is really exhausted.
                if (++zeroReads < MAX_ZERO_READS) continue
                val failure = IllegalStateException("decoder stalled: ${stream.format}")
                zeroReads = 0
                throw failure
            }
            if (read < 0) {
                // A trailing partial frame has no complete sample to decode; it is dropped rather
                // than mistaken for the start of a real frame.
                leftover = 0
                eof = true
                continue
            }
            zeroReads = 0
            val total = leftover + read
            val frames = total / sourceFrameSize
            leftover = total % sourceFrameSize
            if (frames <= 0) {
                // Still less than one frame: keep the bytes and read again.
                continue
            }
            produce(frames)
            if (leftover > 0) {
                System.arraycopy(chunkBytes, frames * sourceFrameSize, chunkBytes, 0, leftover)
            }
            if (pending.isNotEmpty()) return true
        }
    }

    private fun produce(frames: Int) {
        decode(frames)
        val targetFrames: Int
        val engines = resamplers
        if (engines == null) {
            targetFrames = mixInterleaved(decoded, frames, mixed)
        } else {
            split(frames)
            var produced = outCapacity
            for (channel in 0 until sourceChannels) {
                val count = engines[channel].process(planarIn[channel], frames, planarOut[channel])
                produced = min(produced, count)
            }
            targetFrames = mixPlanar(planarOut, produced, mixed)
        }
        queue(targetFrames)
    }

    private fun emitTail(): Boolean {
        val engines = resamplers ?: return false
        var produced = outCapacity
        for (channel in 0 until sourceChannels) {
            produced = min(produced, engines[channel].flush(planarOut[channel]))
        }
        if (produced <= 0) return false
        val frames = mixPlanar(planarOut, produced, mixed)
        queue(frames)
        return pending.isNotEmpty()
    }

    private fun queue(frames: Int) {
        if (frames <= 0) {
            pending = EMPTY_BYTES
            pendingPos = 0
            return
        }
        val samples = FloatArray(frames * targetChannels)
        System.arraycopy(mixed, 0, samples, 0, samples.size)
        pending = quantize(samples, frames, target.sampleSizeInBits, dither, random)
        pendingPos = 0
    }

    // ------------------------------------------------------------------ decoding

    private fun decode(frames: Int) {
        var read = 0
        var write = 0
        val strideSkip = sourceFrameSize - sourceChannels * sourceSampleBytes
        for (frame in 0 until frames) {
            for (channel in 0 until sourceChannels) {
                decoded[write++] = readSample(read)
                read += sourceSampleBytes
            }
            read += strideSkip
        }
    }

    private fun readSample(offset: Int): Float = when (source.encoding) {
        AudioFormat.Encoding.PCM_SIGNED -> signedSample(offset)
        AudioFormat.Encoding.PCM_UNSIGNED -> unsignedSample(offset)
        AudioFormat.Encoding.PCM_FLOAT -> floatSample(offset)
        else -> throw IllegalArgumentException("unsupported encoding: ${source.encoding}")
    }

    private fun signedSample(offset: Int): Float = when (sourceSampleBytes) {
        1 -> chunkBytes[offset].toFloat() / 128f
        2 -> readShort(offset).toFloat() / 32_768f
        3 -> readInt24(offset).toFloat() / 8_388_608f
        4 -> readInt(offset).toFloat() / 2_147_483_648f
        else -> throw IllegalArgumentException("unsupported PCM width: ${source.sampleSizeInBits} bits")
    }

    private fun unsignedSample(offset: Int): Float = when (sourceSampleBytes) {
        1 -> ((chunkBytes[offset].toInt() and 0xFF) - 128).toFloat() / 128f
        2 -> ((readShort(offset).toInt() and 0xFFFF) - 32_768).toFloat() / 32_768f
        3 -> (readInt24Unsigned(offset) - 8_388_608).toFloat() / 8_388_608f
        4 -> {
            val value = (readInt(offset).toLong() and 0xFFFF_FFFFL) - 2_147_483_648L
            value.toFloat() / 2_147_483_648f
        }
        else -> throw IllegalArgumentException("unsupported PCM width: ${source.sampleSizeInBits} bits")
    }

    private fun floatSample(offset: Int): Float = when (sourceSampleBytes) {
        4 -> Float.fromBits(readInt(offset))
        8 -> Double.fromBits(readLong(offset)).toFloat()
        else -> throw IllegalArgumentException("unsupported float width: ${source.sampleSizeInBits} bits")
    }

    private fun readShort(offset: Int): Short {
        val first = chunkBytes[offset].toInt() and 0xFF
        val second = chunkBytes[offset + 1].toInt() and 0xFF
        return if (source.isBigEndian) {
            ((first shl 8) or second).toShort()
        } else {
            ((second shl 8) or first).toShort()
        }
    }

    private fun readInt24(offset: Int): Int {
        val value = readInt24Unsigned(offset)
        return (value shl 8) shr 8
    }

    private fun readInt24Unsigned(offset: Int): Int {
        val first = chunkBytes[offset].toInt() and 0xFF
        val second = chunkBytes[offset + 1].toInt() and 0xFF
        val third = chunkBytes[offset + 2].toInt() and 0xFF
        return if (source.isBigEndian) {
            (first shl 16) or (second shl 8) or third
        } else {
            (third shl 16) or (second shl 8) or first
        }
    }

    private fun readInt(offset: Int): Int {
        val first = chunkBytes[offset].toInt() and 0xFF
        val second = chunkBytes[offset + 1].toInt() and 0xFF
        val third = chunkBytes[offset + 2].toInt() and 0xFF
        val fourth = chunkBytes[offset + 3].toInt() and 0xFF
        return if (source.isBigEndian) {
            (first shl 24) or (second shl 16) or (third shl 8) or fourth
        } else {
            (fourth shl 24) or (third shl 16) or (second shl 8) or first
        }
    }

    private fun readLong(offset: Int): Long {
        var value = 0L
        if (source.isBigEndian) {
            for (byte in 0 until 8) value = (value shl 8) or (chunkBytes[offset + byte].toLong() and 0xFF)
        } else {
            for (byte in 7 downTo 0) value = (value shl 8) or (chunkBytes[offset + byte].toLong() and 0xFF)
        }
        return value
    }

    // ------------------------------------------------------------------ channel mapping

    private fun split(frames: Int) {
        if (sourceChannels == 1) {
            System.arraycopy(decoded, 0, planarIn[0], 0, frames)
            return
        }
        var index = 0
        for (frame in 0 until frames) {
            for (channel in 0 until sourceChannels) {
                planarIn[channel][frame] = decoded[index++]
            }
        }
    }

    /**
     * Maps source channels onto target channels for the un-resampled path.
     *
     * Mono→stereo copies the sample unchanged: adding 3 dB (or summing a duplicated channel) would
     * make the same track 3 dB louder just because the device is stereo-only. The stereo→mono average
     * is a safety net for a plan the ladder never generates (it never asks for fewer channels), and it
     * averages rather than sums for the same loudness reason.
     */
    private fun mixInterleaved(input: FloatArray, frames: Int, output: FloatArray): Int {
        when {
            sourceChannels == targetChannels ->
                System.arraycopy(input, 0, output, 0, frames * targetChannels)

            sourceChannels == 1 -> {
                for (frame in 0 until frames) {
                    val value = input[frame]
                    val base = frame * targetChannels
                    for (channel in 0 until targetChannels) output[base + channel] = value
                }
            }

            targetChannels == 1 -> {
                for (frame in 0 until frames) {
                    var sum = 0f
                    for (channel in 0 until sourceChannels) sum += input[frame * sourceChannels + channel]
                    output[frame] = sum / sourceChannels
                }
            }

            else -> {
                for (frame in 0 until frames) {
                    val sourceBase = frame * sourceChannels
                    val targetBase = frame * targetChannels
                    for (channel in 0 until targetChannels) {
                        output[targetBase + channel] = input[sourceBase + min(channel, sourceChannels - 1)]
                    }
                }
            }
        }
        return frames
    }

    /** [mixInterleaved] for planar channel buffers produced by the resamplers. */
    private fun mixPlanar(input: Array<FloatArray>, frames: Int, output: FloatArray): Int {
        if (frames <= 0) return 0
        when {
            targetChannels == 1 && sourceChannels > 1 -> {
                for (frame in 0 until frames) {
                    var sum = 0f
                    for (channel in 0 until sourceChannels) sum += input[channel][frame]
                    output[frame] = sum / sourceChannels
                }
            }

            else -> {
                for (frame in 0 until frames) {
                    val base = frame * targetChannels
                    for (channel in 0 until targetChannels) {
                        output[base + channel] = input[min(channel, sourceChannels - 1)][frame]
                    }
                }
            }
        }
        return frames
    }

    private companion object {
        val EMPTY_BYTES = ByteArray(0)

        val SUPPORTED_ENCODINGS = setOf(
            AudioFormat.Encoding.PCM_SIGNED,
            AudioFormat.Encoding.PCM_UNSIGNED,
            AudioFormat.Encoding.PCM_FLOAT,
        )
    }
}

/** Below this a "rate" is really a compressed frame rate, not a sample rate. */
private const val MIN_DEVICE_RATE_HZ = 8_000f

/** Lowest bit depth any plan asks for; Java Sound has no signed 8-bit output path. */
private const val MIN_OUTPUT_BITS = 16

/** Highest packed depth Windows consumer mixers accept; wider source material is dithered down. */
private const val HIGH_OUTPUT_BITS = 24

/** Polyphase rows; 256 phases give ~0.4 % of a sample of table quantisation, then rows are blended. */
private const val PHASES = 256

/** 48 taps: the spec's 32-tap floor widened for a ~-60 dB alias floor with a Hann window. */
private const val MIN_TAPS = 32

/** Chosen once for the whole pipeline; matches the [SincResampler] default. */
private const val RESAMPLER_TAPS = 48

/** Source frames decoded per [PcmConverter] fill; bounds latency and allocation. */
private const val CHUNK_FRAMES = 4_096

/**
 * How many consecutive zero-length reads a decoder may return before it counts as stalled.
 *
 * Vorbis' converted stream returns 0 twice while it primes its buffer; treating that as end of
 * stream made Ogg files finish instantly, while tolerating it forever would spin a core.
 */
private const val MAX_ZERO_READS = 8

private const val MIN_BUFFER = 256