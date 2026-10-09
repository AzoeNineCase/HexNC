package com.hexnc.music.data.netease

import java.util.Locale

/**
 * NetEase serves its playable media over **plain HTTP** (`http://m802.music.126.net/...`) while the
 * same path is perfectly happy over TLS. Since Android 9 (API 28) cleartext HTTP is blocked by
 * default for apps that target 28+, so handing one of those URLs straight to ExoPlayer fails with
 * `ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED` — the metadata (cover art, artist, lyrics) all arrive over
 * HTTPS and keep working, which makes the failure look like a player bug rather than a URL issue.
 *
 * Upgrading the scheme is therefore the fix, and it is strictly better than switching cleartext on
 * app-wide: the request stays encrypted and the platform keeps refusing cleartext everywhere else.
 * `res/xml/network_security_config.xml` additionally permits cleartext for these same NetEase media
 * domains, so a URL that somehow still arrives insecure on a host without TLS is not a hard failure.
 */
private val NETEASE_MEDIA_DOMAINS = listOf("126.net", "163.com")

/** True when [host] is (or is a subdomain of) a NetEase media domain. */
internal fun isNeteaseMediaHost(host: String): Boolean {
    val h = host.lowercase(Locale.ROOT)
    if (h.isEmpty()) return false
    return NETEASE_MEDIA_DOMAINS.any { h == it || h.endsWith(".$it") }
}

/**
 * Rewrites an insecure NetEase media URL to `https`, leaving everything else untouched.
 *
 * Deliberately conservative: only the scheme is ever changed, only for NetEase hosts, and a URL that
 * is already secure (or belongs to somebody else) is returned verbatim. Returns `null` for a blank
 * input so callers can keep using null as "no URL".
 */
internal fun httpsStreamUrl(url: String?): String? {
    val raw = url?.trim().orEmpty()
    if (raw.isEmpty()) return null
    if (!raw.startsWith("http://", ignoreCase = true)) return raw
    val rest = raw.substring("http://".length)
    val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
    val host = authority.substringAfterLast('@').substringBefore(':')
    return if (isNeteaseMediaHost(host)) "https://$rest" else raw
}
