package com.hexnc.music.data.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Android download target.
 *
 * On API 29+ files go into the shared Music collection through `MediaStore`, which needs no storage
 * permission: the row is inserted with `IS_PENDING = 1`, the bytes are streamed into it, and only
 * then is the flag cleared — the platform equivalent of the desktop `.part` rename, and the reason a
 * half-written track never shows up in the user's music player.
 *
 * Older releases have no `RELATIVE_PATH`, so they fall back to the app-specific external Music
 * folder. That needs no permission either, and the returned location says exactly where the file is,
 * which is better than asking for `WRITE_EXTERNAL_STORAGE` and still guessing.
 *
 * `.lrc` companions use the `Files` collection with a text MIME, because the audio collection only
 * accepts audio MIME types.
 */
class AndroidDownloadSink(
    private val context: Context,
    private val folder: String = "Music/HexNC",
) : DownloadSink {

    private val resolver get() = context.contentResolver

    override fun location(relativePath: String): String = "$folder/$relativePath"

    override fun exists(relativePath: String): Boolean {
        val name = relativePath.substringAfterLast('/')
        val relativeDir = folder + "/" + relativePath.substringBeforeLast('/', "").trimEnd('/') + "/"
        return runCatching {
            val collection = collectionFor(relativePath)
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
            resolver.query(collection, projection, selection, arrayOf(relativeDir, name), null)
                ?.use { it.moveToFirst() } ?: false
        }.getOrElse { false }
    }

    override fun write(relativePath: String, bytes: ByteArray): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val written = runCatching { writeThroughMediaStore(relativePath, bytes) }.getOrNull()
            if (written != null) return written
        }
        return writeToAppExternal(relativePath, bytes)
    }

    private fun writeThroughMediaStore(relativePath: String, bytes: ByteArray): String {
        val name = relativePath.substringAfterLast('/')
        val subPath = relativePath.substringBeforeLast('/', "").trimEnd('/')
        val relativeDir = buildString {
            append(folder)
            append('/')
            if (subPath.isNotEmpty()) {
                append(subPath)
                append('/')
            }
        }
        val collection = collectionFor(relativePath)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(name))
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(collection, values) ?: error("MediaStore refused $relativeDir$name")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: error("no output stream for $uri")
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return "$relativeDir$name"
    }

    private fun writeToAppExternal(relativePath: String, bytes: ByteArray): String {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        val file = File(File(base, folder.removePrefix("Music/")), relativePath)
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".part")
        try {
            temp.writeBytes(bytes)
            if (!temp.renameTo(file)) temp.copyTo(file, overwrite = true)
        } finally {
            if (temp.exists()) temp.delete()
        }
        return file.absolutePath
    }

    private fun collectionFor(relativePath: String): Uri =
        if (relativePath.substringAfterLast('.').lowercase() == "lrc") {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.').lowercase()) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "m4a", "aac" -> "audio/mp4"
        "lrc" -> "text/plain"
        else -> "application/octet-stream"
    }
}