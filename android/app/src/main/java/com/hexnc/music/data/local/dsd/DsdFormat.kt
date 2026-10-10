package com.hexnc.music.data.local.dsd

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Which container wrapped the 1-bit stream.
 *
 * The two formats differ in more than their magic bytes, and both differences matter to the
 * decoder:
 *
 *  * **DSF** (Sony) stores one block of [DsdTrackInfo.blockSizePerChannel] bytes per channel at a
 *    time, so a stereo file is `L0 R0 L1 R1 …` in 4096-byte units, and the bits inside a byte run
 *    least-significant first.
 *  * **DSDIFF/DFF** (Philips) stores one byte per channel per frame (`L R L R …`) and the bits run
 *    most-significant first.
 *
 * Both facts were checked against FFmpeg's `dsfdec.c`/`dsddec.c`, which is what every other player
 * on the machine uses.
 */
internal enum class DsdContainer {
    DSF,
    DFF,
}

/**
 * Everything needed to decode a DSD stream, as read from its header.
 *
 * @param bitRateHz the 1-bit sample rate: 2 822 400 (DSD64), 5 644 800 (DSD128), 11 289 600
 *   (DSD256)…
 * @param bitsPerChannel how many 1-bit samples each channel holds.
 * @param blockSizePerChannel DSF's block size (4096 in every real file); 0 for the flat DFF layout.
 * @param lsbFirst whether the bits inside a byte are stored least-significant first (DSF) or
 *   most-significant first (DFF).
 */
internal data class DsdTrackInfo(
    val container: DsdContainer,
    val channels: Int,
    val bitRateHz: Int,
    val bitsPerChannel: Long,
    val blockSizePerChannel: Int,
    val lsbFirst: Boolean,
) {
    /** PCM frames produced after decimating by [factor]. */
    fun outputFrames(factor: Int): Long = bitsPerChannel / factor
}

/**
 * Reads DSD container headers.
 *
 * Parsing is deliberately streaming: neither platform has random access to the audio (Android may
 * hand us a `content://` URI), so the probe walks the chunk list from the front and stops with the
 * stream positioned on the first byte of DSD data.
 */
internal object DsdProbe {

    const val DSD64_BIT_RATE = 2_822_400
    const val DSD128_BIT_RATE = 5_644_800
    const val DSD256_BIT_RATE = 11_289_600

    private const val DSF_DEFAULT_BLOCK_SIZE = 4_096
    private val SKIP_BUFFER = ByteArray(8 * 1024)

    /**
     * Parses [input], leaving it positioned on the first byte of DSD audio.
     *
     * @return the header facts, or `null` when the stream is not a DSD container, or is a DSD
     *   container this decoder cannot read (`DST ` compression inside a DFF, or a malformed header).
     */
    fun parse(input: InputStream): DsdTrackInfo? {
        val magic = readFully(input, 4) ?: return null
        return when {
            magic.sameAs("DSD ") -> parseDsf(input)
            magic.sameAs("FRM8") -> parseDff(input)
            else -> null
        }
    }

    /** [parse] over a file, closing the stream afterwards. */
    fun parse(file: File): DsdTrackInfo? =
        runCatching { FileInputStream(file).use { parse(BufferedInputStream(it)) } }.getOrNull()

    /**
     * Whether the file at [path] is a DSD file this app can decode.
     *
     * Used by the transports to tell "DSD that plays" apart from "DSD that cannot" (DST
     * compression), because the two deserve different messages.
     */
    fun canDecode(path: String?): Boolean {
        val value = path?.takeIf { it.isNotBlank() } ?: return false
        return parse(File(value)) != null
    }

    // ------------------------------------------------------------------ DSF

    private fun parseDsf(input: InputStream): DsdTrackInfo? {
        // `DSD ` was already consumed: chunk size (8), file size (8), metadata pointer (8).
        val head = readFully(input, 24) ?: return null
        if (leLong(head, 0) != 28L) return null

        var pending: DsdTrackInfo? = null
        while (true) {
            val id = readFully(input, 4) ?: return null
            val sizeBytes = readFully(input, 8) ?: return null
            val size = leLong(sizeBytes, 0)
            if (size < 12L) return null
            when {
                id.sameAs("fmt ") -> {
                    val body = readFully(input, (size - 12L).toInt()) ?: return null
                    pending = dsfFmt(body) ?: return null
                }

                // The data chunk's size field counts its own 12-byte header, which is why the
                // payload starts right here — the stream is already positioned on it.
                id.sameAs("data") -> return pending

                else -> if (!skip(input, size - 12L)) return null
            }
        }
    }

    private fun dsfFmt(body: ByteArray): DsdTrackInfo? {
        if (body.size < 40) return null
        val version = leInt(body, 0)
        val formatId = leInt(body, 4)
        val channels = leInt(body, 12)
        val bitRate = leInt(body, 16)
        val bitsPerSample = leInt(body, 20)
        val sampleCount = leLong(body, 24)
        val blockSize = leInt(body, 32)
        if (version != 1 || formatId != 0) return null
        if (channels <= 0 || channels > 32 || bitRate <= 0 || sampleCount <= 0L) return null
        return DsdTrackInfo(
            container = DsdContainer.DSF,
            channels = channels,
            bitRateHz = bitRate,
            bitsPerChannel = sampleCount,
            blockSizePerChannel = if (blockSize > 0) blockSize else DSF_DEFAULT_BLOCK_SIZE,
            // 1 = least significant bit first, 8 = most significant bit first.
            lsbFirst = bitsPerSample != 8,
        )
    }

