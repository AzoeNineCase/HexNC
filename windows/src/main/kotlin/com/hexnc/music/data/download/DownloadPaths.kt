package com.hexnc.music.data.download

import java.util.Locale

/**
 * Naming rules for the files a download produces.
 *
 * Two platforms write into these paths — Windows via `java.io` and Android via its own file APIs —
 * and they do not agree on which characters are legal. Windows additionally refuses a trailing dot
 * or space, which is the failure that only shows up when a song is literally called "Mr." or an
 * album ends in a space: the write fails with an `IOException` that says nothing about the name.
 * Everything here is therefore normalized before it touches the disk, and the result is a relative
 * path (`artist/album/NN - title.ext`) that both platforms accept verbatim.
 */
object DownloadPaths {

    /** Characters neither Windows nor Android accepts in a file name. */
    private const val ILLEGAL = "\\/:*?\"<>|"

    /**
     * Characters Windows and Android both reject in a file name, replaced by '_'.
     *
     * Runs of illegal characters collapse into a single '_', so a title like "AC/DC: Live" becomes
     * "AC_DC_ Live" rather than "AC__DC__ Live" — and a segment made only of illegal characters
     * becomes the single "_" that both platforms accept as a name.
     */
    fun sanitizeSegment(raw: String, maxLength: Int = 120): String {
        if (maxLength <= 0) return "_"
        val sb = StringBuilder(raw.length)
        var previousWasIllegal = false
        for (ch in raw) {
            val illegal = ch < ' ' || ILLEGAL.indexOf(ch) >= 0
            if (illegal) {
                if (!previousWasIllegal) sb.append('_')
                previousWasIllegal = true
            } else {
                sb.append(ch)
                previousWasIllegal = false
            }
        }
        var result = sb.toString().trimEnd(' ', '.')
        if (result.length > maxLength) {
            // Truncation can expose a trailing dot/space that was legal mid-string, so trim again;
            // a name that truncates down to nothing still has to be a usable "_".
            result = result.substring(0, maxLength).trimEnd(' ', '.')
        }
        return result.ifEmpty { "_" }
    }

    /**
     * `<artist>/<album>/NN - <title>.<ext>`, with '/' separators and no leading separator.
     * Missing artist -> "未知艺术家", missing album -> "未知专辑" (English: "Unknown artist" / "Unknown album"
     * — take the fallbacks as parameters with those defaults so callers can localise).
     * Track number is zero-padded to 2 digits; 0 -> "00".
     */
    fun relativePath(
        artist: String,
        album: String,
        trackNo: Int,
        title: String,
        extension: String,
        unknownArtist: String = "Unknown artist",
        unknownAlbum: String = "Unknown album",
    ): String {
        val artistSegment = sanitizeSegment(artist.trim().ifEmpty { unknownArtist })
        val albumSegment = sanitizeSegment(album.trim().ifEmpty { unknownAlbum })
        val track = if (trackNo > 0) trackNo else 0
        val trackSegment = track.toString().padStart(2, '0')
        val titleSegment = sanitizeSegment(title.trim())
        val ext = extension.trim().trimStart('.').lowercase(Locale.ROOT).ifEmpty { "mp3" }
        return "$artistSegment/$albumSegment/$trackSegment - $titleSegment.$ext"
    }

    /**
     * `<base> (2).<ext>`, `(3)`, … choosing the first name [exists] reports false for.
     *
     * The counter goes before the extension and after any directory, so "A/B.mp3" becomes
     * "A/B (2).mp3" and the directory part is never touched. A name without an extension simply
     * gets the counter appended.
     */
    fun uniquePath(desired: String, exists: (String) -> Boolean): String {
        if (!exists(desired)) return desired
        val slash = desired.lastIndexOf('/')
        val dot = desired.lastIndexOf('.')
        val splitAt = if (dot > slash + 1) dot else desired.length
        val base = desired.substring(0, splitAt)
        val suffix = desired.substring(splitAt) // "" or ".ext"
        var counter = 2
        while (true) {
            val candidate = "$base ($counter)$suffix"
            if (!exists(candidate)) return candidate
            counter++
        }
    }

    /** Extension for the container NetEase reports (`mp3`, `flac`, `m4a`, …), defaulting to `mp3`. */
    fun extensionFor(containerType: String?): String {
        return when (containerType?.trim()?.lowercase(Locale.ROOT)) {
            "mp3" -> "mp3"
            "flac" -> "flac"
            // AAC is normally delivered in an MP4/M4A container; ExoPlayer and Java Sound both key
            // off the container, not the codec.
            "m4a", "aac" -> "m4a"
            else -> "mp3"
        }
    }
}