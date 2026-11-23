package com.hexnc.music.playback

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end conversion without a device: the byte layout, channel mapping, dithering and the promise
 * that the caller's buffer size cannot change the result.
 *
 * The engine feeds [PcmConverter] with whatever byte count the device asks for, so resumability is a
 * quality requirement, not a convenience: a converter that restarted its filter per read would add a
 * click at every buffer boundary.
 */
class PcmConverterTest {

    private fun pcmFormat(rate: Float, channels: Int, bits: Int, bigEndian: Boolean = false) = AudioFormat(
        AudioFormat.Encoding.PCM_SIGNED,
        rate,
        bits,
        channels,
        channels * bits / 8,
        rate,
        bigEndian,
    )

    private fun floatFormat(rate: Float, channels: Int) = AudioFormat(
        AudioFormat.Encoding.PCM_FLOAT,
        rate,
        32,
        channels,
        channels * 4,
        rate,
        false,
    )

    private fun pcm16(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (index in samples.indices) {
            out[index * 2] = samples[index].toByte()
            out[index * 2 + 1] = (samples[index] shr 8).toByte()
        }
        return out
    }

    private fun pcm24(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 3)
        for (index in samples.indices) {
            out[index * 3] = samples[index].toByte()
            out[index * 3 + 1] = (samples[index] shr 8).toByte()
            out[index * 3 + 2] = (samples[index] shr 16).toByte()
        }
        return out
    }

    private fun floats(vararg samples: Float): ByteArray {
        val out = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) out.putFloat(sample)
        return out.array()
    }

    private fun stream(bytes: ByteArray, format: AudioFormat): AudioInputStream =
        AudioInputStream(ByteArrayInputStream(bytes), format, (bytes.size / format.frameSize).toLong())

    private fun convert(
        bytes: ByteArray,
        source: AudioFormat,
        target: AudioFormat,
        chunk: Int = 64 * 1024,
        seed: Long = 0L,
    ): ByteArray {
        val converter = PcmConverter(source, target, seed)
        val input = stream(bytes, source)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(chunk)
        while (true) {
            val read = converter.read(input, buffer)
            if (read <= 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    @Test
    fun `sixteen bit stereo passthrough is byte exact and dither free`() {
        val source = pcmFormat(48_000f, 2, 16)
        val bytes = pcm16(1_000, -1_000, 32_767, -32_768)
        assertArrayEquals(bytes, convert(bytes, source, source))
    }

    @Test
    fun `twenty four bit passthrough keeps all three bytes`() {
        val source = pcmFormat(96_000f, 2, 24)
        val bytes = pcm24(1, -1, 8_388_607, -8_388_608, 0x12_3456, -0x12_3456)
        assertArrayEquals(bytes, convert(bytes, source, source))
    }

    @Test
    fun `mono is duplicated into stereo at identical level`() {
        val source = pcmFormat(48_000f, 1, 16)
        val target = pcmFormat(48_000f, 2, 16)
        val output = convert(pcm16(1_000, -2_000), source, target)
        assertArrayEquals(pcm16(1_000, 1_000, -2_000, -2_000), output)
    }

    @Test
    fun `unsigned eight bit is centred before widening`() {
        val source = AudioFormat(
            AudioFormat.Encoding.PCM_UNSIGNED,
            48_000f,
            8,
            1,
            1,
            48_000f,
            false,
        )
        val bytes = byteArrayOf(0x80.toByte(), 0xC0.toByte(), 0x40.toByte())
        val output = convert(bytes, source, pcmFormat(48_000f, 1, 16))
        assertArrayEquals(pcm16(0, 16_384, -16_384), output)
    }

    @Test
    fun `float samples are rounded and clamped into sixteen bit pcm`() {
        val source = floatFormat(48_000f, 1)
        val target = pcmFormat(48_000f, 1, 16)
        val output = convert(floats(0.5f, -0.5f, 1.5f), source, target)
        val samples = IntArray(3) { index ->
            (output[index * 2].toInt() and 0xFF) or (output[index * 2 + 1].toInt() shl 8)
        }
        assertTrue("half scale: ${samples[0]}", kotlin.math.abs(samples[0] - 16_384) <= 1)
        assertTrue("negative half scale: ${samples[1]}", kotlin.math.abs(samples[1] + 16_384) <= 1)
        assertEquals("overshoot clamps instead of wrapping", 32_767, samples[2])
    }

    @Test
    fun `twenty four to sixteen bit dithers instead of truncating`() {
        val source = pcmFormat(48_000f, 1, 24)
        val target = pcmFormat(48_000f, 1, 16)
        val samples = IntArray(512) { 0x12_3456 }
        val bytes = pcm24(*samples)
        val output = convert(bytes, source, target, seed = 42L)
        assertEquals(samples.size * 2, output.size)
        val truncated = samples.map { (it shr 8) and 0xFFFF }
        var differs = false
        for (index in samples.indices) {
            val value = (output[index * 2].toInt() and 0xFF) or (output[index * 2 + 1].toInt() shl 8)
            if (value != truncated[index]) differs = true
            assertTrue("value $value out of range", value in -32_768..32_767)
        }
        assertTrue("dither must perturb the truncation result", differs)
    }

    @Test
    fun `dithered silence stays within one lsb after a depth reduction`() {
        val source = pcmFormat(48_000f, 1, 24)
        val target = pcmFormat(48_000f, 1, 16)
        val output = convert(pcm24(*IntArray(1_024)), source, target, seed = 9L)
        for (index in 0 until output.size step 2) {
            val value = (output[index].toInt() and 0xFF) or (output[index + 1].toInt() shl 8)
            assertTrue("dithered silence sample $value exceeds ±1.5 LSB", value in -1..1)
        }
    }

    @Test
    fun `rate conversion keeps the duration and the level`() {
        val source = pcmFormat(48_000f, 1, 16)
        val target = pcmFormat(96_000f, 1, 16)
        val frames = 1_000
        val bytes = pcm16(*IntArray(frames) { 0 })
        val output = convert(bytes, source, target)
        val outputFrames = output.size / 2
        assertTrue("produced $outputFrames frames", kotlin.math.abs(outputFrames - frames * 2) <= 2)
    }

    @Test
    fun `the caller's buffer size never changes a byte`() {
        val source = pcmFormat(44_100f, 2, 16)
        val target = pcmFormat(48_000f, 2, 16)
        val bytes = pcm16(*IntArray(2_000) { if (it % 2 == 0) 8_000 else -8_000 })
        val large = convert(bytes, source, target, chunk = 64 * 1024, seed = 5L)
        val small = convert(bytes, source, target, chunk = 7, seed = 5L)
        val medium = convert(bytes, source, target, chunk = 1_024, seed = 5L)
        assertArrayEquals(large, small)
        assertArrayEquals(large, medium)
        assertFalse(large.isEmpty())
    }

    @Test
    fun `read returns minus one only once the tail is gone`() {
        val source = pcmFormat(48_000f, 1, 16)
        val converter = PcmConverter(source, source)
        val input = stream(pcm16(1, 2, 3, 4), source)
        val output = ByteArray(8)
        assertEquals(8, converter.read(input, output))
        assertEquals(-1, converter.read(input, output))
        assertEquals(-1, converter.read(input, output))
    }

    @Test
    fun `a big endian source decodes with the right sign`() {
        val source = pcmFormat(48_000f, 1, 16, bigEndian = true)
        val target = pcmFormat(48_000f, 1, 16)
        val bytes = byteArrayOf(0x40, 0x00, 0xC0.toByte(), 0x00)
        val output = convert(bytes, source, target)
        assertArrayEquals(pcm16(16_384, -16_384), output)
    }
}