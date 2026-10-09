package com.hexnc.music.data.repo

import com.hexnc.music.data.db.LyricDao
import com.hexnc.music.data.lyrics.LrclibClient
import com.hexnc.music.data.lyrics.LyricCandidate
import com.hexnc.music.data.lyrics.LyricSource
import com.hexnc.music.data.lyrics.ScoredCandidate
import com.hexnc.music.data.lyrics.bestAutoCandidate
import com.hexnc.music.data.lyrics.rankCandidates
import com.hexnc.music.data.lyrics.searchKeyword
import com.hexnc.music.data.lyrics.stripReleaseNoise
import com.hexnc.music.data.lyrics.toCandidate
import com.hexnc.music.data.model.Song
import com.hexnc.music.data.netease.NcSong
import java.util.concurrent.ConcurrentHashMap

/** What one automatic online pass achieved. */
data class OnlineLyricOutcome(
    /** A sheet was found, was convincing enough, and is now stored. */
    val imported: Boolean = false,
    /** The candidate that was imported, when one was. */
    val candidate: LyricCandidate? = null,
    /** Everything the search turned up, best first — what the picker shows. */
    val suggestions: List<ScoredCandidate> = emptyList(),
    /** Deliberately not searched: the song already has lyrics, or it was searched moments ago. */
    val skipped: Boolean = false,
    /** The search itself failed (offline, rate-limited, timed out). */
    val failed: Boolean = false,
)

/** Result of the batch pass behind Settings ▸ Scan local lyrics. */
data class OnlineLyricReport(
    val searched: Int,
    val imported: Int,
    /** Songs still without a sheet when the batch stopped — either beyond the limit or unmatched. */
    val remaining: Int,
)

/**
 * Completes local lyrics from online sources.
 *
 * A local file carries tags and nothing else, so a miss in the sidecar scan left the lyric pane
 * permanently empty — even though the same song is one search away on two different databases. This
 * repository is the join between the two: [candidates] turns a [Song] into ranked possibilities, and
 * [autoComplete] imports one without asking when the match is beyond doubt.
 *
 * The automatic path is deliberately timid, and it is guarded twice: a song is searched at most once
 * per [SEARCH_TTL_MS] (the lyric pane re-runs its effect on every open, and a search per open would
 * hammer both services), and only a near-exact title/artist/duration match is imported — anything
 * less becomes a suggestion the user can pick. A wrong sheet that the user has no reason to suspect
 * is worse than no sheet at all.
 */
