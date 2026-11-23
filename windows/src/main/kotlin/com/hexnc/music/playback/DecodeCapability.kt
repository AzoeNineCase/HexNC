package com.hexnc.music.playback

import java.io.File
import java.io.RandomAccessFile

/**
 * What this platform can actually do with a local file, decided from its container header (and, when
 * the header is not conclusive, from its extension).
 *
 * The list is deliberately narrow: it answers "will tapping this track work", not "does a decoder
 * exist for the general family". Everything the bundled Java Sound providers read is [SUPPORTED];
 * the three families that are known to fail on both platforms — Ogg Opus, WMA/ASF and DST-compressed
 * DSD — get their own reason so the UI can explain *why* instead of a generic "playback failed".
 */
enum class DecodeSupport {
    /** A decoder is installed and has read files of this container before. */
    SUPPORTED,

    /** Ogg Opus. No pure-Java Opus decoder is bundled, so these files cannot play. */
    UNSUPPORTED_OPUS,

    /** WMA/ASF. Microsoft's codec is not available to Java Sound. */
    UNSUPPORTED_WMA,

    /** Monkey's Audio. Kept for parity with the Android enum; the desktop build reads `.ape`. */
    UNSUPPORTED_APE,

    /** `.dff` carrying DST-compressed frames; the DSD reader only handles uncompressed DSDIFF. */
    UNSUPPORTED_DST,

    /** Unknown extension *and* unfamiliar header: play it and let the decoder decide. */
    UNKNOWN,
    ;

    /** True when the user should be warned before the track is even tapped. */
    val isBlocked: Boolean get() = this != SUPPORTED && this != UNKNOWN
}

/**
 * Cheap, header-based capability check for local files.
 *
 * Reading 64 KiB from a file is fast enough to run while a list row is composed, but not so cheap
 * that it should happen on every recomposition, so answers are memoised per path + size + mtime —
 * exactly like the cover cache, and invalidated automatically when a file is replaced.
 *
 * The check never opens an [javax.sound.sampled.AudioInputStream]: Java Sound's own
 * `AudioSystem.getAudioFileTypes()` only reports the platform's built-in providers (`aif`, `au`,
 * `wav`) and therefore under-reports every SPI provider this app installs, while actually decoding a
 * file to find out costs far more than a header read.
 */
object DecodeCapability {

    /** Extensions the bundled providers read, mapped to the reason they are blocked when they are. */
    private val BY_EXTENSION = mapOf(
        "mp3" to DecodeSupport.SUPPORTED,
        "flac" to DecodeSupport.SUPPORTED,
        "wav" to DecodeSupport.SUPPORTED,
        "ogg" to DecodeSupport.SUPPORTED,
        "oga" to DecodeSupport.SUPPORTED,
        "m4a" to DecodeSupport.SUPPORTED,
        "mp4" to DecodeSupport.SUPPORTED,
        "3gp" to DecodeSupport.SUPPORTED,
        "aac" to DecodeSupport.SUPPORTED,
        "alac" to DecodeSupport.SUPPORTED,
        "ape" to DecodeSupport.SUPPORTED,
        "dsf" to DecodeSupport.SUPPORTED,
        "dff" to DecodeSupport.SUPPORTED,
        "opus" to DecodeSupport.UNSUPPORTED_OPUS,
        "wma" to DecodeSupport.UNSUPPORTED_WMA,
        "asf" to DecodeSupport.UNSUPPORTED_WMA,
    )

    /** Header magic → answer, checked before the extension so a mislabelled file is still caught. */
    private const val ASF_GUID_HEX = "3026b2758e66cf11"

    private const val OGG_MAGIC = "OggS"
    private const val OPUS_MAGIC = "OpusHead"
    private const val FLAC_MAGIC = "fLaC"
    private const val DSDIFF_MAGIC = "FRM8"
    private const val DSF_MAGIC = "DSD "
    private const val DST_CHUNK = "DST "

    private const val PROBE_BYTES = 64 * 1024
    private const val CACHE_ENTRIES = 512

    private val cache = object : LinkedHashMap<String, DecodeSupport>(CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DecodeSupport>?): Boolean =
            size > CACHE_ENTRIES
    }

    /** Capability for [file]; never throws — an unreadable file simply answers [DecodeSupport.UNKNOWN]. */
    fun of(file: File): DecodeSupport {
        val key = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
        synchronized(cache) { cache[key] }?.let { return it }
        val answer = runCatching { inspect(file) }.getOrDefault(DecodeSupport.UNKNOWN)
        synchronized(cache) { cache[key] = answer }
        return answer
    }

    /** Capability for a path that may not be a [File] any more (a URI-backed local track). */
    fun of(path: String): DecodeSupport = of(File(path))

    /** Pure header/extension decision, exposed for tests. */
    fun inspect(extension: String, header: ByteArray): DecodeSupport {
        val ext = extension.lowercase().removePrefix(".")
        // The whole header is kept: a DSDIFF `DST ` chunk can sit past the first 64 bytes, after the
        // `FRM8`/`DSD ` prelude, and scanning only a prefix would miss exactly the file we must refuse.
        val head = header
        if (head.size >= 8 && head.copyOfRange(0, 8).toHex() == ASF_GUID_HEX) return DecodeSupport.UNSUPPORTED_WMA
        when {
            head.startsWith(OggMagic) && head.contains(OPUS_MAGIC) -> return DecodeSupport.UNSUPPORTED_OPUS
            head.startsWith(OggMagic) -> return DecodeSupport.SUPPORTED
            head.startsWith(FlacMagic) -> return DecodeSupport.SUPPORTED
            head.startsWith(DsdiffMagic) || head.startsWith(DsfMagic) ->
                return if (header.contains(DST_CHUNK)) DecodeSupport.UNSUPPORTED_DST else DecodeSupport.SUPPORTED
        }
        return BY_EXTENSION[ext] ?: DecodeSupport.UNKNOWN
    }

    private fun inspect(file: File): DecodeSupport {
        if (!file.isFile) return DecodeSupport.UNKNOWN
        val header = RandomAccessFile(file, "r").use { handle ->
            val size = minOf(handle.length(), PROBE_BYTES.toLong()).toInt()
            val bytes = ByteArray(size)
            handle.readFully(bytes)
            bytes
        }
        return inspect(file.extension, header)
    }

    private fun ByteArray.startsWith(text: String): Boolean {
        val magic = text.toByteArray(Charsets.US_ASCII)
        if (size < magic.size) return false
        for (index in magic.indices) if (this[index] != magic[index]) return false
        return true
    }

    private fun ByteArray.contains(text: String): Boolean {
        val needle = text.toByteArray(Charsets.US_ASCII)
        if (needle.isEmpty() || size < needle.size) return false
        outer@ for (start in 0..size - needle.size) {
            for (index in needle.indices) if (this[start + index] != needle[index]) continue@outer
            return true
        }
        return false
    }

    private fun ByteArray.toHex(): String {
        val digits = "0123456789abcdef"
        val out = StringBuilder(size * 2)
        for (byte in this) {
            val value = byte.toInt() and 0xFF
            out.append(digits[value ushr 4]).append(digits[value and 0x0F])
        }
        return out.toString()
    }

    private val OggMagic = OGG_MAGIC
    private val OpusMagic = OPUS_MAGIC
    private val FlacMagic = FLAC_MAGIC
    private val DsdiffMagic = DSDIFF_MAGIC
    private val DsfMagic = DSF_MAGIC
}