package com.hexnc.music.data.model

import com.hexnc.music.data.local.LrcParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the three-line lyric display ("japanese / chinese / romaji").
 *
 * The setting that turns romanisation on was stored, persisted and read back, but the player never
 * consulted it — so the switch did nothing at all and the whole feature existed only in the settings
 * screen. The render checks the same two facts these tests pin: whether a line *has* a romaji line to
 * show, and whether a Japanese line without one should say so instead of silently rendering two lines
 * (an incomplete sheet and a broken switch look identical otherwise).
 */
class LyricSubLineTest {

    @Test
    fun `kana marks a line as japanese`() {
        assertTrue(LyricLine(timeMs = 0, text = "君の名前を呼ぶ").isJapaneseScript)
        assertTrue(LyricLine(timeMs = 0, text = "カタカナだけ").isJapaneseScript)
        // A line mixing kanji with kana is still Japanese; kana is the giveaway, not the kanji.
        assertTrue(LyricLine(timeMs = 0, text = "夜が明ける").isJapaneseScript)
    }

    @Test
    fun `chinese and english lines are not japanese`() {
        assertFalse(LyricLine(timeMs = 0, text = "极光掠过山脊").isJapaneseScript)
        assertFalse(LyricLine(timeMs = 0, text = "Aurora over the ridge").isJapaneseScript)
        assertFalse(LyricLine(timeMs = 0, text = "♪").isJapaneseScript)
    }

    @Test
    fun `romaji alone is attached without inventing a translation`() {
        val base = LrcParser.parseLrc("[00:01.00]Hello\n[00:02.00]World")
        val merged = LrcParser.merge(
            base,
            tlyric = null,
            romalrc = "[00:01.00]Ni hao\n[00:02.00]Shi jie",
        )

        assertEquals("Ni hao", merged.lines[0].roman)
        assertNull(merged.lines[0].translation)
        assertEquals("Shi jie", merged.lines[1].roman)
        assertNull(merged.lines[1].translation)
    }

    @Test
    fun `a line with neither auxiliary document has neither sub-line`() {
        val base = LrcParser.parseLrc("[00:01.00]Hello")
        val merged = LrcParser.merge(base, tlyric = null, romalrc = null)

        assertNull(merged.lines[0].roman)
        assertNull(merged.lines[0].translation)
        assertFalse(merged.lines[0].isJapaneseScript)
    }

    @Test
    fun `both documents attach to the same line`() {
        val base = LrcParser.parseLrc("[00:01.00]こんにちは")
        val merged = LrcParser.merge(
            base,
            tlyric = "[00:01.00]你好",
            romalrc = "[00:01.00]konnichiwa",
        )

        assertEquals("你好", merged.lines[0].translation)
        assertEquals("konnichiwa", merged.lines[0].roman)
        assertTrue(merged.lines[0].isJapaneseScript)
    }
}