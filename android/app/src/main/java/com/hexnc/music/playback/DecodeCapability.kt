package com.hexnc.music.playback

import java.io.File
import java.io.RandomAccessFile

/**
 * What Android's own decoders can do with a local file, decided from the extension (and, for a file
 * the app can open directly, from its container header).
 *
 * The table is deliberately narrower than "media3 has a decoder for the family": it answers "will
 * tapping this track work", and the three families ExoPlayer has no extractor for — WMA/ASF, Monkey's
 * Audio, and DST-compressed DSD — get their own reason so the UI can explain *why* instead of showing
 * a generic "playback failed". Opus is *supported here* even though the desktop build refuses it: the
 * platform ships an Opus decoder, which is exactly why this file cannot be shared with the JVM build.
 */
enum class DecodeSupport {
    /** media3 (or the DSD transcoder) has a decoder for this container. */
    SUPPORTED,

    /** Ogg Opus. Kept for parity with the desktop enum; the platform always answers [SUPPORTED]. */
    UNSUPPORTED_OPUS,

    /** WMA/ASF. media3 ships no extractor for it. */
    UNSUPPORTED_WMA,

    /** Monkey's Audio. No extractor in media3. */
    UNSUPPORTED_APE,

    /** `.dff` carrying DST-compressed frames; the transcoder only handles uncompressed DSDIFF. */
    UNSUPPORTED_DST,

    /** Unknown extension and no readable header: play it and let the platform decide. */
    UNKNOWN,
    ;

    /** True when the user should be warned before the track is even tapped. */
    val isBlocked: Boolean get() = this != SUPPORTED && this != UNKNOWN
}

/**
 * Cheap, header-based capability check for local files.
 *
 * Answers are memoised per path + size + mtime so a scrolling list does not re-read the same headers.
 * A `content://` URI is [DecodeSupport.UNKNOWN]: the app cannot open a random header through SAF
 * without a grant, and refusing a file we merely cannot inspect would be worse than trying it.
 */
object DecodeCapability {

    private val BY_EXTENSION = mapOf(
        "mp3" to DecodeSupport.SUPPORTED,
        "flac" to DecodeSupport.SUPPORTED,
        "wav" to DecodeSupport.SUPPORTED,
        "ogg" to DecodeSupport.SUPPORTED,
        "oga" to DecodeSupport.SUPPORTED,
        "opus" to DecodeSupport.SUPPORTED,
        "m4a" to DecodeSupport.SUPPORTED,
        "mp4" to DecodeSupport.SUPPORTED,
        "3gp" to DecodeSupport.SUPPORTED,
        "aac" to DecodeSupport.SUPPORTED,
        "alac" to DecodeSupport.SUPPORTED,
        "dsf" to DecodeSupport.SUPPORTED,
        "dff" to DecodeSupport.SUPPORTED,
        "wma" to DecodeSupport.UNSUPPORTED_WMA,
        "asf" to DecodeSupport.UNSUPPORTED_WMA,
        "ape" to DecodeSupport.UNSUPPORTED_APE,
    )

    private const val ASF_GUID_HEX = "3026b2758e66cf11"
    private const val DSDIFF_MAGIC = "FRM8"
    private const val DSF_MAGIC = "DSD "
    private const val DST_CHUNK = "DST "
    private const val PROBE_BYTES = 64 * 1024
    private const val CACHE_ENTRIES = 512

    private val cache = object : LinkedHashMap<String, DecodeSupport>(CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DecodeSupport>?): Boolean =
            size > CACHE_ENTRIES
    }

    /** Capability for a filesystem path; `content://` and blank paths answer [DecodeSupport.UNKNOWN]. */
    fun of(path: String?): DecodeSupport {
        val trimmed = path?.takeIf { it.isNotBlank() } ?: return DecodeSupport.UNKNOWN
        if (trimmed.startsWith("content://")) return DecodeSupport.UNKNOWN
        val file = File(trimmed)
        val key = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
        synchronized(cache) { cache[key] }?.let { return it }
        val answer = runCatching { inspect(file) }.getOrDefault(DecodeSupport.UNKNOWN)
        synchronized(cache) { cache[key] = answer }
        return answer
    }

    /** Capability for a [File] that may no longer exist. */
    fun of(file: File): DecodeSupport = of(file.absolutePath)

    /** Pure header/extension decision, exposed for tests. */
    fun inspect(extension: String, header: ByteArray): DecodeSupport {
        val ext = extension.lowercase().removePrefix(".")
        if (header.size >= 8 && header.copyOfRange(0, 8).toHex() == ASF_GUID_HEX) return DecodeSupport.UNSUPPORTED_WMA
        if (ext == "dff" || ext == "dsf") {
            val isDsdiff = header.startsWith(DSDIFF_MAGIC) || header.startsWith(DSF_MAGIC)
            // A file whose header we cannot see is still offered: the transcoder's own error is a
            // better answer than a badge we guessed.
            if (!isDsdiff) return DecodeSupport.UNKNOWN
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
}