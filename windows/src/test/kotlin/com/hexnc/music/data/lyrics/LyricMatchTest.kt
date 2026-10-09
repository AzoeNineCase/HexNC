package com.hexnc.music.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The matching rules that decide whether an online lyric sheet belongs to a local file.
 *
 * These are the tests that matter most in the online-completion feature: the import runs without the
 * user watching, so a rule that is too generous attaches someone else's lyrics to a song and the
 * mistake is invisible. Each case below therefore pins one signal — title, artist, duration — and
 * the ways it is allowed to be wrong.
 */
class LyricMatchTest {

    private fun netease(
        id: Long,
        title: String,
        artist: String,
        durationMs: Long,
        hasSynced: Boolean = true,
        instrumental: Boolean = false,
    ) = LyricCandidate(
        source = LyricSource.NETEASE,
        id = id.toString(),
        title = title,
        artist = artist,
        durationMs = durationMs,
        hasSynced = hasSynced,
        instrumental = instrumental,
        neteaseId = id,
    )

    private fun lrclib(
        id: Long,
        title: String,
        artist: String,
        durationMs: Long,
        synced: String? = null,
        plain: String? = null,
        instrumental: Boolean = false,
    ) = LyricCandidate(
        source = LyricSource.LRCLIB,
        id = id.toString(),
        title = title,
        artist = artist,
        durationMs = durationMs,
        hasSynced = synced != null,
        instrumental = instrumental,
        lrclibId = id,
        syncedLyrics = synced,
        plainLyrics = plain,
    )

    // ------------------------------------------------------------------ source keys

    @Test
    fun `source keys round-trip and anything unknown falls back to NetEase`() {
        assertEquals(LyricSource.NETEASE, LyricSource.fromKey("netease"))
        assertEquals(LyricSource.LRCLIB, LyricSource.fromKey("lrclib"))
        assertEquals(LyricSource.NETEASE, LyricSource.fromKey(null))
        assertEquals(LyricSource.NETEASE, LyricSource.fromKey("spotify"))
    }

    // ------------------------------------------------------------------ title

    @Test
    fun `release decorations are stripped so a live take still matches`() {
        assertEquals("晴天", stripReleaseNoise("晴天 (Live)"))
        assertEquals("Numb", stripReleaseNoise("Numb [Remastered 2011]"))
        assertEquals("Hello", stripReleaseNoise("Hello（高音质）"))
        assertEquals("Yesterday", stripReleaseNoise("Yesterday【无损】"))
        assertEquals("Song", stripReleaseNoise("Song  "))
    }

    @Test
    fun `a bracket that is part of the title is kept`() {
        // Nothing in the bracket describes a release, so dropping it would rename the song.
        assertEquals("Time (Clock of the Heart)", stripReleaseNoise("Time (Clock of the Heart)"))
        assertEquals("Stay (With You)", stripReleaseNoise("Stay (With You)"))
    }

    @Test
    fun `titles fold case width and punctuation`() {
        assertEquals("ac dc", normalizeTitle("ＡＣ/ＤＣ"))
        assertEquals("don t stop me now", normalizeTitle("Don't Stop Me Now!"))
        assertEquals("numb", normalizeTitle("Numb (Remastered)"))
        assertEquals("晴天", normalizeTitle("晴天"))
    }

    @Test
    fun `identical titles score one, and an empty side scores zero`() {
        assertEquals(1.0, titleSimilarity("Numb", "numb (Remastered)"), 1e-9)
        assertEquals(0.0, titleSimilarity("Numb", ""), 1e-9)
        assertEquals(0.0, titleSimilarity("", "Numb"), 1e-9)
    }

    @Test
    fun `a one character difference in a short title costs half the score`() {
        assertEquals(0.5, titleSimilarity("晴天", "晴夭"), 1e-9)
    }

    @Test
    fun `a title that contains the other is similar but not identical`() {
        val score = titleSimilarity("Song", "Song for Someone")
        assertTrue("containment should beat a plain edit distance: $score", score > 0.7)
        assertTrue("but it must not be an automatic match: $score", score < 0.9)
    }

    @Test
    fun `edit distance is symmetric and handles empty strings`() {
        assertEquals(0, editDistance("abc", "abc"))
        assertEquals(3, editDistance("", "abc"))
        assertEquals(3, editDistance("abc", ""))
        assertEquals(3, editDistance("kitten", "sitting"))
        assertEquals(editDistance("kitten", "sitting"), editDistance("sitting", "kitten"))
    }

    // ------------------------------------------------------------------ artist

    @Test
    fun `artists split on every separator the tags use`() {
        assertEquals(listOf("a", "b"), splitArtists("A / B"))
        assertEquals(listOf("a", "b"), splitArtists("A & B"))
        assertEquals(listOf("a", "b", "c"), splitArtists("A feat. B、C"))
        assertEquals(listOf("jay chou"), splitArtists("Jay Chou"))
    }

    @Test
    fun `one shared artist name is a full match regardless of order`() {
        assertEquals(1.0, artistSimilarity("Jay Chou / 周杰伦", "周杰伦"), 1e-9)
        assertEquals(1.0, artistSimilarity("周杰伦", "周杰伦 / Jay Chou"), 1e-9)
    }

