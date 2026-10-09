package com.hexnc.music.data.netease

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Raised when NetEase answers with a business-level failure inside an HTTP 200 body.
 *
 * [code] is the server's own code (`301` unauthenticated, `400` bad request, `404` not found,
 * `501`/`502` bad credentials, `512` retry-with-duplicated-track-list, …); [serverMessage] is
 * whatever human-readable text accompanied it, which is usually Chinese.
 */
class NeteaseException(
    val code: Int,
    val serverMessage: String? = null,
    cause: Throwable? = null,
) : Exception("NetEase error $code${serverMessage?.let { ": $it" } ?: ""}", cause)

/**
 * Transport for the NetEase Cloud Music API.
 *
 * Two encodings are in use and the choice is dictated by the endpoint, not by preference:
 *
 *  * **plain `api`** — `https://music.163.com/api/<path>`, an ordinary form body, no crypto. Works
 *    for nearly every read endpoint.
 *  * **`eapi`** — `https://interface.music.163.com/eapi/<path>`, a single `params` field holding
 *    uppercase-hex AES-ECB of a signed envelope. Required for song URLs and `lyric/v1`.
 *
 * `weapi` is deliberately absent: it is dead (HTTP 200 with a zero-byte body on every route).
 *
 * Cookies are managed by hand rather than with a `CookieJar` so that the NetEase client
 * fingerprint — device id, `_ntes_nuid`, `WNMCID`, … — is always present, and so that `Set-Cookie`
 * is absorbed from *every* response (the session token is silently refreshed mid-flow).
 */
