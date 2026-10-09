package com.hexnc.music.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A failed stream used to leave a dead transport bar with no explanation. These codes are the ones
 * the user can act on — an unreachable host, a refused cleartext URL, a file that has gone away, a
 * format this machine cannot decode — and everything else deliberately collapses into one generic
 * message rather than leaking an English decoder string into a Chinese UI.
 *
 * The Android build read the codes off `PlaybackException`; the desktop player has no ExoPlayer, so
 * it defines the same numbers itself ([PlaybackErrorCode]) — this test is what keeps the mapping
 * from drifting away from the Android behaviour it reproduces.
 */
class PlaybackMessageTest {

    @Test
    fun cleartextIsReportedSeparately() {
        assertEquals(
            MESSAGE_PLAYBACK_CLEARTEXT,
            playbackMessageCodeFor(PlaybackErrorCode.IO_CLEARTEXT_NOT_PERMITTED),
        )
    }

    @Test
    fun transportProblemsShareOneCode() {
        assertEquals(
            MESSAGE_PLAYBACK_NETWORK,
            playbackMessageCodeFor(PlaybackErrorCode.IO_NETWORK_CONNECTION_FAILED),
        )
        assertEquals(
            MESSAGE_PLAYBACK_NETWORK,
            playbackMessageCodeFor(PlaybackErrorCode.IO_NETWORK_CONNECTION_TIMEOUT),
        )
        assertEquals(
            MESSAGE_PLAYBACK_NETWORK,
            playbackMessageCodeFor(PlaybackErrorCode.IO_BAD_HTTP_STATUS),
        )
    }

    @Test
    fun aVanishedLocalFileIsReportedAsSuch() {
        assertEquals(
            MESSAGE_PLAYBACK_FILE_MISSING,
            playbackMessageCodeFor(PlaybackErrorCode.IO_FILE_NOT_FOUND),
        )
        assertEquals(
            MESSAGE_PLAYBACK_FILE_MISSING,
            playbackMessageCodeFor(PlaybackErrorCode.IO_NO_PERMISSION),
        )
    }

    @Test
    fun anUndecodableFormatIsNotCalledAGenericFailure() {
        assertEquals(
            MESSAGE_PLAYBACK_UNSUPPORTED,
            playbackMessageCodeFor(PlaybackErrorCode.DECODING_FAILED),
        )
    }

    @Test
    fun anythingElseIsGeneric() {
        assertEquals(MESSAGE_PLAYBACK_FAILED, playbackMessageCodeFor(0))
        assertEquals(MESSAGE_PLAYBACK_FAILED, playbackMessageCodeFor(Int.MIN_VALUE))
    }

    @Test
    fun theCodesAreDistinct() {
        val codes = setOf(
            MESSAGE_NO_PLAYABLE,
            MESSAGE_PLAYBACK_CLEARTEXT,
            MESSAGE_PLAYBACK_NETWORK,
            MESSAGE_PLAYBACK_FILE_MISSING,
            MESSAGE_PLAYBACK_UNSUPPORTED,
            MESSAGE_PLAYBACK_DSD,
            MESSAGE_PLAYBACK_FAILED,
        )
        assertEquals(7, codes.size)
    }
}
