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
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
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

    /** Reads the raw text of a single `.lrc` file the user picked; null when unreadable or blank. */
    suspend fun readText(file: File): String? = withContext(Dispatchers.IO) {
        runCatching { decodeLyricBytes(file.readBytes()) }.getOrNull()?.takeIf { it.isNotBlank() }
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

    /**
     * `C:\Music\a.mp3` → the text of `C:\Music\a.lrc`, trying the common spellings of the suffix.
     *
     * Windows itself is case-insensitive, so the spellings mostly matter for a tree copied from
     * Linux; the directory listing at the end is the belt-and-braces case for the odd mix such as
     * `.lRc` that no fixed list would contain.
     */
    private fun readSiblingLyric(path: String): String? {
        val file = File(path)
        val parent = file.parentFile ?: return null
        val stem = file.name.substringBeforeLast('.', file.name)
        if (stem.isBlank()) return null
        val candidate = LYRIC_EXTENSION_SPELLINGS
            .asSequence()
            .map { File(parent, "$stem$it") }
            .firstOrNull { candidate -> runCatching { candidate.isFile && candidate.canRead() }.getOrDefault(false) }
            ?: lyricSiblingByListing(parent, stem)
            ?: return null
        return runCatching { decodeLyricBytes(candidate.readBytes()) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Last-resort sibling lookup: one directory listing, matched on the exact stem and any
     * capitalisation of the `.lrc` suffix.
     *
     * Only reached when every spelling above missed, so the readdir is paid for by the songs that
     * would otherwise import no lyrics at all.
     */
    private fun lyricSiblingByListing(parent: File, stem: String): File? = runCatching {
        parent.listFiles()?.firstOrNull { candidate ->
            candidate.isFile &&
                candidate.name.substringBeforeLast('.', candidate.name) == stem &&
                candidate.name.substringAfterLast('.', "").equals("lrc", ignoreCase = true)
        }
    }.getOrNull()

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
            // Pair on the audio *file* stem first, not on the tag title. A file named
            // `01 - Song.mp3` whose ID3 title is just `Song` would otherwise never find
            // `01 - Song.lrc` — the user sees a folder full of lyrics import none of them.
            val stem = song.localPath?.let { baseName(File(it).name) }?.takeIf { it.isNotBlank() }
            val key = stem?.takeIf { it in sidecars }
                ?: sidecars.keys.firstOrNull { it == baseName(song.title) }
                ?: return@forEach
            val sidecar = sidecars[key] ?: return@forEach
            runCatching { decodeLyricBytes(sidecar.readBytes()) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { out[song.id] = it }
        }
        return out
    }

    /** The `.lrc` sitting next to [file] with the same base name, if any. */
    private fun sidecarBeside(file: File): File? {
        val parent = file.parentFile ?: return null
        val stem = file.name.substringBeforeLast('.', file.name)
        if (stem.isBlank()) return null
        // Same suffix spellings as [readSiblingLyric]: a folder copied from a case-sensitive system
        // can hold `Song.LRC`, and a hard-coded lowercase name would miss it.
        return LYRIC_EXTENSION_SPELLINGS
            .asSequence()
            .map { File(parent, "$stem$it") }
            .firstOrNull { it.isFile }
            ?: lyricSiblingByListing(parent, stem)
    }

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

        /**
         * Spellings tried for a sidecar lyric file, in order.
         *
         * Both cases are listed because a folder copied from a case-sensitive source can hold
         * `Song.LRC`; anything rarer than these three is caught by [lyricSiblingByListing].
         */
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
 * Decodes a `.lrc`/`.txt` sidecar into text.
 *
 * `File.readText()` assumes UTF-8, and that assumption is wrong often enough to make a local lyric
 * import look broken: Chinese lyric sites still ship GBK, and files written by older Windows editors
 * are UTF-16 with a BOM. Decoding GBK bytes as UTF-8 either throws (so the sheet is silently
 * dropped) or produces mojibake (so the sheet is stored as garbage) — from the user's side both read
 * as "the lyrics will not import". That is why every read in this file goes through here instead of
 * calling `readText()`.
 *
 * The order is deliberate:
 *
 *  1. a BOM is the writer stating the encoding, so it wins outright;
 *  2. UTF-16 that lost its BOM is sniffed by its NUL pattern, because NUL is legal UTF-8 and the
 *     strict check below would otherwise accept that file and return a NUL after every character;
 *  3. strict UTF-8 — nearly every file, and the only case where the guess can be *proven*;
 *  4. GB18030, a superset of GBK and GB2312, which covers the Chinese legacy case the bug report is
 *     about;
 *  5. lenient UTF-8, so the function is total: a genuinely odd file still yields something the user
 *     can recognise as wrong instead of an empty lyric pane.
 */
internal fun decodeLyricBytes(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    when {
        // A leading U+FEFF as UTF-8 would otherwise survive into the first line and break the
        // `[ti:…]` tag match, so the BOM bytes are skipped rather than decoded.
        bytes.hasPrefix(UTF8_BOM) ->
            return String(bytes, UTF8_BOM.size, bytes.size - UTF8_BOM.size, Charsets.UTF_8)
        bytes.hasPrefix(UTF16_LE_BOM) -> return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        bytes.hasPrefix(UTF16_BE_BOM) -> return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
    }
    sniffBomlessUtf16(bytes)?.let { return it }
    strictDecode(bytes, Charsets.UTF_8)?.let { return it }
    strictDecode(bytes, GB18030)?.let { return it }
    return String(bytes, Charsets.UTF_8)
}

private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
private val UTF16_LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
private val UTF16_BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

/** GB18030 also covers GBK and GB2312, which is what Chinese lyric sites actually ship. */
private val GB18030: Charset = Charset.forName("GB18030")

private fun ByteArray.hasPrefix(prefix: ByteArray): Boolean {
    if (size < prefix.size) return false
    for (i in prefix.indices) if (this[i] != prefix[i]) return false
    return true
}

/** Decodes strictly, or returns null if the bytes are not valid in [charset]. */
private fun strictDecode(bytes: ByteArray, charset: Charset): String? = runCatching {
    charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
}.getOrNull()

/**
 * Decodes UTF-16 that has no BOM, or returns null when the bytes do not look like it.
 *
 * Latin text in UTF-16 is mostly `<char> 0x00` (LE) or `0x00 <char>` (BE), and those NULs are legal
 * UTF-8, so this has to run before the strict UTF-8 attempt. The 60% bar keeps a stray NUL in an
 * otherwise ordinary UTF-8 file from being read as UTF-16.
 */
private fun sniffBomlessUtf16(bytes: ByteArray): String? {
    // An even window, so that a byte pair never straddles the sample boundary.
    val sample = minOf(bytes.size, 4096) and 0x7FFFFFFE
    if (sample < 4) return null
    var evenZeros = 0
    var oddZeros = 0
    var i = 0
    while (i < sample) {
        if (bytes[i] == 0.toByte()) evenZeros++
        if (bytes[i + 1] == 0.toByte()) oddZeros++
        i += 2
    }
    val pairs = sample / 2
    return when {
        oddZeros * 10 >= pairs * 6 -> String(bytes, Charsets.UTF_16LE)
        evenZeros * 10 >= pairs * 6 -> String(bytes, Charsets.UTF_16BE)
        else -> null
    }
}

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
