package com.hexnc.music.data.local

import com.hexnc.music.data.model.Lyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-side verification of lyric parsing. Lyric files in the wild are inconsistent enough that the
 * edge cases below are the ones that actually decide whether the lyric pane renders or stays blank.
 */
class LrcParserTest {

    @Test
    fun `parses plain lrc with metadata lines`() {
        val lyrics = LrcParser.parseLrc(
            """
            [ti:Test Song]
            [ar:Someone]
            [00:01.00]First
            [00:02.50]Second
            """.trimIndent()
        )
        assertEquals(2, lyrics.lines.size)
        assertEquals(1000L, lyrics.lines[0].timeMs)
        assertEquals("First", lyrics.lines[0].text)
        assertEquals(2500L, lyrics.lines[1].timeMs)
        assertEquals("Second", lyrics.lines[1].text)
        assertEquals(0L, lyrics.offsetMs)
    }

    @Test
    fun `supports multiple timestamps on one line and the colon fraction variant`() {
        val lyrics = LrcParser.parseLrc("[00:10:50][01:20.25]Chorus")
        assertEquals(2, lyrics.lines.size)
        // `[mm:ss:xx]` is the 2-digit-hundredths variant of the more common `[mm:ss.xx]`.
        assertEquals(10_500L, lyrics.lines[0].timeMs)
        assertEquals(80_250L, lyrics.lines[1].timeMs)
        assertEquals("Chorus", lyrics.lines[0].text)
        assertEquals("Chorus", lyrics.lines[1].text)
    }

    @Test
    fun `accumulates the offset tag on top of the base offset`() {
        val lyrics = LrcParser.parseLrc("[offset:250]\n[00:01.00]A", baseOffsetMs = 100L)
        assertEquals(350L, lyrics.offsetMs)
        assertEquals(1000L, lyrics.lines[0].timeMs)
    }

    @Test
    fun `fraction field is scaled by its digit count`() {
        assertEquals(1_000L, LrcParser.parseLrc("[00:01.0]A").lines[0].timeMs)
        assertEquals(1_500L, LrcParser.parseLrc("[00:01.5]A").lines[0].timeMs)
        assertEquals(1_050L, LrcParser.parseLrc("[00:01.05]A").lines[0].timeMs)
        assertEquals(1_005L, LrcParser.parseLrc("[00:01.005]A").lines[0].timeMs)
    }

    @Test
    fun `collapses duplicated timestamps with identical text`() {
        val lyrics = LrcParser.parseLrc("[00:05.00]Same\n[00:05.00]Same")
        assertEquals(1, lyrics.lines.size)
    }

    @Test
    fun `blank input and untimed input both yield the empty document`() {
        assertTrue(LrcParser.parseLrc(null).isEmpty)
        assertTrue(LrcParser.parseLrc("").isEmpty)
        assertTrue(LrcParser.parseLrc("no timestamps here").isEmpty)
        // A timestamp with no text is a positional marker, not a lyric line.
        assertTrue(LrcParser.parseLrc("[00:01.00]").isEmpty)
    }

    @Test
    fun `detects the placeholder pure-music document`() {
        val lyrics = LrcParser.parseLrc("[00:00.00]纯音乐，请欣赏")
        assertEquals(1, lyrics.lines.size)
        assertTrue(lyrics.isPureMusic)
    }

    @Test
    fun `parses yrc word-level karaoke`() {
        val lyrics = LrcParser.parseYrc(
            """
            {"t":0,"c":[{"tx":"ignored"}]}
            [1000,2000](0,500,0)Hel(500,500,0)lo (1000,1000,0)world
            """.trimIndent()
        )
        assertEquals(1, lyrics.lines.size)
        val line = lyrics.lines[0]
        assertEquals(1000L, line.timeMs)
        assertEquals(2000L, line.durationMs)
        assertEquals("Hello world", line.text)
        assertEquals(3, line.words.size)
        assertEquals("Hel", line.words[0].text)
        assertEquals(500L, line.words[0].durationMs)
        assertEquals("world", line.words[2].text)
    }

    @Test
    fun `yrc without word timing falls back to plain line text`() {
        val lyrics = LrcParser.parseYrc("[1000,2000]Just a line")
        assertEquals(1, lyrics.lines.size)
        assertEquals("Just a line", lyrics.lines[0].text)
        assertTrue(lyrics.lines[0].words.isEmpty())
    }

    @Test
    fun `merge attaches translations by exact timestamp`() {
        val base = LrcParser.parseLrc("[00:01.00]Hello\n[00:02.00]World")
        val merged = LrcParser.merge(
            base,
            tlyric = "[00:01.00]你好\n[00:02.00]世界",
            romalrc = "[00:01.00]Ni hao\n[00:02.00]Shi jie"
        )
        assertEquals("你好", merged.lines[0].translation)
        assertEquals("世界", merged.lines[1].translation)
        assertEquals("Ni hao", merged.lines[0].roman)
        assertEquals("[00:01.00]你好\n[00:02.00]世界", merged.rawTlyric)
    }

