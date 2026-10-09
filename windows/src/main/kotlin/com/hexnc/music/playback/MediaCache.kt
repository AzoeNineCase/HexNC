package com.hexnc.music.playback

import com.hexnc.music.data.netease.NeteaseIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** A download failure carrying one of [PlaybackErrorCode]'s values. */
internal class MediaCacheException(val code: Int, message: String) : Exception(message)

/**
 * Local copies of streamed tracks.
 *
 * ExoPlayer could play a URL directly; Java Sound cannot — it needs a file or a stream it can seek,
 * and NetEase hands out URLs that expire after about twenty minutes. Downloading once and playing
 * from disk therefore solves three desktop problems at the same time: seeking becomes reliable,
 * decoding a slow connection cannot stutter, and the next track can be prefetched while the current
 * one plays.
 *
 * Files are keyed by song id and quality rather than by URL, because the URL's query string carries
 * a rotating token: keying on the URL would re-download the same song every twenty minutes.
 */
internal class MediaCache(
    private val dir: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** The cached copy of [key], or null when it has not been downloaded yet. */
    fun cached(key: String): File? = fileFor(key).takeIf { it.isFile && it.length() > 0L }

    /**
     * Returns the cached copy of [key], downloading it from [url] when absent.
     *
     * @param onProgress fraction of the body received, 0..1; 1 when the download completes.
     * @throws MediaCacheException with the code the transport should report to the user.
     */
    suspend fun fetch(key: String, url: String, onProgress: (Float) -> Unit = {}): File =
        withContext(Dispatchers.IO) {
            cached(key)?.let { onProgress(1f); return@withContext it }
            dir.mkdirs()
            val target = fileFor(key)
            val temp = File(dir, target.name + ".part")
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", NeteaseIdentity.WEB_UA)
                    .header("Referer", "https://music.163.com/")
                    .get()
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw MediaCacheException(
                            PlaybackErrorCode.IO_BAD_HTTP_STATUS,
                            "HTTP ${response.code} for $url",
                        )
                    }
                    val length = response.body.contentLength()
                    var total = 0L
                    temp.outputStream().use { out ->
                        response.body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                total += read
                                if (total > MAX_TRACK_BYTES) {
                                    throw MediaCacheException(
                                        PlaybackErrorCode.IO_BAD_HTTP_STATUS,
                                        "stream larger than the $MAX_TRACK_BYTES-byte cap",
                                    )
                                }
                                out.write(buffer, 0, read)
                                if (length > 0L) {
                                    onProgress((total.toFloat() / length).coerceIn(0f, 1f))
                                }
                            }
                        }
                    }
                    if (total <= 0L) throw MediaCacheException(PlaybackErrorCode.IO_BAD_HTTP_STATUS, "empty body")
                }
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
            } catch (e: MediaCacheException) {
                temp.delete()
                throw e
            } catch (e: SocketTimeoutException) {
                temp.delete()
                throw MediaCacheException(PlaybackErrorCode.IO_NETWORK_CONNECTION_TIMEOUT, e.message ?: "timeout")
            } catch (e: IOException) {
                temp.delete()
                throw MediaCacheException(PlaybackErrorCode.IO_NETWORK_CONNECTION_FAILED, e.message ?: "network")
            }
            onProgress(1f)
            trim()
            target
        }

    /** Deletes least-recently-used entries until the cache fits inside [maxBytes]. */
    fun trim() {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= maxBytes) return
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    /** Total bytes the cache currently occupies. */
    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun fileFor(key: String): File = File(dir, sha1(key) + ".media")

    private fun sha1(value: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                append("0123456789abcdef"[(byte.toInt() shr 4) and 0x0F])
                append("0123456789abcdef"[byte.toInt() and 0x0F])
            }
        }
    }

    private companion object {
        const val MAX_TRACK_BYTES = 128L * 1024 * 1024
        const val DEFAULT_MAX_BYTES = 1_024L * 1024 * 1024
    }
}
