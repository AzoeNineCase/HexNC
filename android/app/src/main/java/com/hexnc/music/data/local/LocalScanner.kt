package com.hexnc.music.data.local

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.hexnc.music.data.model.Ids
import com.hexnc.music.data.model.Song
import com.hexnc.music.data.model.SongSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** The result of scanning a folder: the tracks found plus any `.lrc` files that sat beside them. */
data class ScannedFolder(
    val folderName: String,
    val songs: List<Song>,
    /** Song id → raw `.lrc` text, already matched by file name. */
    val lyrics: Map<String, String>,
)

/**
 * Imports music that already lives on the device.
 *
 * Two sources are supported because they answer different needs:
 *
 *  * **MediaStore** — a one-shot scan of the system's audio index, which is what the user means by
 *    "import my local music". No permission prompt beyond the audio read permission, and it sees
 *    files that other apps added.
 *  * **A folder picked through SAF** — for music the media scanner has not indexed (a freshly
 *    copied folder, or a location the index skips). This path is also the only way to pick up
 *    sidecar `.lrc` files, because the media index does not track them.
 */
class LocalScanner(private val context: Context) {

    private val resolver = context.contentResolver

    /**
     * Scans the system audio index.
     *
     * Filtering happens in the query rather than in Kotlin so that a device with tens of thousands
     * of files does not stream them all across the binder. Sidecar `.lrc` sheets are looked up
     * afterwards ([lyricsFor]) because the media index does not track them at all — which is why a
     * MediaStore import used to produce songs without any lyrics.
     */
    suspend fun scanMediaStore(): ScannedFolder = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DATA,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.SIZE} > 0"
        val songs = ArrayList<Song>()
        resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val trackColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

            while (cursor.moveToNext()) {
                val mediaId = cursor.getLong(idColumn)
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    mediaId,
                )
                val path = cursor.getString(pathColumn)
                val albumId = cursor.getLong(albumIdColumn)
                songs += Song(
                    id = Ids.localSongId(contentUri.toString()),
                    source = SongSource.LOCAL,
                    title = cursor.getString(titleColumn) ?: "—",
                    artist = cleanArtist(cursor.getString(artistColumn)),
                    album = cursor.getString(albumColumn).orEmpty(),
                    albumId = albumId.takeIf { it > 0 },
                    durationMs = cursor.getLong(durationColumn),
                    mediaUri = contentUri.toString(),
                    localPath = path,
                    // The system album-art provider is a synthetic stream; Coil can load it directly.
                    coverUrl = if (albumId > 0) {
                        ContentUris.withAppendedId(ALBUM_ART_URI, albumId).toString()
                    } else {
                        null
                    },
                    trackNo = cursor.getInt(trackColumn) and 0xFFFF,
                    size = cursor.getLong(sizeColumn),
                    addedAt = System.currentTimeMillis(),
                )
            }
        }
        ScannedFolder(DEVICE_LIBRARY, songs, findLyricsForBlocking(songs))
    }

    /**
     * Recursively imports a folder tree the user granted access to.
     *
     * Audio and `.lrc` files are collected in one pass and then paired by base name, which is the
     * near-universal convention for sidecar lyrics (`song.mp3` + `song.lrc`).
     */
    suspend fun scanFolder(treeUri: Uri): ScannedFolder = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return@withContext ScannedFolder("", emptyList(), emptyMap())
        val audio = mutableListOf<DocumentFile>()
        val lyricsByBaseName = HashMap<String, DocumentFile>()
        walk(root, audio, lyricsByBaseName, depth = 0)

        val lyrics = HashMap<String, String>()
        val songs = ArrayList<Song>(audio.size)
        audio.forEach { file ->
            val uri = file.uri
            val name = file.name.orEmpty()
            val songId = Ids.localSongId(uri.toString())
            songs += Song(
                id = songId,
                source = SongSource.LOCAL,
                title = name.substringBeforeLast('.').ifBlank { name },
                artist = "",
                album = root.name.orEmpty(),
                durationMs = 0L,
                mediaUri = uri.toString(),
                localPath = name,
                size = file.length(),
                addedAt = System.currentTimeMillis(),
            )
            lyricsByBaseName[baseName(name)]?.let { sidecar ->
                runCatching { resolver.openInputStream(sidecar.uri)?.use { it.readBytes().decodeToString() } }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { lyrics[songId] = it }
            }
        }
        ScannedFolder(root.name.orEmpty(), songs, lyrics)
    }

    /**
     * Finds sidecar `.lrc` text for [songs], trying every source the app might have access through.
     *
     * Three sources are tried, best first:
     *
     *  1. a sibling file next to the audio file — direct and exact, and what a MediaStore import gets
     *     on Android 12 and below;
     *  2. the MediaStore index of `.lrc` files, matched on (folder, base name) — the only route on
     *     Android 13+ for a file the app does not own, and a partial one at that, because the media
     *     index hides non-media files from apps that did not create them;
     *  3. every folder tree the user granted the app persistent access to, matched on base name —
     *     which is why the folder import keeps its grant ([rememberTreeAccess]).
     *
     * @return song id → raw text; songs with no sheet found are simply absent from the map.
     */
    suspend fun lyricsFor(songs: List<Song>): Map<String, String> = withContext(Dispatchers.IO) {
        if (songs.isEmpty()) emptyMap() else findLyricsForBlocking(songs)
    }

    /**
     * Keeps read access to a tree the user picked through SAF.
     *
     * Without this the grant lasts only until the task dies, so a folder-imported song loses both its
     * lyrics lookup and its ability to play after the app is swiped away or the device reboots.
     */
    fun rememberTreeAccess(uri: Uri) {
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun findLyricsForBlocking(songs: List<Song>): Map<String, String> {
        val found = HashMap<String, String>()
        val pending = ArrayList<Song>()

        songs.forEach { song ->
            val text = song.localPath?.let(::readSiblingLyric)
            if (text != null) found[song.id] = text else pending += song
        }

        if (pending.isNotEmpty()) {
            val index = mediaStoreLyricIndex()
            if (index.isNotEmpty()) {
                pending.retainAll { song ->
                    val text = songFolderKey(song)?.let(index::get)?.let(::readUriText)
                    if (text != null) found[song.id] = text
                    text == null
                }
            }
        }

        if (pending.isNotEmpty()) {
            val index = persistedTreeLyricIndex()
            if (index.isNotEmpty()) {
                pending.forEach { song ->
                    val name = baseName(song.localPath ?: song.mediaUri ?: song.title)
                    index[name]?.let(::readUriText)?.let { found[song.id] = it }
                }
            }
        }
        return found
    }

    /** `/Music/a.mp3` → the text of `/Music/a.lrc`, when that file is directly readable. */
    private fun readSiblingLyric(path: String): String? {
        val file = File(path)
        val parent = file.parentFile ?: return null
        val stem = file.name.substringBeforeLast('.', file.name)
        if (stem.isBlank()) return null
        return LYRIC_EXTENSION_SPELLINGS
            .asSequence()
            .map { File(parent, "$stem$it") }
            .firstOrNull { candidate -> runCatching { candidate.isFile && candidate.canRead() }.getOrDefault(false) }
            ?.let { candidate -> runCatching { candidate.readText() }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
    }

    /** Folder + base name of a song, which is the key a sidecar `.lrc` is matched on. */
    private fun songFolderKey(song: Song): String? {
        val path = song.localPath?.takeIf { it.isNotBlank() } ?: return null
        val file = File(path)
        val parent = file.parentFile?.absolutePath ?: return null
        return indexKey(parent, file.name)
    }

    private fun indexKey(folder: String, fileName: String): String =
        folder.trimEnd('/', '\\').lowercase(Locale.ROOT) + "/" + baseName(fileName)

    /** `.lrc` rows the media store knows about, keyed by folder + base name. */
    private fun mediaStoreLyricIndex(): Map<String, Uri> = runCatching {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATA,
        )
        val index = HashMap<String, Uri>()
        // SQLite's LIKE is case-insensitive for ASCII, so this also finds `A.LRC`.
        resolver.query(
            collection,
            projection,
            "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?",
            arrayOf("%.lrc"),
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameColumn) ?: continue
                val path = cursor.getString(pathColumn) ?: continue
                val parent = File(path).parentFile?.absolutePath ?: continue
                index.putIfAbsent(
                    indexKey(parent, name),
                    ContentUris.withAppendedId(collection, cursor.getLong(idColumn)),
                )
            }
        }
        index
    }.getOrDefault(emptyMap())

    /** `.lrc` files inside every tree whose read grant the app has kept, keyed by base name. */
    private fun persistedTreeLyricIndex(): Map<String, Uri> {
        val index = HashMap<String, Uri>()
        val granted = runCatching {
            resolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }
        }.getOrDefault(emptyList())
        granted.forEach { treeUri ->
            val root = runCatching { DocumentFile.fromTreeUri(context, treeUri) }.getOrNull()
                ?: return@forEach
            collectLyricFiles(root, index, depth = 0)
        }
        return index
    }

    private fun collectLyricFiles(dir: DocumentFile, out: MutableMap<String, Uri>, depth: Int) {
        if (depth > MAX_DEPTH) return
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        children.forEach { child ->
            if (child.isDirectory) {
                collectLyricFiles(child, out, depth + 1)
                return@forEach
            }
            val name = child.name.orEmpty()
            if (name.substringAfterLast('.', "").equals("lrc", ignoreCase = true)) {
                out.putIfAbsent(baseName(name), child.uri)
            }
        }
    }

    private fun readUriText(uri: Uri): String? =
        runCatching { resolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    /** Reads the raw text of a single `.lrc` file the user picked. */
    suspend fun readText(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching { resolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
    }

    /** The display name of a picked document, used to label an import. */
    fun displayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun walk(
        dir: DocumentFile,
        audio: MutableList<DocumentFile>,
        lyrics: MutableMap<String, DocumentFile>,
        depth: Int,
    ) {
        // A hard depth cap keeps a symlink loop (or a pathological archive layout) from running away.
        if (depth > MAX_DEPTH) return
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        children.forEach { child ->
            if (child.isDirectory) {
                walk(child, audio, lyrics, depth + 1)
                return@forEach
            }
            val name = child.name.orEmpty()
            val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            when {
                extension in AUDIO_EXTENSIONS -> audio += child
                extension == "lrc" -> lyrics.putIfAbsent(baseName(name), child)
            }
        }
    }

    /** `"Song.LRC"` → `"song"`, so that audio and lyric files pair regardless of case. */
    private fun baseName(name: String): String =
        name.substringBeforeLast('.').trim().lowercase(Locale.ROOT)

    /** MediaStore reports unknown artists as the literal `<unknown>`; blank it so the UI can say "—". */
    private fun cleanArtist(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        return if (trimmed.equals("<unknown>", ignoreCase = true)) "" else trimmed
    }

    private companion object {
        const val MAX_DEPTH = 12

        /** Display name of a playlist-less media-store import; never shown in the UI. */
        const val DEVICE_LIBRARY = "device"

        /** Spellings tried for a sidecar lyric file, in order. */
        val LYRIC_EXTENSION_SPELLINGS = listOf(".lrc", ".LRC", ".Lrc")

        val ALBUM_ART_URI: Uri = Uri.parse("content://media/external/audio/albumart")

        /** DSD is listed so that a scan still reports it; playback then explains why it cannot play. */
        val AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma", "ape", "alac", "mp4", "3gp",
            "dsf", "dff",
        )
    }
}

/** Extensions of Direct Stream Digital audio, which Android ships no decoder or extractor for. */
internal val DSD_EXTENSIONS = setOf("dsf", "dff", "dsd")

/**
 * True when [pathOrUri] names a DSD file.
 *
 * A `content://` media URI carries no extension, so callers pass the song's `localPath` first. The
 * query and fragment are stripped because a SAF document URI ends in its child's name, which may
 * itself contain a `.`.
 */
internal fun isDsdSource(pathOrUri: String?): Boolean {
    if (pathOrUri.isNullOrBlank()) return false
    val bare = pathOrUri.substringBefore('?').substringBefore('#')
    return bare.substringAfterLast('.', "").lowercase(Locale.ROOT) in DSD_EXTENSIONS
}
