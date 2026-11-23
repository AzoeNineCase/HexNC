package com.hexnc.music.data.download

import java.io.ByteArrayOutputStream

/**
 * The tags a downloaded track carries. All fields optional except [title].
 *
 * Kept as a tiny value type rather than reusing the library's song model: the downloader has the
 * NetEase song detail in hand when the bytes land, and this is exactly the subset that survives a
 * round trip through an MP3 tag. [coverJpeg] holds the raw JPEG bytes (never a re-encoded image),
 * so [Id3v2] can embed them verbatim.
 */
data class AudioTags(
    val title: String,
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val trackNo: Int = 0,
    val discNo: Int = 0,
    val year: String = "",
    /** Raw JPEG bytes of the cover, or null when no cover is embedded. */
    val coverJpeg: ByteArray? = null,
)

/**
 * Writer for the subset of ID3v2.3 that a downloaded MP3 needs: the text frames every player shows,
 * plus the front cover.
 *
 * The output is deliberately exact and deterministic — 2.3 (not 2.4), UTF-16 text, no padding, no
 * timestamps — so the same [AudioTags] always produce the same bytes. That makes `buildTag` safe to
 * use as its own verification oracle: [Id3v2Reader] reads back what was written, which is how the
 * downloader proves a finished file is intact before it reports success.
 */
object Id3v2 {

    /** "ID3" + version/revision/flags + four syncsafe size bytes. */
    private const val HEADER_SIZE = 10

    /** Four-character id + four-byte big-endian size + two flag bytes. */
    private const val FRAME_HEADER_SIZE = 10

    private const val ENC_LATIN1 = 0
    private const val ENC_UTF16_BOM = 1

    private const val PICTURE_TYPE_FRONT_COVER = 0x03

    private const val JPEG_MIME = "image/jpeg"

    /** Builds a complete ID3v2.3 tag (header + frames) for [tags]. */
    fun buildTag(tags: AudioTags): ByteArray {
        val frames = ByteArrayOutputStream(256)

        writeTextFrame(frames, "TIT2", tags.title)
        writeTextFrame(frames, "TPE1", tags.artist)
        writeTextFrame(frames, "TALB", tags.album)
        writeTextFrame(frames, "TPE2", tags.albumArtist)

        // TRCK/TPOS are plain numbers here, never "n/total": the total is not part of AudioTags and
        // inventing one would make the value disagree with what the player shows.
        if (tags.trackNo > 0) writeTextFrame(frames, "TRCK", tags.trackNo.toString())
        if (tags.discNo > 0) writeTextFrame(frames, "TPOS", tags.discNo.toString())

        val year = tags.year.trim()
        if (year.isNotEmpty()) {
            writeTextFrame(frames, "TYER", if (year.length > 4) year.substring(0, 4) else year)
        }

        val cover = tags.coverJpeg
        if (cover != null && cover.isNotEmpty()) {
            writeFrame(frames, "APIC", apicBody(cover))
        }

        val frameBytes = frames.toByteArray()
        val out = ByteArray(HEADER_SIZE + frameBytes.size)
        out[0] = 'I'.code.toByte()
        out[1] = 'D'.code.toByte()
        out[2] = '3'.code.toByte()
        out[3] = 3 // major: ID3v2.3
        out[4] = 0 // revision
        out[5] = 0 // flags: no unsynchronisation, no extended header, no footer
        writeSyncsafe(out, 6, frameBytes.size)
        System.arraycopy(frameBytes, 0, out, HEADER_SIZE, frameBytes.size)
        return out
    }

    /**
     * Prepends [tag] to an MP3 payload only when it does not already begin with an ID3 tag;
     * returns the payload unchanged otherwise. Never strips an existing tag.
     *
     * The check is the three-byte prefix, not [Id3v2Reader.hasTag]'s well-formedness test: a file
     * that opens with "ID3" already carries a tag as far as every reader is concerned, even if its
     * header is damaged, and stacking a second tag in front of it would only bury the damage.
     * Returning the *same array instance* for the already-tagged case is intentional: the caller
     * can then skip the write entirely rather than copying a multi-megabyte buffer for nothing.
     */
    fun attach(mp3: ByteArray, tag: ByteArray): ByteArray {
        if (tag.isEmpty() || beginsWithId3(mp3)) return mp3
        val out = ByteArray(tag.size + mp3.size)
        System.arraycopy(tag, 0, out, 0, tag.size)
        System.arraycopy(mp3, 0, out, tag.size, mp3.size)
        return out
    }

    private fun beginsWithId3(bytes: ByteArray): Boolean =
        bytes.size >= 3 &&
            bytes[0] == 'I'.code.toByte() &&
            bytes[1] == 'D'.code.toByte() &&
            bytes[2] == '3'.code.toByte()

    private fun writeTextFrame(out: ByteArrayOutputStream, id: String, value: String) {
        // An empty optional field is omitted entirely: a zero-length TALB is worse than no TALB,
        // because players render an empty album name instead of falling back to the file name.
        if (value.isEmpty()) return
        writeFrame(out, id, textBody(value))
    }

