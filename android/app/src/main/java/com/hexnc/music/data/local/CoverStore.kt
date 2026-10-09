package com.hexnc.music.data.local

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest

/**
 * Storage for user-chosen cover art.
 *
 * Covers are *copied* out of the provider that the picker returned rather than referenced, because
 * a SAF grant is not durable across reboots and the source file often lives on removable storage.
 * Copies are content-addressed, so setting the same image on twelve albums stores one file, and
 * replacing a cover never orphans the previous one in a way that cannot be cleaned up.
 */
class CoverStore(context: Context) {

    private val appContext = context.applicationContext
    private val dir: File = File(appContext.filesDir, DIR).apply { mkdirs() }

    /**
     * Copies the image behind [uri] into private storage.
     *
     * @return the absolute path of the stored copy, or null when the source could not be read.
     */
    fun import(uri: Uri): String? = runCatching {
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readBytes()
            if (bytes.isEmpty()) return null
            write(bytes)
        }
    }.getOrNull()

    /** Stores an already-decoded image, e.g. one produced by a cover search. */
    fun write(bytes: ByteArray): String? = runCatching {
        if (bytes.isEmpty()) return null
        val target = File(dir, hash(bytes) + ".img")
        if (!target.exists()) {
            // Write to a temporary sibling first so that an interrupted copy cannot leave a
            // truncated file behind under the final, content-addressed name.
            val temp = File(dir, target.name + ".part")
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) temp.delete()
        }
        target.absolutePath
    }.getOrNull()

    /** Removes a stored cover. Safe to call with null or with a path outside the cover directory. */
    fun delete(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching {
            val file = File(path)
            if (file.parentFile?.absolutePath == dir.absolutePath) file.delete()
        }
    }

    /** True when [path] still resolves to a readable file. */
    fun exists(path: String?): Boolean = !path.isNullOrBlank() && File(path).isFile

    /** Total bytes currently occupied by stored covers. */
    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    /** Deletes covers no longer referenced by any of [referenced]. */
    fun prune(referenced: Set<String>): Int {
        val live = referenced.mapNotNull { it.takeIf(String::isNotBlank) }.toSet()
        var removed = 0
        dir.listFiles()?.forEach { file ->
            if (file.absolutePath !in live && file.delete()) removed++
        }
        return removed
    }

    private fun hash(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                append("0123456789abcdef"[(byte.toInt() shr 4) and 0x0F])
                append("0123456789abcdef"[byte.toInt() and 0x0F])
            }
        }
    }

    private companion object {
        const val DIR = "covers"
    }
}
