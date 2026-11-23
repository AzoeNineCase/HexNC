package com.hexnc.music.data.repo

import com.hexnc.music.data.db.CachedAlbum
import com.hexnc.music.data.db.LyricDao
import com.hexnc.music.data.db.LyricDao.Origin
import com.hexnc.music.data.db.NeteaseAlbumDao
import com.hexnc.music.data.db.PlaylistDao
import com.hexnc.music.data.db.SongDao
import com.hexnc.music.data.local.LrcParser
import com.hexnc.music.data.model.Ids
import com.hexnc.music.data.model.Lyrics
import com.hexnc.music.data.model.Playlist
import com.hexnc.music.data.model.PlaylistSource
import com.hexnc.music.data.model.Song
import com.hexnc.music.data.model.SongSource
import com.hexnc.music.data.netease.NeteaseApi
import com.hexnc.music.data.netease.NeteaseClient
import com.hexnc.music.data.netease.NeteaseCookieStore
import com.hexnc.music.data.netease.NeteaseException
import com.hexnc.music.data.netease.NcAlbum
import com.hexnc.music.data.netease.NcLyricResponse
import com.hexnc.music.data.netease.NcPlaylist
import com.hexnc.music.data.netease.NcSearchResult
import com.hexnc.music.data.netease.NcSong
import com.hexnc.music.data.netease.NcSongUrl
import com.hexnc.music.data.netease.intOrNull
import com.hexnc.music.data.netease.parseCookieInput
import com.hexnc.music.data.netease.trackIdList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** The signed-in NetEase account, or [NeteaseSession.SIGNED_OUT]. */
data class NeteaseSession(
    val userId: Long,
    val nickname: String,
    val avatarUrl: String?,
    val signature: String?,
) {
    val isSignedIn: Boolean get() = userId > 0L

    companion object {
        val SIGNED_OUT = NeteaseSession(0L, "", null, null)
    }
}

/** Progress of a QR sign-in attempt. */
enum class QrStage {
    /** The code is on screen and nobody has scanned it. */
    WAITING,

    /** Scanned, but the user has not confirmed on their phone yet. */
    SCANNED,

    /** Confirmed; the session is now valid. */
    AUTHORISED,

    /** The code timed out and a new one must be requested. */
    EXPIRED,

    /**
     * Risk control stopped the attempt and wants a captcha solved first.
     *
     * The service answers `-462` with a `verifyUrl`; this never clears by itself, so the caller must
     * show the challenge instead of continuing to poll.
     */
    VERIFY_REQUIRED;

    companion object {
        /** "Complete the verification first" — the service's risk-control code. */
        const val RISK_CONTROL = -462

        /**
         * Maps a `/api/login/qrcode/client/login` result code onto a stage.
         *
         * `801` is the *only* code that means "keep polling". An unrecognised code resolves to
         * [EXPIRED] rather than [WAITING] on purpose: silently treating an unmapped code as "still
         * waiting" is what previously turned the `-462` risk-control gate into an endless spinner
         * that only ended in a misleading "QR code expired".
         */
        fun fromCode(code: Int): QrStage = when (code) {
            801 -> WAITING
            802 -> SCANNED
            803 -> AUTHORISED
            800 -> EXPIRED
            RISK_CONTROL -> VERIFY_REQUIRED
            else -> EXPIRED
        }
    }
}

/**
 * One poll of a QR attempt: the [stage] plus whatever the server said about it.
 *
 * [verifyUrl] is populated only when [stage] is [QrStage.VERIFY_REQUIRED].
 */
data class QrPoll(
    val stage: QrStage,
    val message: String? = null,
    val verifyUrl: String? = null,
    val code: Int = 200,
) {
    companion object {
        val WAITING = QrPoll(QrStage.WAITING)
    }
}

/** What a sync is currently doing, for the progress indicator. */
enum class SyncPhase { PLAYLISTS, TRACKS, ALBUMS, LYRICS, CLEANUP, DONE }

data class SyncProgress(
    val phase: SyncPhase,
    val done: Int,
    val total: Int,
    val label: String? = null,
) {
    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}

/** The outcome of a full library sync. */
data class SyncReport(
    val playlists: Int = 0,
    val tracks: Int = 0,
    val newTracks: Int = 0,
    val lyrics: Int = 0,
    /** Favourited albums written to the cache; 0 when the album read failed (see [failures]). */
    val albums: Int = 0,
    /** Playlists left untouched because their track count had not changed since the last sync. */
    val skipped: Int = 0,
    val removedPlaylists: Int = 0,
    /** Non-fatal problems. A sync with entries here still applied everything else. */
    val failures: List<String> = emptyList(),
) {
    val isClean: Boolean get() = failures.isEmpty()
}

/** One page of the favourited-album listing, with the paging metadata the UI needs. */
data class AlbumPage(
    val albums: List<NcAlbum> = emptyList(),
    /** Server-side total, when the reply carried one. */
    val total: Int? = null,
    /** Whether another page exists, by either transport's flag. */
    val more: Boolean = false,
    val offset: Int = 0,
    val code: Int = 200,
)

/** A stream URL together with the moment it stops being usable. */
/**
 * What the service actually returned for a track, as opposed to the tier that was requested.
 *
 * The app asks for `hires` and receives the best it is entitled to, which may be a 24-bit FLAC, a
 * 16-bit FLAC or a 320 kbps MP3 — and, for tracks whose copyright never licensed a lossless master,
 * nothing better than that. Nothing in the UI should claim a tier the service did not grant, so the
 * granted container and bitrate travel with the stream.
 */
