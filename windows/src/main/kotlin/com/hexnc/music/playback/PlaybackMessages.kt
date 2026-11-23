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

/** The container or codec is known-unsupported on this platform (Opus, WMA, DST-compressed DSD). */
internal const val MESSAGE_PLAYBACK_UNSUPPORTED_FORMAT = "playback_unsupported_format"

/** A decoder exists but rejected the bitstream — a truncated, corrupt or DRM-wrapped file. */
internal const val MESSAGE_PLAYBACK_DECODE = "playback_decode"

/** The audio device would not open, or stopped accepting samples mid-track. */
internal const val MESSAGE_PLAYBACK_DEVICE = "playback_device"

/** The file exists but the app is not allowed to read it. */
internal const val MESSAGE_PLAYBACK_ACCESS = "playback_access"

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

    /** The one legacy decoder code, kept because stored playlists and tests still mention it. */
    const val DECODING_FAILED = 3001

    /** The container or codec is not supported on this platform — the user can act on the format. */
    const val UNSUPPORTED_FORMAT = 3002

    /** A decoder exists but rejected the bytes: truncated, corrupt, or DRM-wrapped. */
    const val DECODE_ERROR = 3003

    /** No output device would open, or it stopped accepting samples. Retrying usually helps. */
    const val DEVICE_ERROR = 3004

    /** The file is there but unreadable — permissions, a lock, or a removed drive. */
    const val FILE_ACCESS = 3005
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

    // The file was moved or deleted between the scan and the tap.
    PlaybackErrorCode.IO_FILE_NOT_FOUND -> MESSAGE_PLAYBACK_FILE_MISSING

    // It is still there, but the app is not allowed to read it: permissions, a lock, or a removed
    // drive. Worth a different sentence from "missing", because the fix is different.
    PlaybackErrorCode.IO_NO_PERMISSION,
    PlaybackErrorCode.FILE_ACCESS,
    -> MESSAGE_PLAYBACK_ACCESS

    // The codec is known-unsupported here (Opus, WMA, DST). Nothing the user plays differently will
    // help, so say the format itself is the problem.
    PlaybackErrorCode.UNSUPPORTED_FORMAT -> MESSAGE_PLAYBACK_UNSUPPORTED_FORMAT

    // A decoder existed and rejected the bytes: truncated, corrupt, or DRM-wrapped.
    PlaybackErrorCode.DECODING_FAILED,
    PlaybackErrorCode.DECODE_ERROR,
    -> MESSAGE_PLAYBACK_DECODE

    // Hardware, not the file. Retrying, or picking another output device, is the way out.
    PlaybackErrorCode.DEVICE_ERROR -> MESSAGE_PLAYBACK_DEVICE

    else -> MESSAGE_PLAYBACK_FAILED
}
