package com.hexnc.music.playback

import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quantisation, dither and clamping, tested without an audio device.
 *
 * R4 forbids truncation and forbids wrap-around, so these tests pin the exact bytes for signed
 * 16/24-bit output, prove that TPDF dither keeps the error inside ±1.5 LSB, and prove that two
 * conversions with the same seed are identical.
 */
class PcmQuantizeTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `half scale is exactly half the signed range`() {
        val out = quantize(floatArrayOf(0.5f), 1, 16, dither = false, Random(0L))
        assertArrayEquals(bytes(0x00, 0x40), out)
    }

    @Test
    fun `positive full scale clamps to the largest representable value`() {
        val out = quantize(floatArrayOf(1.0f), 1, 16, dither = false, Random(0L))
        assertArrayEquals(bytes(0xFF, 0x7F), out)
    }

    @Test
    fun `negative full scale clamps to the smallest representable value`() {
        val out = quantize(floatArrayOf(-1.0f), 1, 16, dither = false, Random(0L))
        assertArrayEquals(bytes(0x00, 0x80), out)
    }

    @Test
    fun `values far outside the range clamp instead of wrapping`() {
        val out = quantize(floatArrayOf(4.0f, -4.0f), 2, 16, dither = false, Random(0L))
        assertArrayEquals(bytes(0xFF, 0x7F, 0x00, 0x80), out)
    }

    @Test
    fun `rounding is to nearest rather than truncation`() {
        // 0.6 LSB must become 1; truncation would leave 0.
        val out = quantize(floatArrayOf(0.6f / 32_768f), 1, 16, dither = false, Random(0L))
        assertArrayEquals(bytes(0x01, 0x00), out)
    }

    @Test
    fun `twenty four bit output is three little endian bytes`() {
        val out = quantize(floatArrayOf(0.5f, -0.5f), 2, 24, dither = false, Random(0L))
        assertArrayEquals(bytes(0x00, 0x00, 0x40, 0x00, 0x00, 0xC0), out)
    }

    @Test
    fun `dither changes the result and stays inside one and a half lsb`() {
        val silence = FloatArray(4_096)
        val plain = quantize(silence, silence.size, 16, dither = false, Random(7L))
        val dithered = quantize(silence, silence.size, 16, dither = true, Random(7L))
        assertFalse("dither must be measurable", plain.contentEquals(dithered))
        for (index in 0 until dithered.size step 2) {
            val sample = (dithered[index].toInt() and 0xFF) or (dithered[index + 1].toInt() shl 8)
            assertTrue(
                "dithered silence sample $sample exceeds ±1.5 LSB",
                sample >= -1 && sample <= 1,
            )
        }
    }

    @Test
    fun `the same seed reproduces the same dither exactly and another seed differs`() {
        val samples = FloatArray(1_024) { 0.1f }
        val first = quantize(samples, samples.size, 24, dither = true, Random(11L))
        val again = quantize(samples, samples.size, 24, dither = true, Random(11L))
        val other = quantize(samples, samples.size, 24, dither = true, Random(12L))
        assertArrayEquals(first, again)
        assertFalse(first.contentEquals(other))
    }

    @Test
    fun `dither is skipped when no bits are lost`() {
        val samples = floatArrayOf(0.25f, -0.25f)
        val plain = quantize(samples, 2, 16, dither = false, Random(3L))
        val alsoPlain = quantize(samples, 2, 16, dither = false, Random(3L))
        assertArrayEquals(plain, alsoPlain)
        assertArrayEquals(bytes(0x00, 0x20, 0x00, 0xE0), plain)
    }
}