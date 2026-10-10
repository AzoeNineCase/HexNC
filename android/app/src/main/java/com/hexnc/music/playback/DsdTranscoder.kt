package com.hexnc.music.playback

import android.content.Context
import android.net.Uri
import com.hexnc.music.data.local.dsd.DsdPcmInputStream
import com.hexnc.music.data.local.dsd.DsdProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Makes a DSD file playable on Android by decoding it into a cached PCM WAV.
 *
 * ExoPlayer has neither an extractor nor a decoder for `.dsf`/`.dff`, so a DSD track handed to it
 * fails a second later with an opaque container error. The two steps it would need are split here:
 * [DsdProbe]/[DsdPcmInputStream] (platform-neutral Kotlin, shared with the Windows build) turn the
 * 1-bit stream into 16-bit PCM at [TARGET_RATE_HZ], and this class writes that PCM into a plain
 * canonical WAV under `cacheDir`, which the platform decodes with the code path it already trusts.
 *
 * The decode happens once per song id and every later call returns the same file, because a
 * five-minute DSD64 stereo track is ~100 MB of PCM: re-decoding it on every play (and on every
 * seek back into the queue) would be both slow and pointless. The cache is bounded by [MAX_CACHE_BYTES]
 * and evicted oldest-first, so it cannot grow without limit on a device with little free space.
 *
 * All stream and file work happens on [Dispatchers.IO], and a [Mutex] serialises decodes: parallel
 * decodes of two large tracks would compete for the same disk and memory for no benefit.
 */
internal class DsdTranscoder(private val context: Context) {

    private val mutex = Mutex()

