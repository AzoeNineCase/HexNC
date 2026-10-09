package com.hexnc.music.data.local

import android.content.Context
import com.hexnc.music.data.model.Ids
import com.hexnc.music.data.model.Song
import com.hexnc.music.data.model.SongSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
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
 * Imports music that already lives on this PC.
 *
 * The Android build had two sources (the `MediaStore` audio index and a SAF folder tree) because a
 * phone has no shared filesystem. Windows does, so the desktop version has one real source — a
 * directory tree walked with `java.io.File` — plus a shortcut that scans the standard
 * `%USERPROFILE%\Music` library so the common case needs no picker at all.
 *
 * Reading tags directly (rather than trusting the file name, as the SAF path had to) is the desktop
 * equivalent of what `MediaStore` supplied on Android: title, artist, album, track number, duration
 * and embedded cover art.
 */
class LocalScanner(private val context: Context) {

    private val covers = CoverStore(context)

    /** Scans the user's standard Music folder, one level of "artist\album" nesting included. */
    suspend fun scanDefaultLibrary(): List<Song> = withContext(Dispatchers.IO) {
        defaultRoots().filter { it.isDirectory }.flatMap { scanTree(it).songs }
    }

    /**
     * Recursively imports a folder tree the user picked.
     *
     * Audio and `.lrc` files are collected in one pass and then paired by base name, which is the
     * near-universal convention for sidecar lyrics (`song.mp3` + `song.lrc`).
     */
    suspend fun scanFolder(root: File): ScannedFolder = withContext(Dispatchers.IO) { scanTree(root) }

    /**
     * Imports an explicit list of files (the multi-file picker), pairing sidecar `.lrc` files that
     * sit next to each one.
     */
    suspend fun scanFiles(files: List<File>): ScannedFolder = withContext(Dispatchers.IO) {
        val audio = files.filter { it.isFile && extensionOf(it.name) in AUDIO_EXTENSIONS }
        val folder = audio.firstOrNull()?.parentFile
        val sidecars = HashMap<String, File>()
        audio.forEach { file -> sidecarBeside(file)?.let { sidecars[baseName(file.name)] = it } }
        val songs = audio.map { songOf(it, folder?.name.orEmpty(), sidecars) }
        ScannedFolder(folder?.name.orEmpty(), songs, lyricsOf(songs, sidecars))
    }

    /** Reads the raw text of a single `.lrc` file the user picked. */
    suspend fun readText(file: File): String? = withContext(Dispatchers.IO) {
        runCatching { file.readText() }.getOrNull()
    }

    /** The display name of a picked file, used to label an import. */
    fun displayName(file: File): String = file.name

    /**
     * Finds the sidecar `.lrc` for songs that are already in the library.
     *
     * A lyric file sits next to its audio file by convention (`song.mp3` + `song.lrc`), and the
     * desktop build can read it straight off the filesystem — so unlike the Android implementation
     * there is exactly one lookup strategy here, with no media-index or SAF detour.
     *
     * @return song id → raw text; songs with no sheet found are simply absent from the map.
     */
    suspend fun lyricsFor(songs: List<Song>): Map<String, String> = withContext(Dispatchers.IO) {
        val found = HashMap<String, String>()
        songs.forEach { song ->
            val path = song.localPath?.takeIf { it.isNotBlank() } ?: return@forEach
            readSiblingLyric(path)?.let { found[song.id] = it }
        }
        found
    }

    /** `C:\Music\a.mp3` → the text of `C:\Music\a.lrc`, trying the common spellings of the suffix. */
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

    private fun scanTree(root: File): ScannedFolder {
        if (!root.isDirectory) return ScannedFolder(root.name, emptyList(), emptyMap())
        val audio = mutableListOf<File>()
        val sidecars = HashMap<String, File>()
        walk(root, audio, sidecars, depth = 0)
        val songs = audio.map { songOf(it, root.name, sidecars) }
        return ScannedFolder(root.name, songs, lyricsOf(songs, sidecars))
    }

