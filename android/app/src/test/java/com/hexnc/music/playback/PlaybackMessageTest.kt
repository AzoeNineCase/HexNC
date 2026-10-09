package com.hexnc.music.playback

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A failed stream used to leave a dead transport bar with no explanation. The codes below are the
 * ones the user can act on — a network problem, a missing file, a format the device cannot play —
 * and everything else deliberately collapses into one generic message rather than leaking an
 * English ExoPlayer decoder string into a Chinese UI.
 */
class PlaybackMessageTest {

    @Test
    fun cleartextIsReportedSeparately() {
        assertEquals(
            MESSAGE_PLAYBACK_CLEARTEXT,
            playbackMessageCodeFor(PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED),
        )
    }

    @Test
    fun transportProblemsShareOneCode() {
        assertEquals(
            MESSAGE_PLAYBACK_NETWORK,
            playbackMessageCodeFor(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED),
        )
        assertEquals(
            MESSAGE_PLAYBACK_NETWORK,
            playbackMessageCodeFor(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT),
        )
        assertEquals(
            MESSAGE_PLAYBACK_NETWORK,
            playbackMessageCodeFor(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS),
        )
    }

    @Test
    fun anythingElseIsGeneric() {
        assertEquals(MESSAGE_PLAYBACK_FAILED, playbackMessageCodeFor(0))
        assertEquals(MESSAGE_PLAYBACK_FAILED, playbackMessageCodeFor(PlaybackException.ERROR_CODE_UNSPECIFIED))
    }

    @Test
    fun `file problems are separated from decode problems`() {
        // A revoked SAF grant or a deleted file is the user's to fix; it must not be blamed on the
        // file's format.
        assertEquals(
            MESSAGE_PLAYBACK_FILE_MISSING,
            playbackMessageCodeFor(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND),
        )
        assertEquals(
            MESSAGE_PLAYBACK_FILE_MISSING,
            playbackMessageCodeFor(PlaybackException.ERROR_CODE_IO_NO_PERMISSION),
        )
    }

    @Test
    fun `unsupported containers, codecs and rates share one hint`() {
        val codes = listOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            // What a 192 kHz stream used to fail with before the sink resampled it.
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        )
        for (code in codes) {
            assertEquals("code $code", MESSAGE_PLAYBACK_UNSUPPORTED, playbackMessageCodeFor(code))
        }
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