    @Test
    fun `a missing artist tag is neutral rather than a veto`() {
        assertEquals(NEUTRAL_ARTIST_SCORE, artistSimilarity("", "Anyone"), 1e-9)
        assertEquals(NEUTRAL_ARTIST_SCORE, artistSimilarity("Anyone", ""), 1e-9)
    }

    @Test
    fun `unrelated artists score zero`() {
        assertEquals(0.0, artistSimilarity("Jay Chou", "Adele"), 1e-9)
    }

    @Test
    fun `a tribute act is a different artist rather than a partial match`() {
        // The dangerous shape: "Linkin Park Tribute" contains every token of the real name, so a
        // token-overlap rule scores it ~0.63 and that alone carries it past the auto-import bar.
        assertEquals(0.0, artistSimilarity("Linkin Park", "Linkin Park Tribute"), 1e-9)
    }

    @Test
    fun `a misspelt artist still earns partial credit`() {
        val score = artistSimilarity("Linkin Prk", "Linkin Park")
        assertTrue("a typo in one track's tag should stay a match: $score", score > 0.8)
    }

    // ------------------------------------------------------------------ duration

    @Test
    fun `duration differences fall into bands and unknown durations are neutral`() {
        assertEquals(1.0, durationSimilarity(215_000L, 216_500L), 1e-9)
        assertEquals(0.7, durationSimilarity(215_000L, 221_000L), 1e-9)
        assertEquals(0.3, durationSimilarity(215_000L, 230_000L), 1e-9)
        assertEquals(0.0, durationSimilarity(215_000L, 300_000L), 1e-9)
        assertEquals(0.5, durationSimilarity(0L, 215_000L), 1e-9)
        assertEquals(0.5, durationSimilarity(215_000L, 0L), 1e-9)
    }

    // ------------------------------------------------------------------ automatic import

    @Test
    fun `an exact title artist and duration is imported without asking`() {
        val hit = netease(1, "Numb", "Linkin Park", 185_000L)
        assertEquals(hit, bestAutoCandidate("Numb", "Linkin Park", 185_000L, listOf(hit)))
    }

    @Test
    fun `a same-named song by another artist is only a suggestion`() {
        val cover = netease(2, "Numb", "Some Cover Band", 185_000L)
        assertNull(bestAutoCandidate("Numb", "Linkin Park", 185_000L, listOf(cover)))
        assertEquals(1, rankCandidates("Numb", "Linkin Park", 185_000L, listOf(cover)).size)
    }

    @Test
    fun `a matching duration cannot carry a wrong title`() {
        val other = lrclib(3, "Completely Different", "", 185_000L, synced = "[00:01.00]x")
        assertNull(bestAutoCandidate("Numb", "Linkin Park", 185_000L, listOf(other)))
    }

    @Test
    fun `an instrumental hit is never auto-imported`() {
        val instrumental = lrclib(4, "Numb", "Linkin Park", 185_000L, instrumental = true)
        assertNull(bestAutoCandidate("Numb", "Linkin Park", 185_000L, listOf(instrumental)))
    }

    @Test
    fun `a partial artist match is not enough on its own`() {
        // "Linkin Park" vs "Linkin Park Tribute": one shared token, but not the same recording artist.
        val tribute = netease(5, "Numb", "Linkin Park Tribute", 185_000L)
        assertNull(bestAutoCandidate("Numb", "Linkin Park", 185_000L, listOf(tribute)))
    }

    @Test
    fun `no candidates means no automatic import`() {
        assertNull(bestAutoCandidate("Numb", "Linkin Park", 185_000L, emptyList()))
    }

    // ------------------------------------------------------------------ ranking

    @Test
    fun `ranking prefers a timed sheet and then NetEase`() {
        val plain = lrclib(5, "Numb", "Linkin Park", 185_000L, plain = "just words")
        val timed = lrclib(6, "Numb", "Linkin Park", 185_000L, synced = "[00:01.00]words")
        val catalogue = netease(7, "Numb", "Linkin Park", 185_000L)
        val ranked = rankCandidates("Numb", "Linkin Park", 185_000L, listOf(plain, catalogue, timed))
        // All three match equally on the signals that are scored, so the tie-breaks decide: a timed
        // document first, and NetEase above LRCLIB because its sheet also carries translations.
        assertEquals(
            listOf(catalogue.id, timed.id, plain.id),
            ranked.map { it.candidate.id },
        )
    }

    @Test
    fun `a worse match always ranks below a better one`() {
        val wrong = lrclib(8, "Something Else", "Another Band", 300_000L)
        val right = lrclib(9, "Numb", "Linkin Park", 185_000L, synced = "[00:01.00]x")
        val ranked = rankCandidates("Numb", "Linkin Park", 185_000L, listOf(wrong, right))
        assertEquals(right.id, ranked.first().candidate.id)
        assertTrue(ranked.first().score > ranked.last().score)
    }

    // ------------------------------------------------------------------ query

    @Test
    fun `the search keyword joins the de-noised title and the artist`() {
        assertEquals("晴天 周杰伦", searchKeyword("晴天 (Live)", "周杰伦"))
        assertEquals("Numb", searchKeyword("Numb", ""))
        assertEquals("Numb", searchKeyword("Numb", "   "))
    }
}