    private fun textBody(value: String): ByteArray {
        val body = ByteArrayOutputStream(value.length * 2 + 5)
        body.write(ENC_UTF16_BOM)
        // Java's "UTF-16" encoder emits a big-endian BOM; ID3 wants one explicit BOM, so write the
        // little-endian BOM by hand and encode the payload as UTF-16LE.
        body.write(0xFF)
        body.write(0xFE)
        body.write(value.toByteArray(Charsets.UTF_16LE))
        body.write(0x00)
        body.write(0x00)
        return body.toByteArray()
    }

    private fun apicBody(jpeg: ByteArray): ByteArray {
        val mime = JPEG_MIME.toByteArray(Charsets.US_ASCII)
        val body = ByteArrayOutputStream(jpeg.size + mime.size + 4)
        // APIC mixes encodings: the text encoding byte applies only to the description, while the
        // MIME type is always a NUL-terminated ISO-8859-1 string.
        body.write(ENC_LATIN1)
        body.write(mime)
        body.write(0x00)
        body.write(PICTURE_TYPE_FRONT_COVER)
        body.write(0x00) // empty description
        body.write(jpeg)
        return body.toByteArray()
    }

    private fun writeFrame(out: ByteArrayOutputStream, id: String, body: ByteArray) {
        require(id.length == 4) { "ID3 frame ids are four characters: $id" }
        val header = ByteArray(FRAME_HEADER_SIZE)
        for (i in 0 until 4) header[i] = id[i].code.toByte()
        writeInt32(header, 4, body.size)
        header[8] = 0 // status flags
        header[9] = 0 // format flags
        out.write(header)
        out.write(body)
    }

    private fun writeInt32(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value ushr 24) and 0xFF).toByte()
        target[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 3] = (value and 0xFF).toByte()
    }

    /** Syncsafe integers keep seven bits per byte so a size can never look like a frame marker. */
    private fun writeSyncsafe(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value ushr 21) and 0x7F).toByte()
        target[offset + 1] = ((value ushr 14) and 0x7F).toByte()
        target[offset + 2] = ((value ushr 7) and 0x7F).toByte()
        target[offset + 3] = (value and 0x7F).toByte()
    }

    internal fun readSyncsafe(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)

    internal fun readInt32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}

/**
 * Minimal ID3v2.3/2.4 reader. Returns null when [bytes] does not start with an ID3 tag.
 *
 * This exists to verify a tag that HexNC itself just wrote, and to recover tags from files the
 * folder importer meets. It is therefore forgiving by design: it understands both the v2.3
 * (plain 32-bit) and v2.4 (syncsafe) frame size encodings, decodes UTF-16/ISO-8859-1/UTF-8 text,
 * skips frames it does not know, and never throws on a truncated or malformed buffer — a partial
 * tag yields whatever fields could be read.
 */
object Id3v2Reader {

    private const val HEADER_SIZE = 10
    private const val FRAME_HEADER_SIZE = 10

    /** Whether the buffer starts with a well-formed ID3v2 header. */
    fun hasTag(bytes: ByteArray): Boolean {
        if (bytes.size < HEADER_SIZE) return false
        if (bytes[0] != 'I'.code.toByte()) return false
        if (bytes[1] != 'D'.code.toByte()) return false
        if (bytes[2] != '3'.code.toByte()) return false
        val major = bytes[3].toInt() and 0xFF
        if (major < 2 || major > 4) return false
        if ((bytes[4].toInt() and 0xFF) == 0xFF) return false
        // The tag size must itself be syncsafe; a header whose size bytes have the high bit set is
        // not something any writer produces, and treating it as one would mis-skip real audio.
        for (i in 6..9) if ((bytes[i].toInt() and 0x80) != 0) return false
        return true
    }

