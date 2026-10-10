package com.hexnc.music.playback

import android.content.Context
import android.net.Uri
import com.hexnc.music.data.local.dsd.DsdProbe
import com.hexnc.music.data.local.isDsdSource
import com.hexnc.music.data.model.Song
import com.hexnc.music.data.model.SongSource
import com.hexnc.music.data.netease.httpsStreamUrl
import com.hexnc.music.data.prefs.PlayMode
import com.hexnc.music.data.prefs.SettingsStore
import com.hexnc.music.data.repo.NeteaseRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Everything the UI needs to draw the transport bar, as one immutable snapshot. */
data class PlayerSnapshot(
    val connected: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val playbackError: String? = null,
    val index: Int = 0,
    val count: Int = 0,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val playMode: PlayMode = PlayMode.SEQUENCE,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
) {
    val hasTrack: Boolean get() = count > 0
    val progress: Float
        get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/**
 * The app-facing half of playback.
 *
 * On Android this held a media3 `MediaController` bound to [PlaybackService]. A desktop process has
 * no service to bind to and no ExoPlayer to bind with, so the controller now owns an [AudioEngine]
 * directly — which turns out to be an improvement rather than a compromise: the 250 ms position
 * ticker reads a plain counter instead of crossing a binder, and queue/transport state lives in the
 * same process as the UI.
 *
 * NetEase URLs expire after ~20 minutes, so the resolved URLs are short-lived by design: tracks are
 * resolved to concrete URLs whenever a queue is handed to the player, downloaded once into
 * [MediaCache], and refilled transparently by [NeteaseRepository] on the next play.
 *
 * The constructor deliberately still takes the app `Context` (a shim on desktop, see the
 * `android.content` package) so `di/AppContainer.kt` stays byte-identical to the Android build.
 */
class PlaybackController(
    private val context: Context,
    private val netease: NeteaseRepository,
    private val settings: SettingsStore,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cache = MediaCache(File(context.filesDir, "media"))

    private var engine: AudioEngine? = null
    private var ticker: Job? = null
    private var loadJob: Job? = null
    private var prefetchJob: Job? = null

    /** The songs backing the current player queue, in player order. */
    private var queue: List<Song> = emptyList()

    /** Index into [queue] that is currently loaded. */
    private var index: Int = 0

    /** Playback order over [queue]'s indices; a plain 0..n-1 sequence except in shuffle mode. */
    private var order: List<Int> = emptyList()
    private var orderPos: Int = 0

    /** NetEase id → resolved stream URL for the queue in hand. */
    private var urls: Map<Long, String> = emptyMap()

    private var mode: PlayMode = PlayMode.SEQUENCE
    private var failureStreak = 0

    @Volatile private var buffering = false
    @Volatile private var downloadFraction = 0f
    @Volatile private var currentDurationMs = 0L

    /**
     * Volume most recently handed to the running engine.
     *
     * A desktop window has a volume slider, so the setting can change while a track is playing —
     * something the Android build never needed, because there the hardware volume keys did it
     * outside the app. [-1f] means "nothing applied yet", which forces a sync on the first tick.
     */
    private var appliedVolume = -1f

    private val _snapshot = MutableStateFlow(PlayerSnapshot())
    val snapshot: StateFlow<PlayerSnapshot> = _snapshot.asStateFlow()

    private val _current = MutableStateFlow<Song?>(null)
    val current: StateFlow<Song?> = _current.asStateFlow()

    private val _queueFlow = MutableStateFlow<List<Song>>(emptyList())
    val queueFlow: StateFlow<List<Song>> = _queueFlow.asStateFlow()

    /** Non-fatal messages ("track unavailable, skipped") for the UI to surface as a snackbar. */
    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages.asStateFlow()

    fun consumeMessage() {
        _messages.value = null
    }

    /**
     * Pushes a one-shot message for the UI to show.
     *
     * Codes such as `"no_playable"` are translated by the host; anything the host does not know is
     * shown verbatim, which lets callers hand over an already-localised string.
     */
    fun postMessage(code: String) {
        _messages.value = code
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Prepares the transport. Safe to call repeatedly; only the first call does work.
     *
     * On Android this was an asynchronous service bind and [snapshot] reported `connected = false`
     * until the callback fired. Here playback is in-process, so the connection is immediate — the
     * flag is kept because the UI still reads it.
     */
    fun connect() {
        if (ticker != null) return
        mode = settings.current.playMode
        startTicker()
        pushSnapshot()
    }

    fun release() {
        ticker?.cancel()
        ticker = null
        loadJob?.cancel()
        loadJob = null
        prefetchJob?.cancel()
        prefetchJob = null
        engine?.release()
        engine = null
    }

    // ------------------------------------------------------------------ queue building

    /** Turns songs into the ones that actually have a reachable source. */
    private suspend fun urlsFor(songs: List<Song>): Map<Long, String> = withContext(Dispatchers.IO) {
        val ids = songs.filter { it.source == SongSource.NETEASE }.mapNotNull { it.neteaseId }
        if (ids.isEmpty()) return@withContext emptyMap()
        runCatching { netease.streamUrls(ids, settings.current.neteaseQuality) }
            .getOrDefault(emptyMap())
            // NetEase hands out `http://` media URLs; desktop Java Sound does not care about the
            // scheme the way Android's cleartext policy does, but the upgrade is kept so both builds
            // resolve a track to the same string.
            .mapValues { (_, url) -> httpsStreamUrl(url) ?: url }
    }

    /**
     * What the transport can actually open, plus how many DSD tracks it had to drop.
     *
     * [dsdSkipped] is carried alongside the items because "the queue is shorter than what you tapped"
     * is a question the user will ask, and the only place that knows the answer is the resolver.
     */
    private data class PlayableQueue(val items: List<Song>, val dsdSkipped: Int)

    private fun playable(songs: List<Song>, resolved: Map<Long, String>): PlayableQueue {
        var dsdSkipped = 0
        val items = songs.filter { song ->
            when (song.source) {
                SongSource.LOCAL -> {
                    // DSF/DFF is decoded by the bundled Java Sound provider, so it plays like any
                    // other file. Only a container that cannot be decoded — DST compression, or a
                    // malformed header — is dropped here, which is what keeps one unplayable file
                    // from failing the rest of the queue.
                    if (isDsdSource(song.localPath ?: song.mediaUri)) {
                        val file = localFileOf(song)
                        if (file == null || !DsdProbe.canDecode(file.absolutePath)) {
                            dsdSkipped++
                            false
                        } else {
                            true
                        }
                    } else {
                        localFileOf(song) != null
                    }
                }

                SongSource.NETEASE -> song.neteaseId?.let { id -> resolved[id] } != null
            }
        }
        return PlayableQueue(items, dsdSkipped)
    }

    private fun localFileOf(song: Song): File? {
        song.localPath?.takeIf { it.isNotBlank() }?.let { path ->
            val file = File(path)
            if (file.isFile) return file
        }
        return song.mediaUri
            ?.takeIf { it.isNotBlank() }
            ?.let { Uri.parse(it).path }
            ?.let(::File)
            ?.takeIf { it.isFile }
    }

    /** Fetches the local copy of [song], downloading it first when it is a NetEase stream. */
    private suspend fun obtain(song: Song): Result<File> = withContext(Dispatchers.IO) {
        when (song.source) {
            SongSource.LOCAL -> localFileOf(song)?.let { Result.success(it) }
                ?: Result.failure(MediaCacheException(PlaybackErrorCode.IO_FILE_NOT_FOUND, song.title))

            SongSource.NETEASE -> {
                val id = song.neteaseId
                val url = id?.let { urls[it] }
                if (id == null || url == null) {
                    Result.failure(MediaCacheException(PlaybackErrorCode.IO_BAD_HTTP_STATUS, song.title))
                } else {
                    runCatching {
                        cache.fetch("$id-${settings.current.neteaseQuality}", url) { fraction ->
                            downloadFraction = fraction
                            buffering = fraction < 1f
                        }
                    }
                }
            }
        }
    }

    /** Downloads the track after the current one so pressing "next" is instant. */
    private fun prefetchNext() {
        val song = queue.getOrNull(nextIndex(manual = false) ?: return) ?: return
        if (song.source != SongSource.NETEASE) return
        val id = song.neteaseId ?: return
        val url = urls[id] ?: return
        prefetchJob?.cancel()
        prefetchJob = scope.launch(Dispatchers.IO) {
            runCatching { cache.fetch("$id-${settings.current.neteaseQuality}", url) }
        }
    }

    // ------------------------------------------------------------------ transport

    /** Replaces the queue and starts at [startIndex] within [songs]. */
    suspend fun play(songs: List<Song>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        connect()
        val fresh = urlsFor(songs)
        val resolved = playable(songs, fresh)
        if (resolved.items.isEmpty()) {
            postMessage(if (resolved.dsdSkipped > 0) MESSAGE_PLAYBACK_DSD else MESSAGE_NO_PLAYABLE)
            return
        }
        urls = fresh
        if (resolved.dsdSkipped > 0) postMessage(MESSAGE_PLAYBACK_DSD)
        // Map the requested start position onto the filtered list: count how many of the songs
        // before it were dropped so the user still lands on the track they tapped.
        val target = songs.getOrNull(startIndex.coerceIn(0, songs.lastIndex))
        val offset = if (target == null) 0 else resolved.items.indexOfFirst { it.id == target.id }
        queue = resolved.items
        _queueFlow.value = queue
        startAt(if (offset >= 0) offset else 0)
    }

    /** Appends to the end of the current queue without interrupting playback. */
    suspend fun enqueue(songs: List<Song>): Int {
        connect()
        val candidates = songs.filter { candidate -> queue.none { it.id == candidate.id } }
        if (candidates.isEmpty()) return 0
        val merged = urls + urlsFor(candidates)
        val resolved = playable(candidates, merged)
        if (resolved.dsdSkipped > 0) postMessage(MESSAGE_PLAYBACK_DSD)
        if (resolved.items.isEmpty()) return 0
        urls = merged
        val previousSize = queue.size
        queue = queue + resolved.items
        _queueFlow.value = queue
        val added = queue.indices.drop(previousSize)
        order = if (mode == PlayMode.SHUFFLE) order + added.shuffled() else order + added
        if (order.isEmpty()) rebuildOrder(0)
        pushSnapshot()
        return resolved.items.size
    }

    fun toggle() {
        if (engine?.isPlaying == true) pause() else play()
    }

    fun play() {
        engine?.play()
        pushSnapshot()
    }

    fun pause() {
        engine?.pause()
        pushSnapshot()
    }

    fun next() {
        startAt(nextIndex(manual = true) ?: return)
    }

    fun previous() {
        if (queue.isEmpty()) return
        val position = engine?.positionMs() ?: 0L
        val back = previousIndex()
        // Match the platform convention: restart the track unless we are near its start.
        startAt(if (position > 3_000L || back == null) index else back)
    }

    fun seekTo(positionMs: Long) {
        val ms = positionMs.coerceAtLeast(0L)
        engine?.seekTo(ms)
        _snapshot.value = _snapshot.value.copy(positionMs = ms)
    }

    fun seekToIndex(index: Int) {
        if (index in queue.indices) startAt(index)
    }

    fun stop() {
        engine?.release()
        engine = null
        queue = emptyList()
        order = emptyList()
        orderPos = 0
        index = 0
        urls = emptyMap()
        buffering = false
        downloadFraction = 0f
        currentDurationMs = 0L
        _queueFlow.value = emptyList()
        _current.value = null
        pushSnapshot()
    }

    fun setPlayMode(mode: PlayMode) {
        this.mode = mode
        rebuildOrder(index)
        pushSnapshot()
    }

    /** Total bytes of downloaded tracks currently held on disk. */
    fun cacheSizeBytes(): Long = cache.sizeBytes()

    /** Drops every downloaded track; the next play re-downloads. */
    fun clearCache() = cache.clear()

    // ------------------------------------------------------------------ engine plumbing

    private fun startAt(target: Int) {
        val song = queue.getOrNull(target) ?: return
        index = target
        orderPos = order.indexOf(target).coerceAtLeast(0)
        if (order.isEmpty()) rebuildOrder(target)
        failureStreak = 0
        currentDurationMs = song.durationMs
        downloadFraction = 0f
        buffering = true
        engine?.release()
        engine = null
        _current.value = song
        // Remembered here rather than when the engine reports "started": a track that fails to
        // download still opened, and the history is what the user just asked to play.
        settings.addRecentlyPlayedId(song.id)
        pushSnapshot()

        loadJob?.cancel()
        loadJob = scope.launch {
            val result = obtain(song)
            val file = result.getOrNull()
            if (file == null) {
                val code = (result.exceptionOrNull() as? MediaCacheException)?.code
                    ?: PlaybackErrorCode.IO_FILE_NOT_FOUND
                fail(code)
                return@launch
            }
            val player = AudioEngine(engineListener)
            engine = player
            val volume = settings.current.volume
            appliedVolume = volume
            player.start(file, 0L, volume)
        }
    }

    private val engineListener = object : AudioEngine.Listener {
        override fun onStarted(durationMs: Long) {
            if (durationMs > 0L) currentDurationMs = durationMs
            buffering = false
            downloadFraction = 1f
            failureStreak = 0
            _snapshot.value = _snapshot.value.copy(playbackError = null)
            pushSnapshot()
            prefetchNext()
        }

        override fun onEnded() {
            scope.launch { advance() }
        }

        override fun onFailure(code: Int) {
            scope.launch { fail(code) }
        }
    }

    private fun advance() {
        if (!settings.current.autoPlayNext) {
            halt()
            return
        }
        val target = nextIndex(manual = false)
        if (target == null) halt() else startAt(target)
    }

    private fun halt() {
        engine?.release()
        engine = null
        buffering = false
        pushSnapshot()
    }

    /**
     * Reports a playback failure and skips on when the user asked for that.
     *
     * The streak counter is what stops a queue of unplayable tracks from being an infinite loop: at
     * most one failed attempt per queued song is made before the transport parks.
     */
    private fun fail(code: Int) {
        engine?.release()
        engine = null
        buffering = false
        failureStreak++
        _snapshot.value = _snapshot.value.copy(
            playbackError = code.toString(),
            isPlaying = false,
            isBuffering = false,
        )
        postMessage(playbackMessageCodeFor(code))
        val target = if (settings.current.skipOnError && failureStreak <= queue.size) {
            nextIndex(manual = true)
        } else {
            null
        }
        if (target != null) startAt(target) else pushSnapshot()
    }

    private fun nextIndex(manual: Boolean): Int? {
        if (queue.isEmpty()) return null
        if (mode == PlayMode.REPEAT_ONE && !manual) return index
        val next = orderPos + 1
        if (next < order.size) return order[next]
        return if (mode == PlayMode.REPEAT_ALL && order.isNotEmpty()) order[0] else null
    }

    private fun previousIndex(): Int? {
        if (queue.isEmpty()) return null
        val previous = orderPos - 1
        return if (previous >= 0) order[previous] else if (mode == PlayMode.REPEAT_ALL) order.lastOrNull() else null
    }

    private fun rebuildOrder(start: Int) {
        order = if (mode == PlayMode.SHUFFLE) {
            listOf(start) + queue.indices.filter { it != start }.shuffled()
        } else {
            queue.indices.toList()
        }
        orderPos = order.indexOf(start).coerceAtLeast(0)
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (true) {
                val player = engine
                if (player != null) {
                    // A volume slider can move mid-track; the gain has to follow it.
                    val wantVolume = settings.current.volume
                    if (wantVolume != appliedVolume) {
                        player.setVolume(wantVolume)
                        appliedVolume = wantVolume
                    }
                    _snapshot.value = _snapshot.value.copy(
                        connected = true,
                        isPlaying = player.isPlaying && !buffering,
                        isBuffering = buffering,
                        positionMs = player.positionMs(),
                        durationMs = currentDurationMs,
                        bufferedMs = if (buffering) {
                            (currentDurationMs * downloadFraction).toLong()
                        } else {
                            currentDurationMs
                        },
                    )
                }
                delay(if (buffering || _snapshot.value.isPlaying) 250L else 1_000L)
            }
        }
    }

    private fun pushSnapshot() {
        val player = engine
        val hasNext = nextIndex(manual = true) != null
        _snapshot.value = PlayerSnapshot(
            connected = true,
            isPlaying = player?.isPlaying == true && !buffering,
            isBuffering = buffering,
            playbackError = null,
            index = index,
            count = queue.size,
            positionMs = player?.positionMs() ?: 0L,
            durationMs = currentDurationMs,
            bufferedMs = if (buffering) {
                (currentDurationMs * downloadFraction).toLong()
            } else {
                currentDurationMs
            },
            playMode = mode,
            hasNext = hasNext,
            hasPrevious = previousIndex() != null,
        )
        _current.value = queue.getOrNull(index)
    }
}
