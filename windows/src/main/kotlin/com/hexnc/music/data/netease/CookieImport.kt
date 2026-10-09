package com.hexnc.music.data.netease

/**
 * Normalises a pasted browser session into `name=value` cookie pairs.
 *
 * This exists because the login endpoints are behind NetEase risk control and answer `-462` for
 * some networks no matter how coherent the client fingerprint is. Copying the session out of a
 * signed-in browser is the only path that skips the gate entirely, and people copy all sorts of
 * shapes while doing it: the whole `document.cookie` string, a multi-line dump from a devtools
 * panel, a `Cookie:` request header, or just the `MUSIC_U` value on its own.
 *
 * Anything with no `=` at all is taken to be a bare token, and a bare token can only sensibly be
 * `MUSIC_U` — it is the cookie that identifies the session.
 *
 * Splitting on `;` is safe for real cookie values: RFC 6265 forbids a bare `;` inside one, and the
 * NetEase tokens are hex/base64url anyway. Empty fragments are dropped so a trailing `;` or a
 * blank line in a dump cannot produce a nameless cookie.
 */
internal fun parseCookieInput(raw: String): List<String> {
    val text = raw.trim().removePrefixIgnoringCase("Cookie:").trim()
    if (text.isEmpty()) return emptyList()
    val parts = text
        .split(';', '\n', '\r')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    if (parts.isEmpty()) return emptyList()
    if (parts.none { it.contains('=') }) {
        // One `=`-less fragment is a bare token; several are junk (e.g. a lone `;`), not a token.
        return if (parts.size == 1) listOf("MUSIC_U=${parts[0]}") else emptyList()
    }
    return parts.filter { it.contains('=') }
}

/**
 * Drops a leading `Cookie:` label, which is how devtools and `curl` present a request header.
 *
 * Matching is case-insensitive because the header name is not case-sensitive; [prefix] is small and
 * ASCII, so the index check is exact rather than locale-dependent.
 */
private fun String.removePrefixIgnoringCase(prefix: String): String =
    if (length >= prefix.length && regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) {
        substring(prefix.length)
    } else {
        this
    }
