package com.hexnc.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.sound.sampled.AudioFormat

/**
 * The format ladder the engine walks before it opens a device.
 *
 * This is the regression guard for the "192 kHz and up will not play" defect: Windows mixers report
 * support for rates they do not actually render, so a file's own rate must stop being offered above
 * [MAX_DEVICE_RATE_HZ] no matter what `AudioSystem.isLineSupported` claims.
 */
class AudioOutputFormatTest {

    private fun pcm(rate: Float, channels: Int = 2, bits: Int = 16) = AudioFormat(
        AudioFormat.Encoding.PCM_SIGNED,
        rate,
        bits,
        channels,
        channels * bits / 8,
        rate,
        false,
    )

    @Test
    fun `rates above 192 k are never offered to the device`() {
        val formats = outputFormatsFor(pcm(384_000f))
        assertTrue("nothing above the cap may be offered: $formats", formats.none { it.frameRate > MAX_DEVICE_RATE_HZ })
        assertEquals(48_000f, formats[0].frameRate, 1e-3f)
        assertEquals(44_100f, formats[1].frameRate, 1e-3f)
        assertEquals(2, formats.size)
    }

    @Test
    fun `the dsd decoder rate is offered first and capped rates follow`() {
        val formats = outputFormatsFor(pcm(176_400f))
        assertEquals(176_400f, formats[0].frameRate, 1e-3f)
        assertEquals(48_000f, formats[1].frameRate, 1e-3f)
        assertEquals(44_100f, formats[2].frameRate, 1e-3f)
    }

    @Test
    fun `an ordinary rate keeps the file's own rate first`() {
        val formats = outputFormatsFor(pcm(44_100f))
        assertEquals(2, formats.size)
        assertEquals(44_100f, formats[0].frameRate, 1e-3f)
        assertEquals(48_000f, formats[1].frameRate, 1e-3f)
    }

    @Test
    fun `converted candidates are 16 bit little endian at the target rate`() {
        val converted = rateFormat(pcm(96_000f), 48_000f)
        assertEquals(AudioFormat.Encoding.PCM_SIGNED, converted.encoding)
        assertEquals(16, converted.sampleSizeInBits)
        assertEquals(2, converted.channels)
        assertEquals(4, converted.frameSize)
        assertEquals(48_000f, converted.sampleRate, 1e-3f)
        assertEquals(48_000f, converted.frameRate, 1e-3f)
    }

    @Test
    fun `non pcm formats are normalised and pcm is handed back untouched`() {
        val float = AudioFormat(AudioFormat.Encoding.PCM_FLOAT, 96_000f, 32, 2, 8, 96_000f, false)
        val normalised = pcmFormatFor(float)
        assertEquals(AudioFormat.Encoding.PCM_SIGNED, normalised.encoding)
        assertEquals(16, normalised.sampleSizeInBits)
        assertTrue(!normalised.isBigEndian)
        assertEquals(96_000f, normalised.frameRate, 1e-3f)
        // The common path must not copy: a converter is only installed when the format changes.
        assertSame(normalised, pcmFormatFor(normalised))
    }
}
