package com.hexnc.music.data.netease

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The typed endpoint facade over [NeteaseClient].
 *
 * Every method here answers one question about the service and returns a decode of the wire model;
 * nothing in this file touches local storage. The route, method and body shape of each call are the
 * ones verified against the live service — several obvious-looking alternatives are known-broken
 * (`weapi`, `/api/cloudsearch/get/web`) and are not used.
 */
class NeteaseApi(private val client: NeteaseClient) {

    // ------------------------------------------------------------------ account / session

    /**
     * The currently signed-in account, or `null` when the session is anonymous.
     *
     * A signed-out server answers `{"code":200,"account":null,"profile":null}`, so a null body is a
     * normal result rather than an error.
     */
    suspend fun account(): NcAccountResponse =
        client.api("/api/w/nuser/account/get")
            .requireSuccess("/api/w/nuser/account/get")
            .decode()

    suspend fun logout() {
        runCatching { client.api("/api/logout") }
    }

    /**
     * Creates a QR login handle. The caller renders `https://music.163.com/login?codekey=<unikey>`
     * and then polls [qrCheck] roughly every three seconds.
     */
    suspend fun qrCreate(): String {
        val json = client.api("/api/login/qrcode/unikey", mapOf("type" to QR_TYPE))
            .requireSuccess("/api/login/qrcode/unikey")
        return json.stringOrNull("unikey")
            ?: throw NeteaseException(json.intOrNull("code") ?: -1, "QR handle missing")
    }

    /**
     * One poll of a QR login attempt.
     *
     * The four status codes are results, not errors, so this deliberately skips
     * [requireSuccess]: `800` expired · `801` awaiting scan · `802` scanned · `803` authorised.
     */
    suspend fun qrCheck(unikey: String): NcQrPollResponse =
        client.api(
            "/api/login/qrcode/client/login",
            mapOf("key" to unikey, "type" to QR_TYPE),
        ).decode()

    /**
     * Signs in with a phone number.
     *
     * The password is sent as its MD5 hex digest — the service requires that, and it is also why
     * the raw password never has to be persisted.
     *
     * @param countryCode without a leading `+`; use `86` for mainland China.
     */
    suspend fun loginCellphone(phone: String, password: String, countryCode: String = "86"): NcLoginResponse {
        val json = client.api(
            "/api/w/login/cellphone",
            mapOf(
                "type" to "1",
                "https" to "true",
                "phone" to phone,
                "countrycode" to countryCode,
                "password" to NeteaseCrypto.md5Hex(password),
                "remember" to "true",
            ),
        )
        val parsed: NcLoginResponse = json.decode()
        parsed.requireLoginOk("/api/w/login/cellphone")
        return parsed
    }

    /** Signs in with an email address; see [loginCellphone] for the password convention. */
    suspend fun loginEmail(email: String, password: String): NcLoginResponse {
        val json = client.api(
            "/api/w/login",
            mapOf(
                "type" to "0",
                "https" to "true",
                "username" to email,
                "password" to NeteaseCrypto.md5Hex(password),
                "rememberLogin" to "true",
            ),
        )
        val parsed: NcLoginResponse = json.decode()
        parsed.requireLoginOk("/api/w/login")
        return parsed
    }

    // ------------------------------------------------------------------ playlists

    /** Every playlist the user owns or subscribes to, following pagination to the end. */
    suspend fun userPlaylists(uid: Long): List<NcPlaylist> {
        val collected = mutableListOf<NcPlaylist>()
        var offset = 0
        // The service caps a page well below this, so a generous limit only ever reduces round trips.
        val limit = 1000
        while (true) {
            val page: NcUserPlaylistResponse = client.api(
                "/api/user/playlist",
                mapOf("uid" to uid.toString(), "limit" to limit.toString(), "offset" to offset.toString()),
            ).requireSuccess("/api/user/playlist").decode()
            collected += page.playlist
            if (!page.more || page.playlist.isEmpty()) break
            offset += page.playlist.size
        }
        return collected
    }

