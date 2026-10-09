package com.hexnc.music.data.netease

/**
 * Builds the `requestId` field carried by the eapi `header` object.
 *
 * Every NetEase client sends `"<epoch millis>_<four digits>"` — the underscore is part of the
 * format, not decoration: without it the payload is simply not shaped like a client request, and
 * the value is one of the fields the server's risk control can read.
 *
 * The random source is injected so the format is testable without a device; `digit` must return
 * `0..9` and anything outside that range is clamped rather than allowed to widen the field.
 */
internal fun requestIdAt(millis: Long, digit: () -> Int): String = buildString(18) {
    append(millis)
    append('_')
    repeat(4) { append('0' + digit().coerceIn(0, 9)) }
}
