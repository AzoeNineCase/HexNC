package com.hexnc.music.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "I asked for Hi-Res" and "I am hearing Hi-Res" are different claims.
 *
 * The service answers a `hires` request with the best the account and the track's copyright allow,
 * so the granted tier has to be read back and shown rather than assumed. [GrantedQuality] is the
 * single place that decides whether a grant counts as lossless; getting it wrong means the player
 * tells the user they are listening to a master while a 320 kbps MP3 is playing.
 */
class GrantedQualityTest {

    private fun quality(level: String, container: String = "flac", bitrate: Int = 1_039_182) =
        GrantedQuality(container = container, bitrate = bitrate, level = level)

    @Test
    fun `lossless grant is not flagged as below lossless`() {
        assertFalse(quality("lossless").belowLossless)
    }

    @Test
    fun `hires grant is not flagged as below lossless`() {
        assertFalse(quality("hires").belowLossless)
    }

    @Test
    fun `exhigh grant is below lossless`() {
        assertTrue(quality("exhigh", container = "mp3", bitrate = 320_000).belowLossless)
    }

    @Test
    fun `standard grant is below lossless`() {
        assertTrue(quality("standard", container = "mp3", bitrate = 128_000).belowLossless)
    }

    @Test
    fun `an unreported level makes no lossless claim`() {
        // Silence is not a promise: when the reply omits `level` the UI must not print "lossless".
        assertTrue(quality("").belowLossless)
    }

    @Test
    fun `level matching ignores case`() {
        assertFalse(quality("LOSSLESS").belowLossless)
    }

    @Test
    fun `container and bitrate are carried through unchanged`() {
        val grant = quality("lossless", container = "flac", bitrate = 1_004_614)
        assertEquals("flac", grant.container)
        assertEquals(1_004_614, grant.bitrate)
        assertEquals("lossless", grant.level)
    }
}