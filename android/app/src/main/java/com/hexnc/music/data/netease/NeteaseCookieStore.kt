package com.hexnc.music.data.netease

import android.content.Context
import android.content.SharedPreferences

/**
 * Cookie jar for `music.163.com`, persisted across launches so the login survives a restart.
 *
 * OkHttp's [okhttp3.CookieJar] is intentionally not used: NetEase hands out cookies with attributes
 * (`Domain=.music.163.com`, odd `Expires` values) that the strict RFC-6265 matcher can reject, and
 * the API only ever needs a flat `name=value; ...` header. Managing the string by hand is both
 * simpler and more forgiving.
 */
class NeteaseCookieStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val map = LinkedHashMap<String, String>()

    init {
        load()
    }

    @Synchronized
    private fun load() {
        val raw = prefs.getString(KEY, null) ?: return
        raw.split(';').forEach { part ->
            val trimmed = part.trim()
            if (trimmed.isEmpty()) return@forEach
            val eq = trimmed.indexOf('=')
            if (eq > 0) map[trimmed.substring(0, eq)] = trimmed.substring(eq + 1)
        }
    }

    @Synchronized
    private fun persist() {
        prefs.edit().putString(KEY, map.entries.joinToString("; ") { "${it.key}=${it.value}" }).apply()
    }

    /** Header value for the next request; empty string when nothing is stored. */
    @Synchronized
    fun header(): String = map.entries.joinToString("; ") { "${it.key}=${it.value}" }

    @Synchronized
    fun get(name: String): String? = map[name]

    @Synchronized
    fun has(name: String): Boolean = map[name]?.isNotEmpty() == true

    @Synchronized
    fun put(name: String, value: String) {
        if (value.isEmpty()) map.remove(name) else map[name] = value
        persist()
    }

    /**
     * Merge a batch of `Set-Cookie` header values. A cookie whose value is empty is a server-side
     * deletion and removes the entry.
     */
    @Synchronized
    fun absorb(setCookies: List<String>) {
        if (setCookies.isEmpty()) return
        var changed = false
        setCookies.forEach { header ->
            splitJoined(header).forEach inner@{ raw ->
                val pair = raw.substringBefore(';').trim()
                val eq = pair.indexOf('=')
                if (eq <= 0) return@inner
                val name = pair.substring(0, eq).trim()
                val value = pair.substring(eq + 1).trim()
                if (name.isEmpty()) return@inner
                val previous = map[name]
                if (value.isEmpty()) {
                    if (map.remove(name) != null) changed = true
                } else if (previous != value) {
                    map[name] = value
                    changed = true
                }
            }
        }
        if (changed) persist()
    }

    /**
     * Splits a header that may carry several comma-joined cookies back into individual cookies.
     *
     * OkHttp usually keeps `Set-Cookie` values separate, but some HTTP stacks (and proxies) collapse
     * them into one comma-separated line. The split only fires where a comma is followed by
     * something that looks like `name=`, so the comma inside an `Expires=Wed, 09 Jun …` date — whose
     * next token starts with a digit — is left alone.
     */
    private fun splitJoined(header: String): List<String> =
        if (header.indexOf(',') < 0) listOf(header)
        else header.split(COOKIE_SEPARATOR).filter { it.isNotBlank() }

    @Synchronized
    fun clear() {
        map.clear()
        prefs.edit().remove(KEY).remove(KEY_LIBRARY_STATE).apply()
    }

    /**
     * Records that [uid]'s library was synced at [at].
     *
     * This used to be a one-shot claim: the second launch of the same account was silently skipped,
     * so newly added songs never arrived until the user wiped the app and signed in again. The
     * timestamp is kept instead, purely so the UI can say "上次同步于 X" and so the automatic
     * refresh can decide whether it is due — it never blocks a sync on its own.
     */
    @Synchronized
    fun markLibrarySynced(uid: Long, at: Long = System.currentTimeMillis()) {
        if (uid <= 0L) return
        prefs.edit().putString(KEY_LIBRARY_STATE, "$uid:$at").apply()
    }

    /** The last successful sync for [uid], or 0 when it never synced / another account is signed in. */
    @Synchronized
    fun lastLibrarySyncAt(uid: Long): Long {
        if (uid <= 0L) return 0L
        val raw = prefs.getString(KEY_LIBRARY_STATE, null) ?: return 0L
        val split = raw.indexOf(':')
        if (split <= 0) return 0L
        if (raw.substring(0, split).toLongOrNull() != uid) return 0L
        return raw.substring(split + 1).toLongOrNull() ?: 0L
    }

    /** The CSRF token the eapi payload header expects, derived from the `__csrf` cookie. */
    val csrf: String get() = get("__csrf") ?: ""

    /** True when a real (non-anonymous) session is present. */
    val isLoggedIn: Boolean get() = has("MUSIC_U")

    @Synchronized
    fun snapshot(): Map<String, String> = LinkedHashMap(map)

    companion object {
        private const val PREFS = "hexnc_netease_session"
        private const val KEY = "cookies"

        /** `uid:timestamp` of the last successful library sync, or absent when never synced. */
        private const val KEY_LIBRARY_STATE = "library_sync_state"

        /** A comma that begins another `name=value` pair rather than an `Expires` date. */
        private val COOKIE_SEPARATOR = Regex(",(?=\\s*[A-Za-z_$][A-Za-z0-9_$.\\-]*=)")
    }
}
