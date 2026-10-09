package com.hexnc.music.data.repo

import android.net.Uri
import com.hexnc.music.data.db.AlbumGroup
import com.hexnc.music.data.db.ArtistGroup
import com.hexnc.music.data.db.LyricDao
import com.hexnc.music.data.db.LyricDao.Origin
import com.hexnc.music.data.db.PlaylistDao
import com.hexnc.music.data.db.SongDao
import com.hexnc.music.data.db.SongOrder
import com.hexnc.music.data.local.CoverStore
import com.hexnc.music.data.local.LrcParser
import com.hexnc.music.data.model.Ids
import com.hexnc.music.data.model.Lyrics
import com.hexnc.music.data.model.Playlist
import com.hexnc.music.data.model.PlaylistSource
import com.hexnc.music.data.model.Song
import com.hexnc.music.data.model.SongSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Everything the UI can do to the on-device library.
 *
 * The repository owns a monotonically increasing [revision] counter instead of exposing reactive
 * database queries. Android's `SQLiteOpenHelper` has no change notifications, and hand-rolled
 * `ContentObserver` plumbing would be more code than it saves; a counter that every mutation bumps
 * lets a screen re-read whatever it needs with a single `LaunchedEffect(revision)`.
 *
 * All methods are safe to call from the main thread: each one moves the database work to
 * [Dispatchers.IO].
 */