    /**
     * A playlist's metadata plus its full track-id list.
     *
     * `trackIds` is the authoritative, complete ordering and is returned in full at *any* `n` —
     * measured at `n` = 0, 1, 10, 1000 and 100000 on a 965-track playlist, all 965 ids. `n` only
     * caps the inline `tracks` sample, which the service holds at 10 entries however large `n` gets,
     * so 10 is the largest useful value. Tracks are hydrated through [songDetail], never through the
     * inline array.
     */
    suspend fun playlistDetail(id: Long): NcPlaylist {
        val json = client.api(
            "/api/v6/playlist/detail",
            mapOf("id" to id.toString(), "n" to "10", "s" to "8"),
        ).requireSuccess("/api/v6/playlist/detail")
        return json.objOrNull("playlist")?.let { NeteaseJson.decodeFromString<NcPlaylist>(it.toString()) }
            ?: throw NeteaseException(json.intOrNull("code") ?: -1, "playlist $id missing from response")
    }

    // ------------------------------------------------------------------ songs

    /**
     * Hydrates track metadata for [ids], transparently chunked at the service's 1000-id ceiling.
     *
     * Ids the service does not know are silently dropped from the response, so callers must not
     * assume the result is index-aligned with the request.
     */
    suspend fun songDetail(ids: List<Long>): List<NcSong> {
        if (ids.isEmpty()) return emptyList()
        val out = ArrayList<NcSong>(ids.size)
        for (chunk in ids.distinct().chunked(NeteaseClient.SONG_DETAIL_CHUNK)) {
            val page: NcSongDetailResponse = client.api(
                "/api/v3/song/detail",
                mapOf("c" to songDetailJson(chunk)),
            ).requireSuccess("/api/v3/song/detail").decode()
            out += page.songs
        }
        return out
    }

    suspend fun songDetail(id: Long): NcSong? = songDetail(listOf(id)).firstOrNull()

    /**
     * Resolves streaming URLs for [ids].
     *
     * Runs over eapi, which is the only transport that answers this route. The per-track `code` is
     * the thing that matters: `404` means the account cannot play that track at all. The granted
     * tier is routinely *lower* than the requested one, so callers should read back
     * [NcSongUrl.level] rather than assume [level] was honoured. URLs expire (currently after
     * `expi` seconds, in practice 1200), so they must not be cached for long.
     *
     * @param level one of `standard`, `higher`, `exhigh`, `lossless`, `hires`.
     */
    suspend fun songUrl(ids: List<Long>, level: String): List<NcSongUrl> {
        if (ids.isEmpty()) return emptyList()
        val out = ArrayList<NcSongUrl>(ids.size)
        for (chunk in ids.distinct().chunked(SONG_URL_CHUNK)) {
            val body = buildJsonObject {
                put("ids", trackIdJson(chunk))
                put("level", level)
                put("encodeType", encodeTypeFor(level))
            }
            val json = client.eapi("/api/song/enhance/player/url/v1", body)
                .requireSuccess("/api/song/enhance/player/url/v1")
            out += json.arrOrNull("data")
                ?.mapNotNull { element ->
                    runCatching { NeteaseJson.decodeFromString<NcSongUrl>(element.toString()) }.getOrNull()
                }
                .orEmpty()
                // The service hands back `http://` media URLs; see [httpsStreamUrl] for why they have
                // to be upgraded before ExoPlayer is allowed anywhere near them.
                .map { it.copy(url = httpsStreamUrl(it.url)) }
        }
        return out
    }

    // ------------------------------------------------------------------ lyrics

    /**
     * Fetches every lyric variant the service holds for a track.
     *
     * Over eapi because plain `api` does not return the word-by-word `yrc` stream. A successful
     * response may legitimately omit `code`, so [NeteaseClient.api]'s code check is not applied
     * here; instead the response's own `nolyric` / `uncollected` flags describe the empty cases.
     */
    suspend fun lyrics(id: Long): NcLyricResponse {
        val body = buildJsonObject {
            put("id", id)
            put("cp", false)
            put("tv", 0)
            put("lv", 0)
            put("rv", 0)
            put("kv", 0)
            put("yv", 0)
            put("ytv", 0)
            put("yrv", 0)
        }
        val json = client.eapi("/api/song/lyric/v1", body)
        val code = json.intOrNull("code")
        if (code != null && code != 200) {
            throw NeteaseException(code, json.stringOrNull("message") ?: json.stringOrNull("msg"))
        }
        return json.decode()
    }

    // ------------------------------------------------------------------ discovery

