package com.hexnc.music.data.local

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the lyric text decoding.
 *
 * Local `.lrc` files in a Chinese music library are far more often GBK/GB18030 than UTF-8, and the
 * scanner used to read them with a plain `File.readText()` — every such file came out as replacement
 * characters, `parseLyricDocument` then found no timestamps, and the import was dropped. These cases
 * are the regression line for that bug: the decoder has to honour a byte order mark, sniff an
 * unmarked UTF-16 file, and fall back to GB18030 before giving up on UTF-8.
 */
class LyricDecodeTest {

    private val lyric = "[00:12.34]窗外下着雨\n[00:16.00]我在想你\n"

    @Test
    fun `utf8 without bom decodes`() {
        val bytes = lyric.toByteArray(Charsets.UTF_8)
        assertEquals(lyric, decodeLyricBytes(bytes))
    }

    @Test
    fun `utf8 bom is stripped`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            lyric.toByteArray(Charsets.UTF_8)
        assertEquals(lyric, decodeLyricBytes(bytes))
    }

    @Test
    fun `gb18030 chinese lyric decodes`() {
        val bytes = lyric.toByteArray(Charset.forName("GB18030"))
        // The bytes are genuinely not UTF-8: read as UTF-8 they would contain replacement chars.
        assertTrue("fixture should be non-UTF-8", String(bytes, Charsets.UTF_8).contains('\uFFFD'))
        assertEquals(lyric, decodeLyricBytes(bytes))
    }

    @Test
    fun `utf16 with bom decodes`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            lyric.toByteArray(Charsets.UTF_16LE)
        assertEquals(lyric, decodeLyricBytes(bytes))
    }

    @Test
    fun `utf16 without bom is sniffed`() {
        // No BOM, but every other byte is zero for Latin text: the NUL pattern is the only hint,
        // and without it the strict UTF-8 check below would accept the file and insert a NUL after
        // every character.
        val decoded = decodeLyricBytes(lyric.toByteArray(Charsets.UTF_16LE))
        assertTrue("expected the timestamp to survive, got: $decoded", decoded.contains("[00:12.34]"))
        assertTrue("expected the Chinese text to survive, got: $decoded", decoded.contains("窗外下着雨"))
    }

    @Test
    fun `ascii lyric decodes`() {
        val ascii = "[00:01.00]hello\n[00:02.00]world\n"
        assertEquals(ascii, decodeLyricBytes(ascii.toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun `empty input is empty not a crash`() {
        assertEquals("", decodeLyricBytes(ByteArray(0)))
    }

    @Test
    fun `timestamps survive every path so the parser keeps the lines`() {
        // The parse step only keeps lines it can attach to a timestamp; a decoder that returns
        // mojibake still passes the regex, so assert on the real Chinese payload too.
        listOf(Charsets.UTF_8, Charset.forName("GB18030"), Charsets.UTF_16LE).forEach { charset ->
            val decoded = decodeLyricBytes(lyric.toByteArray(charset))
            assertTrue("text lost with $charset: $decoded", decoded.contains("窗外下着雨"))
            assertTrue("timestamp lost with $charset: $decoded", decoded.contains("[00:12.34]"))
        }
    }
}