    @Test
    fun `merge falls back to ordinal alignment when timestamps drift`() {
        val base = LrcParser.parseLrc("[00:01.00]Hello\n[00:02.00]World")
        val merged = LrcParser.merge(base, tlyric = "[00:01.50]你好\n[00:02.50]世界", romalrc = null)
        assertEquals("你好", merged.lines[0].translation)
        assertEquals("世界", merged.lines[1].translation)
        assertNull(merged.lines[0].roman)
    }

    @Test
    fun `merge is a no-op for empty or absent auxiliary documents`() {
        val base = LrcParser.parseLrc("[00:01.00]Hello")
        assertEquals(base.lines[0].translation, LrcParser.merge(base, null, null).lines[0].translation)
        assertTrue(LrcParser.merge(Lyrics.EMPTY, "[00:01.00]x", null).isEmpty)
    }

    @Test
    fun `writeLrc round-trips through the parser`() {
        val original = LrcParser.parseLrc("[00:01.00]A\n[01:20.25]B")
        val text = LrcParser.writeLrc(original)
        assertEquals("[00:01.000]A\n[01:20.250]B\n", text)
        val reparsed = LrcParser.parseLrc(text)
        assertEquals(2, reparsed.lines.size)
        assertEquals(original.lines[0].timeMs, reparsed.lines[0].timeMs)
        assertEquals("B", reparsed.lines[1].text)
        assertEquals(80_250L, reparsed.lines[1].timeMs)
    }

    @Test
    fun `writeLrc emits and round-trips a non-zero offset`() {
        val lyrics = Lyrics(lines = LrcParser.parseLrc("[00:01.00]A").lines, offsetMs = 250L)
        val text = LrcParser.writeLrc(lyrics)
        assertTrue(text.startsWith("[offset:250]\n"))
        assertEquals(250L, LrcParser.parseLrc(text).offsetMs)
    }

    @Test
    fun `decodes json-ish escapes before parsing`() {
        val lyrics = LrcParser.parseLrc("[00:01.00]line1\\n[00:02.00]line2")
        assertEquals(2, lyrics.lines.size)
        assertEquals("line1", lyrics.lines[0].text)
        assertEquals("line2", lyrics.lines[1].text)
        assertEquals("/", LrcParser.decodeEscapes("\\/"))
    }

    @Test
    fun `plain text becomes an unsynced sheet of untimed lines`() {
        val lyrics = LrcParser.parsePlainText("First line\nSecond line")
        assertEquals(2, lyrics.lines.size)
        assertEquals(0L, lyrics.lines[0].timeMs)
        assertEquals("First line", lyrics.lines[0].text)
        assertEquals("Second line", lyrics.lines[1].text)
        assertTrue(lyrics.isUnsynced)
    }

    @Test
    fun `plain text and lrc disagree about the same untimed document`() {
        val raw = "First line\nSecond line"
        // parseLrc deliberately drops untimed lines, which is exactly why the plain-text reader exists.
        assertTrue(LrcParser.parseLrc(raw).isEmpty)
        assertEquals(2, LrcParser.parsePlainText(raw).lines.size)
    }

    @Test
    fun `plain text skips metadata tags and blank lines`() {
        val lyrics = LrcParser.parsePlainText("[ti:Some Song]\n\n   \nReal line\n[ar:Someone]")
        assertEquals(1, lyrics.lines.size)
        assertEquals("Real line", lyrics.lines[0].text)
        assertEquals("[ti:Some Song]\n\n   \nReal line\n[ar:Someone]", lyrics.rawLrc)
    }

    @Test
    fun `plain text yields the empty document when nothing survives`() {
        assertTrue(LrcParser.parsePlainText(null).isEmpty)
        assertTrue(LrcParser.parsePlainText("").isEmpty)
        assertTrue(LrcParser.parsePlainText("   \n\n").isEmpty)
        assertTrue(LrcParser.parsePlainText("[ti:x]\n[ar:y]").isEmpty)
    }

    @Test
    fun `plain text honours the base offset and decodes escapes`() {
        assertEquals(250L, LrcParser.parsePlainText("A", baseOffsetMs = 250L).offsetMs)
        val escaped = LrcParser.parsePlainText("line1\\nline2")
        assertEquals(2, escaped.lines.size)
        assertEquals("line1", escaped.lines[0].text)
        assertEquals("line2", escaped.lines[1].text)
    }

    @Test
    fun `written timestamps stay ASCII under a non-latin numbering locale`() {
        // `"%02d:%02d.%03d".format(...)` uses the *default* locale, which on an ar / fa / bn device
        // renders Arabic-Indic or Bengali digits and produces an .lrc file no parser can read.
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"))
            val text = LrcParser.writeLrc(LrcParser.parseLrc("[00:01.00]A\n[01:20.25]B"))
            assertTrue("expected ASCII stamps, got: $text", text.all { it.code < 128 })
            assertEquals("[00:01.000]A\n[01:20.250]B\n", text)
            // Round-trip must still work under that locale.
            assertEquals(80_250L, LrcParser.parseLrc(text).lines[1].timeMs)
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }
}