class LibraryRepository(
    private val songs: SongDao,
    private val playlists: PlaylistDao,
    private val lyrics: LyricDao,
    private val covers: CoverStore,
) {

    private val _revision = MutableStateFlow(0L)

    /** Increments after every write, so screens can invalidate their cached reads. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    /**
     * Songs whose stored lyrics the user deleted during this session.
     *
     * A local `.lrc` is discovered lazily whenever a sheet is opened, so without this the very next
     * open — or the "scan local lyrics" repair action — would put back the sheet the user just
     * deleted. Picking a file for the song by hand clears the entry again.
     */
    private val lyricsDeleted =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    private fun touch() {
        _revision.value += 1
    }

    /**
     * Signals that a different component wrote to the database (a NetEase sync, most often).
     *
     * The revision counter is the single invalidation channel every screen listens to, so writers
     * outside this class must be able to bump it too.
     */
    fun notifyExternalChange() = touch()

    // ------------------------------------------------------------------ songs

    suspend fun songs(order: SongOrder = SongOrder.TITLE_ASC, limit: Int = 5000, offset: Int = 0): List<Song> =
        io { songs.all(order, limit, offset) }

    suspend fun songsOf(source: SongSource, order: SongOrder = SongOrder.TITLE_ASC): List<Song> =
        io { songs.bySource(source, order) }

    suspend fun favoriteSongs(order: SongOrder = SongOrder.ADDED_DESC): List<Song> = io { songs.favorites(order) }

    suspend fun recentlyAdded(limit: Int = 50): List<Song> = io { songs.recentlyAdded(limit) }

    suspend fun searchLibrary(text: String, limit: Int = 60): List<Song> =
        io { if (text.isBlank()) emptyList() else songs.search(text, limit) }

    suspend fun song(id: String): Song? = io { songs.byId(id) }

    suspend fun songsByIds(ids: List<String>): List<Song> =
        io { if (ids.isEmpty()) emptyList() else songs.byIds(ids) }

    suspend fun albums(): List<AlbumGroup> = io { songs.albums() }

    suspend fun albumSongs(album: String): List<Song> = io { songs.albumSongs(album) }

    suspend fun artists(): List<ArtistGroup> = io { songs.artists() }

    suspend fun artistSongs(artist: String): List<Song> = io { songs.artistSongs(artist) }

    suspend fun addSongs(imported: List<Song>): Int {
        if (imported.isEmpty()) return 0
        val inserted = io { songs.upsertAll(imported) }
        // Every local import lands in the reserved playlist, so a song can never be reachable only
        // through the album/artist pages the way it was before.
        if (imported.any { it.source == SongSource.LOCAL }) io { syncAllLocalPlaylist() }
        touch()
        return inserted
    }

    suspend fun bulkFavorite(ids: List<String>, value: Boolean) {
        if (ids.isEmpty()) return
        io { ids.forEach { songs.setFavorite(it, value) } }
        touch()
    }

    suspend fun deleteSongs(ids: List<String>): Int {
        if (ids.isEmpty()) return 0
        val removed = io {
            // Membership rows are cleared per song first: deleting the song row cascades, but doing
            // it explicitly keeps the join table consistent even for ids that were never stored.
            ids.forEach { playlists.removeSongEverywhere(it) }
            songs.deleteMany(ids)
        }
        touch()
        return removed
    }

    suspend fun libraryStats(): LibraryStats = io {
        LibraryStats(
            songCount = songs.count(),
            localCount = songs.count(SongSource.LOCAL),
            neteaseCount = songs.count(SongSource.NETEASE),
            favoriteCount = songs.favorites().size,
            playlistCount = playlists.all().size,
            lyricCount = lyrics.count(),
            totalDurationMs = songs.totalDurationMs(),
        )
    }

    // ------------------------------------------------------------------ playlists

    suspend fun playlists(): List<Playlist> = io { playlists.all() }

    suspend fun playlistsOf(source: PlaylistSource): List<Playlist> = io { playlists.bySource(source) }

    suspend fun playlist(id: String): Playlist? = io { playlists.byId(id) }

    /**
     * Creates the reserved "all local music" playlist if it is missing and makes it hold every local
     * song, so that a library scanned before this feature existed is filled in as well.
     *
     * @return the number of local songs the playlist now contains.
     */
    suspend fun ensureAllLocalPlaylist(): Int {
        val count = io { syncAllLocalPlaylist() }
        touch()
        return count
    }

    private fun syncAllLocalPlaylist(): Int {
        val now = System.currentTimeMillis()
        val existing = playlists.byId(Ids.ALL_LOCAL_PLAYLIST_ID)
        val ids = songs.bySource(SongSource.LOCAL, SongOrder.ADDED_DESC).map { it.id }
        // A library with no local music at all must not grow an empty placeholder, but an existing
        // playlist survives its last song being removed.
        if (ids.isEmpty() && existing == null) return 0
        if (existing == null) {
            playlists.upsert(
                Playlist(
                    id = Ids.ALL_LOCAL_PLAYLIST_ID,
                    name = ALL_LOCAL_PLAYLIST_NAME,
                    source = PlaylistSource.LOCAL,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        playlists.addSongs(Ids.ALL_LOCAL_PLAYLIST_ID, ids, allowDuplicate = false)
        return ids.size
    }

    suspend fun playlistSongs(playlistId: String, order: SongOrder = SongOrder.ADDED_DESC): List<Song> =
        io { if (order == SongOrder.ADDED_DESC) playlists.songs(playlistId) else playlists.songsSorted(playlistId, order) }

    suspend fun createPlaylist(name: String): Playlist {
        val now = System.currentTimeMillis()
        val playlist = Playlist(
            id = Ids.localPlaylistId(),
            name = name.trim().ifBlank { "Playlist" },
            source = PlaylistSource.LOCAL,
            createdAt = now,
            updatedAt = now,
        )
        io { playlists.upsert(playlist) }
        touch()
        return playlist
    }

    suspend fun renamePlaylist(id: String, name: String) {
        // The reserved playlist is titled by the UI in the user's language; its stored name is a
        // fallback only, so renaming it would just diverge from what is displayed.
        if (id == Ids.ALL_LOCAL_PLAYLIST_ID) return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        io { playlists.rename(id, trimmed) }
        touch()
    }

    suspend fun deletePlaylist(id: String): Int {
        // It is recreated by the next import anyway; deleting it would look like the songs were lost.
        if (id == Ids.ALL_LOCAL_PLAYLIST_ID) return 0
        val removed = io { playlists.delete(id) }
        touch()
        return removed
    }

    suspend fun addToPlaylist(playlistId: String, songIds: List<String>): Int {
        if (songIds.isEmpty()) return 0
        val added = io { playlists.addSongs(playlistId, songIds, allowDuplicate = false) }
        touch()
        return added
    }

    suspend fun removeFromPlaylist(playlistId: String, songId: String): Int {
        val removed = io { playlists.removeSong(playlistId, songId) }
        touch()
        return removed
    }

    /** Reorders a track within a playlist; indices are positions in the playlist, not song ids. */
    suspend fun moveInPlaylist(playlistId: String, from: Int, to: Int): Boolean {
        val moved = io { playlists.moveSong(playlistId, from, to) }
        if (moved) touch()
        return moved
    }

    suspend fun playlistsContaining(songId: String): List<Playlist> = io { playlists.playlistsContaining(songId) }

    // ------------------------------------------------------------------ lyrics

    suspend fun lyricsFor(songId: String): Lyrics? = io { lyrics.load(songId) }

    suspend fun hasLyrics(songId: String): Boolean = io { lyrics.has(songId) }

    /**
     * Stores a `.lrc` file the user picked for one song.
     *
     * The text is parsed before it is saved so that an unparseable file is rejected rather than
     * stored as an empty lyric sheet; the raw text is kept alongside the parsed lines so that the
     * original file can be exported again unchanged.
     */
    suspend fun importLyricText(songId: String, rawText: String, origin: Origin = Origin.SIDECAR): Boolean =
        saveLyrics(songId, rawText, origin)

    /**
     * Stores a lyric document fetched from any source — a sidecar file, NetEase, or an online
     * database — after parsing it.
     *
     * @param resurrect whether a sheet the user deleted for this song may be written again. The
     *   *automatic* paths (the local scan, the online search that runs when the lyric pane opens)
     *   must not: the user asked for no lyrics on this song, and a background pass silently undoing
     *   that is the kind of thing nobody can diagnose. An explicit tap in the search sheet passes
     *   `true`, because that click is a fresh decision by the user.
     */
    suspend fun saveLyrics(
        songId: String,
        rawText: String,
        origin: Origin = Origin.SIDECAR,
        resurrect: Boolean = false,
    ): Boolean {
        if (songId in lyricsDeleted && !resurrect) return false
        val parsed = parseLyricDocument(rawText)
        if (parsed.lines.isEmpty()) return false
        io {
            lyrics.save(songId, parsed, origin)
            songs.setHasLyrics(songId, true)
        }
        lyricsDeleted -= songId
        touch()
        return true
    }

    /**
     * Stores every sheet found by a local lyric scan in one go.
     *
     * A scan can turn up hundreds of pairs; saving them inside a single IO hop keeps the database
     * from being hit once per song while the UI waits for the counter.
     *
     * @return how many sheets were stored.
     */
    suspend fun importLyricTexts(found: Map<String, String>): Int {
        // A sheet the user deleted must not be resurrected by a scan; see [lyricsDeleted].
        val imports = found.filterKeys { it !in lyricsDeleted }
        if (imports.isEmpty()) return 0
        val stored = io {
            var count = 0
            imports.forEach { (songId, rawText) ->
                val parsed = parseLyricDocument(rawText)
                if (parsed.lines.isEmpty()) return@forEach
                lyrics.save(songId, parsed, Origin.SIDECAR)
                songs.setHasLyrics(songId, true)
                count++
            }
            count
        }
        if (stored > 0) touch()
        return stored
    }

    /** Local songs with no sheet stored, which is what a lyric re-scan has to look for. */
    suspend fun localSongsMissingLyrics(): List<Song> =
        io { songs.bySource(SongSource.LOCAL, SongOrder.TITLE_ASC).filterNot { lyrics.has(it.id) } }

    /**
     * Timed first: a document whose lines carry stamps is a real LRC file. Only when it has none is
     * it read as untimed text — which is how a hand-written sidecar still gets displayed, as static
     * lines, instead of being rejected as "not a lyric file".
     */
    private fun parseLyricDocument(rawText: String): Lyrics {
        val timed = LrcParser.parseLrc(rawText)
        return if (timed.lines.isNotEmpty()) timed else LrcParser.parsePlainText(rawText)
    }

    /** Attaches a translation or romanisation stream to an existing lyric sheet. */
    suspend fun importAuxiliaryLyric(songId: String, tlyric: String? = null, romalrc: String? = null) {
        io {
            lyrics.saveAuxiliary(songId, tlyric, romalrc)
            songs.setHasLyrics(songId, true)
        }
        touch()
    }

    suspend fun deleteLyrics(songId: String) {
        io {
            lyrics.delete(songId)
            songs.setHasLyrics(songId, false)
        }
        lyricsDeleted += songId
        touch()
    }

    /**
     * Forgets that the user deleted this song's lyrics.
     *
     * Needed by sources that write through their own DAO ([NeteaseRepository] keeps its own
     * `LyricDao` reference): the caller must clear the tombstone before the write, or the next scan
     * would refuse to restore what was just deliberately re-imported.
     */
    suspend fun allowLyricsAgain(songId: String) {
        lyricsDeleted -= songId
    }

    /** The lyric sheet as a `.lrc` document, for the share/export action. */
    suspend fun exportLyricText(songId: String): String? =
        io { lyrics.load(songId)?.let { LrcParser.writeLrc(it) } }

    // ------------------------------------------------------------------ covers

    /**
     * Stores a user-chosen image as a song's cover, replacing any previous override.
     *
     * @param scope a single track, or every track sharing an album.
     */
    suspend fun setCover(songId: String, image: Uri, scope: CoverScope): Boolean = io {
        val path = covers.import(image) ?: return@io false
        when (scope) {
            CoverScope.SONG -> songs.setCoverOverride(songId, path)
            CoverScope.ALBUM -> {
                val song = songs.byId(songId) ?: return@io false
                songs.setCoverOverride(songId, path)
                if (song.album.isNotBlank()) songs.setAlbumCoverOverride(song.album, path)
            }
        }
        covers.prune(referencedCovers())
        touch()
        true
    }

    suspend fun setPlaylistCover(playlistId: String, image: Uri): Boolean = io {
        val path = covers.import(image) ?: return@io false
        playlists.setCoverOverride(playlistId, path)
        covers.prune(referencedCovers())
        touch()
        true
    }

    /** Removes a cover override, falling back to the NetEase artwork or embedded art. */
    suspend fun clearCover(songId: String, scope: CoverScope): Boolean = io {
        val song = songs.byId(songId) ?: return@io false
        songs.setCoverOverride(songId, null)
        if (scope == CoverScope.ALBUM && song.album.isNotBlank()) {
            songs.setAlbumCoverOverride(song.album, null)
        }
        touch()
        true
    }

    suspend fun clearPlaylistCover(playlistId: String) {
        io { playlists.setCoverOverride(playlistId, null) }
        touch()
    }

    /** Every cover path still referenced by a row; the prune set for [CoverStore]. */
    private fun referencedCovers(): Set<String> {
        val fromSongs = songs
            .query("SELECT * FROM songs WHERE cover_override IS NOT NULL")
            .mapNotNull { it.coverOverridePath }
        val fromPlaylists = playlists
            .query("SELECT * FROM playlists WHERE cover_override IS NOT NULL")
            .mapNotNull { it.coverOverridePath }
        return (fromSongs + fromPlaylists).toSet()
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private companion object {
        /**
         * Stored fallback name of the reserved playlist. Every surface that shows it renders
         * `Strings.allLocalMusic` instead, so this only ever appears in raw database output.
         */
        const val ALL_LOCAL_PLAYLIST_NAME = "全部本地音乐"
    }
}

/** How widely a cover change applies. */
enum class CoverScope { SONG, ALBUM }

/** Aggregate counts for the library overview screen. */
data class LibraryStats(
    val songCount: Int,
    val localCount: Int,
    val neteaseCount: Int,
    val favoriteCount: Int,
    val playlistCount: Int,
    val lyricCount: Int,
    val totalDurationMs: Long,
)
