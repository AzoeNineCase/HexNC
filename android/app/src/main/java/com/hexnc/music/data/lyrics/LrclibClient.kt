package com.hexnc.music.data.lyrics

import com.hexnc.music.BuildConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * One hit from the LRCLIB search endpoint.
 *
 * `duration` is in **seconds** (fractional), unlike every duration in this app, so callers must
 * convert. `plainLyrics` / `syncedLyrics` arrive with the search result itself — no second request.
 */
@Serializable
data class LrclibTrack(
    val id: Long = 0L,
    @SerialName("trackName") val trackName: String = "",
    @SerialName("artistName") val artistName: String = "",
    @SerialName("albumName") val albumName: String = "",
    val duration: Double = 0.0,
    val instrumental: Boolean = false,
    @SerialName("plainLyrics") val plainLyrics: String? = null,
    @SerialName("syncedLyrics") val syncedLyrics: String? = null,
)

/**
 * Turn a hit into a rankable candidate.
 *
 * The duration conversion is the point of this function: LRCLIB reports seconds (fractional), and
 * everything else in this app is milliseconds. Getting it wrong by a factor of 1 000 would make
 * every candidate look like a different recording, silently disabling the automatic match.
 */
internal fun LrclibTrack.toCandidate(fallbackTitle: String = trackName): LyricCandidate = LyricCandidate(
    source = LyricSource.LRCLIB,
    id = id.toString(),
    title = trackName.ifBlank { fallbackTitle },
    artist = artistName,
    album = albumName,
    durationMs = (duration * 1000.0).toLong(),
    hasSynced = !syncedLyrics.isNullOrBlank(),
    instrumental = instrumental,
    lrclibId = id.takeIf { it > 0L },
    plainLyrics = plainLyrics,
    syncedLyrics = syncedLyrics,
)

/**
 * The second lyric source: [LRCLIB](https://lrclib.net), a free database with no account and no key.
 *
 * It is here for the two cases NetEase cannot serve. A user who never signed in has no NetEase
 * session at all, and a local file has no NetEase id to look lyrics up by — but LRCLIB indexes by
 * *name and duration*, which is precisely the metadata a local file carries. Its documents are
 * already `lrc`, so they need no translation layer.
 *
 * Every failure is reported as "no results" rather than thrown: a lyrics search runs automatically
 * when the user opens the lyric pane, and a dead network must not turn into an error dialog there.
 */
class LrclibClient(private val client: OkHttpClient? = null) {

    private val http: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Searches for [title], optionally narrowed by [artist] and [album].
     *
     * A miss with the artist attached is retried without it: local tags are often wrong or absent
     * ("Unknown Artist", a differently romanised name), and LRCLIB matches `track_name` alone well
     * enough that the looser query is worth the extra request. The retry only happens when the first
     * answer was empty, so a normal search costs one round trip.
     */
    suspend fun search(title: String, artist: String = "", album: String = ""): List<LrclibTrack> {
        if (title.isBlank()) return emptyList()
        val narrowed = query(title, artist, album)
        if (narrowed.isNotEmpty() || (artist.isBlank() && album.isBlank())) return narrowed
        return query(title, "", "")
    }

    private suspend fun query(title: String, artist: String, album: String): List<LrclibTrack> =
        withContext(Dispatchers.IO) {
            val builder = SEARCH_URL.toHttpUrl().newBuilder()
                .addQueryParameter("track_name", title)
            if (artist.isNotBlank()) builder.addQueryParameter("artist_name", artist)
            if (album.isNotBlank()) builder.addQueryParameter("album_name", album)
            val request = Request.Builder()
                .url(builder.build())
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()
            runCatching {
                http.newCall(request).execute().use { response ->
                    val body = response.body.string()
                    // Non-2xx is normal here (LRCLIB answers 404 with `{"error": …}` for a miss).
                    if (!response.isSuccessful || body.isBlank()) return@use emptyList()
                    if (body.trimStart().startsWith("{")) return@use emptyList()
                    json.decodeFromString<List<LrclibTrack>>(body)
                }
            }.getOrDefault(emptyList())
        }

    companion object {
        private const val SEARCH_URL = "https://lrclib.net/api/search"

        /**
         * LRCLIB asks clients to identify themselves so they can be contacted before being blocked —
         * worth honouring, since this client has no key and depends on their goodwill.
         */
        private val USER_AGENT = "HexNC v${BuildConfig.VERSION_NAME} (https://github.com/hexnc)"
    }
}
