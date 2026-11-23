package com.hexnc.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The capability probe is what turns "the file failed to play" into "this format is not supported",
 * so it has to be right about the three families no bundled decoder reads — and quiet about the ones
 * it merely does not recognise, because guessing "unsupported" on an exotic-but-playable container
 * would refuse a file that would have worked.
 */
class DecodeCapabilityTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun header(text: String, padTo: Int = 32): ByteArray {
        val bytes = text.toByteArray(Charsets.US_ASCII)
        return bytes + ByteArray((padTo - bytes.size).coerceAtLeast(0))
    }

    @Test
    fun opusInOggIsBlocked() {
        val bytes = header("OggS") + header("OpusHead", 16)
        assertEquals(DecodeSupport.UNSUPPORTED_OPUS, DecodeCapability.inspect("opus", bytes))
        assertTrue(DecodeSupport.UNSUPPORTED_OPUS.isBlocked)
    }

    /** Opus is recognised from the bitstream too, because `.ogg` files are routinely Opus inside. */
    @Test
    fun opusSmuggledIntoAnOggExtensionIsStillBlocked() {
        val bytes = header("OggS") + header("OpusHead", 16)
        assertEquals(DecodeSupport.UNSUPPORTED_OPUS, DecodeCapability.inspect("ogg", bytes))
    }

    @Test
    fun vorbisInOggIsSupported() {
        val bytes = header("OggS") + header("\u0001vorbis", 16)
        assertEquals(DecodeSupport.SUPPORTED, DecodeCapability.inspect("ogg", bytes))
    }

    @Test
    fun wmaIsBlockedByItsAsfGuid() {
        val guid = byteArrayOf(0x30, 0x26, 0xB2.toByte(), 0x75, 0x8E.toByte(), 0x66, 0xCF.toByte(), 0x11)
        assertEquals(DecodeSupport.UNSUPPORTED_WMA, DecodeCapability.inspect("wma", guid + ByteArray(24)))
        assertTrue(DecodeSupport.UNSUPPORTED_WMA.isBlocked)
    }

    /** A `.wma` whose header we cannot read is still refused: the codec, not the header, is the issue. */
    @Test
    fun wmaIsBlockedByItsExtensionWhenTheHeaderIsUnfamiliar() {
        assertEquals(DecodeSupport.UNSUPPORTED_WMA, DecodeCapability.inspect("WMA", header("junk")))
    }

    @Test
    fun dstCompressedDsdiffIsBlockedButPlainDsdIsNot() {
        val plain = header("FRM8") + header("DSD ", 16)
        assertEquals(DecodeSupport.SUPPORTED, DecodeCapability.inspect("dff", plain))
        val dst = header("FRM8") + header("DSD ", 16) + header("DST ", 16)
        assertEquals(DecodeSupport.UNSUPPORTED_DST, DecodeCapability.inspect("dff", dst))
        assertTrue(DecodeSupport.UNSUPPORTED_DST.isBlocked)
    }

    @Test
    fun dsfIsSupported() {
        assertEquals(DecodeSupport.SUPPORTED, DecodeCapability.inspect("dsf", header("DSD ")))
    }

    @Test
    fun theFormatsTheBundledProvidersReadAreSupported() {
        assertEquals(DecodeSupport.SUPPORTED, DecodeCapability.inspect("flac", header("fLaC")))
        assertEquals(DecodeSupport.SUPPORTED, DecodeCapability.inspect("mp3", header("ID3\u0003")))
        assertEquals(DecodeSupport.SUPPORTED, DecodeCapability.inspect("wav", header("RIFF")))
        assertEquals(DecodeSupport.SUPPORTED, DecodeCapability.inspect("m4a", header("\u0000\u0000\u0000\u0018ftypM4A ")))
        assertEquals(DecodeSupport.SUPPORTED, DecodeCapability.inspect("aac", header("\u0000\u0000\u0000\u0018ftyp")))
        assertEquals(DecodeSupport.SUPPORTED, DecodeCapability.inspect("ape", header("MAC ")))
    }

    @Test
    fun anUnknownContainerIsNotCalledUnsupported() {
        assertEquals(DecodeSupport.UNKNOWN, DecodeCapability.inspect("xyz", header("????")))
        assertFalse(DecodeSupport.UNKNOWN.isBlocked)
        assertFalse(DecodeSupport.SUPPORTED.isBlocked)
    }

    /** The cached path is what rows actually call, so the real-file route must answer identically. */
    @Test
    fun aRealFileIsJudgedFromItsHeaderAndCached() {
        val file = folder.newFile("sample.opus")
        file.writeBytes(header("OggS") + header("OpusHead", 16))
        assertEquals(DecodeSupport.UNSUPPORTED_OPUS, DecodeCapability.of(file))
        // Replacing the contents keeps the path but changes size and mtime, so the answer must follow.
        file.writeBytes(header("OggS") + header("\u0001vorbis", 16) + ByteArray(64))
        assertEquals(DecodeSupport.SUPPORTED, DecodeCapability.of(file))
    }

    @Test
    fun aMissingFileIsUnknownRatherThanAnError() {
        val missing = File(folder.root, "not-there.mp3")
        assertEquals(DecodeSupport.UNKNOWN, DecodeCapability.of(missing))
    }
}