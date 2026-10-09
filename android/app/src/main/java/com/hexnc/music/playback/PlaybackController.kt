package com.hexnc.music.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
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
 * ExoPlayer lives inside [PlaybackService]; this class holds a `MediaController` bound to that
 * session and projects it onto Compose-friendly [StateFlow]s. Keeping the controller here (rather
 * than letting screens touch ExoPlayer) means the transport keeps working when no screen is alive.
 *
 * NetEase URLs expire after ~20 minutes, so the resolved queue is short-lived by design: tracks are
 * resolved to concrete URIs whenever a queue is handed to the player, and the [NeteaseRepository]
 * cache refills them transparently on the next play.
 */
class PlaybackController(
    private val context: Context,
    private val netease: NeteaseRepository,
    private val settings: SettingsStore,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var ticker: Job? = null

    /** The songs backing the current player queue, in player order. */
    private var queue: List<Song> = emptyList()

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

    // ------------------------------------------------------------------ connection

    /**
     * Binds to [PlaybackService]. Safe to call repeatedly; only the first call does work.
     *
     * The binding is asynchronous, so [snapshot] reports `connected = false` until the callback
     * fires — screens render an inert transport bar for that brief window rather than crashing.
     */
    fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val pending = MediaController.Builder(context, token).buildAsync()
        future = pending
        pending.addListener(
            {
                val media = runCatching { pending.get() }.getOrNull() ?: return@addListener
                controller = media
                media.addListener(playerListener)
                applyPlayMode(settings.current.playMode)
                pushSnapshot()
                startTicker()
            },
            MoreExecutors.directExecutor(),
        )
    }

    fun release() {
        ticker?.cancel()
        ticker = null
        controller?.removeListener(playerListener)
        controller = null
        future?.let { MediaController.releaseFuture(it) }
        future = null
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            pushSnapshot()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            _snapshot.value = _snapshot.value.copy(playbackError = error.message)
            // Tell the user *why* rather than leaving a dead transport bar: a cleartext URL blocked
            // by the platform and an unreachable host look identical from the outside otherwise.
            postMessage(playbackMessageCodeFor(error.errorCode))
            // A failed stream should not stall the queue: move on unless this was the last track.
            val media = controller
            if (media != null && settings.current.skipOnError && media.hasNextMediaItem()) {
                media.seekToNextMediaItem()
                media.prepare()
                media.play()
            }
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (true) {
                val media = controller
                if (media != null && media.isPlaying) {
                    _snapshot.value = _snapshot.value.copy(
                        positionMs = media.currentPosition.coerceAtLeast(0L),
                        durationMs = media.duration.takeIf { it > 0L } ?: 0L,
                        bufferedMs = media.bufferedPosition.coerceAtLeast(0L),
                    )
                }
                delay(if (media?.isPlaying == true) 250L else 1_000L)
            }
        }
    }

    private fun pushSnapshot() {
        val media = controller ?: run {
            _snapshot.value = _snapshot.value.copy(connected = false, isPlaying = false)
            return
        }
        val index = media.currentMediaItemIndex.coerceAtLeast(0)
        _snapshot.value = PlayerSnapshot(
            connected = true,
            isPlaying = media.isPlaying,
            isBuffering = media.playbackState == Player.STATE_BUFFERING,
            playbackError = null,
            index = index,
            count = media.mediaItemCount,
            positionMs = media.currentPosition.coerceAtLeast(0L),
            durationMs = media.duration.takeIf { it > 0L } ?: 0L,
            bufferedMs = media.bufferedPosition.coerceAtLeast(0L),
            playMode = playModeOf(media),
            hasNext = media.hasNextMediaItem(),
            hasPrevious = media.hasPreviousMediaItem(),
        )
        _current.value = queue.getOrNull(index)
    }

    // ------------------------------------------------------------------ queue building

    private fun mediaItemOf(song: Song, uri: String): MediaItem {
        val artwork: Uri? = song.coverOverridePath
            ?.let { File(it).takeIf(File::exists)?.let(Uri::fromFile) }
            ?: song.coverUrl?.takeIf { it.isNotBlank() }?.let(Uri::parse)
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.displayArtist)
            .setAlbumTitle(song.album)
            .setArtworkUri(artwork)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .build()
        return MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun localUriOf(song: Song): String? {
        song.mediaUri?.takeIf { it.isNotBlank() }?.let { return it }
        val path = song.localPath?.takeIf { it.isNotBlank() } ?: return null
        val file = File(path)
        return if (file.exists()) Uri.fromFile(file).toString() else null
    }

    /**
     * Turns songs into playable media items, dropping anything without a reachable source.
     *
     * Resolution happens before anything reaches the player so that a queue containing unavailable
     * tracks (VIP-only, deleted locally, region-locked) starts on a track that actually plays
     * instead of immediately tripping the error listener.
     *
     * @return the resolvable songs paired with their media items, plus how many local DSD tracks were
     *   dropped on the way so the caller can tell the user why the queue got shorter.
     */
    private suspend fun resolve(songs: List<Song>): ResolvedQueue =
        withContext(Dispatchers.IO) {
            val neteaseIds = songs.filter { it.source == SongSource.NETEASE }.mapNotNull { it.neteaseId }
            val urlByNetease: Map<Long, String> =
                if (neteaseIds.isEmpty()) emptyMap()
                else runCatching {
                    netease.streamUrls(neteaseIds, settings.current.neteaseQuality)
                }.getOrDefault(emptyMap())

            var dsdSkipped = 0
            val items = songs.mapNotNull { song ->
                // Android has no DSD decoder, so a `.dsf`/`.dff` file would reach the extractor and
                // come back as an opaque container error a second later. Refusing it here turns that
                // into a message the user can act on, and lets the rest of the queue keep playing.
                if (song.source == SongSource.LOCAL && isDsdSource(song.localPath ?: song.mediaUri)) {
                    dsdSkipped++
                    return@mapNotNull null
                }
                val uri = when (song.source) {
                    SongSource.LOCAL -> localUriOf(song)
                    // Last stop before the URI becomes a MediaItem: the platform refuses cleartext
                    // on a modern targetSdk, and NetEase hands out `http://` media URLs, so the
                    // scheme is forced here as well as at the API boundary. Nothing downstream can
                    // bypass this, and a URL that is already https passes through untouched.
                    SongSource.NETEASE -> song.neteaseId
                        ?.let { urlByNetease[it] }
                        ?.let(::httpsStreamUrl)
                }
                uri?.let { song to mediaItemOf(song, it) }
            }
            ResolvedQueue(items, dsdSkipped)
        }

    // ------------------------------------------------------------------ commands

    /** Replaces the queue and starts at [startIndex] within [songs]. */
    suspend fun play(songs: List<Song>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        connect()
        val resolved = resolve(songs)
        if (resolved.items.isEmpty()) {
            postMessage(if (resolved.dsdSkipped > 0) MESSAGE_PLAYBACK_DSD else MESSAGE_NO_PLAYABLE)
            return
        }
        if (resolved.dsdSkipped > 0) postMessage(MESSAGE_PLAYBACK_DSD)
        // Map the requested start position onto the filtered list: count how many of the songs
        // before it were dropped so the user still lands on the track they tapped.
        val target = songs.getOrNull(startIndex.coerceIn(0, songs.lastIndex))
        val offset = if (target == null) 0 else resolved.items.indexOfFirst { it.first.id == target.id }
        val start = if (offset >= 0) offset else 0

        queue = resolved.items.map { it.first }
        _queueFlow.value = queue

        val media = controller ?: return
        media.setMediaItems(resolved.items.map { it.second }, start, 0L)
        media.prepare()
        media.play()
        pushSnapshot()
    }

    /** Appends to the end of the current queue without interrupting playback. */
    suspend fun enqueue(songs: List<Song>): Int {
        connect()
        val media = controller ?: return 0
        val resolved = resolve(songs.filter { candidate -> queue.none { it.id == candidate.id } })
        if (resolved.dsdSkipped > 0) postMessage(MESSAGE_PLAYBACK_DSD)
        if (resolved.items.isEmpty()) return 0
        queue = queue + resolved.items.map { it.first }
        _queueFlow.value = queue
        media.addMediaItems(resolved.items.map { it.second })
        return resolved.items.size
    }

    fun toggle() {
        val media = controller ?: return
        if (media.isPlaying) media.pause() else media.play()
    }

    fun play() {
        controller?.play()
    }

    fun pause() {
        controller?.pause()
    }

    fun next() {
        val media = controller ?: return
        if (media.hasNextMediaItem()) media.seekToNextMediaItem()
    }

    fun previous() {
        val media = controller ?: return
        // Match the platform convention: restart the track unless we are near its start.
        if (media.currentPosition > 3_000L || !media.hasPreviousMediaItem()) media.seekTo(0L)
        else media.seekToPreviousMediaItem()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun seekToIndex(index: Int) {
        val media = controller ?: return
        if (index in 0 until media.mediaItemCount) media.seekTo(index, 0L)
    }

    fun stop() {
        controller?.stop()
        controller?.clearMediaItems()
        queue = emptyList()
        _queueFlow.value = emptyList()
        _current.value = null
        pushSnapshot()
    }

    fun setPlayMode(mode: PlayMode) {
        applyPlayMode(mode)
        pushSnapshot()
    }

    private fun applyPlayMode(mode: PlayMode) {
        val media = controller ?: return
        media.shuffleModeEnabled = mode == PlayMode.SHUFFLE
        media.repeatMode = when (mode) {
            PlayMode.SEQUENCE, PlayMode.SHUFFLE -> Player.REPEAT_MODE_OFF
            PlayMode.REPEAT_ALL -> Player.REPEAT_MODE_ALL
            PlayMode.REPEAT_ONE -> Player.REPEAT_MODE_ONE
        }
    }

    private fun playModeOf(media: Player): PlayMode = when {
        media.repeatMode == Player.REPEAT_MODE_ONE -> PlayMode.REPEAT_ONE
        media.repeatMode == Player.REPEAT_MODE_ALL -> PlayMode.REPEAT_ALL
        media.shuffleModeEnabled -> PlayMode.SHUFFLE
        else -> PlayMode.SEQUENCE
    }
}

