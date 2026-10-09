package com.hexnc.music.data.local

import com.hexnc.music.data.model.LyricLine
import com.hexnc.music.data.model.LyricWord
import com.hexnc.music.data.model.Lyrics

/**
 * Parser for every lyric flavour NetEase and the wild return:
 *
 *  - `lrc`     plain `[mm:ss.xx]text` lines, multiple timestamps per line allowed
 *  - `tlyric`  translated lines, matched onto the base lrc by fuzzy text
 *  - `romalrc` romanised lines, matched the same way
 *  - `yrc`     word-level karaoke: `[start,duration](wStart,wDur,0)word...`
 *  - metadata  `[ar:]`, `[ti:]`, `[al:]`, `[by:]`, `[offset:]`, `[length:]`
 *
 * The parser is deliberately tolerant: unparseable lines are skipped rather than throwing, and an
 * empty result is reported as [Lyrics.EMPTY] so the UI can fall back to "no lyrics".
 */
object LrcParser {

    private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val META_TAG = Regex("""^\[(ar|ti|al|by|offset|length|re|ve|kana):(.*)]$""", RegexOption.IGNORE_CASE)
    private val YRC_LINE = Regex("""^\[(\d+),(\d+)](.*)$""")
    private val YRC_WORD = Regex("""\((\d+),(\d+),\d+\)([^()]*)""")

    /** NetEase wraps some payloads in JSON-ish escapes; normalise before parsing. */
    fun decodeEscapes(raw: String): String =
        raw.replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\/", "/")

    /**
     * Parse a plain LRC document.
     *
     * @param raw lyric text; may be null/blank.
     * @param baseOffsetMs extra offset applied on top of any `[offset:]` tag found in the document.
     */
    fun parseLrc(raw: String?, baseOffsetMs: Long = 0L): Lyrics {
        if (raw.isNullOrBlank()) return Lyrics.EMPTY
        val text = decodeEscapes(raw)
        var offset = baseOffsetMs
        val pairs = ArrayList<Pair<Long, String>>(256)

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim('\r', '\uFEFF')
            if (line.isBlank()) return@forEach

            val meta = META_TAG.matchEntire(line)
            if (meta != null) {
                if (meta.groupValues[1].equals("offset", ignoreCase = true)) {
                    meta.groupValues[2].trim().toLongOrNull()?.let { offset += it }
                }
                return@forEach
            }

            val stamps = TIME_TAG.findAll(line).toList()
            if (stamps.isEmpty()) return@forEach

            val content = line.substring(stamps.last().range.last + 1).trim()
            if (content.isEmpty()) return@forEach
            // Skip the `[00:00.000] 作词 : X` style credit lines? No — they are legitimate
            // timed content in many files, so they are kept and shown as-is.
            stamps.forEach { m ->
                parseStamp(m)?.let { pairs += it to content }
            }
        }

        if (pairs.isEmpty()) return Lyrics.EMPTY