    /**
     * The cached WAV holding [songId]'s decoded PCM, decoding [source] first when necessary.
     *
     * @return the file to play, or `null` when the source cannot be decoded — a DST-compressed DFF,
     *   a malformed header, or a stream that cannot be opened at all. A `null` is what tells the
     *   resolver to skip the song and report it, so I/O problems are reported rather than thrown.
     */
    suspend fun pcmFor(songId: String, source: Uri?): File? {
        val uri = source ?: return null
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val target = wavFile(songId)
                if (isUsable(target)) return@withLock target
                decode(uri, target)
            }
        }
    }

    // ------------------------------------------------------------------ decoding

    private fun decode(source: Uri, target: File): File? {
        val directory = target.parentFile ?: return null
        if (!directory.isDirectory && !directory.mkdirs()) return null
        val part = File(directory, target.name + PART_SUFFIX)
        part.delete()
        return try {
            val dataBytes = context.contentResolver.openInputStream(source)
                ?.let(::BufferedInputStream)
                ?.use { input ->
                    // The probe leaves the stream on the first payload byte, which is exactly where
                    // the PCM stream expects to start reading.
                    val info = DsdProbe.parse(input) ?: return null
                    val pcm = DsdPcmInputStream(info, input, TARGET_RATE_HZ)
                    FileOutputStream(part).use { out ->
                        out.write(wavHeader(pcm.channels, pcm.outputRateHz))
                        var written = 0L
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        while (true) {
                            val read = pcm.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            // The header's sample count is what the container claims; the bytes the
                            // decoder actually produced are what the WAV must declare.
                            written += read
                        }
                        written
                    }
                } ?: return null
            patchSizes(part, dataBytes)
            // Rename last: a crash or a failed decode leaves a `.part` file that the next attempt
            // overwrites, never a half-written WAV that would be mistaken for a valid cache hit.
            if (part.renameTo(target)) {
                prune(directory, keep = target)
                target
            } else {
                part.delete()
                target.takeIf(::isUsable)
            }
        } catch (error: Exception) {
            // An unreadable or truncated source is an expected outcome here, not a crash: the caller
            // turns `null` into the "DSD could not be decoded" message.
            part.delete()
            null
        }
    }

    private fun wavFile(songId: String): File {
        val name = songId.map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }
            .joinToString("")
            .ifBlank { "track" }
        return File(File(context.cacheDir, CACHE_DIR_NAME), name + WAV_SUFFIX)
    }

    /** A cached file is only reused when it carries at least a full header and some PCM. */
    private fun isUsable(file: File): Boolean = file.isFile && file.length() > HEADER_BYTES

    /**
     * Writes the canonical 44-byte RIFF/WAVE header for 16-bit little-endian PCM.
     *
     * The two size fields are left at zero and patched by [patchSizes] once the real byte count is
     * known: `DsdPcmInputStream` streams, so the length is not known before the data is written.
     */
    private fun wavHeader(channels: Int, sampleRateHz: Int): ByteArray {
        val blockAlign = channels * BITS_PER_SAMPLE / 8
        val out = ByteArray(HEADER_BYTES)
        putAscii(out, 0, "RIFF")
        putLe32(out, 4, 0)
        putAscii(out, 8, "WAVE")
        putAscii(out, 12, "fmt ")
        putLe32(out, 16, 16)
        putLe16(out, 20, 1)
        putLe16(out, 22, channels)
        putLe32(out, 24, sampleRateHz)
        putLe32(out, 28, sampleRateHz * blockAlign)
        putLe16(out, 32, blockAlign)
        putLe16(out, 34, BITS_PER_SAMPLE)
        putAscii(out, 36, "data")
        putLe32(out, 40, 0)
        return out
    }

    /** Fills in `RIFF` size (36 + payload) and `data` size once the PCM length is known. */
    private fun patchSizes(file: File, dataBytes: Long) {
        RandomAccessFile(file, "rw").use { out ->
            out.seek(4L)
            out.write(le32(36L + dataBytes))
            out.seek(40L)
            out.write(le32(dataBytes))
        }
    }

    // ------------------------------------------------------------------ cache

    /**
     * Deletes cached tracks oldest-first until the directory fits in [MAX_CACHE_BYTES].
     *
     * [keep] is the file that was just decoded: it is the one the caller is about to hand to the
     * player, so evicting it would defeat the point of having decoded it.
     */
    private fun prune(directory: File, keep: File) {
        val wavs = directory.listFiles { file -> file.isFile && file.name.endsWith(WAV_SUFFIX) } ?: return
        var total = wavs.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return
        for (file in wavs.sortedBy { it.lastModified() }) {
            if (total <= MAX_CACHE_BYTES) return
            if (file == keep) continue
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    // ------------------------------------------------------------------ bytes

    private fun putAscii(target: ByteArray, offset: Int, text: String) {
        for (index in text.indices) target[offset + index] = text[index].code.toByte()
    }

    private fun putLe16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun putLe32(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    /** A RIFF size field is 32 bits, so it saturates at the largest value it can hold. */
    private fun le32(value: Long): ByteArray {
        val clamped = value.coerceIn(0L, 0xFFFFFFFFL).toInt()
        return byteArrayOf(
            (clamped and 0xFF).toByte(),
            ((clamped ushr 8) and 0xFF).toByte(),
            ((clamped ushr 16) and 0xFF).toByte(),
            ((clamped ushr 24) and 0xFF).toByte(),
        )
    }

    private companion object {
        /**
         * PCM rate the DSD stream is decimated to: 88 200 Hz, the DSD-native family's base rate and
         * a rate every Android device can play. Higher targets (176 400) buy nothing audible — the
         * DSD noise shaping above 20 kHz is filtered out either way — and double the cache.
         */
        const val TARGET_RATE_HZ = 88_200

        const val BITS_PER_SAMPLE = 16
        const val HEADER_BYTES = 44
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val CACHE_DIR_NAME = "dsd"
        const val WAV_SUFFIX = ".wav"
        const val PART_SUFFIX = ".part"

        /** Roughly half a gigabyte of decoded PCM, which is a handful of long DSD tracks. */
        const val MAX_CACHE_BYTES = 512L * 1024 * 1024
    }
}