// ------------------------------------------------------------------ messages

/**
 * The outcome of turning a list of songs into playable media items.
 *
 * [dsdSkipped] is carried alongside the items because "the queue is shorter than what you tapped" is
 * a question the user will ask, and the only place that knows the answer is the resolver.
 */
private data class ResolvedQueue(val items: List<Pair<Song, MediaItem>>, val dsdSkipped: Int)

/** Nothing in the queue could be turned into a playable URI. */
internal const val MESSAGE_NO_PLAYABLE = "no_playable"

/** The platform refused the URL because it is plain HTTP. */
internal const val MESSAGE_PLAYBACK_CLEARTEXT = "playback_cleartext"

/** The host could not be reached, or answered with an HTTP error. */
internal const val MESSAGE_PLAYBACK_NETWORK = "playback_network"

/** The file is gone, or the app lost the permission it needs to read it. */
internal const val MESSAGE_PLAYBACK_FILE_MISSING = "playback_file_missing"

/** Android has no decoder for this container or bitstream (DSD, exotic hi-res, DRM). */
internal const val MESSAGE_PLAYBACK_UNSUPPORTED = "playback_unsupported"

/** A local `.dsf`/`.dff` track was skipped before the decoder could fail on it. */
internal const val MESSAGE_PLAYBACK_DSD = "playback_dsd"

/** Anything else ExoPlayer reported. */
internal const val MESSAGE_PLAYBACK_FAILED = "playback_failed"

/**
 * Maps an ExoPlayer error code onto one of the codes the host knows how to translate.
 *
 * Kept as a pure function so the mapping can be unit-tested without an Android player; the cases are
 * the ones the user can actually act on — an unreachable host, a refused cleartext URL, a file that
 * is no longer readable, and a format Android simply cannot decode — and everything else deliberately
 * collapses to a single "playback failed" rather than leaking an English decoder string into a
 * Chinese UI.
 */
internal fun playbackMessageCodeFor(errorCode: Int): String = when (errorCode) {
    PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> MESSAGE_PLAYBACK_CLEARTEXT
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
    -> MESSAGE_PLAYBACK_NETWORK
    // A local song whose SAF grant expired, or whose file was moved or deleted, fails exactly here.
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
    PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
    -> MESSAGE_PLAYBACK_FILE_MISSING
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
    -> MESSAGE_PLAYBACK_UNSUPPORTED
    else -> MESSAGE_PLAYBACK_FAILED
}
