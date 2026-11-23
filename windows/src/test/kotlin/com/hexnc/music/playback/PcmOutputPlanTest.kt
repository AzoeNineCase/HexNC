package com.hexnc.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.sound.sampled.AudioFormat

/**
 * The plan ladder [AudioEngine] walks when it opens a device.
 *
 * These are the quality guarantees R4 adds: own rate and 24-bit depth first, 16-bit before a rate
 * change, and a mono->stereo duplicate only after the same-channel plan so loudness never changes.
 */
class PcmOutputPlanTest {

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
    fun `an ordinary cd source starts at its own rate and depth`() {
        val plans = outputPlans(pcm(44_100f))
        assertEquals(OutputPlan(44_100f, 16, 2), plans.first())
        assertEquals(listOf(44_100f, 48_000f), plans.map { it.rate }.distinct())
    }

    @Test
    fun `rates above the device cap are never offered`() {
        val plans = outputPlans(pcm(384_000f, bits = 24))
        assertTrue("nothing above the cap: $plans", plans.none { it.rate > MAX_DEVICE_RATE_HZ })
        assertEquals(listOf(48_000f, 44_100f), plans.map { it.rate }.distinct())
    }

    @Test
    fun `a twenty four bit source keeps 24 bit before falling back to 16`() {
        val plans = outputPlans(pcm(96_000f, bits = 24))
        assertEquals(listOf(24, 16, 24, 16, 24, 16), plans.map { it.bits })
        assertEquals(
            listOf(96_000f, 96_000f, 48_000f, 48_000f, 44_100f, 44_100f),
            plans.map { it.rate },
        )
    }

    @Test
    fun `a sixteen bit source is never padded into a wider container`() {
        val plans = outputPlans(pcm(48_000f, bits = 16))
        assertTrue("all depths stay 16: $plans", plans.all { it.bits == 16 })
    }

    @Test
    fun `the dsd decoder rate is offered first with its 16 bit depth`() {
        val plans = outputPlans(pcm(176_400f))
        assertEquals(176_400f, plans.first().rate, 1e-3f)
        assertEquals(16, plans.first().bits)
        assertEquals(listOf(176_400f, 48_000f, 44_100f), plans.map { it.rate }.distinct())
    }

    @Test
    fun `mono offers a stereo duplicate only after the same channel plan`() {
        val plans = outputPlans(pcm(48_000f, channels = 1))
        assertEquals(1, plans[0].channels)
        assertEquals(2, plans[1].channels)
        assertEquals(plans[0].rate, plans[1].rate, 1e-3f)
        assertEquals(plans[0].bits, plans[1].bits)
        // Every stereo plan must be preceded by its identical-level mono twin.
        plans.forEachIndexed { index, plan ->
            if (plan.channels == 2) {
                val previous = plans[index - 1]
                assertEquals(1, previous.channels)
                assertEquals(plan.rate, previous.rate, 1e-3f)
                assertEquals(plan.bits, previous.bits)
            }
        }
    }

    @Test
    fun `stereo never asks for a channel change`() {
        assertTrue(outputPlans(pcm(44_100f, channels = 2)).all { it.channels == 2 })
    }

    @Test
    fun `output format follows the plan exactly`() {
        val format = outputFormatOf(OutputPlan(96_000f, 24, 2))
        assertEquals(AudioFormat.Encoding.PCM_SIGNED, format.encoding)
        assertEquals(96_000f, format.sampleRate, 1e-3f)
        assertEquals(96_000f, format.frameRate, 1e-3f)
        assertEquals(24, format.sampleSizeInBits)
        assertEquals(2, format.channels)
        assertEquals(6, format.frameSize)
        assertTrue(!format.isBigEndian)
    }
}