package com.hexnc.music.data.lyrics

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Where an online lyric candidate came from.
 *
 * NetEase is the service this app already talks to; LRCLIB is a free, key-less lyrics database that
 * indexes by *name and duration*, which is exactly what a local file offers — and it works without a
 * signed-in account, so it is also the only source for a user who never logged in.
 */
enum class LyricSource(val key: String) {
    NETEASE("netease"),
    LRCLIB("lrclib");

    companion object {
        fun fromKey(key: String?): LyricSource = entries.firstOrNull { it.key == key } ?: NETEASE
    }
}

/**
 * One online search hit, before anything has been downloaded.
 *
 * [plainLyrics] / [syncedLyrics] are filled in only by sources that return the document with the
 * search (LRCLIB does; NetEase needs a second request, made only after the user picks a candidate).
 */
data class LyricCandidate(
    val source: LyricSource,
    val id: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    /** Whether the source says it has timed lines; a plain-only hit is still worth offering. */
    val hasSynced: Boolean = true,
    /** The source reports an instrumental track, which has no lyrics to import. */
    val instrumental: Boolean = false,
    val neteaseId: Long? = null,
    val lrclibId: Long? = null,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
)

/** A candidate together with how well it matches the song being searched for. */
data class ScoredCandidate(
    val candidate: LyricCandidate,
    val score: Double,
    val titleScore: Double,
)

// How much each signal counts. The three weights sum to 1, so a score is readable as "how sure are
// we, in percent" — which is what the auto-import threshold below is expressed in.
internal const val MATCH_TITLE_WEIGHT = 0.55
internal const val MATCH_ARTIST_WEIGHT = 0.30
internal const val MATCH_DURATION_WEIGHT = 0.15

/** An artist field we cannot read (blank, or a tag like "Unknown Artist") is neither a match nor a veto. */
internal const val NEUTRAL_ARTIST_SCORE = 0.6

/** Below this, two artist names are treated as different artists rather than a sloppy match. */
internal const val ARTIST_NEAR_MATCH = 0.9

/** Same duration within three seconds: a different master, not a different recording. */
internal const val DURATION_EXACT_MS = 3_000L
internal const val DURATION_CLOSE_MS = 8_000L
internal const val DURATION_LOOSE_MS = 20_000L

/**
 * The bar for importing without asking.
 *
 * Deliberately high: a wrong sheet is worse than an empty pane, because the user cannot tell that
 * the lyrics on screen belong to another song. Anything below this is still offered in the picker.
 */
internal const val AUTO_IMPORT_SCORE = 0.86

/** Auto-import additionally demands a near-identical title; the score alone can be reached on duration. */
internal const val AUTO_IMPORT_TITLE_SCORE = 0.90

/**
 * Bracketed fragments that describe the release rather than the song.
 *
 * `(Remastered 2011)`, `[Live]`, `（高音质）` … all appear in file names but not in a lyrics database,
 * so they are removed before comparing — otherwise "晴天 (Live)" would never match "晴天".
 * `伴奏`/`instrumental` is deliberately **not** here: that is a different recording, not a decoration.
 */
private val NOISE_SEGMENT = Regex("""[\(\[（【]([^)\]}】]*)[\)\]）】]""")

private val NOISE_PREFIXES = listOf(
    "feat", "ft.", "ft ", "featuring", "remaster", "live", "version", "ver.", "explicit",
    "hq", "hires", "hi-res", "hi res", "official", "audio", "lyrics", "mv",
    "高音质", "无损", "完整版", "现场版", "纯音乐版",
)

private val ARTIST_SPLIT = Regex("""\s*(?:/|\\|&|、|,|;|；|，|feat\.?|ft\.?|with)\s*""", RegexOption.IGNORE_CASE)

private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")

/**
 * Drops trailing decorations such as `(Live)` / `[Remastered]`, keeping the rest of the text as-is.
 *
 * The result is still displayable — the search keyword is built from it, so it must not be folded to
 * lower case or have its own punctuation removed.
 */
internal fun stripReleaseNoise(raw: String): String {
    val withoutSegments = NOISE_SEGMENT.replace(raw) { match ->
        val inner = match.groupValues[1].trim().lowercase(Locale.ROOT)
        if (inner.isEmpty() || NOISE_PREFIXES.any { inner.startsWith(it) }) " " else match.value
    }
    return withoutSegments.replace(Regex("""\s+"""), " ").trim()
}

/**
 * Folds a title or artist into what two spellings of the same song have in common: case, width and
 * punctuation. `ＡＣ/ＤＣ` → `ac dc`, `Don't Stop Me Now!` → `don t stop me now`.
 */
internal fun normalizeTitle(raw: String): String {
    val halfWidth = StringBuilder(raw.length)
    raw.forEach { ch ->
        when {
            ch.code in 0xFF01..0xFF5E -> halfWidth.append((ch.code - 0xFEE0).toChar())
            ch == '\u3000' -> halfWidth.append(' ')
            else -> halfWidth.append(ch)
        }
    }
    return NON_WORD.replace(stripReleaseNoise(halfWidth.toString()).lowercase(Locale.ROOT), " ")
        .trim()
}

/**
 * Split an artist field into individual names, normalised; `A / B feat. C` → `[a, b, c]`.
 *
 * The split happens on the **raw** text, before normalisation, because normalisation turns `/`, `&`
 * and `、` into spaces — splitting afterwards would see one long name instead of three.
 */
internal fun splitArtists(raw: String): List<String> =
    ARTIST_SPLIT.split(raw).map { normalizeTitle(it) }.filter { it.isNotEmpty() }.distinct()

