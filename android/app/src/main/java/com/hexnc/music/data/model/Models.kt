package com.hexnc.music.data.model

/**
 * Where a track came from. NetEase tracks are streamed; local tracks are files on the device.
 */
enum class SongSource {
    LOCAL,
    NETEASE;

    companion object {
        fun fromKey(key: String?): SongSource =
            if (key.equals("NETEASE", ignoreCase = true)) NETEASE else LOCAL
    }
}

/**
 * A single playable track, either scanned from the device or synced from NetEase Cloud Music.
 *
 * [id] is the stable primary key. It is namespaced so that the same logical song can be held
 * both as a local file and as a NetEase stream without colliding:
 *  - local:    `local:<sha1 of normalized path/uri>`
 *  - netease:  `ne:<neteaseId>`
 */
data class Song(
    val id: String,
    val source: SongSource,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val albumId: Long? = null,
    val neteaseId: Long? = null,
    val durationMs: Long = 0L,
    /** `content://`, `file://` or `http(s)://` address used for playback. */
    val mediaUri: String? = null,
    val localPath: String? = null,
    val coverUrl: String? = null,
    /** User-supplied cover stored in the app's private cover folder; wins over [coverUrl]. */
    val coverOverridePath: String? = null,
    val trackNo: Int = 0,
    val bitrate: Int = 0,
    val size: Long = 0L,
    val addedAt: Long = 0L,
    val favorite: Boolean = false,
    val hasLyrics: Boolean = false
) {
    val displayArtist: String get() = artist.ifBlank { "—" }

    fun withFavorite(value: Boolean): Song = copy(favorite = value)
}

enum class PlaylistSource {
    LOCAL,
    NETEASE;

    companion object {
        fun fromKey(key: String?): PlaylistSource =
            if (key.equals("NETEASE", ignoreCase = true)) NETEASE else LOCAL
    }
}

/**
 * A playlist. NetEase playlists carry [neteaseId] so they can be re-synced in place;
 * local playlists are user-created and never overwritten by sync.
 */
data class Playlist(
    val id: String,
    val name: String,
    val source: PlaylistSource,
    val neteaseId: Long? = null,
    val description: String = "",
    val creator: String = "",
    val coverUrl: String? = null,
    val coverOverridePath: String? = null,
    val trackCount: Int = 0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val syncedAt: Long = 0L,
    val subscribed: Boolean = false,
    /** NetEase "special" playlists: 5 = liked songs, 4 = mv, 3 = radio. */
    val specialType: Int = 0,
    val playCount: Long = 0L
) {
    val isLikedSongs: Boolean get() = specialType == 5

    /**
     * True for the auto-maintained "all local music" playlist.
     *
     * It is filled by the repository on every import so that every scanned local song is reachable
     * somewhere: the library tab itself only ever renders a handful of rows, and a song past that
     * cut only shows up inside an album, an artist or a playlist.
     */
    val isAllLocal: Boolean get() = id == Ids.ALL_LOCAL_PLAYLIST_ID

    val displayCreator: String get() = creator.ifBlank { "—" }
}

/** A single line of a lyric track, with optional translation / romanisation / karaoke words. */
data class LyricLine(
    val timeMs: Long,
    val text: String,
    val translation: String? = null,
    val roman: String? = null,
    val durationMs: Long = 0L,
    val words: List<LyricWord> = emptyList()
)

/** Word-level timing used by NetEase `yrc` (karaoke) lyrics. */
data class LyricWord(
    val startMs: Long,
    val durationMs: Long,
    val text: String
)

/**
 * A fully parsed lyric set. [offsetMs] follows the LRC convention: the value is the delay in
 * milliseconds that should be **added** to every timestamp (NetEase/`[offset:]` semantics), so a
 * positive offset shifts lyrics later.
 */
data class Lyrics(
    val lines: List<LyricLine> = emptyList(),
    val offsetMs: Long = 0L,
    val rawLrc: String = "",
    val rawTlyric: String? = null,
    val rawRoma: String? = null,
    val rawYrc: String? = null,
    val isPureMusic: Boolean = false,
    val isUncollected: Boolean = false
) {
    val isEmpty: Boolean get() = lines.isEmpty()

    /**
     * True for a sheet whose lines carry no timestamps at all, e.g. a hand-written `.txt`-style
     * lyric file that was imported as one.
     *
     * Such a sheet can still be *displayed* — as a plain, scrollable block of text — but it has no
     * line to highlight, so the player must not try to follow it.
     */
    val isUnsynced: Boolean get() = lines.isNotEmpty() && lines.all { it.timeMs <= 0L }

    /** Index of the line that should be highlighted at [positionMs], or -1 before the first line. */
    fun indexAt(positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        val t = positionMs + offsetMs
        if (t < lines[0].timeMs) return -1
        var lo = 0
        var hi = lines.size - 1
        var result = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= t) {
                result = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return result
    }

    companion object {
        val EMPTY = Lyrics()
    }
}

/** A NetEase artist reference as returned by the search / detail endpoints. */
data class ArtistRef(val id: Long, val name: String)

/** Generic paged wrapper used by search results. */
data class Paged<T>(
    val items: List<T>,
    val total: Int = items.size,
    val hasMore: Boolean = false
)
