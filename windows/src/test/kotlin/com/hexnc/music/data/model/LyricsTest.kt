package com.hexnc.music.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Lyrics.indexAt] drives the highlighted line in `ui/screens/NowPlayingScreen.kt`, and its
 * interaction with [Lyrics.offsetMs] is easy to get backwards: `indexAt` *adds* the offset, while the
 * player's tap-to-seek handler *subtracts* it. These tests pin both directions down.
 */
class LyricsTest {

    private fun lyrics(vararg times: Long, offset: Long = 0L) = Lyrics(
        lines = times.map { LyricLine(timeMs = it, text = "t$it") },
        offsetMs = offset
    )

    @Test
    fun `empty lyrics never highlight anything`() {
        assertEquals(-1, Lyrics.EMPTY.indexAt(0))
        assertEquals(-1, Lyrics.EMPTY.indexAt(999_999))
        assertEquals(true, Lyrics.EMPTY.isEmpty)
    }

    @Test
    fun `before the first line nothing is highlighted`() {
        val l = lyrics(1_000, 5_000)
        assertEquals(-1, l.indexAt(0))
        assertEquals(-1, l.indexAt(999))
        // Exactly on the first line is already highlighted.
        assertEquals(0, l.indexAt(1_000))
    }

    @Test
    fun `a position selects the last line at or before it`() {
        val l = lyrics(1_000, 5_000, 9_000)
        assertEquals(0, l.indexAt(1_500))
        assertEquals(1, l.indexAt(5_000))
        assertEquals(1, l.indexAt(8_999))
        assertEquals(2, l.indexAt(9_000))
        // Past the end the final line stays highlighted.
        assertEquals(2, l.indexAt(60_000))
    }

    @Test
    fun `a single line stays highlighted until the end`() {
        val l = lyrics(3_000)
        assertEquals(-1, l.indexAt(2_999))
        assertEquals(0, l.indexAt(3_000))
        assertEquals(0, l.indexAt(500_000))
    }

    @Test
    fun `a positive offset shifts lyrics later, a negative one earlier`() {
        // The offset is added to the player position, so a +500 offset means the line lands 500ms later.
        val later = lyrics(10_000, offset = 500)
        assertEquals(-1, later.indexAt(9_499))
        assertEquals(0, later.indexAt(9_500))

        val earlier = lyrics(10_000, offset = -500)
        assertEquals(-1, earlier.indexAt(10_499))
        assertEquals(0, earlier.indexAt(10_500))
    }

    @Test
    fun `a large positive offset makes the first line due immediately`() {
        // offset 25_000 pushes a line at 10_000 back to position -15_000, so it is already due at 0.
        val l = lyrics(10_000, offset = 25_000)
        assertEquals(0, l.indexAt(0))
    }

    @Test
    fun `a large negative offset delays the first line past the start`() {
        val l = lyrics(10_000, offset = -25_000)
        assertEquals(-1, l.indexAt(0))
        assertEquals(-1, l.indexAt(34_999))
        assertEquals(0, l.indexAt(35_000))
    }

    @Test
    fun `tap-to-seek inverts the offset for every line that is not clamped`() {
        // This is the invariant NowPlayingScreen relies on: seeking to (timeMs - offsetMs) must make
        // indexAt() report that very line again. Lines whose seek target would fall before 0 are
        // clamped to 0 by the player, so they are excluded here.
        for (offset in listOf(-1_200L, -1L, 0L, 1L, 1_200L)) {
            val times = listOf(0L, 1L, 1_000L, 7_500L, 7_501L, 120_000L)
            val l = Lyrics(lines = times.map { LyricLine(timeMs = it, text = "t$it") }, offsetMs = offset)
            times.forEachIndexed { i, t ->
                if (t - offset < 0L) return@forEachIndexed
                assertEquals("offset=$offset line=$i", i, l.indexAt(t - offset))
            }
        }
    }

    @Test
    fun `duplicate timestamps resolve to the last of the group`() {
        val l = lyrics(2_000, 2_000, 2_000)
        assertEquals(2, l.indexAt(2_000))
    }

    @Test
    fun `a sheet whose lines are all untimed is unsynced`() {
        // This is what a plain-text sheet looks like: the lyric pane renders it as a static list with
        // no highlight and no tap-to-seek, which is decided by exactly this flag.
        val plain = Lyrics(
            lines = listOf(LyricLine(timeMs = 0L, text = "a"), LyricLine(timeMs = 0L, text = "b")),
        )
        assertTrue(plain.isUnsynced)
        // indexAt() is not what drives the pane for these sheets — it resolves equal timestamps to the
        // last of the group, so the untimed list is rendered directly instead.
        assertEquals(1, plain.indexAt(0))
    }

    @Test
    fun `a timed sheet is never unsynced, and neither is an empty one`() {
        assertFalse(lyrics(0L, 1_000L).isUnsynced)
        assertFalse(lyrics(1_000L).isUnsynced)
        assertFalse(Lyrics.EMPTY.isUnsynced)
    }
}
