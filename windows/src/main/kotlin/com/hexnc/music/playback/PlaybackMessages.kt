package com.hexnc.music.playback

/** Nothing in the queue could be turned into a playable file. */
internal const val MESSAGE_NO_PLAYABLE = "no_playable"

/** The URL was refused because it is plain HTTP. */
internal const val MESSAGE_PLAYBACK_CLEARTEXT = "playback_cleartext"

/** The host could not be reached, or answered with an HTTP error. */
internal const val MESSAGE_PLAYBACK_NETWORK = "playback_network"

/** The file is gone, or the app lost the permission it needs to read it. */
internal const val MESSAGE_PLAYBACK_FILE_MISSING = "playback_file_missing"

/** There is no decoder for this container or bitstream (DSD, exotic hi-res, DRM). */
internal const val MESSAGE_PLAYBACK_UNSUPPORTED = "playback_unsupported"

/** A local `.dsf`/`.dff` track was skipped before the decoder could fail on it. */
internal const val MESSAGE_PLAYBACK_DSD = "playback_dsd"

/** The track downloaded but could not be decoded, or failed for any other reason. */
internal const val MESSAGE_PLAYBACK_FAILED = "playback_failed"

/**
 * The player error codes this app distinguishes.
 *
 * Android got these from ExoPlayer (`PlaybackException.ERROR_CODE_IO_*`). Desktop playback is a
 * plain Java Sound pipeline with no ExoPlayer, so the numbers are reproduced here — they keep the
 * transport's error vocabulary, and [playbackMessageCodeFor] identical to the Android build.
 */
object PlaybackErrorCode {
    const val IO_NETWORK_CONNECTION_FAILED = 2001
    const val IO_NETWORK_CONNECTION_TIMEOUT = 2002
    const val IO_BAD_HTTP_STATUS = 2004
    const val IO_CLEARTEXT_NOT_PERMITTED = 2007
    const val IO_FILE_NOT_FOUND = 2005
    const val IO_NO_PERMISSION = 2006
    const val DECODING_FAILED = 3001
}

/**
 * Maps a player error code onto one of the codes the host knows how to translate.
 *
 * Kept as a pure function so the mapping can be unit-tested without an audio device; the cases are
 * the ones the user can actually act on — an unreachable host, a refused cleartext URL, a file that
 * is no longer readable, and a format this machine simply cannot decode — and everything else
 * deliberately collapses to a single "playback failed" rather than leaking an English decoder string
 * into a Chinese UI.
 */
internal fun playbackMessageCodeFor(errorCode: Int): String = when (errorCode) {
    PlaybackErrorCode.IO_CLEARTEXT_NOT_PERMITTED -> MESSAGE_PLAYBACK_CLEARTEXT
    PlaybackErrorCode.IO_NETWORK_CONNECTION_FAILED,
    PlaybackErrorCode.IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackErrorCode.IO_BAD_HTTP_STATUS,
    -> MESSAGE_PLAYBACK_NETWORK

    // The file was moved or deleted between the scan and the tap, or it is no longer readable.
    PlaybackErrorCode.IO_FILE_NOT_FOUND,
    PlaybackErrorCode.IO_NO_PERMISSION,
    -> MESSAGE_PLAYBACK_FILE_MISSING

    // Java Sound reports an unsupported codec as a decode failure, which is the one case where the
    // user can act on the format rather than on the file.
    PlaybackErrorCode.DECODING_FAILED,
    -> MESSAGE_PLAYBACK_UNSUPPORTED

    else -> MESSAGE_PLAYBACK_FAILED
}
