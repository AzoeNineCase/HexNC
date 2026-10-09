package com.hexnc.music.playback

import com.hexnc.music.data.local.isDsdSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decision logic behind the two local-file failures reported for the Android build: a stream whose
 * sample rate is above what AudioTrack accepts, and a DSD file ExoPlayer cannot parse at all.
 *
 * The two functions are pure on purpose — the Android side only supplies the device's own output
 * rate, so the interesting arithmetic is all verifiable on the host.
 */
class AudioResamplingTest {

    @Test
    fun `a stream the device can already play is passed through untouched`() {
        assertNull(resampleTargetHz(48_000, 48_000))
        assertNull(resampleTargetHz(44_100, 48_000))
        assertNull(resampleTargetHz(44_100, 44_100))
        // 96 kHz is still within what a 192 kHz device accepts.
        assertNull(resampleTargetHz(96_000, 192_000))
    }

    @Test
    fun `a stream above the device rate is brought down to it`() {
        assertEquals(48_000, resampleTargetHz(96_000, 48_000))
        assertEquals(48_000, resampleTargetHz(176_400, 48_000))
        // The reported case: 192 kHz and above.
        assertEquals(48_000, resampleTargetHz(192_000, 48_000))
        assertEquals(48_000, resampleTargetHz(384_000, 48_000))
        assertEquals(192_000, resampleTargetHz(352_800, 192_000))
    }

    @Test
    fun `an unknown device rate falls back to 48 kHz`() {
        assertEquals(FALLBACK_SAMPLE_RATE, resampleTargetHz(96_000, 0))
        assertEquals(FALLBACK_SAMPLE_RATE, resampleTargetHz(192_000, 0))
        // 44.1 kHz is below the fallback too, so it is still left alone.
        assertNull(resampleTargetHz(44_100, 0))
    }

    @Test
    fun `an implausible device rate is clamped into the AudioTrack range`() {
        assertEquals(MIN_AUDIO_TRACK_SAMPLE_RATE, resampleTargetHz(96_000, 1))
        // A device claiming to run above 192 kHz must not make us up-sample into it.
        assertEquals(MAX_AUDIO_TRACK_SAMPLE_RATE, resampleTargetHz(384_000, 1_000_000))
    }

    @Test
    fun `an unusable input rate is never resampled`() {
        assertNull(resampleTargetHz(0, 48_000))
        assertNull(resampleTargetHz(-1, 48_000))
    }

    @Test
    fun `dsd sources are recognised by extension`() {
        assertTrue(isDsdSource("/storage/emulated/0/Music/Album/track.dsf"))
        assertTrue(isDsdSource("/storage/emulated/0/Music/Album/track.DFF"))
        assertTrue(isDsdSource("album/track.dsd"))
        // A SAF document URI keeps its query, and a fragment sits after the (encoded) child name.
        assertTrue(isDsdSource("content://com.android.externalstorage.documents/document/a.dsf?x=1"))
        assertTrue(isDsdSource("content://com.android.externalstorage.documents/document/primary%3AMusic%2Fa.dff#x"))
    }

    @Test
    fun `ordinary sources are not mistaken for dsd`() {
        assertFalse(isDsdSource(null))
        assertFalse(isDsdSource(""))
        assertFalse(isDsdSource("   "))
        assertFalse(isDsdSource("/m/a.mp3"))
        assertFalse(isDsdSource("/m/a.flac"))
        // A MediaStore URI carries no extension at all.
        assertFalse(isDsdSource("content://media/external/audio/media/123"))
        // ...and a dot in a *directory* name must not count as the extension.
        assertFalse(isDsdSource("/storage/Music/Album 1.5/track"))
    }
}
