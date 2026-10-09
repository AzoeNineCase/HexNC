package com.hexnc.music.data.model

/**
 * How far through a line's karaoke words playback has reached, as a fraction in `0f..1f`.
 *
 * The result drives the wipe that fills the active lyric line, so it is weighted by word length
 * rather than by word count: a line of four long words would otherwise appear to sweep four times
 * faster than one of four short words.
 *
 * [offsetMs] follows the `Lyrics` convention (it is *added* to the reported position, so a positive
 * offset moves lyrics later).
 *
 * Returns `0f` when there are no words or when nothing has started yet, so callers can treat `0f` and
 * "no karaoke data" the same way.
 */
internal fun wordProgress(words: List<LyricWord>, positionMs: Long, offsetMs: Long = 0L): Float {
    if (words.isEmpty()) return 0f
    val t = positionMs + offsetMs

    var sung = 0.0
    var total = 0.0
    for (word in words) {
        // A word that is blank or a stray space still occupies a slot; never let it weigh zero,
        // otherwise a line of spaces could divide by zero.
        val weight = word.text.length.coerceAtLeast(1).toDouble()
        total += weight

        val start = word.startMs
        val end = start + word.durationMs.coerceAtLeast(0L)
        sung += when {
            t >= end -> weight
            t <= start -> 0.0
            else -> weight * ((t - start).toDouble() / (end - start).toDouble())
        }
    }
    if (total <= 0.0) return 0f
    return (sung / total).toFloat().coerceIn(0f, 1f)
}

/**
 * Fraction of a plain (non-karaoke) line that has been played, derived from when the next line starts.
 *
 * `yrc` gives per-word timings, but ordinary `lrc` does not, so the only honest signal is the gap to
 * the following line. [nextStartMs] of `null` means this is the last line and the sweep simply stays
 * put. A gap longer than [maxSpanMs] is clamped: a one-line chorus followed by twenty seconds of
 * instrumental should not turn into a twenty-second crawl.
 */
internal fun lineProgress(
    lineStartMs: Long,
    nextStartMs: Long?,
    positionMs: Long,
    offsetMs: Long = 0L,
    maxSpanMs: Long = 8_000L,
): Float {
    val next = nextStartMs ?: return if (positionMs + offsetMs >= lineStartMs) 1f else 0f
    val span = (next - lineStartMs).coerceAtLeast(1L)
    val clamped = span.coerceAtMost(maxSpanMs.coerceAtLeast(1L))
    val elapsed = (positionMs + offsetMs) - lineStartMs
    if (elapsed <= 0L) return 0f
    return (elapsed.toDouble() / clamped.toDouble()).toFloat().coerceIn(0f, 1f)
}