data class GrantedQuality(
    val container: String,
    /** Bits per second, as reported by the service; 0 when it did not say. */
    val bitrate: Int,
    val level: String,
) {
    /** True when the track plays below lossless, i.e. when "lossless" was refused. */
    val belowLossless: Boolean
        get() = level.lowercase() !in LOSSLESS_LEVELS

    companion object {
        private val LOSSLESS_LEVELS = setOf("lossless", "hires")
    }
}

/** A stream URL together with the moment it stops being usable. */
private data class CachedStream(val url: String, val expiresAtMs: Long, val level: String)

/** How long a completed sync is considered fresh enough for the automatic launch pass. */
private const val AUTO_SYNC_INTERVAL_MS = 10L * 60L * 1000L

/** Hard cap on the album listing, so a lying paging flag cannot loop forever. */
private const val MAX_ALBUM_PAGES = 20

/**
 * NetEase Cloud Music: account, library sync, search and streaming.
 *
 * Sync is *reconciling* rather than destructive — NetEase playlists are mirrored into the local
 * `playlists` table under their own ids and their membership is reconciled in place, so a user's
 * favourite flags, cover overrides and imported lyrics all survive a re-sync. Playlists that
 * disappeared server-side are reported and dropped only when the caller asks for it.
 *
 * Every remote read is paced: the service rate-limits aggressively and answers a burst with
 * zero-byte bodies, so requests are spaced with jitter and retried with exponential backoff.
 */