        pairs.sortBy { it.first }
        val lines = ArrayList<LyricLine>(pairs.size)
        pairs.forEach { (t, content) -> lines += LyricLine(timeMs = t, text = content) }
        return Lyrics(
            lines = dedupe(lines),
            offsetMs = offset,
            rawLrc = text,
            isPureMusic = pairs.size == 1 && pairs[0].second.contains("纯音乐")
        )
    }

    /**
     * Parse a lyric document that has no timestamps at all — a plain-text `.lrc`, or a `.lrc` whose
     * tags were stripped.
     *
     * [parseLrc] deliberately drops untimed lines (a NetEase sheet with timestamps must never gain
     * phantom `00:00` lines), so a local sidecar with no tags would come back empty and be rejected
     * as "not a lyric file". Keeping the two entry points separate means neither convention leaks
     * into the other: this one is only reached when [parseLrc] found nothing.
     *
     * Every line becomes a [LyricLine] with `timeMs == 0`, which marks the whole sheet as
     * [Lyrics.isUnsynced] and tells the player to show it as static text.
     */
    fun parsePlainText(raw: String?, baseOffsetMs: Long = 0L): Lyrics {
        if (raw.isNullOrBlank()) return Lyrics.EMPTY
        val text = decodeEscapes(raw)
        val lines = ArrayList<LyricLine>(128)
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim('\r', '\uFEFF').trim()
            if (line.isBlank() || META_TAG.matchEntire(line) != null) return@forEach
            lines += LyricLine(timeMs = 0L, text = line)
        }
        if (lines.isEmpty()) return Lyrics.EMPTY
        return Lyrics(lines = lines, offsetMs = baseOffsetMs, rawLrc = text)
    }

    /**
     * Parse a NetEase `yrc` karaoke document, producing [LyricLine.words] with per-character timing.
     * Falls back to [parseLrc] when the document is not word-timed.
     */
    fun parseYrc(raw: String?, baseOffsetMs: Long = 0L): Lyrics {
        if (raw.isNullOrBlank()) return Lyrics.EMPTY
        val text = decodeEscapes(raw)
        val lines = ArrayList<LyricLine>(256)
        var sawWordTiming = false

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            val head = YRC_LINE.matchEntire(line)
            if (head == null) {
                // yrc documents usually start with JSON header lines like `{"t":0,...}`.
                return@forEach
            }
            val startMs = head.groupValues[1].toLongOrNull() ?: return@forEach
            val durationMs = head.groupValues[2].toLongOrNull() ?: 0L
            val body = head.groupValues[3]

            val words = ArrayList<LyricWord>()
            YRC_WORD.findAll(body).forEach { w ->
                val ws = w.groupValues[1].toLongOrNull() ?: return@forEach
                val wd = w.groupValues[2].toLongOrNull() ?: 0L
                val wt = w.groupValues[3]
                if (wt.isNotEmpty()) words += LyricWord(ws, wd, wt)
            }

            val plain = if (words.isNotEmpty()) {
                sawWordTiming = true
                words.joinToString("") { it.text }
            } else {
                body
            }
            if (plain.isBlank()) return@forEach
            lines += LyricLine(timeMs = startMs, text = plain, durationMs = durationMs, words = words)
        }

        if (lines.isEmpty()) return Lyrics.EMPTY
        lines.sortBy { it.timeMs }
        return Lyrics(
            lines = dedupe(lines),
            offsetMs = baseOffsetMs,
            rawYrc = text,
            isPureMusic = !sawWordTiming && lines.size == 1 && lines[0].text.contains("纯音乐")
        )
    }

    /**
     * Merge a translation (`tlyric`) and/or romanisation (`romalrc`) document into [base].
     *
     * NetEase guarantees the auxiliary documents share timestamps with the base in the common case;
     * when they drift we first try an exact timestamp match, then fall back to aligning by index
     * among non-empty lines, which is what the official clients effectively do.
     */
    fun merge(base: Lyrics, tlyric: String?, romalrc: String?): Lyrics {
        if (base.isEmpty) return base
        val trans = if (tlyric.isNullOrBlank()) emptyMap() else indexByTime(parseLrc(tlyric))
        val roma = if (romalrc.isNullOrBlank()) emptyMap() else indexByTime(parseLrc(romalrc))
        if (trans.isEmpty() && roma.isEmpty()) return base

        val transByIndex = indexByOrdinal(parseLrc(tlyric), base.lines.size)
        val romaByIndex = indexByOrdinal(parseLrc(romalrc), base.lines.size)

        val merged = base.lines.mapIndexed { i, line ->
            val t = trans[line.timeMs] ?: transByIndex[i]
            val r = roma[line.timeMs] ?: romaByIndex[i]
            line.copy(translation = t?.takeIf { it.isNotBlank() }, roman = r?.takeIf { it.isNotBlank() })
        }
        return base.copy(
            lines = merged,
            rawTlyric = tlyric,
            rawRoma = romalrc
        )
    }

    /** Render plain LRC text (used when exporting / writing a `.lrc` sidecar). */
    fun writeLrc(lyrics: Lyrics): String {
        val sb = StringBuilder()
        if (lyrics.offsetMs != 0L) sb.append("[offset:").append(lyrics.offsetMs).append("]\n")
        lyrics.lines.forEach { line ->
            sb.append('[').append(formatStamp(line.timeMs)).append(']').append(line.text).append('\n')
        }
        return sb.toString()
    }

    private fun parseStamp(m: MatchResult): Long? {
        val minutes = m.groupValues[1].toLongOrNull() ?: return null
        val seconds = m.groupValues[2].toLongOrNull() ?: return null
        val frac = m.groupValues[3]
        val millis = when {
            frac.isEmpty() -> 0L
            frac.length == 1 -> frac.toLong() * 100L
            frac.length == 2 -> frac.toLong() * 10L
            else -> frac.substring(0, 3).toLong()
        }
        return minutes * 60_000L + seconds * 1_000L + millis
    }

    private fun formatStamp(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val totalSeconds = safe / 1000L
        // Locale.ROOT is mandatory: the default locale would render these digits in the device's
        // numbering system (Arabic-Indic, Bengali, …) and write an unparseable `.lrc` file.
        return String.format(
            java.util.Locale.ROOT,
            "%02d:%02d.%03d",
            totalSeconds / 60L,
            totalSeconds % 60L,
            safe % 1000L
        )
    }

    /** Same timestamp appearing twice with identical text is common in the wild; collapse it. */
    private fun dedupe(lines: List<LyricLine>): List<LyricLine> {
        if (lines.size < 2) return lines
        val out = ArrayList<LyricLine>(lines.size)
        lines.forEach { line ->
            val prev = out.lastOrNull()
            if (prev != null && prev.timeMs == line.timeMs && prev.text == line.text) return@forEach
            out += line
        }
        return out
    }

    private fun indexByTime(lyrics: Lyrics): Map<Long, String> {
        if (lyrics.isEmpty) return emptyMap()
        val map = HashMap<Long, String>(lyrics.lines.size)
        lyrics.lines.forEach { line -> map.putIfAbsent(line.timeMs, line.text) }
        return map
    }

    /**
     * Align auxiliary lines onto the base by ordinal position, skipping blank entries.
     * Returns an index-keyed map so callers can use it as a second-chance lookup.
     */
    private fun indexByOrdinal(lyrics: Lyrics, size: Int): Map<Int, String> {
        if (lyrics.isEmpty) return emptyMap()
        val meaningful = lyrics.lines.filter { it.text.isNotBlank() }
        if (meaningful.isEmpty()) return emptyMap()
        val map = HashMap<Int, String>(meaningful.size)
        meaningful.forEachIndexed { i, line -> if (i < size) map[i] = line.text }
        return map
    }
}
