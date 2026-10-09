package com.hexnc.music.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The karaoke wipe is driven entirely by these two functions, and an off-by-one in the clock shows up
 * on screen as a line that lights up in the wrong place — this pins the arithmetic down.
 */
class LyricProgressTest {

    private val twoWords = listOf(
        LyricWord(startMs = 0L, durationMs = 1_000L, text = "ab"),
        LyricWord(startMs = 1_000L, durationMs = 1_000L, text = "cd"),
    )

    @Test
    fun `no words means no progress`() {
        assertEquals(0f, wordProgress(emptyList(), positionMs = 5_000L), 0.0001f)
    }

    @Test
    fun `walks from zero to one across the words`() {
        assertEquals(0f, wordProgress(twoWords, 0L), 0.0001f)
        assertEquals(0.25f, wordProgress(twoWords, 500L), 0.0001f)
        assertEquals(0.5f, wordProgress(twoWords, 1_000L), 0.0001f)
        assertEquals(0.75f, wordProgress(twoWords, 1_500L), 0.0001f)
        assertEquals(1f, wordProgress(twoWords, 2_000L), 0.0001f)
    }

    @Test
    fun `clamps outside the line`() {
        assertEquals(0f, wordProgress(twoWords, -5_000L), 0.0001f)
        assertEquals(1f, wordProgress(twoWords, 999_999L), 0.0001f)
    }

    @Test
    fun `weights words by their length not their count`() {
        val lopsided = listOf(
            LyricWord(startMs = 0L, durationMs = 1_000L, text = "a"),
            LyricWord(startMs = 1_000L, durationMs = 1_000L, text = "aaaaaaa"),
        )
        // One short word is 1/8 of the line, so by the time it is sung only an eighth is filled.
        assertEquals(0.125f, wordProgress(lopsided, 1_000L), 0.0001f)
    }

    @Test
    fun `blank words still carry weight`() {
        val blank = listOf(
            LyricWord(startMs = 0L, durationMs = 500L, text = ""),
            LyricWord(startMs = 500L, durationMs = 500L, text = "h"),
        )
        assertEquals(0.5f, wordProgress(blank, 500L), 0.0001f)
    }

    @Test
    fun `offset is added to the position`() {
        // `Lyrics.indexAt` adds the sheet offset, so the progress helpers must agree with it.
        assertEquals(0f, wordProgress(twoWords, positionMs = -500L, offsetMs = 500L), 0.0001f)
        assertEquals(0.5f, wordProgress(twoWords, positionMs = 500L, offsetMs = 500L), 0.0001f)
    }

    @Test
    fun `zero duration word counts as sung at its stamp`() {
        val instant = listOf(LyricWord(startMs = 1_000L, durationMs = 0L, text = "x"))
        assertEquals(0f, wordProgress(instant, 999L), 0.0001f)
        assertEquals(1f, wordProgress(instant, 1_000L), 0.0001f)
    }

    @Test
    fun `plain line sweeps towards the next line`() {
        assertEquals(0f, lineProgress(1_000L, 3_000L, 500L), 0.0001f)
        assertEquals(0.5f, lineProgress(1_000L, 3_000L, 2_000L), 0.0001f)
        assertEquals(1f, lineProgress(1_000L, 3_000L, 9_000L), 0.0001f)
    }

    @Test
    fun `last line has nothing to sweep towards`() {
        assertEquals(0f, lineProgress(1_000L, null, 999L), 0.0001f)
        assertEquals(1f, lineProgress(1_000L, null, 1_000L), 0.0001f)
    }

    @Test
    fun `long gap is clamped so the line does not crawl`() {
        // A one-line chorus followed by twenty seconds of instrumental should finish filling early.
        assertEquals(1f, lineProgress(1_000L, 21_000L, 9_000L), 0.0001f)
        assertEquals(0.5f, lineProgress(1_000L, 21_000L, 5_000L), 0.0001f)
    }

    @Test
    fun `line offset is added too`() {
        assertEquals(0f, lineProgress(1_000L, 3_000L, 500L, offsetMs = 500L), 0.0001f)
        assertEquals(0.5f, lineProgress(1_000L, 3_000L, 1_500L, offsetMs = 500L), 0.0001f)
    }
}