class NeteaseClient(
    private val cookies: NeteaseCookieStore,
    private val identity: NeteaseIdentity,
) {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // ------------------------------------------------------------------ transport

    /**
     * Performs a plain-`api` call and returns the raw body.
     *
     * @param path a path beginning with `/api/`, e.g. `/api/v3/song/detail`.
     * @param form ordinary form fields. JSON-valued fields (`c`, `ids`, `trackIds`) are passed
     *   pre-encoded as strings.
     */
    suspend fun rawApi(path: String, form: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            val builder = FormBody.Builder()
            form.forEach { (key, value) -> builder.add(key, value) }
            val request = Request.Builder()
                .url("$API_HOST$path")
                .header("Cookie", cookieHeader())
                .header("User-Agent", NeteaseIdentity.WEB_UA)
                .header("Referer", "https://music.163.com/")
                .header("Accept", "application/json, text/plain, */*")
                .post(builder.build())
                .build()
            perform(request, path)
        }

    /** Same as [rawApi] but with no body at all (some endpoints take their argument in the path). */
    suspend fun rawApiEmpty(path: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$API_HOST$path")
            .header("Cookie", cookieHeader())
            .header("User-Agent", NeteaseIdentity.WEB_UA)
            .header("Referer", "https://music.163.com/")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        perform(request, path)
    }

    /** Plain-`api` call decoded to a [JsonObject]. */
    suspend fun api(path: String, form: Map<String, String> = emptyMap()): JsonObject =
        parseObject(rawApi(path, form), path)

    /** Plain-`api` call with an empty body, decoded to a [JsonObject]. */
    suspend fun apiEmpty(path: String): JsonObject = parseObject(rawApiEmpty(path), path)

    /** `eapi` call decoded to a [JsonObject]. [path] must still be written as `/api/...`. */
    suspend fun eapi(path: String, body: JsonObject = JsonObject(emptyMap())): JsonObject =
        withContext(Dispatchers.IO) {
            val header = identity.eapiHeader(cookies.get(MUSIC_U), cookies.csrf)
            val payload = buildJsonObject {
                body.forEach { (key, value) -> put(key, value) }
                // The header is part of the signed payload, so it must be added before encoding.
                put("header", header)
            }
            val encoded = NeteaseCrypto.eapi(path, payload.toString())
            val request = Request.Builder()
                .url(EAPI_HOST + eapiPath(path))
                .header("Cookie", cookieHeader())
                .header("User-Agent", NeteaseIdentity.EAPI_UA)
                .header("Referer", "https://music.163.com/")
                .post(FormBody.Builder().add("params", encoded).build())
                .build()
            parseObject(perform(request, path), path)
        }

    // ------------------------------------------------------------------ plumbing

    private fun perform(request: Request, path: String): String {
        val response = try {
            http.newCall(request).execute()
        } catch (e: java.io.IOException) {
            throw NeteaseException(0, "network: ${e.message}", e)
        }
        response.use { res ->
            absorbCookies(res)
            val body = res.body.string()
            if (!res.isSuccessful) {
                throw NeteaseException(res.code, "HTTP ${res.code} for $path")
            }
            if (body.isBlank()) {
                // A zero-byte 200 is how this API reports a malformed request or a rejected
                // signature. It is never a legitimate empty success.
                throw NeteaseException(-1, "empty response for $path")
            }
            return body
        }
    }

    private fun absorbCookies(response: okhttp3.Response) {
        val raw = response.headers.values("Set-Cookie")
        if (raw.isEmpty()) return
        cookies.absorb(raw)
    }

    /**
     * Builds the request `Cookie` header: the stable client fingerprint first, then the live
     * session values, which therefore win on any collision.
     */
    private fun cookieHeader(): String {
        val merged = LinkedHashMap<String, String>()
        merged.putAll(identity.cookies())
        merged.putAll(cookies.snapshot())
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun eapiPath(path: String): String = path.replaceFirst("/api/", "/eapi/")

    private fun parseObject(text: String, path: String): JsonObject =
        try {
            kotlinx.serialization.json.Json.parseToJsonElement(text) as? JsonObject
                ?: throw NeteaseException(-1, "unexpected JSON payload for $path")
        } catch (e: kotlinx.serialization.SerializationException) {
            throw NeteaseException(-1, "malformed JSON for $path", e)
        }

    companion object {
        const val API_HOST = "https://music.163.com"

        /**
         * The mobile eapi gateway.
         *
         * The Android client talks to `interface.music.163.com`, not `interfacepc.music.163.com`:
         * every third-party implementation checked (the Node reference server's `config.json`,
         * Mooic's `ApiClient`, HyPlayer's contracts) uses the non-`pc` host, and the gateway has to
         * agree with the Android `os`/`appver`/`deviceId` profile this client sends.
         */
        const val EAPI_HOST = "https://interface.music.163.com"

        const val MUSIC_U = "MUSIC_U"

        /** `/api/song/enhance/player/url/v1` and friends are sensitive to how many ids ride along. */
        const val SONG_DETAIL_CHUNK = 1000
    }
}

// ---------------------------------------------------------------------- JSON helpers

internal fun JsonObject.intOrNull(key: String): Int? =
    (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

internal fun JsonObject.longOrNull(key: String): Long? =
    (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

internal fun JsonObject.stringOrNull(key: String): String? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    return primitive.takeIf { it.isString }?.content ?: primitive.content.takeIf { it != "null" }
}

internal fun JsonObject.boolOrNull(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

internal fun JsonObject.objOrNull(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arrOrNull(key: String): JsonArray? = this[key] as? JsonArray

/**
 * Throws [NeteaseException] unless the body reports success.
 *
 * A body with **no** `code` member counts as success: several endpoints (notably the lyric ones)
 * omit it entirely on a good response.
 */
internal fun JsonObject.requireSuccess(path: String, vararg alsoAllowed: Int): JsonObject {
    val code = intOrNull("code") ?: return this
    if (code == 200 || code in alsoAllowed) return this
    throw NeteaseException(code, stringOrNull("message") ?: stringOrNull("msg"))
}

/** Encodes a list of track ids as the JSON string NetEase expects inside `c` / `ids` / `trackIds`. */
internal fun trackIdJson(ids: List<Long>): String =
    buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } }.toString()

/** Encodes `c` for `/api/v3/song/detail`: an array of `{"id":n}` objects. */
internal fun songDetailJson(ids: List<Long>): String =
    buildJsonArray { ids.forEach { add(buildJsonObject { put("id", it) }) } }.toString()