    // ------------------------------------------------------------------ DFF

    private fun parseDff(input: InputStream): DsdTrackInfo? {
        // `FRM8` was already consumed.
        val sizeBytes = readFully(input, 8) ?: return null
        if (beLong(sizeBytes, 0) < 4L) return null
        val form = readFully(input, 4) ?: return null
        if (!form.sameAs("DSD ")) return null

        var channels = 0
        var bitRate = 0
        while (true) {
            val id = readFully(input, 4) ?: return null
            val chunkSizeBytes = readFully(input, 8) ?: return null
            val size = beLong(chunkSizeBytes, 0)
            if (size < 0L) return null
            when {
                // Compressed DSD. Decoding DST is a research project of its own; say so instead
                // of failing halfway through the file.
                id.sameAs("DST ") -> return null

                id.sameAs("PROP") -> {
                    val props = parseProp(input, size) ?: return null
                    channels = props.first
                    bitRate = props.second
                }

                id.sameAs("DSD ") -> {
                    if (channels <= 0 || bitRate <= 0 || size <= 0L) return null
                    return DsdTrackInfo(
                        container = DsdContainer.DFF,
                        channels = channels,
                        bitRateHz = bitRate,
                        bitsPerChannel = (size / channels) * 8L,
                        blockSizePerChannel = 0,
                        lsbFirst = false,
                    )
                }

                else -> if (!skipPadded(input, size)) return null
            }
        }
    }

    /** @return `channels to bitRateHz` from the `SND ` properties inside a `PROP` chunk. */
    private fun parseProp(input: InputStream, size: Long): Pair<Int, Int>? {
        val form = readFully(input, 4) ?: return null
        if (!form.sameAs("SND ")) return null
        var remaining = size - 4
        var channels = 0
        var bitRate = 0
        while (remaining >= 12L) {
            val id = readFully(input, 4) ?: return null
            val sizeBytes = readFully(input, 8) ?: return null
            val chunkSize = beLong(sizeBytes, 0)
            if (chunkSize < 0L) return null
            val consumed = 12L + chunkSize + (chunkSize and 1L)
            if (consumed > remaining) return null
            when {
                id.sameAs("FS  ") && chunkSize >= 4L -> {
                    val value = readFully(input, 4) ?: return null
                    bitRate = sampleRate(value)
                    val extra = chunkSize - 4L
                    if (extra > 0L && !skipPadded(input, extra)) return null
                }

                id.sameAs("CHNL") && chunkSize >= 2L -> {
                    val count = readFully(input, 2) ?: return null
                    channels = ((count[0].toInt() and 0xFF) shl 8) or (count[1].toInt() and 0xFF)
                    val extra = chunkSize - 2L
                    if (extra > 0L && !skipPadded(input, extra)) return null
                }

                else -> if (!skipPadded(input, chunkSize)) return null
            }
            remaining -= consumed
        }
        if (remaining > 0L && !skipPadded(input, remaining)) return null
        if (channels <= 0 || channels > 32 || bitRate <= 0) return null
        return channels to bitRate
    }

    /**
     * The `FS ` chunk is an unsigned 32-bit sample rate in the specification, but a few writers
     * store it as an IEEE float. Both are accepted, and anything implausible is rejected.
     */
    private fun sampleRate(raw: ByteArray): Int {
        val asInt = beInt(raw, 0)
        if (asInt in 1_000_000..20_000_000) return asInt
        val asFloat = Float.fromBits(asInt)
        return if (asFloat.isFinite() && asFloat >= 1_000_000f && asFloat <= 20_000_000f) {
            asFloat.toInt()
        } else {
            0
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun readFully(input: InputStream, count: Int): ByteArray? {
        if (count <= 0) return ByteArray(0)
        val buffer = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val read = input.read(buffer, filled, count - filled)
            if (read < 0) return null
            filled += read
        }
        return buffer
    }

    private fun skip(input: InputStream, count: Long): Boolean {
        var left = count
        // Streams handed to us close over a file or a URI, both of which report 0 from skip() for
        // large jumps, so the buffer is the reliable path.
        while (left > 0L) {
            val ask = minOf(left, SKIP_BUFFER.size.toLong()).toInt()
            val read = input.read(SKIP_BUFFER, 0, ask)
            if (read < 0) return false
            left -= read
        }
        return true
    }

    private fun skipPadded(input: InputStream, count: Long): Boolean =
        count <= 0L || skip(input, count + (count and 1L))

    private fun ByteArray.sameAs(text: String): Boolean {
        if (size < text.length) return false
        for (i in text.indices) {
            if (this[i] != text[i].code.toByte()) return false
        }
        return true
    }

    private fun leInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun leLong(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 7 downTo 0) {
            value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return value
    }

    private fun beInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun beLong(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return value
    }
}
