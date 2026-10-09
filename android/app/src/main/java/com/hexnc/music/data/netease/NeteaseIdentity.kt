package com.hexnc.music.data.netease

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom

/**
 * The stable "device" identity NetEase expects on every request.
 *
 * The server fingerprints clients by these cookies/fields, so they must be generated once and then
 * reused forever: rotating them per request is exactly the pattern risk-control reacts to. All of
 * them are persisted in `SharedPreferences` on first use.
 *
 * The values are *shape*-compatible with the official desktop and Android clients rather than
 * cryptographically meaningful — the server does not validate their entropy, only their presence
 * and stability.
 */
class NeteaseIdentity(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val random = SecureRandom()

    /** 52 uppercase hex characters, matching the official Android client. */
    val deviceId: String = prefs.stringOrCreate(KEY_DEVICE_ID) { randomHex(26).uppercase() }

    /** `_ntes_nuid` — the long-lived visitor identifier. */
    val nuid: String = prefs.stringOrCreate(KEY_NUID) { randomHex(16) }

    /** `NMTID` — a per-install token; only sent on non-login requests. */
    val nmtid: String = prefs.stringOrCreate(KEY_NMTID) { randomHex(16) }

    /** `WNMCID` — `"<6 lower-case letters>.<epoch millis>.01.0"`. */
    val wnmcid: String = prefs.stringOrCreate(KEY_WNMCID) {
        val prefix = buildString(6) { repeat(6) { append(('a'.code + random.nextInt(26)).toChar()) } }
        "$prefix.${System.currentTimeMillis()}.01.0"
    }

    /** `_ntes_nnid` — `"<nuid>,<epoch millis>"`. */
    val nnid: String = prefs.stringOrCreate(KEY_NNID) { "$nuid,${System.currentTimeMillis()}" }

    /**
     * Lower-case hex, deliberately free of `String.format`.
     *
     * `"%02x".format(it)` looks equivalent but is locale-sensitive: `Formatter` renders digits in the
     * default locale's numbering system, so on an `ar` / `fa` / `bn` device the "hex" would come back
     * with Arabic-Indic or Bengali digits. These strings are sent as cookies and feed the eapi
     * signature, so they must be ASCII on every device.
     */
    private fun randomHex(bytes: Int): String {
        val out = ByteArray(bytes)
        random.nextBytes(out)
        val sb = StringBuilder(bytes * 2)
        for (b in out) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v shr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private fun SharedPreferences.stringOrCreate(key: String, create: () -> String): String {
        prefs.getString(key, null)?.let { return it }
        val created = create()
        prefs.edit().putString(key, created).apply()
        return created
    }

    /** Client identity sent as plain cookies on every request. */
    fun cookies(): Map<String, String> = linkedMapOf(
        "os" to OS,
        "appver" to APP_VER,
        "osver" to OS_VER,
        "deviceId" to deviceId,
        "channel" to CHANNEL,
        "buildver" to BUILD_VER,
        "resolution" to RESOLUTION,
        "versioncode" to VERSION_CODE,
        "mobilename" to "",
        "_ntes_nuid" to nuid,
        "_ntes_nnid" to nnid,
        "WNMCID" to wnmcid,
        "NMTID" to nmtid,
    )

    /**
     * The `header` object the eapi transport embeds in its payload.
     *
     * @param musicU the `MUSIC_U` login token, or null when signed out.
     */
    fun eapiHeader(musicU: String?, csrf: String): JsonObject = buildJsonObject {
        put("osver", OS_VER)
        put("deviceId", deviceId)
        put("os", OS)
        put("appver", APP_VER)
        put("versioncode", VERSION_CODE)
        put("mobilename", "")
        put("buildver", BUILD_VER)
        put("resolution", RESOLUTION)
        put("__csrf", csrf)
        put("channel", CHANNEL)
        put("requestId", requestId())
        if (!musicU.isNullOrEmpty()) put("MUSIC_U", musicU)
    }

    /** `"<epoch millis>_<4 random digits>"`, the shape the official client sends. */
    fun requestId(): String = requestIdAt(System.currentTimeMillis()) { random.nextInt(10) }

    companion object {
        private const val PREFS = "hexnc_netease_identity"
        private const val HEX = "0123456789abcdef"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_NUID = "ntes_nuid"
        private const val KEY_NNID = "ntes_nnid"
        private const val KEY_NMTID = "nmtid"
        private const val KEY_WNMCID = "wnmcid"

        const val OS = "android"
        const val APP_VER = "8.20.20.231215173437"
        const val OS_VER = "14"
        const val CHANNEL = "xiaomi"
        const val VERSION_CODE = "8008020"
        const val RESOLUTION = "1080x2400"

        /**
         * `buildver` is the official client's build epoch. It is pinned rather than derived from
         * "now" so that the value stays consistent with [APP_VER] across app launches.
         */
        const val BUILD_VER = "1702656000"

        /** A current Android Chrome UA; used for the plain-`api` transport. */
        const val WEB_UA =
            "Mozilla/5.0 (Linux; Android 14; 2211133C) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36"

        /** The official Android client UA; used for the eapi transport. */
        const val EAPI_UA = "NeteaseMusic/$APP_VER ($CHANNEL; $OS $OS_VER; zh_CN)"
    }
}
