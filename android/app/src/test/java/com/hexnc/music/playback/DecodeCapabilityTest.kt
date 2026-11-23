package com.hexnc.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The capability probe exists so a list can warn "unsupported" *before* a tap turns into a dead
 * transport bar. These cases pin the families the platform genuinely cannot open (WMA/ASF, Monkey's
 * Audio, DST-compressed DSDIFF) and, just as important, that Opus is *not* among them on Android —
 * the platform ships a decoder, unlike the desktop build.
 */
class DecodeCapabilityTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private fun header(vararg text: String): ByteArray =
        text.joinToString("").toByteArray(Charsets.US_ASCII)

    @Test
    fun `opus is supported because the platform ships a decoder`() {
        assertEquals(DecodeSupport.SUPPORTED, DecodeCapability.inspect("opus", ByteArray(0)))
        assertEquals(DecodeSupport.SUPPORTED, DecodeCapability.inspect("opus", header("OggS")))
        assertFalse(DecodeSupport.SUPPORTED.isBlocked)
    }

    @Test
    fun `wma is blocked by extension and by the ASF guid`() {
        assertEquals(DecodeSupport.UNSUPPORTED_WMA, DecodeCapability.inspect("wma", ByteArray(0)))
        assertEquals(DecodeSupport.UNSUPPORTED_WMA, DecodeCapability.inspect("asf", ByteArray(0)))
        // A .wma the scanner mislabels as .mp3 is still recognisable from the container itself.
        val asf = byteArrayOf(0x30, 0x26, 0xB2.toByte(), 0x75, 0x8E.toByte(), 0x66, 0xCF.toByte(), 0x11)
        assertEquals(DecodeSupport.UNSUPPORTED_WMA, DecodeCapability.inspect("mp3", asf))
        assertTrue(DecodeSupport.UNSUPPORTED_WMA.isBlocked)
    }

    @Test
    fun `ape is blocked because media3 has no extractor`() {
        assertEquals(DecodeSupport.UNSUPPORTED_APE, DecodeCapability.inspect("ape", ByteArray(0)))
        assertEquals(DecodeSupport.UNSUPPORTED_APE, DecodeCapability.inspect(".APE", ByteArray(0)))
    }

    @Test
    fun `a dff carrying a dst chunk is distinguished from uncompressed dsdiff`() {
        val plain = header("FRM8", "0000DSD ")
        val compressed = header("FRM8", "0000DST ")
        assertEquals(DecodeSupport.SUPPORTED, DecodeCapability.inspect("dff", plain))
        assertEquals(DecodeSupport.UNSUPPORTED_DST, DecodeCapability.inspect("dff", compressed))
        assertEquals(DecodeSupport.UNSUPPORTED_DST, DecodeCapability.inspect("dsf", compressed))
    }

    @Test
    fun `a dsf file is supported when its own magic is present`() {
        assertEquals(DecodeSupport.SUPPORTED, DecodeCapability.inspect("dsf", header("DSD ")))
        // Without a readable header we decline to guess: the transcoder's error is a better answer.
        assertEquals(DecodeSupport.UNKNOWN, DecodeCapability.inspect("dsf", ByteArray(0)))
        assertFalse(DecodeSupport.UNKNOWN.isBlocked)
    }

    @Test
    fun `the platforms own formats are all supported`() {
        for (extension in listOf("mp3", "flac", "wav", "ogg", "oga", "m4a", "mp4", "3gp", "aac", "alac")) {
            assertEquals(
                "extension $extension",
                DecodeSupport.SUPPORTED,
                DecodeCapability.inspect(extension, ByteArray(0)),
            )
        }
    }

    @Test
    fun `an unknown container asks the platform instead of refusing`() {
        assertEquals(DecodeSupport.UNKNOWN, DecodeCapability.inspect("xyz", ByteArray(0)))
        assertEquals(DecodeSupport.UNKNOWN, DecodeCapability.inspect("", ByteArray(0)))
        assertFalse(DecodeSupport.UNKNOWN.isBlocked)
    }

    @Test
    fun `a content uri is never guessed at`() {
        // SAF gives no way to open a random header up front, and refusing a file we merely cannot
        // inspect would hide playable music.
        assertEquals(DecodeSupport.UNKNOWN, DecodeCapability.of("content://com.android.externalstorage/media/1"))
        assertEquals(DecodeSupport.UNKNOWN, DecodeCapability.of(null))
        assertEquals(DecodeSupport.UNKNOWN, DecodeCapability.of("   "))
    }

    @Test
    fun `a real file is inspected and the answer is memoised`() {
        val file = temporary.newFile("real.wma")
        val answer = DecodeCapability.of(file)
        assertEquals(DecodeSupport.UNSUPPORTED_WMA, answer)
        assertEquals(answer, DecodeCapability.of(file))
        // Replacing the bytes with a supported container must be noticed: the cache key includes
        // size and mtime, otherwise a re-imported track would keep a stale verdict.
        file.writeBytes(ByteArray(2048) { if (it == 0) 0x66 else 0 })
        file.setLastModified(System.currentTimeMillis() + 5_000)
        assertEquals(DecodeSupport.UNKNOWN, DecodeCapability.of(file.absolutePath.replace(".wma", ".bin")))
    }

    @Test
    fun `a missing file is unknown rather than an error`() {
        assertEquals(DecodeSupport.UNKNOWN, DecodeCapability.of(File(temporary.root, "gone.mp3")))
    }

    @Test
    fun `only blocked support values warn the user`() {
        val blocked = DecodeSupport.entries.filter { it.isBlocked }.toSet()
        assertEquals(
            setOf(
                DecodeSupport.UNSUPPORTED_OPUS,
                DecodeSupport.UNSUPPORTED_WMA,
                DecodeSupport.UNSUPPORTED_APE,
                DecodeSupport.UNSUPPORTED_DST,
            ),
            blocked,
        )
    }
}