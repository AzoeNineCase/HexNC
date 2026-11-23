package com.hexnc.music.data.download

import com.hexnc.music.data.model.Song
import com.hexnc.music.data.model.SongSource
import com.hexnc.music.data.netease.NeteaseIdentity
import com.hexnc.music.data.repo.LibraryRepository
import com.hexnc.music.data.repo.NeteaseRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Where a finished download goes.
 *
 * The downloader itself is shared, so the file system never appears in it: a desktop writes under
 * the user's Music folder, an Android build inserts into `MediaStore.Downloads`, and both are just
 * "a path relative to the download root".
 */
interface DownloadSink {

    /** Human-readable location of [relativePath] — what the "saved to …" toast names. */
    fun location(relativePath: String): String

    /** Whether something already occupies [relativePath] (audio or its `.lrc`). */
    fun exists(relativePath: String): Boolean

    /**
     * Writes [bytes] atomically: a partially written file must never be visible under the final
     * name, so implementations stage a `.part` (or `IS_PENDING`) entry first.
     *
     * @return [location] of the written file.
     */
    fun write(relativePath: String, bytes: ByteArray): String
}

/** Knobs a download run takes from the settings screen. */
data class DownloadConfig(
    /** NetEase tier: `standard`, `higher`, `exhigh`, `lossless` or `hires`. */
    val level: String = "standard",
    /** Embed a 500×500 JPEG cover in the ID3 tag of MP3 files. */
    val embedCover: Boolean = true,
    /** Write a `.lrc` next to the audio file when the library has lyrics for it. */
    val writeLyric: Boolean = true,
    val unknownArtist: String = "Unknown artist",
    val unknownAlbum: String = "Unknown album",
)

/** Why one track did not land on disk. */
enum class DownloadError {
    /** Not a NetEase track, or no NetEase id: nothing to download. */
    NOT_SUPPORTED,

    /** The service refused: no copyright, region lock, or a paid tier. */
    NO_COPYRIGHT,

    /** The URL resolved but carried no address — usually an expired or empty response. */
    NO_TIER,

    /** Network failure after all retries. */
    NETWORK,

    /** The file could not be written. */
    IO_FAILED,
}

data class DownloadFailure(val songId: String, val title: String, val error: DownloadError)

/** A snapshot of one download run. */
data class DownloadState(
    val running: Boolean = false,
    val total: Int = 0,
    val finished: Int = 0,
    val completed: Int = 0,
    val skipped: Int = 0,
    val failed: List<DownloadFailure> = emptyList(),
    /** Title of the track currently being written, for the queue panel. */
    val currentTitle: String? = null,
    /** Fraction of the current file, 0..1. */
    val fraction: Float = 0f,
    /** Where the most recent file landed. */
    val lastLocation: String? = null,
    /** Completed files per container, e.g. `{"flac" to 2, "mp3" to 1}`; shown in the panel. */
    val formats: Map<String, Int> = emptyMap(),
    /** How many of [completed] landed below lossless because the track had no master. */
    val belowLossless: Int = 0,
) {
    /** Overall progress across the whole queue, 0..1. */
    val progress: Float
        get() = if (total <= 0) 0f else ((finished.toFloat() + fraction) / total).coerceIn(0f, 1f)
}

/**
 * Downloads NetEase tracks to the user's own storage.
 *
 * Everything here is shared between the two apps; only [DownloadSink] differs. The rules that
 * matter are the ones a music player gets wrong:
 *
 *  - the extension comes from the container the service actually returned, never from the tier
 *    that was asked for, because a "standard" request can legitimately answer with FLAC and
 *    renaming that to `.mp3` would produce a file that plays nowhere;
 *  - a file is written under a temporary name first, so a cancelled or failed download cannot leave
 *    a half-track behind that looks valid to a scanner;
 *  - an existing file of the same name is *skipped*, not overwritten and not duplicated as `(2)`;
 *  - MP3 gets a real ID3v2.3 tag (title/artist/album/track/disc/cover), which is the difference
 *    between "a download" and a cache entry;
 *  - at most [MAX_PARALLEL] transfers run at once, so a ten-track album does not compete with
 *    playback for bandwidth or disk.
 */
