package com.hexnc.music.data.download

import com.hexnc.music.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The app-wide download queue.
 *
 * Screens never talk to [SongDownloader] directly: they enqueue and observe [state], which keeps the
 * "download this album" and "download this track" paths identical and lets a user leave the screen
 * while the transfer continues.
 *
 * Requests that arrive while a run is in flight are appended to [pending] rather than dropped, so
 * tapping download twice in a row downloads twice instead of silently ignoring the second tap.
 */
class DownloadManager(
    private val scope: CoroutineScope,
    private val downloader: SongDownloader,
) {

    private val _state = MutableStateFlow(DownloadState())
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    private val pending = ArrayList<Song>()
    private val known = HashMap<String, Song>()
    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    /** Queues [songs] and starts the worker when nothing is running. */
    fun enqueue(songs: List<Song>, config: DownloadConfig) {
        if (songs.isEmpty()) return
        synchronized(pending) {
            songs.forEach { song ->
                known[song.id] = song
                pending.add(song)
            }
        }
        if (job?.isActive == true) {
            // Reflect the newly queued work immediately so the panel does not look stuck.
            _state.value = _state.value.copy(total = _state.value.total + songs.size)
            return
        }
        job = scope.launch {
            while (true) {
                val batch = synchronized(pending) {
                    val next = pending.toList()
                    pending.clear()
                    next
                }
                if (batch.isEmpty()) break
                _state.value = DownloadState(total = batch.size, running = true)
                val finalState = downloader.download(batch, config) { snapshot ->
                    // Called from worker threads; StateFlow assignment is atomic.
                    _state.value = snapshot
                }
                _state.value = finalState
            }
        }
    }

    /** Stops the current run. Files already written stay; the in-flight track is discarded. */
    fun cancel() {
        job?.cancel()
        job = null
        synchronized(pending) { pending.clear() }
        _state.value = _state.value.copy(running = false, currentTitle = null, fraction = 0f)
    }

    /** Re-queues whatever failed in the last run. */
    fun retryFailed(config: DownloadConfig) {
        val failed = _state.value.failed.mapNotNull { known[it.songId] }
        if (failed.isEmpty()) return
        _state.value = _state.value.copy(failed = emptyList(), finished = 0, completed = 0, skipped = 0)
        enqueue(failed, config)
    }

    /** Clears the panel once the user has read the result. */
    fun clearResult() {
        if (_state.value.running) return
        _state.value = DownloadState()
    }
}