class NeteaseRepository(
    private val api: NeteaseApi,
    private val cookieStore: NeteaseCookieStore,
    private val songs: SongDao,
    private val playlists: PlaylistDao,
    private val lyrics: LyricDao,
    private val library: LibraryRepository,
    private val albums: NeteaseAlbumDao,
) {

    /**
     * Serialises syncs.
     *
     * The launch refresh and the "sync now" button can fire at the same moment; without a lock they
     * interleave their membership writes, and the reconcile step of the slower one then deletes rows
     * the faster one had just added.
     */
    private val syncLock = Mutex()

    /** Page size for the album listing; the service silently caps anything above 100. */
    private val albumPageSize = 100

    private val _session = MutableStateFlow(NeteaseSession.SIGNED_OUT)

    /** The signed-in account; starts signed out and is refreshed from the cookie jar on launch. */
    val session: StateFlow<NeteaseSession> = _session.asStateFlow()

    /** Stream URLs expire after about twenty minutes, so they are cached only briefly. */
    private val streamCache = HashMap<Long, CachedStream>()

    private val _granted = MutableStateFlow<Map<Long, GrantedQuality>>(emptyMap())

    /**
     * The tier the service granted for each recently resolved track, newest last.
     *
     * Kept as state rather than a single "last" value because the player resolves a whole queue in
     * one batch: a screen showing one song must be able to ask about *that* song, not about whichever
     * entry happened to come back last.
     */
    val granted: StateFlow<Map<Long, GrantedQuality>> = _granted.asStateFlow()

    // ------------------------------------------------------------------ session

    /** Re-reads the account from the service; the source of truth for [session]. */
    suspend fun refreshSession(): NeteaseSession {
        if (!cookieStore.isLoggedIn) {
            _session.value = NeteaseSession.SIGNED_OUT
            return NeteaseSession.SIGNED_OUT
        }
        return runCatching {
            val response = api.account()
            val profile = response.profile
            val userId = profile?.userId ?: response.account?.id ?: 0L
            if (userId <= 0L) {
                NeteaseSession.SIGNED_OUT
            } else {
                NeteaseSession(
                    userId = userId,
                    nickname = profile?.nickname.orEmpty(),
                    avatarUrl = profile?.avatarUrl,
                    signature = profile?.signature,
                )
            }
        }.getOrElse { NeteaseSession.SIGNED_OUT }
            .also { _session.value = it }
    }

    suspend fun signOut() {
        cookieStore.clear()
        streamCache.clear()
        _session.value = NeteaseSession.SIGNED_OUT
    }

    /** Requests a QR handle and returns the string to render as `https://music.163.com/login?codekey=…`. */
    suspend fun startQrLogin(): String = api.qrCreate()

    /** The URL a QR code image should encode. */
    fun qrLoginUrl(unikey: String): String = "https://music.163.com/login?codekey=$unikey"

    /**
     * One poll of a QR login.
     *
     * On [QrStage.AUTHORISED] the session is already refreshed, because the server's `Set-Cookie`
     * headers have been absorbed by the transport before this returns. On
     * [QrStage.VERIFY_REQUIRED] the service has stopped the attempt behind a captcha and further
     * polling cannot help; [QrPoll.verifyUrl] names the page that has to be opened.
     */
    suspend fun pollQrLogin(unikey: String): QrPoll {
        val response = api.qrCheck(unikey)
        val stage = QrStage.fromCode(response.code)
        if (stage == QrStage.AUTHORISED) refreshSession()
        return QrPoll(
            stage = stage,
            message = response.data?.blockText ?: response.message,
            verifyUrl = response.data?.challengeUrl,
            code = response.code,
        )
    }

    /**
     * Polls [unikey] until it is authorised, expires, or is stopped by risk control.
     *
     * Returns the session on success and null otherwise; [onStage] runs once per poll so the caller
     * can render progress. The loop stops immediately on [QrStage.VERIFY_REQUIRED]: the captcha gate
     * does not clear on its own, and continuing to poll only burns the request budget.
     */
    suspend fun awaitQrLogin(
        unikey: String,
        pollIntervalMs: Long = 3000L,
        onStage: (QrPoll) -> Unit = {},
    ): NeteaseSession? {
        val deadline = System.currentTimeMillis() + QR_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val poll = runCatching { pollQrLogin(unikey) }.getOrDefault(QrPoll.WAITING)
            onStage(poll)
            when (poll.stage) {
                QrStage.AUTHORISED -> return _session.value
                QrStage.EXPIRED, QrStage.VERIFY_REQUIRED -> return null
                else -> delay(pollIntervalMs)
            }
        }
        onStage(QrPoll(QrStage.EXPIRED))
        return null
    }

    suspend fun loginWithCellphone(phone: String, password: String): Result<NeteaseSession> =
        runCatching {
            val response = api.loginCellphone(phone, password)
            cookiesFromLoginResponse(response.cookie)
            refreshSession().also { if (!it.isSignedIn) throw NeteaseException(response.code, "login incomplete") }
        }

    suspend fun loginWithEmail(email: String, password: String): Result<NeteaseSession> =
        runCatching {
            val response = api.loginEmail(email, password)
            cookiesFromLoginResponse(response.cookie)
            refreshSession().also { if (!it.isSignedIn) throw NeteaseException(response.code, "login incomplete") }
        }

    /**
     * The login responses return the whole cookie set as one string, which is the only place the
     * long-lived `MUSIC_U` token is visible in a parsed form; absorb it before asking who we are.
     */
    private fun cookiesFromLoginResponse(cookie: String?) {
        if (cookie.isNullOrBlank()) return
        cookieStore.absorb(cookie.split(';').map { it.trim() }.filter { it.isNotEmpty() })
    }

    /**
     * Adopts an existing browser session instead of going through the login endpoints.
     *
     * This is the escape hatch for accounts that risk control refuses to let this client sign in:
     * the user signs in at `music.163.com` in a browser, copies the cookies, and pastes them here.
     * Only `MUSIC_U` is strictly required, because that is the token that identifies the session;
     * `__csrf` is kept alongside it because the eapi-signed mutating calls (like/unlike, playlist
     * edits) sign their payload header with it.
     *
     * @param raw a `name=value; name=value` cookie header, or a bare `MUSIC_U` token.
     */
    suspend fun importSessionCookie(raw: String): Result<NeteaseSession> = runCatching {
        val pairs = parseCookieInput(raw)
        if (pairs.isEmpty()) throw NeteaseException(-1, "no cookies supplied")
        cookieStore.absorb(pairs)
        if (!cookieStore.isLoggedIn) throw NeteaseException(-1, "MUSIC_U cookie is missing")
        val session = refreshSession()
        if (!session.isSignedIn) throw NeteaseException(-1, "the service rejected these cookies")
        session
    }

    // ------------------------------------------------------------------ sync

    /**
     * Mirrors every NetEase playlist of the signed-in user into the local database.
     *
     * @param withLyrics also downloads lyrics for tracks that do not have any yet. This is one
     *   request per track, so it is off by default and paced when on.
     * @param dropRemoved delete `netease:` playlists the account no longer has. Off by default
     *   because unlinking a subscription on the phone and losing the local copy on the next sync is
     *   almost never what the user wants.
     */
    suspend fun syncLibrary(
        withLyrics: Boolean = false,
        dropRemoved: Boolean = false,
        onProgress: (SyncProgress) -> Unit = {},
    ): Result<SyncReport> = syncLock.withLock {
        syncLibraryLocked(withLyrics, dropRemoved, onProgress)
    }

    private suspend fun syncLibraryLocked(
        withLyrics: Boolean,
        dropRemoved: Boolean,
        onProgress: (SyncProgress) -> Unit,
    ): Result<SyncReport> = withContext(Dispatchers.IO) {
        runCatching {
            val account = refreshSession()
            if (!account.isSignedIn) throw NeteaseException(301, "not signed in")

            val failures = mutableListOf<String>()

            onProgress(SyncProgress(SyncPhase.PLAYLISTS, 0, 1))
            val remote = api.userPlaylists(account.userId)
                .filter { it.id > 0L }
                .distinctBy { it.id }
            onProgress(SyncProgress(SyncPhase.PLAYLISTS, 1, 1))

            // ---- playlists -------------------------------------------------
            val now = System.currentTimeMillis()
            val mapped = remote.map { it.toPlaylist(now) }
            playlists.upsertAll(mapped)

            val removed = if (dropRemoved) {
                onProgress(SyncProgress(SyncPhase.CLEANUP, 0, 1))
                val dropped = playlists.deleteNeteaseNotIn(remote.map { it.id }, emptySet())
                onProgress(SyncProgress(SyncPhase.CLEANUP, 1, 1))
                dropped.size
            } else {
                0
            }

            // ---- track ids -------------------------------------------------
            // `/api/user/playlist` lists the playlists but carries no usable track data: every
            // entry has `tracks: null` and no `trackIds` key at all, so the ids have to be fetched
            // per playlist. `trackIds` on `/api/v6/playlist/detail` is complete and ordered.
            // ---- which playlists actually need a detail read ----------------
            // An unchanged `trackCount` is the cheap signal that nothing was added or removed
            // server-side. Re-reading every playlist's ids on every launch is what made the old
            // "sync" feel like a full download; the incremental pass touches only what moved.
            val unchanged = remote.filter { nc ->
                val stored = playlists.byNeteaseId(nc.id)
                stored != null && nc.trackCount > 0 && stored.trackCount == nc.trackCount &&
                    stored.syncedAt > 0
            }.map { it.id }.toHashSet()

            val perPlaylistIds = LinkedHashMap<Long, List<Long>>(remote.size)
            val partial = HashSet<Long>()
            onProgress(SyncProgress(SyncPhase.PLAYLISTS, 0, remote.size))
            remote.forEachIndexed { index, nc ->
                if (nc.id in unchanged) {
                    onProgress(SyncProgress(SyncPhase.PLAYLISTS, index + 1, remote.size, nc.name))
                    return@forEachIndexed
                }
                val detail = runCatching { paced { api.playlistDetail(nc.id) } }
                    .getOrElse { error ->
                        failures += "${nc.name}: ${error.message}"
                        null
                    }
                // A playlist whose ids could not be fetched is simply absent from the map, which is
                // what keeps its existing membership intact in the reconcile step below.
                if (detail != null) {
                    val ids = detail.trackIdList()
                    if (ids.size < nc.trackCount) {
                        // Fewer ids than the listing advertises: a track has lost copyright and is
                        // absent from `trackIds`. The list is still merged — append-only, because
                        // reconciling a short list would delete real membership — so the rest of
                        // the playlist syncs instead of the whole playlist being dropped.
                        failures += "${nc.name}: ${ids.size}/${nc.trackCount} tracks"
                        partial += nc.id
                    }
                    perPlaylistIds[nc.id] = ids
                }
                onProgress(SyncProgress(SyncPhase.PLAYLISTS, index + 1, remote.size, nc.name))
            }

            // ---- tracks ----------------------------------------------------
            val allIds = perPlaylistIds.values.flatten().distinct()
            onProgress(SyncProgress(SyncPhase.TRACKS, 0, allIds.size))

            var fetched = 0
            // A page that failed leaves its ids missing from `idByNetease`, so membership must not
            // be reconciled at all after a partial hydration — see the comment below.
            var hydrated = true
            val knownBefore = songs.byNeteaseIds(allIds).map { it.neteaseId }.toHashSet()
            val ncSongs = ArrayList<NcSong>(allIds.size)
            allIds.chunked(NeteaseClient.SONG_DETAIL_CHUNK).forEach { chunk ->
                val batch = runCatching { paced { api.songDetail(chunk) } }
                    .getOrElse { error ->
                        failures += "tracks: ${error.message}"
                        hydrated = false
                        emptyList()
                    }
                ncSongs += batch
                fetched += chunk.size
                onProgress(SyncProgress(SyncPhase.TRACKS, fetched, allIds.size))
            }

            val mappedSongs = ncSongs.map { it.toSong() }
            val inserted = songs.upsertAll(mappedSongs)
            val newIds = mappedSongs.map { it.id }.filterNot { id ->
                val neteaseId = id.removePrefix(Ids.NETEASE_PREFIX).toLongOrNull()
                neteaseId != null && neteaseId in knownBefore
            }

            // ---- membership -----------------------------------------------
            // `reconcileMembers` is destructive — anything missing from the list it is handed is
            // deleted — so it runs only when the ids are known to be complete: no failed detail
            // fetch (absent from `perPlaylistIds`) and no failed song page (`hydrated`).
            if (hydrated) {
                val idByNetease = songs.byNeteaseIds(allIds)
                    .mapNotNull { stored -> stored.neteaseId?.let { it to stored.id } }
                    .toMap()
                remote.forEach { nc ->
                    val ids = perPlaylistIds[nc.id] ?: return@forEach
                    val songIds = ids.mapNotNull { idByNetease[it] }
                    val localId = Ids.neteasePlaylistId(nc.id)
                    // A short id list can only be trusted to *add*: removing from it would delete
                    // tracks that are merely unlisted, not gone.
                    if (nc.id in partial) {
                        playlists.appendMissing(localId, songIds)
                    } else {
                        playlists.reconcileMembers(localId, songIds)
                    }
                    playlists.setSyncedAt(localId, now)
                }
            }

            // ---- lyrics (optional) ----------------------------------------
            var lyricCount = 0
            if (withLyrics) {
                val pending = newIds.ifEmpty { mappedSongs.map { it.id } }
                    .filter { !lyrics.has(it) }
                onProgress(SyncProgress(SyncPhase.LYRICS, 0, pending.size))
                pending.forEachIndexed { index, songId ->
                    val neteaseId = songId.removePrefix(Ids.NETEASE_PREFIX).toLongOrNull()
                    if (neteaseId != null) {
                        val ok = runCatching { downloadLyrics(songId, neteaseId) }.getOrDefault(false)
                        if (ok) lyricCount++
                    }
                    onProgress(SyncProgress(SyncPhase.LYRICS, index + 1, pending.size, songId))
                }
            }

            // ---- favourited albums ----------------------------------------
            // Read as part of the library sync rather than on page entry: the page then renders
            // from cache, and a failed read degrades to "stale + retry" instead of "no albums".
            var albumCount = 0
            onProgress(SyncProgress(SyncPhase.ALBUMS, 0, 1))
            runCatching { fetchAllAlbums() }
                .onSuccess { albumCount = it.size }
                .onFailure { failures += "albums: ${it.message}" }
            onProgress(SyncProgress(SyncPhase.ALBUMS, 1, 1))

            // Records the timestamp only; it never gates the next sync.
            cookieStore.markLibrarySynced(account.userId)

            onProgress(SyncProgress(SyncPhase.DONE, 1, 1))
            library.notifyExternalChange()

            SyncReport(
                playlists = mapped.size,
                tracks = mappedSongs.size,
                newTracks = inserted,
                lyrics = lyricCount,
                albums = albumCount,
                skipped = unchanged.size,
                removedPlaylists = removed,
                failures = failures,
            )
        }
    }

    /**
     * Runs the automatic sync when the account has not synced recently enough.
     *
     * This replaces the old one-shot "import once per account" marker. That marker was the whole
     * reason a phone-side playlist change never arrived until the app was wiped and the account
     * signed in again: the second launch of an account was silently skipped, and the only thing that
     * cleared the marker was a full data wipe. The timestamp is now used purely to decide whether a
     * refresh is *due*, so a sync can always be forced by the button and the launch pass simply
     * skips when it just ran.
     *
     * @return `null` when signed out or when a sync finished less than [minIntervalMs] ago.
     */
    suspend fun syncLibraryIfDue(
        minIntervalMs: Long = AUTO_SYNC_INTERVAL_MS,
        onProgress: (SyncProgress) -> Unit = {},
    ): Result<SyncReport>? {
        val account = refreshSession()
        if (!account.isSignedIn) return null
        val last = cookieStore.lastLibrarySyncAt(account.userId)
        if (last > 0L && System.currentTimeMillis() - last < minIntervalMs) return null
        return syncLibrary(withLyrics = false, onProgress = onProgress)
    }

    /** Re-syncs one playlist in place. Returns the number of tracks now in it. */
    suspend fun syncPlaylist(neteaseId: Long, withLyrics: Boolean = false): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val nc = paced { api.playlistDetail(neteaseId) }
                val ids = nc.trackIdList()
                // Reconciling a short id list would delete real membership rows, so an incomplete
                // response is reported as a failure and leaves the local copy untouched.
                if (nc.trackCount > 0 && ids.size < nc.trackCount) {
                    throw NeteaseException(-1, "${nc.name}: ${ids.size}/${nc.trackCount} tracks")
                }
                val now = System.currentTimeMillis()
                playlists.upsert(nc.toPlaylist(now))

                val ncSongs = ids.chunked(NeteaseClient.SONG_DETAIL_CHUNK)
                    .flatMap { paced { api.songDetail(it) } }
                songs.upsertAll(ncSongs.map { it.toSong() })
                val idByNetease = songs.byNeteaseIds(ids)
                    .mapNotNull { stored -> stored.neteaseId?.let { it to stored.id } }
                    .toMap()
                val songIds = ids.mapNotNull { idByNetease[it] }
                playlists.reconcileMembers(Ids.neteasePlaylistId(neteaseId), songIds)
                playlists.setSyncedAt(Ids.neteasePlaylistId(neteaseId), now)

                if (withLyrics) {
                    songIds.forEach { songId ->
                        if (!lyrics.has(songId)) {
                            songId.removePrefix(Ids.NETEASE_PREFIX).toLongOrNull()?.let {
                                runCatching { downloadLyrics(songId, it) }
                            }
                        }
                    }
                }
                library.notifyExternalChange()
                songIds.size
            }
        }

    /** Adds a single NetEase track to the local library, metadata plus lyrics. */
    suspend fun importTrack(neteaseId: Long, withLyrics: Boolean = true): Result<Song?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val nc = paced { api.songDetail(neteaseId) } ?: return@runCatching null
                val song = nc.toSong()
                songs.upsert(song)
                if (withLyrics) runCatching { downloadLyrics(song.id, neteaseId) }
                library.notifyExternalChange()
                songs.byId(song.id)
            }
        }

    /**
     * Downloads and stores every lyric stream the service holds for a track.
     *
     * `lrc` is the base, `tlyric` the translation and `romalrc` the romanisation; `yrc` is the
     * word-by-word stream and is used in preference to `lrc` when present, because it carries
     * per-word timings that the karaoke view needs and `lrc` does not.
     */
    private suspend fun downloadLyrics(songId: String, neteaseId: Long): Boolean {
        val response: NcLyricResponse = paced { api.lyrics(neteaseId) }
        if (response.nolyric) {
            // Instrumental: record the fact so the UI can say so instead of retrying forever.
            lyrics.save(songId, Lyrics.EMPTY.copy(isPureMusic = true), Origin.NETEASE)
            songs.setHasLyrics(songId, false)
            return false
        }
        val baseRaw = response.lrc?.lyric.orEmpty()
        val yrcRaw = response.yrc?.lyric.orEmpty()
        val tlyric = response.tlyric?.lyric
        val romalrc = response.romalrc?.lyric

        val parsed = if (yrcRaw.isNotBlank()) {
            LrcParser.parseYrc(yrcRaw)
        } else {
            LrcParser.parseLrc(baseRaw)
        }
        if (parsed.lines.isEmpty()) {
            if (response.uncollected) {
                lyrics.save(songId, parsed.copy(isUncollected = true), Origin.NETEASE)
            }
            return false
        }
        val merged = LrcParser.merge(parsed, tlyric, romalrc)
        lyrics.save(songId, merged, Origin.NETEASE)
        songs.setHasLyrics(songId, true)
        return true
    }

    /** Re-fetches lyrics for one already-imported track, e.g. from a "retry" action. */
    suspend fun refreshLyrics(songId: String): Boolean = withContext(Dispatchers.IO) {
        val song = songs.byId(songId) ?: return@withContext false
        val neteaseId = song.neteaseId ?: return@withContext false
        val ok = runCatching { downloadLyrics(songId, neteaseId) }.getOrDefault(false)
        library.notifyExternalChange()
        ok
    }

    /**
     * Fetches lyrics for a NetEase track that the user picked from an **online search** rather than
     * from their own account — the case of a local file matched to a catalogue entry.
     *
     * [refreshLyrics] cannot serve this: it reads `song.neteaseId`, which a local file does not have.
     */
    suspend fun importLyricsFor(songId: String, neteaseId: Long): Boolean = withContext(Dispatchers.IO) {
        val stored = runCatching { downloadLyrics(songId, neteaseId) }.getOrDefault(false)
        library.notifyExternalChange()
        // A `false` from [downloadLyrics] also covers the instrumental and uncollected answers, which
        // *did* store a marker the UI can explain. Reporting those as failure would make the caller
        // blame the network for a track that simply has no lyrics.
        stored || lyrics.has(songId)
    }

    // ------------------------------------------------------------------ search

    /** Searches the NetEase catalogue. @param type `1` song, `10` album, `100` artist, `1000` playlist. */
    suspend fun search(keyword: String, type: Int = 1, limit: Int = 30, offset: Int = 0): Result<NcSearchResult?> =
        withContext(Dispatchers.IO) {
            runCatching { paced { api.search(keyword.trim(), type, limit, offset) } }
        }

    // ------------------------------------------------------------------ streaming

    /**
     * Resolves a playable URL for a NetEase track.
     *
     * @param quality a NetEase level; the service downgrades silently when the account is not
     *   entitled to it, so the caller should read the granted tier from [lastGrantedLevel].
     * @return the URL, or null when the track cannot be played at all (VIP-only, region-locked).
     */
    suspend fun streamUrl(neteaseId: Long, quality: String): String? = withContext(Dispatchers.IO) {
        streamCache[neteaseId]?.let { cached ->
            if (cached.expiresAtMs > System.currentTimeMillis()) return@withContext cached.url
        }
        val results = runCatching { paced { api.songUrl(listOf(neteaseId), quality) } }.getOrNull()
        val entry = results?.firstOrNull { it.id == neteaseId } ?: results?.firstOrNull()
        val url = entry?.url
        if (entry == null || !entry.isPlayable || url.isNullOrEmpty()) {
            return@withContext null
        }
        // The service reports the real lifetime in `expi`; keep a safety margin so a URL is never
        // handed to the player in its last seconds.
        val ttl = (entry.expi.takeIf { it > 60L } ?: DEFAULT_STREAM_TTL_S) * 1000L
        streamCache[neteaseId] = CachedStream(
            url = url,
            expiresAtMs = System.currentTimeMillis() + ttl - STREAM_TTL_MARGIN_MS,
            level = entry.level.orEmpty(),
        )
        lastGrantedLevel = entry.level.orEmpty()
        recordGranted(entry)
        url
    }

    /**
     * The raw URL record for one track, for callers that need more than the URL itself.
     *
     * Playback only cares about the address, but downloading has to know the container
     * ([NcSongUrl.type]) and the granted tier, because a "standard" request can legitimately come
     * back as FLAC and a file must never be labelled with an extension it does not have.
     *
     * Deliberately does not consult [streamCache]: a cached entry only carries the URL, and a
     * download wants the freshly reported size and container.
     */
    suspend fun streamEntry(neteaseId: Long, level: String = "standard"): NcSongUrl? =
        withContext(Dispatchers.IO) {
            if (neteaseId <= 0L) return@withContext null
            runCatching { paced { api.songUrl(listOf(neteaseId), level) } }
                .getOrNull()
                ?.let { results -> results.firstOrNull { it.id == neteaseId } ?: results.firstOrNull() }
        }

    /** Remembers one grant; the map is bounded so a long session cannot grow without limit. */
    private fun recordGranted(entry: NcSongUrl) {
        if (entry.id <= 0L) return
        val granted = GrantedQuality(
            container = (entry.type ?: entry.encodeType ?: "").lowercase(),
            bitrate = entry.br,
            level = entry.level.orEmpty(),
        )
        _granted.update { current ->
            val next = LinkedHashMap(current)
            next.remove(entry.id)
            next[entry.id] = granted
            while (next.size > GRANTED_MEMORY) next.remove(next.keys.first())
            next
        }
    }

    /** The tier the service actually granted on the most recent [streamUrl] call. */
    var lastGrantedLevel: String = ""
        private set

    /**
     * Resolves many tracks at once, reusing the same cache as [streamUrl].
     *
     * The player needs the whole queue up front (Media3 wants concrete URIs when items are added),
     * and the endpoint accepts up to 200 ids per call, so batching turns a 300-song playlist from
     * 300 round trips into two. Ids that resolve to nothing are simply absent from the result, which
     * lets the queue builder drop unplayable tracks before handing anything to ExoPlayer.
     */
    suspend fun streamUrls(ids: List<Long>, quality: String): Map<Long, String> =
        withContext(Dispatchers.IO) {
            val wanted = ids.filter { it > 0L }.distinct()
            if (wanted.isEmpty()) return@withContext emptyMap()

            val out = LinkedHashMap<Long, String>(wanted.size)
            val stale = ArrayList<Long>()
            val now = System.currentTimeMillis()
            wanted.forEach { id ->
                val cached = streamCache[id]
                if (cached != null && cached.expiresAtMs > now) out[id] = cached.url else stale.add(id)
            }

            stale.chunked(200).forEach { chunk ->
                val results = runCatching { paced { api.songUrl(chunk, quality) } }.getOrNull().orEmpty()
                results.forEach { entry ->
                    val url = entry.url
                    if (!entry.isPlayable || url.isNullOrEmpty()) return@forEach
                    val ttl = (entry.expi.takeIf { it > 60L } ?: DEFAULT_STREAM_TTL_S) * 1000L
                    streamCache[entry.id] = CachedStream(
                        url = url,
                        expiresAtMs = System.currentTimeMillis() + ttl - STREAM_TTL_MARGIN_MS,
                        level = entry.level.orEmpty(),
                    )
                    recordGranted(entry)
                    out[entry.id] = url
                }
            }
            out
        }

    // ------------------------------------------------------------------ writes

    /** Likes or unlikes a track server-side, then mirrors the flag locally. */
    suspend fun setLiked(neteaseId: Long, liked: Boolean): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            api.like(neteaseId, liked)
            songs.byNeteaseId(neteaseId)?.let { songs.setFavorite(it.id, liked) }
            library.notifyExternalChange()
            true
        }
    }

    /** Adds tracks to a playlist on the server, then re-syncs that playlist. */
    suspend fun addToRemotePlaylist(playlistNeteaseId: Long, trackIds: List<Long>): Result<Int> =
        runCatching {
            api.playlistAdd(playlistNeteaseId, trackIds)
            syncPlaylist(playlistNeteaseId).getOrDefault(0)
        }

    /** Removes tracks from a playlist on the server, then re-syncs that playlist. */
    suspend fun removeFromRemotePlaylist(playlistNeteaseId: Long, trackIds: List<Long>): Result<Int> =
        runCatching {
            api.playlistRemove(playlistNeteaseId, trackIds)
            syncPlaylist(playlistNeteaseId).getOrDefault(0)
        }

    // ------------------------------------------------------------------ catalogue import

    /**
     * Adds every track of a NetEase album to the local library.
     *
     * Album and artist pages are only reachable from search, where nothing is stored yet: the
     * user taps a result and expects to be able to listen, so the whole album is materialised at
     * that point rather than opening a detail page whose songs all fail to play.
     */
    suspend fun importAlbum(albumId: Long, withLyrics: Boolean = false): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val ncSongs = paced { api.albumDetail(albumId) }.songs
                if (ncSongs.isEmpty()) return@runCatching 0
                songs.upsertAll(ncSongs.map { it.toSong() })
                if (withLyrics) {
                    ncSongs.forEach { nc ->
                        val songId = Ids.neteaseSongId(nc.id)
                        if (!lyrics.has(songId)) runCatching { downloadLyrics(songId, nc.id) }
                    }
                }
                library.notifyExternalChange()
                ncSongs.size
            }
        }

    /**
     * One page of the albums the signed-in account has favourited.
     *
     * Kept separate from the cache write so the page can keep scrolling through a listing without
     * replacing what it already shows: the reply carries the server `total`, which is the only way to
     * know whether "one page" means "one page of many" or "that is all there is".
     */
    suspend fun favouriteAlbumsPage(offset: Int = 0, limit: Int = 100): Result<AlbumPage> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = paced { api.albumSublist(limit = limit, offset = offset) }
                AlbumPage(
                    albums = response.albums,
                    total = response.total,
                    more = response.morePages,
                    offset = offset,
                    code = response.code,
                )
            }
        }

    /** The cached favourited albums, in the order the service listed them. */
    fun cachedFavouriteAlbums(): List<CachedAlbum> = albums.all()

    /** When the album cache was last written; 0 means it never was. */
    fun albumsSyncedAt(): Long = albums.lastSyncedAt()

    /** Re-reads every page of the listing and replaces the cache. Returns the album count. */
    suspend fun refreshFavouriteAlbums(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching { fetchAllAlbums().size }
    }

    /**
     * Walks the whole favourited-album listing and writes it to the cache.
     *
     * Three independent stop conditions, because each transport lies differently: the server `total`
     * (absent on the plain gateway), the `hasMore`/`more` flag (absent or wrong on some replies), and
     * a short page (the only signal that survives both). A cap keeps a misbehaving reply from looping
     * forever.
     *
     * Duplicates are folded by id, and the server's order is preserved, so re-reading is idempotent.
     */
    private suspend fun fetchAllAlbums(): List<CachedAlbum> {
        val now = System.currentTimeMillis()
        val collected = LinkedHashMap<Long, CachedAlbum>()
        var offset = 0
        var page = 0
        while (page < MAX_ALBUM_PAGES) {
            val response = paced { api.albumSublist(limit = albumPageSize, offset = offset) }
            if (response.albums.isEmpty()) break
            response.albums.forEach { collected[it.id] = it.toCachedAlbum(now) }
            offset += response.albums.size
            if (isLastAlbumPage(response.albums.size, response.total, response.morePages, offset, albumPageSize)) {
                break
            }
            page++
        }
        val out = collected.values.toList()
        albums.replaceAll(out)
        return out
    }

    private fun NcAlbum.toCachedAlbum(now: Long): CachedAlbum = CachedAlbum(
        id = id,
        name = name,
        artistName = artistName,
        coverUrl = picUrl,
        trackCount = size,
        publishTime = 0L,
        syncedAt = now,
    )

    /**
     * The albums [artistName] has released, for the artist detail page.
     *
     * Read-only on purpose: opening a discography must not import it. The caller materialises just
     * the one album the user taps, through [importAlbum], so a page view costs a single request and
     * leaves the library untouched.
     *
     * A NetEase id turns this into a direct lookup. Without one — the artist exists only in the
     * local library, or was parsed out of a file tag — the album catalogue is searched by name and
     * the entries whose credited artist matches are kept. That match is a filter rather than a
     * lookup, so it compares the whole trimmed name case-insensitively instead of accepting a
     * prefix: "Jay" must not pull in "Jay-Z".
     */
    suspend fun artistAlbums(
        artistName: String,
        neteaseArtistId: Long? = null,
        limit: Int = 50,
    ): Result<List<NcAlbum>> = withContext(Dispatchers.IO) {
        runCatching {
            val name = artistName.trim()
            // An id of 0 is what a missing NetEase link looks like after a bad map, not an artist.
            val resolvedId = neteaseArtistId?.takeIf { it > 0L }
            val albums = when {
                resolvedId != null -> paced { api.artistAlbums(resolvedId, limit = limit) }.hotAlbums
                name.isEmpty() -> emptyList()
                else -> paced { api.searchAlbums(name, limit = limit) }
                    .filter { it.matchesArtist(name) }
            }
            // The same release can be returned twice across pages of a search; the id is the only
            // stable identity NetEase offers, so de-duplicating on it is what keeps rows unique.
            albums.distinctBy { it.id }.take(limit)
        }
    }

    /**
     * True when any credited artist on this album is [name].
     *
     * Both shapes are consulted because the endpoints disagree: album detail and artist albums send
     * the singular `artist`, while list responses send the plural `artists`.
     */
    private fun NcAlbum.matchesArtist(name: String): Boolean {
        if (name.isEmpty()) return false
        val credited = artists.map { it.name } + listOfNotNull(artist?.name)
        return credited.any { it.trim().equals(name, ignoreCase = true) }
    }

    /** Adds the current top songs of a NetEase artist to the local library. */
    suspend fun importArtistTopSongs(artistId: Long, withLyrics: Boolean = false): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val ncSongs = paced { api.artistTopSongs(artistId) }
                if (ncSongs.isEmpty()) return@runCatching 0
                songs.upsertAll(ncSongs.map { it.toSong() })
                if (withLyrics) {
                    ncSongs.forEach { nc ->
                        val songId = Ids.neteaseSongId(nc.id)
                        if (!lyrics.has(songId)) runCatching { downloadLyrics(songId, nc.id) }
                    }
                }
                library.notifyExternalChange()
                ncSongs.size
            }
        }

    // ------------------------------------------------------------------ pacing

    /**
     * Runs one read against the service with a jittered delay in front of it.
     *
     * NetEase answers a burst of correctly-formed requests with a zero-byte 200 rather than a
     * `429`, so the only defence is to not burst: 300–800 ms of jitter between reads keeps a
     * library sync comfortably inside the tolerated rate.
     */
    private suspend fun <T> paced(block: suspend () -> T): T {
        delay(READ_MIN_MS + Random.nextLong(READ_JITTER_MS))
        return block()
    }

    // ------------------------------------------------------------------ mapping

    private fun NcPlaylist.toPlaylist(now: Long): Playlist = Playlist(
        id = Ids.neteasePlaylistId(id),
        name = name.ifBlank { id.toString() },
        source = PlaylistSource.NETEASE,
        neteaseId = id,
        description = description.orEmpty(),
        creator = creator.nickname,
        coverUrl = coverImgUrl,
        trackCount = trackCount,
        createdAt = now,
        updatedAt = updateTime.takeIf { it > 0L } ?: now,
        syncedAt = now,
        subscribed = subscribed,
        specialType = specialType,
        playCount = playCount,
    )

    private fun NcSong.toSong(): Song = Song(
        id = Ids.neteaseSongId(id),
        source = SongSource.NETEASE,
        title = name.ifBlank { id.toString() },
        artist = artistNames,
        album = effectiveAlbum.name,
        albumId = effectiveAlbum.id.takeIf { it > 0L },
        neteaseId = id,
        durationMs = durationMs,
        coverUrl = effectiveAlbum.picUrl,
        trackNo = no,
        addedAt = System.currentTimeMillis(),
    )

    private companion object {
        const val QR_TIMEOUT_MS = 5 * 60 * 1000L
        const val READ_MIN_MS = 300L
        const val READ_JITTER_MS = 500L
        /** How many resolved tracks keep their granted tier in memory. */
    private const val GRANTED_MEMORY = 256

    const val DEFAULT_STREAM_TTL_S = 1200L
        const val STREAM_TTL_MARGIN_MS = 60_000L
    }
}