    private fun walk(dir: File, audio: MutableList<File>, lyrics: MutableMap<String, File>, depth: Int) {
        // A hard depth cap keeps a junction loop (or a pathological archive layout) from running away.
        if (depth > MAX_DEPTH) return
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        children.forEach { child ->
            when {
                child.isDirectory -> walk(child, audio, lyrics, depth + 1)
                child.isFile -> {
                    val extension = extensionOf(child.name)
                    when {
                        extension in AUDIO_EXTENSIONS -> audio += child
                        extension == "lrc" -> lyrics.putIfAbsent(baseName(child.name), child)
                    }
                }
            }
        }
    }

    private fun songOf(file: File, albumFallback: String, sidecars: Map<String, File>): Song {
        val tags = readTags(file)
        val name = file.name
        val coverPath = tags.coverBytes?.let { covers.write(it) }
        return Song(
            id = Ids.localSongId(file.absolutePath),
            source = SongSource.LOCAL,
            title = tags.title ?: name.substringBeforeLast('.').ifBlank { name },
            artist = tags.artist.orEmpty(),
            album = tags.album ?: albumFallback,
            durationMs = tags.durationMs,
            mediaUri = file.toURI().toString(),
            localPath = file.absolutePath,
            coverOverridePath = coverPath,
            trackNo = tags.trackNo,
            bitrate = tags.bitrate,
            size = file.length(),
            addedAt = System.currentTimeMillis(),
        )
    }

    private fun lyricsOf(songs: List<Song>, sidecars: Map<String, File>): Map<String, String> {
        val out = HashMap<String, String>()
        songs.forEach { song ->
            val key = sidecars.keys.firstOrNull { baseName(song.title) == it } ?: return@forEach
            val sidecar = sidecars[key] ?: return@forEach
            runCatching { sidecar.readText() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { out[song.id] = it }
        }
        return out
    }

    /** The `.lrc` sitting next to [file] with the same base name, if any. */
    private fun sidecarBeside(file: File): File? =
        file.parentFile?.let { parent -> File(parent, file.name.substringBeforeLast('.') + ".lrc").takeIf { it.isFile } }

    private class TrackTags(
        val title: String?,
        val artist: String?,
        val album: String?,
        val trackNo: Int,
        val durationMs: Long,
        val bitrate: Int,
        val coverBytes: ByteArray?,
    )

    /**
     * Reads title/artist/album/track/duration/artwork with jaudiotagger.
     *
     * Every field is optional in practice — a WAV usually has no tags at all — so a read failure or
     * a missing field simply falls back to the file name and an empty artist.
     */
    private fun readTags(file: File): TrackTags {
        val fallback = TrackTags(null, null, null, 0, 0L, 0, null)
        return runCatching {
            val audio = AudioFileIO.read(file)
            val header = audio.audioHeader
            val tag = audio.tag
            val artwork = tag?.firstArtwork?.binaryData
            TrackTags(
                title = tag.getFirst(FieldKey.TITLE)?.takeIf { it.isNotBlank() },
                artist = tag.getFirst(FieldKey.ARTIST)?.takeIf { it.isNotBlank() },
                album = tag.getFirst(FieldKey.ALBUM)?.takeIf { it.isNotBlank() },
                trackNo = tag.getFirst(FieldKey.TRACK)?.toIntOrNull() ?: 0,
                durationMs = (header.trackLength.toLong() * 1000L).coerceAtLeast(0L),
                bitrate = runCatching { header.bitRateAsNumber }.getOrDefault(0L).toInt(),
                coverBytes = artwork,
            )
        }.getOrDefault(fallback)
    }

    private fun defaultRoots(): List<File> {
        val home = System.getProperty("user.home").orEmpty()
        val roots = listOf(File(home, "Music"), File(context.filesDir, "music"))
        return roots.distinctBy { it.absolutePath }
    }

    private fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    /** `"Song.LRC"` → `"song"`, so that audio and lyric files pair regardless of case. */
    private fun baseName(name: String): String =
        name.substringBeforeLast('.').trim().lowercase(Locale.ROOT)

    private companion object {
        const val MAX_DEPTH = 12

        /** Spellings tried for a sidecar lyric file, in order. */
        val LYRIC_EXTENSION_SPELLINGS = listOf(".lrc", ".LRC", ".Lrc")

        /** DSD is listed so that a scan still reports it; playback then explains why it cannot play. */
        val AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma", "ape", "alac", "mp4", "3gp",
            "dsf", "dff",
        )
    }
}

/** Extensions of Direct Stream Digital audio, for which no desktop decoder is wired up. */
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