    fun read(bytes: ByteArray): AudioTags? {
        if (!hasTag(bytes)) return null

        val major = bytes[3].toInt() and 0xFF
        val flags = bytes[5].toInt() and 0xFF
        val declaredEnd = HEADER_SIZE.toLong() + Id3v2.readSyncsafe(bytes, 6).toLong()
        val end = minOf(bytes.size.toLong(), declaredEnd).toInt()

        var pos = HEADER_SIZE
        if (flags and 0x40 != 0) pos = skipExtendedHeader(bytes, pos, end, major)

        var title = ""
        var artist = ""
        var album = ""
        var albumArtist = ""
        var trackNo = 0
        var discNo = 0
        var year = ""
        var cover: ByteArray? = null
        var coverType = -1

        while (pos + FRAME_HEADER_SIZE <= end) {
            if (bytes[pos].toInt() == 0) break // zero padding starts here
            val id = frameId(bytes, pos) ?: break // not a frame id: stop rather than guess
            val plainSize = Id3v2.readInt32(bytes, pos + 4)
            val syncSize = Id3v2.readSyncsafe(bytes, pos + 4)

            // v2.4 writes frame sizes syncsafe, v2.3 plain. If the authoritative reading runs past
            // the buffer, the other reading is tried — that recovers files whose header version
            // disagrees with the size encoding actually used.
            var size = if (major >= 4) syncSize else plainSize
            val bodyStart = pos + FRAME_HEADER_SIZE
            if (size < 0 || bodyStart.toLong() + size > end) {
                val alternate = if (major >= 4) plainSize else syncSize
                size = if (alternate >= 0 && bodyStart.toLong() + alternate <= end) alternate else end - bodyStart
            }
            val bodyEnd = bodyStart + size

            when (id) {
                "TIT2" -> title = decodeText(bytes, bodyStart, bodyEnd)
                "TPE1" -> artist = decodeText(bytes, bodyStart, bodyEnd)
                "TALB" -> album = decodeText(bytes, bodyStart, bodyEnd)
                "TPE2" -> albumArtist = decodeText(bytes, bodyStart, bodyEnd)
                "TRCK" -> trackNo = parseIndex(decodeText(bytes, bodyStart, bodyEnd))
                "TPOS" -> discNo = parseIndex(decodeText(bytes, bodyStart, bodyEnd))
                // TYER is the v2.3 frame; v2.4 replaced it with TDRC, so accept either, in that
                // order of preference.
                "TYER" -> year = yearValue(decodeText(bytes, bodyStart, bodyEnd))
                "TDRC" -> if (year.isEmpty()) year = yearValue(decodeText(bytes, bodyStart, bodyEnd))
                "APIC" -> {
                    val apic = readApic(bytes, bodyStart, bodyEnd)
                    // A front-cover frame wins over whatever came first.
                    if (apic != null && (cover == null || (apic.type == 0x03 && coverType != 0x03))) {
                        cover = apic.jpeg
                        coverType = apic.type
                    }
                }
            }

            pos = if (bodyEnd > pos) bodyEnd else pos + FRAME_HEADER_SIZE
        }

        return AudioTags(
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            trackNo = trackNo,
            discNo = discNo,
            year = year,
            coverJpeg = cover,
        )
    }

    private class Apic(val type: Int, val jpeg: ByteArray)

    private fun skipExtendedHeader(bytes: ByteArray, start: Int, end: Int, major: Int): Int {
        if (start + 4 > end) return end
        return if (major >= 4) {
            val size = Id3v2.readSyncsafe(bytes, start)
            if (size <= 0 || start.toLong() + size > end) end else start + size
        } else {
            val size = Id3v2.readInt32(bytes, start)
            if (size < 0 || start.toLong() + 4 + size > end) end else start + 4 + size
        }
    }

    private fun frameId(bytes: ByteArray, offset: Int): String? {
        for (i in 0 until 4) {
            val c = bytes[offset + i].toInt() and 0xFF
            val valid = (c in 0x41..0x5A) || (c in 0x30..0x39)
            if (!valid) return null
        }
        return String(bytes, offset, 4, Charsets.US_ASCII)
    }

    /**
     * Decodes a text frame body. The first byte is the encoding, the rest is the string including
     * its optional terminator; everything after the first NUL is padding and is dropped.
     */
    private fun decodeText(bytes: ByteArray, from: Int, to: Int): String {
        if (to <= from) return ""
        val encoding = bytes[from].toInt() and 0xFF
        val start = from + 1
        val length = to - start
        if (length <= 0) return ""
        val charset = when (encoding) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16 // BOM-aware, which is what encoding 1 promises
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        return try {
            String(bytes, start, length, charset).substringBefore('\u0000').trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun parseIndex(value: String): Int =
        value.substringBefore('/').trim().toIntOrNull()?.coerceAtLeast(0) ?: 0

    private fun yearValue(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.length > 4) trimmed.substring(0, 4) else trimmed
    }

    private fun readApic(bytes: ByteArray, from: Int, to: Int): Apic? {
        var pos = from
        if (pos >= to) return null
        val encoding = bytes[pos].toInt() and 0xFF
        pos++

        val mimeEnd = indexOfNul(bytes, pos, to, dualByte = false)
        pos = if (mimeEnd < 0) to else mimeEnd + 1
        if (pos >= to) return null

        val pictureType = bytes[pos].toInt() and 0xFF
        pos++

        val descriptionEnd = indexOfNul(bytes, pos, to, dualByte = encoding == 1 || encoding == 2)
        pos = if (descriptionEnd < 0) to else descriptionEnd + if (encoding == 1 || encoding == 2) 2 else 1
        if (pos >= to) return null

        return Apic(pictureType, bytes.copyOfRange(pos, to))
    }

    /** Index of the terminator, or -1. UTF-16 descriptions are terminated by two zero bytes on an even boundary. */
    private fun indexOfNul(bytes: ByteArray, from: Int, to: Int, dualByte: Boolean): Int {
        var i = from
        if (dualByte) {
            while (i + 1 < to) {
                if (bytes[i].toInt() == 0 && bytes[i + 1].toInt() == 0) return i
                i += 2
            }
            return -1
        }
        while (i < to) {
            if (bytes[i].toInt() == 0) return i
            i++
        }
        return -1
    }
}