class OnlineLyricsRepository(
    private val netease: NeteaseRepository,
    private val lrclib: LrclibClient,
    private val library: LibraryRepository,
) {

    /** Song ids searched recently, so an idle pane-open does not become a network round trip. */
    private val searchedAt = ConcurrentHashMap<String, Long>()

    /**
     * Every candidate from every source for [song], ranked best-first.
     *
     * @param keyword a hand-edited query; when absent the song's own tags are used.
     */
    suspend fun candidates(song: Song, keyword: String? = null): List<ScoredCandidate> {
        val typed = keyword?.trim().orEmpty()
        val query = if (typed.isNotEmpty()) typed else searchKeyword(song.title, song.artist)
        val found = buildList {
            addAll(neteaseCandidates(query))
            // LRCLIB is only worth asking with the *file's own* tags: a hand-typed query is usually
            // meant for the catalogue the user actually has in mind.
            addAll(lrclibCandidates(if (typed.isEmpty()) song else song.copy(title = typed, artist = "")))
        }
        return rankCandidates(song.title, song.artist, song.durationMs, found)
    }

    /**
     * Stores one candidate the user chose, or that [autoComplete] accepted.
     *
     * The import is explicit either way, so it is allowed to overwrite a sheet the user previously
     * deleted — see [LibraryRepository.saveLyrics]'s `resurrect`.
     */
    suspend fun importCandidate(song: Song, candidate: LyricCandidate): Boolean = when (candidate.source) {
        LyricSource.NETEASE -> {
            val id = candidate.neteaseId ?: candidate.id.toLongOrNull() ?: 0L
            if (id <= 0L) {
                false
            } else {
                // NetEase writes through its own DAO, which does not know about the deleted-sheet set.
                library.allowLyricsAgain(song.id)
                netease.importLyricsFor(song.id, id)
            }
        }

        LyricSource.LRCLIB -> {
            val text = candidate.syncedLyrics?.takeIf { it.isNotBlank() }
                ?: candidate.plainLyrics?.takeIf { it.isNotBlank() }
            if (text == null) false else library.saveLyrics(song.id, text, LyricDao.Origin.LRCLIB, resurrect = true)
        }
    }

    /**
     * One automatic attempt for [song].
     *
     * @param force ignore the recently-searched guard (used by the explicit search button).
     */
    suspend fun autoComplete(song: Song, force: Boolean = false): OnlineLyricOutcome {
        if (library.hasLyrics(song.id)) return OnlineLyricOutcome(skipped = true)
        if (!force && !claimSearch(song.id)) return OnlineLyricOutcome(skipped = true)
        val suggestions = runCatching { candidates(song) }.getOrElse {
            // Let a failed search be retried; an offline minute must not blacklist the song.
            searchedAt.remove(song.id)
            return OnlineLyricOutcome(failed = true)
        }
        val best = bestAutoCandidate(
            title = song.title,
            artist = song.artist,
            durationMs = song.durationMs,
            candidates = suggestions.map { it.candidate },
        ) ?: return OnlineLyricOutcome(suggestions = suggestions)
        val stored = runCatching { importCandidate(song, best) }.getOrDefault(false)
        return OnlineLyricOutcome(
            imported = stored,
            candidate = best,
            suggestions = suggestions,
            failed = !stored,
        )
    }

    /**
     * Runs [autoComplete] over the songs that still have no sheet, best effort.
     *
     * @param limit how many songs to try, so a tap on a 3 000-song library is still bounded work.
     * @param onProgress called after each song with `(searched, imported)`.
     */
    suspend fun autoCompleteAll(
        limit: Int = DEFAULT_BATCH_LIMIT,
        onProgress: ((searched: Int, imported: Int) -> Unit)? = null,
    ): OnlineLyricReport {
        val pending = library.localSongsMissingLyrics()
        val batch = pending.take(limit)
        var imported = 0
        batch.forEachIndexed { index, song ->
            if (autoComplete(song).imported) imported++
            onProgress?.invoke(index + 1, imported)
        }
        return OnlineLyricReport(
            searched = batch.size,
            imported = imported,
            remaining = (pending.size - batch.size).coerceAtLeast(0),
        )
    }

    /** True when this song may be searched now; records the attempt so the next open is free. */
    private fun claimSearch(songId: String): Boolean {
        val now = System.currentTimeMillis()
        val previous = searchedAt.put(songId, now)
        return previous == null || now - previous >= SEARCH_TTL_MS
    }

    private suspend fun neteaseCandidates(keyword: String): List<LyricCandidate> {
        if (keyword.isBlank()) return emptyList()
        val result = netease.search(keyword, type = 1, limit = NETEASE_LIMIT).getOrNull() ?: return emptyList()
        return result.songs.map { it.toLyricCandidate() }
    }

    private suspend fun lrclibCandidates(song: Song): List<LyricCandidate> {
        val title = stripReleaseNoise(song.title).ifBlank { song.title }
        if (title.isBlank()) return emptyList()
        return lrclib.search(title, song.artist, song.album).map { it.toCandidate(title) }
    }

    companion object {
        /** Long enough that browsing between panes does not re-search, short enough to notice a fix. */
        private const val SEARCH_TTL_MS = 10 * 60 * 1000L

        private const val NETEASE_LIMIT = 15

        /** Bounds one Settings tap: two sources per song, one paced NetEase call each. */
        const val DEFAULT_BATCH_LIMIT = 20
    }
}

/**
 * Turn a catalogue hit into a rankable candidate.
 *
 * `hasSynced` is assumed rather than known: NetEase returns the lyric document on a *second*
 * request (see [NeteaseRepository.importLyricsFor]), so the search hit cannot say whether the lines
 * are timed. Assuming timed lets a NetEase hit win a tie against a plain-only rival, which is the
 * right call — its document, when it arrives, carries translations and romanisations too.
 */
internal fun NcSong.toLyricCandidate(): LyricCandidate = LyricCandidate(
    source = LyricSource.NETEASE,
    id = id.toString(),
    title = name,
    artist = artistNames,
    album = effectiveAlbum.name,
    durationMs = durationMs,
    hasSynced = true,
    neteaseId = id,
)
