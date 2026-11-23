package com.hexnc.music.playback

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A failed stream used to leave a dead transport bar with no explanation. The codes below are the
 * ones the user can act on — a network problem, a missing file, an unreadable file, a format the
 * device cannot play, a damaged bitstream, an output device that refused to start — and everything
 * else deliberately collapses into one generic message rather than leaking an English ExoPlayer
 * decoder string into a Chinese UI.
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
        // A deleted file is a re-scan problem.
        assertEquals(
            MESSAGE_PLAYBACK_FILE_MISSING,
            playbackMessageCodeFor(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND),
        )
    }

    /** A revoked SAF grant is a permission problem; telling the user the file vanished is wrong. */
    @Test
    fun `an unreadable file is not reported as missing`() {
        assertEquals(
            MESSAGE_PLAYBACK_ACCESS,
            playbackMessageCodeFor(PlaybackException.ERROR_CODE_IO_NO_PERMISSION),
        )
    }

    /** No extractor or decoder for the container at all — a format problem, retrying will not help. */
    @Test
    fun `unsupported containers and codecs say the format is unsupported`() {
        val codes = listOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        )
        for (code in codes) {
            assertEquals("code $code", MESSAGE_PLAYBACK_UNSUPPORTED_FORMAT, playbackMessageCodeFor(code))
        }
    }

    /** A decoder existed and rejected the bytes — a damaged-file problem, not a format problem. */
    @Test
    fun `damaged bitstreams are not called unsupported formats`() {
        val codes = listOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        )
        for (code in codes) {
            assertEquals("code $code", MESSAGE_PLAYBACK_DECODE, playbackMessageCodeFor(code))
        }
    }

    /** The output device, not the file. The one case whose fix is "try again / another output". */
    @Test
    fun `audio track failures are a device problem`() {
        assertEquals(
            MESSAGE_PLAYBACK_DEVICE,
            playbackMessageCodeFor(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED),
        )
        assertEquals(
            MESSAGE_PLAYBACK_DEVICE,
            playbackMessageCodeFor(PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED),
        )
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
            MESSAGE_PLAYBACK_DSD_DECODING,
            MESSAGE_PLAYBACK_FAILED,
        )
        assertEquals(12, codes.size)
    }
}