/** Reads the NetEase error code out of a thrown [NeteaseException], for UI messaging. */
fun Throwable.neteaseCode(): Int? = (this as? NeteaseException)?.code

/** True when the failure means "sign in first" rather than "something went wrong". */
fun Throwable.isAuthFailure(): Boolean = neteaseCode()?.let { it == 301 || it == -462 || it == -460 } == true

/**
 * Whether the page just read is the last page of the favourited-album listing.
 *
 * Kept as a pure function because this decision is the one that was wrong before: the listing used
 * to be a single `limit = 200` call, which the service silently caps at 100 entries, so any account
 * with more than 100 favourite albums lost the tail with no error. Three independent signals now
 * answer "are we done" — the `total` count (missing on the plain gateway), the `hasMore`/`more` flag
 * (wrong or absent on some replies), and a short page — and their precedence is what this function
 * pins down:
 *
 *  - an empty page is always the end, whatever the flags claim;
 *  - a known `total` reached by `offset` ends the walk, even if `more` is (wrongly) true;
 *  - otherwise a short page ends it only when `more` is false: a full page with no flag cannot be
 *    trusted, because the service answers `hasMore = false` for every page in some versions.
 *
 * @param received entries in this page, after de-duplication by the caller is not required.
 * @param total server-reported total, or null when the transport does not carry it.
 * @param more the server's "there is another page" flag.
 * @param offset number of entries consumed so far, *including* this page.
 * @param pageSize the page size that was requested (after the transport's own clamping).
 */
internal fun isLastAlbumPage(
    received: Int,
    total: Int?,
    more: Boolean,
    offset: Int,
    pageSize: Int,
): Boolean {
    if (received <= 0) return true
    if (total != null && total > 0 && offset >= total) return true
    return !more && received < pageSize
}