    /**
     * Catalogue search.
     *
     * @param type `1` song, `10` album, `100` artist, `1000` playlist.
     */
    suspend fun search(keyword: String, type: Int = 1, limit: Int = 30, offset: Int = 0): NcSearchResult? {
        val form = mapOf(
            "s" to keyword,
            "type" to type.toString(),
            "limit" to limit.toString(),
            "offset" to offset.toString(),
        )
        // The preferred endpoint is the "pc" cloud search; the older route is a fallback because
        // the two have been observed to disagree on availability.
        val json = runCatching { client.api("/api/cloudsearch/pc", form) }.getOrElse { client.api("/api/search/get", form) }
        return json.requireSuccess("/api/cloudsearch/pc").decode<NcSearchResponse>().result
    }

    suspend fun albumDetail(albumId: Long): NcAlbumDetailResponse =
        client.apiEmpty("/api/v1/album/$albumId").requireSuccess("/api/v1/album").decode()

    /**
     * The albums the signed-in account has favourited, one page at a time.
     *
     * The plain `api` transport is tried first because the analogous sublist route
     * (`/api/user/playlist`) answers there; the eapi retry covers the case where the service serves
     * this one encrypted only. `total=true` is what makes the reply carry `hasMore`, which is the
     * only reliable paging signal this route offers.
     */
    suspend fun albumSublist(limit: Int = 100, offset: Int = 0): NcAlbumSublistResponse {
        val form = mapOf(
            "limit" to limit.toString(),
            "offset" to offset.toString(),
            "total" to "true",
        )
        val json = runCatching {
            client.api("/api/album/sublist", form).requireSuccess("/api/album/sublist")
        }.getOrElse {
            client.eapi(
                "/api/album/sublist",
                buildJsonObject {
                    put("limit", limit)
                    put("offset", offset)
                    put("total", true)
                },
            ).requireSuccess("/api/album/sublist")
        }
        return json.decode()
    }

    suspend fun artistTopSongs(artistId: Long): List<NcSong> {
        val json = client.api("/api/artist/top/song", mapOf("id" to artistId.toString()))
            .requireSuccess("/api/artist/top/song")
        return json.decode<NcArtistTopSongsResponse>().songs
    }

    // ------------------------------------------------------------------ writes

    /**
     * Likes or unlikes a track in the signed-in account's "liked songs".
     *
     * NetEase reports an unauthenticated caller as code `301`, which surfaces as a
     * [NeteaseException] for the caller to translate into a sign-in prompt.
     */
    suspend fun like(trackId: Long, like: Boolean): Boolean {
        val json = client.api(
            "/api/radio/like",
            mapOf(
                "alg" to "itembased",
                "trackId" to trackId.toString(),
                "like" to like.toString(),
                "time" to "3",
            ),
        )
        json.requireSuccess("/api/radio/like")
        return true
    }

    /** Adds tracks to a playlist the signed-in user owns. */
    suspend fun playlistAdd(playlistId: Long, trackIds: List<Long>): Boolean =
        manipulate(playlistId, trackIds, "add")

    /** Removes tracks from a playlist the signed-in user owns. */
    suspend fun playlistRemove(playlistId: Long, trackIds: List<Long>): Boolean =
        manipulate(playlistId, trackIds, "del")

    /**
     * `POST /api/playlist/manipulate/tracks`, including the service's odd retry contract: code
     * `512` means "send the same list again, duplicated", which is the only way a partial batch is
     * accepted.
     */
    private suspend fun manipulate(playlistId: Long, trackIds: List<Long>, op: String): Boolean {
        if (trackIds.isEmpty()) return true
        suspend fun send(ids: List<Long>): JsonObject = client.api(
            "/api/playlist/manipulate/tracks",
            mapOf(
                "op" to op,
                "pid" to playlistId.toString(),
                "trackIds" to trackIdJson(ids),
                "imme" to "true",
            ),
        )
        val first = send(trackIds)
        val code = first.intOrNull("code") ?: 200
        if (code == 512) {
            send(trackIds + trackIds).requireSuccess("/api/playlist/manipulate/tracks")
            return true
        }
        first.requireSuccess("/api/playlist/manipulate/tracks")
        return true
    }

    // ------------------------------------------------------------------ decoding

    private inline fun <reified T> JsonObject.decode(): T = NeteaseJson.decodeFromString<T>(toString())

    private fun NcLoginResponse.requireLoginOk(path: String) {
        if (code != 200) {
            throw NeteaseException(code, message ?: msg ?: "login failed ($path)")
        }
    }

    private fun encodeTypeFor(level: String): String = when (level.lowercase()) {
        "lossless", "hires" -> "flac"
        else -> "mp3"
    }

    companion object {
        private const val QR_TYPE = "3"
        private const val SONG_URL_CHUNK = 200
    }
}
