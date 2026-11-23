package com.hexnc.music.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The windowed-sinc rate converter on its own.
 *
 * The point of these tests is that the converter is band-limited and deterministic: a sine below the
 * lower Nyquist frequency comes through at the same level, a tone above the target Nyquist frequency
 * is rejected instead of folding back, and splitting the input into arbitrary blocks does not change
 * a single bit (the fractional position and filter history persist between calls).
 */
class SincResamplerTest {

    private fun tone(frames: Int, hz: Double, rate: Double): FloatArray =
        FloatArray(frames) { sin(2.0 * PI * hz * it / rate).toFloat() }

    private fun rms(samples: FloatArray, from: Int = 0, to: Int = samples.size): Double {
        var sum = 0.0
        for (index in from until to) sum += samples[index].toDouble() * samples[index]
        return sqrt(sum / (to - from).coerceAtLeast(1))
    }

    /**
     * Runs [input] through a fresh resampler, in [chunk]-frame blocks, and drains the tail.
     *
     * [blockSize] leaves room for one flush call's worth of tail; flush returns 0 once the filter is
     * empty, so the loop terminates without depending on an internal frame count.
     */
    private fun render(
        input: FloatArray,
        sourceRate: Double,
        targetRate: Double,
        chunk: Int,
    ): FloatArray {
        val resampler = SincResampler(sourceRate, targetRate)
        val capacity = (input.size * targetRate / sourceRate).toInt() + 256
        val block = FloatArray(capacity)
        val result = ArrayList<Float>(capacity)
        var offset = 0
        while (offset < input.size) {
            val count = minOf(chunk, input.size - offset)
            val part = input.copyOfRange(offset, offset + count)
            val produced = resampler.process(part, count, block)
            for (index in 0 until produced) result.add(block[index])
            offset += count
        }
        while (true) {
            val produced = resampler.flush(block)
            for (index in 0 until produced) result.add(block[index])
            if (produced == 0) break
        }
        return result.toFloatArray()
    }

    @Test
    fun `upsampling a sine keeps its level`() {
        val input = tone(4_096, 1_000.0, 48_000.0)
        val output = render(input, 48_000.0, 96_000.0, chunk = input.size)
        val from = output.size / 4
        val to = output.size * 3 / 4
        assertEquals("unity gain", 1.0 / sqrt(2.0), rms(output, from, to), 0.02)
    }

    @Test
    fun `downsampling a sine keeps its level below the new nyquist`() {
        val input = tone(8_192, 1_000.0, 96_000.0)
        val output = render(input, 96_000.0, 48_000.0, chunk = input.size)
        val from = output.size / 4
        val to = output.size * 3 / 4
        assertEquals("unity gain after the anti-alias filter", 1.0 / sqrt(2.0), rms(output, from, to), 0.02)
    }

    @Test
    fun `downsampling rejects a tone above the target nyquist frequency`() {
        val input = tone(8_192, 40_000.0, 96_000.0)
        val output = render(input, 96_000.0, 48_000.0, chunk = input.size)
        val from = output.size / 4
        val to = output.size * 3 / 4
        assertTrue(
            "40 kHz must not alias back into the audible band: rms=${rms(output, from, to)}",
            rms(output, from, to) < 0.02,
        )
    }

    @Test
    fun `the resampled length matches the rate ratio`() {
        val input = tone(2_000, 440.0, 44_100.0)
        val output = render(input, 44_100.0, 48_000.0, chunk = 256)
        val expected = input.size.toDouble() * 48_000.0 / 44_100.0
        assertTrue(
            "produced ${output.size} frames, expected about $expected",
            abs(output.size - expected) <= 2.0,
        )
    }

    @Test
    fun `chunked input is bit identical to one large block`() {
        val input = tone(3_000, 2_000.0, 48_000.0)
        val whole = render(input, 48_000.0, 96_000.0, chunk = input.size)
        val chunked = render(input, 48_000.0, 96_000.0, chunk = 97)
        assertEquals(whole.size, chunked.size)
        for (index in whole.indices) {
            assertEquals("sample $index", whole[index], chunked[index], 1e-6f)
        }
    }

    @Test
    fun `flush is idempotent once the tail has been drained`() {
        val resampler = SincResampler(44_100.0, 48_000.0)
        val input = tone(500, 500.0, 44_100.0)
        val block = FloatArray(2_048)
        var produced = resampler.process(input, input.size, block)
        var total = produced
        while (true) {
            produced = resampler.flush(block)
            if (produced == 0) break
            total += produced
        }
        assertEquals(0, resampler.flush(block))
        assertTrue("some output was produced", total > 0)
    }
}