/** Levenshtein distance over two short strings, O(n·m) time and O(m) space. */
internal fun editDistance(a: String, b: String): Int {
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)
    for (i in 1..a.length) {
        current[0] = i
        for (j in 1..b.length) {
            val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
        }
        val swap = previous
        previous = current
        current = swap
    }
    return previous[b.length]
}

private fun tokenOverlap(a: String, b: String): Double {
    val left = a.split(' ').filter { it.isNotEmpty() }.toSet()
    val right = b.split(' ').filter { it.isNotEmpty() }.toSet()
    if (left.isEmpty() || right.isEmpty()) return 0.0
    val shared = left.count { it in right }
    return shared.toDouble() / (left.size + right.size - shared)
}

/**
 * How close two titles are, in 0…1.
 *
 * Three ways to be right, best one wins: identical after normalisation, a small edit distance, or one
 * being contained in the other (weighted by how much of the longer string it covers, so a bare
 * "song" does not match "song for someone else").
 */
internal fun titleSimilarity(a: String, b: String): Double {
    val x = normalizeTitle(a)
    val y = normalizeTitle(b)
    if (x.isEmpty() || y.isEmpty()) return 0.0
    if (x == y) return 1.0
    val (shorter, longer) = if (x.length <= y.length) x to y else y to x
    val ratio = shorter.length.toDouble() / longer.length
    val byDistance = 1.0 - editDistance(x, y).toDouble() / longer.length
    val byContainment = if (longer.contains(shorter)) 0.72 + 0.24 * ratio else tokenOverlap(x, y)
    return max(byDistance, byContainment).coerceIn(0.0, 1.0)
}

/**
 * How close two artist fields are, in 0…1.
 *
 * An empty (or unreadable) field on either side scores [NEUTRAL_ARTIST_SCORE] rather than 0: plenty
 * of local files carry no artist tag, and refusing every candidate because of that would help nobody.
 *
 * Otherwise the signal is close to binary. A shared name is a match; anything else has to be a
 * near-identical spelling ([ARTIST_NEAR_MATCH]) and everything further away scores zero. Partial
 * credit is deliberately not given for token overlap: "Linkin Park" is a token subset of
 * "Linkin Park Tribute", and scoring that as three-quarters right would let a tribute band's sheet
 * clear the automatic-import bar for the real recording.
 */
internal fun artistSimilarity(a: String, b: String): Double {
    val left = splitArtists(a)
    val right = splitArtists(b)
    if (left.isEmpty() || right.isEmpty()) return NEUTRAL_ARTIST_SCORE
    if (left.any { it in right }) return 1.0
    var best = 0.0
    for (one in left) {
        for (other in right) {
            best = max(best, titleSimilarity(one, other))
        }
    }
    return if (best >= ARTIST_NEAR_MATCH) best * 0.95 else 0.0
}

/** Duration agreement, in 0…1; an unknown duration on either side is neutral, not a mismatch. */
internal fun durationSimilarity(queryMs: Long, candidateMs: Long): Double {
    if (queryMs <= 0L || candidateMs <= 0L) return 0.5
    val diff = abs(queryMs - candidateMs)
    return when {
        diff <= DURATION_EXACT_MS -> 1.0
        diff <= DURATION_CLOSE_MS -> 0.7
        diff <= DURATION_LOOSE_MS -> 0.3
        else -> 0.0
    }
}

/** The weighted verdict for one candidate, in 0…1. */
internal fun lyricMatchScore(
    title: String,
    artist: String,
    durationMs: Long,
    candidate: LyricCandidate,
): Double =
    titleSimilarity(title, candidate.title) * MATCH_TITLE_WEIGHT +
        artistSimilarity(artist, candidate.artist) * MATCH_ARTIST_WEIGHT +
        durationSimilarity(durationMs, candidate.durationMs) * MATCH_DURATION_WEIGHT

/**
 * Scores every candidate and sorts the most convincing first.
 *
 * Ties fall to a timed sheet over a plain one, then to NetEase over LRCLIB: NetEase lyrics carry
 * translations and romanisations alongside the base text, so when both are equally likely the richer
 * document wins the automatic import.
 */
internal fun rankCandidates(
    title: String,
    artist: String,
    durationMs: Long,
    candidates: List<LyricCandidate>,
): List<ScoredCandidate> =
    candidates
        .map { candidate ->
            ScoredCandidate(
                candidate = candidate,
                score = lyricMatchScore(title, artist, durationMs, candidate),
                titleScore = titleSimilarity(title, candidate.title),
            )
        }
        .sortedWith(
            compareByDescending<ScoredCandidate> { it.score }
                .thenByDescending { it.candidate.hasSynced }
                .thenByDescending { it.candidate.source == LyricSource.NETEASE },
        )

/**
 * The one candidate worth importing without asking, or null when the search is unconvincing.
 *
 * Instrumental hits are excluded: they are a real match, but there is nothing to import.
 */
internal fun bestAutoCandidate(
    title: String,
    artist: String,
    durationMs: Long,
    candidates: List<LyricCandidate>,
): LyricCandidate? {
    val searched = candidates.filterNot { it.instrumental }
    val best = rankCandidates(title, artist, durationMs, searched).firstOrNull() ?: return null
    return best.candidate.takeIf { best.score >= AUTO_IMPORT_SCORE && best.titleScore >= AUTO_IMPORT_TITLE_SCORE }
}

/** The keyword to hand to NetEase, built from the tags a local file already has. */
internal fun searchKeyword(title: String, artist: String): String =
    listOf(stripReleaseNoise(title), stripReleaseNoise(artist))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .joinToString(" ")