class SongDownloader(
    private val netease: NeteaseRepository,
    private val library: LibraryRepository,
    private val sink: DownloadSink,
) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(300, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Downloads [songs], reporting every state change to [onState].
     *
     * [onState] may be called from a worker thread: callers that feed UI state should hand it a
     * thread-safe setter (a `MutableStateFlow.value` assignment is one).
     */
    suspend fun download(
        songs: List<Song>,
        config: DownloadConfig,
        onState: (DownloadState) -> Unit = {},
    ): DownloadState = withContext(Dispatchers.IO) {
        val queue = songs.distinctBy { it.id }
        val lock = Any()
        var state = DownloadState(total = queue.size, running = queue.isNotEmpty())

        fun update(mutate: (DownloadState) -> DownloadState) {
            val next = synchronized(lock) {
                state = mutate(state)
                state
            }
            onState(next)
        }

        if (queue.isEmpty()) {
            onState(state)
            return@withContext state
        }
        onState(state)

        val gate = Semaphore(MAX_PARALLEL)
        val used = java.util.Collections.synchronizedSet(HashSet<String>())

        coroutineScope {
            queue.map { song ->
                async {
                    gate.withPermit {
                        val outcome = runCatching {
                            downloadOne(song, config, used) { fraction ->
                                update { it.copy(currentTitle = song.title, fraction = fraction) }
                            }
                        }
                        when {
                            outcome.isFailure -> update {
                                it.copy(
                                    finished = it.finished + 1,
                                    failed = it.failed + DownloadFailure(
                                        songId = song.id,
                                        title = song.title,
                                        error = classify(outcome.exceptionOrNull()),
                                    ),
                                    fraction = 0f,
                                )
                            }

                            outcome.getOrNull() is Outcome.Skipped -> update {
                                it.copy(finished = it.finished + 1, skipped = it.skipped + 1, fraction = 0f)
                            }

                            outcome.getOrNull() is Outcome.Failed -> {
                                val error = (outcome.getOrNull() as Outcome.Failed).error
                                update {
                                    it.copy(
                                        finished = it.finished + 1,
                                        failed = it.failed + DownloadFailure(
                                            songId = song.id,
                                            title = song.title,
                                            error = error,
                                        ),
                                        fraction = 0f,
                                    )
                                }
                            }

                            else -> {
                                val written = outcome.getOrNull() as? Outcome.Written
                                update {
                                    val formats = written?.let { w ->
                                        it.formats + (w.container to (it.formats[w.container] ?: 0) + 1)
                                    } ?: it.formats
                                    it.copy(
                                        finished = it.finished + 1,
                                        completed = it.completed + 1,
                                        fraction = 0f,
                                        lastLocation = written?.location ?: it.lastLocation,
                                        formats = formats,
                                        belowLossless = it.belowLossless +
                                            if (written?.belowLossless == true) 1 else 0,
                                    )
                                }
                            }
                        }
                    }
                }
            }.awaitAll()
        }

        update { it.copy(running = false, fraction = 0f, currentTitle = null) }
        state
    }

    private suspend fun downloadOne(
        song: Song,
        config: DownloadConfig,
        used: MutableSet<String>,
        onFraction: (Float) -> Unit,
    ): Outcome {
        val neteaseId = song.neteaseId ?: return Outcome.Failed(DownloadError.NOT_SUPPORTED)
        if (song.source != SongSource.NETEASE || neteaseId <= 0L) {
            return Outcome.Failed(DownloadError.NOT_SUPPORTED)
        }

        val entry = netease.streamEntry(neteaseId, config.level)
            ?: return Outcome.Failed(DownloadError.NO_COPYRIGHT)
        val url = entry.url
        if (entry.code != 0 && entry.code != 200) return Outcome.Failed(DownloadError.NO_COPYRIGHT)
        if (url.isNullOrEmpty()) return Outcome.Failed(DownloadError.NO_TIER)

        // The container decides the extension. `type` is what the service reports for the file it is
        // about to serve; `encodeType` is the fallback for older responses that omit it.
        val extension = DownloadPaths.extensionFor(entry.type ?: entry.encodeType)
        val desired = DownloadPaths.relativePath(
            artist = song.artist,
            album = song.album,
            trackNo = song.trackNo,
            title = song.title,
            extension = extension,
            unknownArtist = config.unknownArtist,
            unknownAlbum = config.unknownAlbum,
        )

        // Same song, same place: this is the "already downloaded" case, not a collision.
        if (sink.exists(desired)) return Outcome.Skipped

        // A different track that sanitises to the same name must still not overwrite it.
        val relative = synchronized(used) {
            val unique = DownloadPaths.uniquePath(desired) { candidate ->
                used.contains(candidate) || sink.exists(candidate)
            }
            used.add(unique)
            unique
        }

        val payload = fetch(url, onFraction)
        val audio = when (extension) {
            "mp3" -> Id3v2.attach(payload, Id3v2.buildTag(tags(song, config)))

            // FLAC keeps its metadata as Vorbis comments in its own block structure: rewriting it
            // preserves the audio frames byte for byte while making the file tagged in any player.
            "flac" -> FlacTags.withTags(payload, tags(song, config))

            // MP4/ALAC still carry their own atoms; those are left untouched rather than half-rewritten.
            else -> payload
        }
        val location = sink.write(relative, audio)

        if (config.writeLyric) {
            val lyrics = runCatching { library.exportLyricText(song.id) }.getOrNull()
            if (!lyrics.isNullOrBlank()) {
                val withoutExtension = relative.substringBeforeLast('.', relative)
                runCatching { sink.write("$withoutExtension.lrc", lyrics.toByteArray(Charsets.UTF_8)) }
            }
        }
        return Outcome.Written(
            location = location,
            container = extension,
            // `entry.level` is what the service granted: asking for `hires` and getting `exhigh`
            // means this track has no lossless master, and the panel should say so.
            belowLossless = entry.level?.lowercase() !in LOSSLESS_LEVELS,
        )
    }

    /** The fields both tag writers fill; the cover lookup happens at most once per track. */
    private suspend fun tags(song: Song, config: DownloadConfig): AudioTags = AudioTags(
        title = song.title,
        artist = song.artist,
        album = song.album,
        albumArtist = song.artist,
        trackNo = song.trackNo,
        discNo = 1,
        year = "",
        coverJpeg = if (config.embedCover) coverBytes(song.coverUrl) else null,
    )

    /** Best-effort cover fetch — a missing cover must never fail the download. */
    private suspend fun coverBytes(coverUrl: String?): ByteArray? {
        if (coverUrl.isNullOrBlank()) return null
        val sized = if (coverUrl.startsWith("http") && !coverUrl.contains("?")) {
            "$coverUrl?param=500y500"
        } else {
            coverUrl
        }
        return runCatching { fetch(sized) {} }.getOrNull()
    }

    /**
     * Fetches [url] into memory with retries.
     *
     * Tracks are capped at [MAX_TRACK_BYTES]: a stuck connection that streams forever would
     * otherwise fill the disk (or the heap) before anyone noticed.
     */
    private suspend fun fetch(url: String, onProgress: (Float) -> Unit): ByteArray {
        var attempt = 0
        var lastError: Exception? = null
        while (attempt < MAX_ATTEMPTS) {
            currentCoroutineContext().ensureActive()
            try {
                return fetchOnce(url, onProgress)
            } catch (e: SocketTimeoutException) {
                lastError = e
            } catch (e: IOException) {
                lastError = e
            }
            attempt++
            if (attempt < MAX_ATTEMPTS) delay(BACKOFF_MS shl (attempt - 1))
        }
        throw DownloadException(DownloadError.NETWORK, lastError?.message ?: "network")
    }

    private fun fetchOnce(url: String, onProgress: (Float) -> Unit): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", NeteaseIdentity.WEB_UA)
            .header("Referer", "https://music.163.com/")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw DownloadException(DownloadError.NETWORK, "HTTP ${response.code}")
            }
            val body = response.body
            val length = body.contentLength()
            val out = ByteArrayOutputStream(
                (if (length in 1..MAX_TRACK_BYTES) length else 1L shl 16).toInt(),
            )
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            body.byteStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_TRACK_BYTES) {
                        throw DownloadException(DownloadError.IO_FAILED, "track larger than the cap")
                    }
                    out.write(buffer, 0, read)
                    if (length > 0L) onProgress((total.toFloat() / length).coerceIn(0f, 1f))
                }
            }
            if (total <= 0L) throw DownloadException(DownloadError.NO_TIER, "empty body")
            return out.toByteArray()
        }
    }

    private fun classify(error: Throwable?): DownloadError =
        (error as? DownloadException)?.error ?: DownloadError.IO_FAILED

    private sealed interface Outcome {
        object Skipped : Outcome
        data class Written(val location: String, val container: String, val belowLossless: Boolean) : Outcome
        data class Failed(val error: DownloadError) : Outcome
    }

    private class DownloadException(val error: DownloadError, message: String) : Exception(message)

    private companion object {
        /** Tiers that really are lossless; anything else means the track has no master. */
        val LOSSLESS_LEVELS = setOf("lossless", "hires")

        /** Two at a time: enough to hide latency, not enough to starve playback. */
        const val MAX_PARALLEL = 2
        const val MAX_ATTEMPTS = 3
        const val BACKOFF_MS = 400L
        const val MAX_TRACK_BYTES = 128L * 1024 * 1024
    }
}