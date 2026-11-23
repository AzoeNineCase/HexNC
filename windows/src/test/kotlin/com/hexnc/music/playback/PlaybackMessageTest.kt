package com.hexnc.music.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A failed stream used to leave a dead transport bar with no explanation. These codes are the ones
 * the user can act on — an unreachable host, a refused cleartext URL, a file that has gone away, a
 * file that is there but unreadable, a format this machine cannot decode, a damaged bitstream, and a
 * device that refused to open — and everything else deliberately collapses into one generic message
 * rather than leaking an English decoder string into a Chinese UI.
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
    }

    /**
     * "The file is gone" and "I am not allowed to read it" send the user to two different places, so
     * they must not share a sentence: one is a re-scan problem, the other a permission problem.
     */
    @Test
    fun anUnreadableFileIsNotReportedAsMissing() {
        assertEquals(
            MESSAGE_PLAYBACK_ACCESS,
            playbackMessageCodeFor(PlaybackErrorCode.IO_NO_PERMISSION),
        )
        assertEquals(
            MESSAGE_PLAYBACK_ACCESS,
            playbackMessageCodeFor(PlaybackErrorCode.FILE_ACCESS),
        )
    }

    /** A known-unsupported container is a format problem: no amount of retrying will help. */
    @Test
    fun anUnsupportedFormatSaysSo() {
        assertEquals(
            MESSAGE_PLAYBACK_UNSUPPORTED_FORMAT,
            playbackMessageCodeFor(PlaybackErrorCode.UNSUPPORTED_FORMAT),
        )
    }

    /** A decoder that rejected the bytes is a damaged-file problem, not a format problem. */
    @Test
    fun aDamagedBitstreamIsNotCalledAnUnsupportedFormat() {
        assertEquals(MESSAGE_PLAYBACK_DECODE, playbackMessageCodeFor(PlaybackErrorCode.DECODE_ERROR))
        assertEquals(MESSAGE_PLAYBACK_DECODE, playbackMessageCodeFor(PlaybackErrorCode.DECODING_FAILED))
    }

    /** The device, not the file: the only case whose fix is "try again / pick another output". */
    @Test
    fun aDeviceFailureIsItsOwnCase() {
        assertEquals(MESSAGE_PLAYBACK_DEVICE, playbackMessageCodeFor(PlaybackErrorCode.DEVICE_ERROR))
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
            MESSAGE_PLAYBACK_ACCESS,
            MESSAGE_PLAYBACK_UNSUPPORTED,
            MESSAGE_PLAYBACK_UNSUPPORTED_FORMAT,
            MESSAGE_PLAYBACK_DECODE,
            MESSAGE_PLAYBACK_DEVICE,
            MESSAGE_PLAYBACK_DSD,
            MESSAGE_PLAYBACK_FAILED,
        )
        assertEquals(11, codes.size)
    }

    /** The four new codes must not collide with the transport codes or with each other. */
    @Test
    fun theNewErrorCodesStayInTheirOwnRange() {
        val decoder = setOf(
            PlaybackErrorCode.DECODING_FAILED,
            PlaybackErrorCode.UNSUPPORTED_FORMAT,
            PlaybackErrorCode.DECODE_ERROR,
            PlaybackErrorCode.DEVICE_ERROR,
            PlaybackErrorCode.FILE_ACCESS,
        )
        assertEquals(5, decoder.size)
        decoder.forEach { assert(it in 3001..3005) { "$it is not a decoder code" } }
    }
}