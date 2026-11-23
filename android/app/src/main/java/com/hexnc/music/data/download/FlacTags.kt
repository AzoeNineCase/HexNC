package com.hexnc.music.data.download

import java.io.ByteArrayOutputStream

/**
 * Writes Vorbis comments and the front cover into a downloaded FLAC stream.
 *
 * A FLAC file is a chain of metadata blocks that sits in front of the audio frames, so tagging is a
 * rebuild rather than an append: [withTags] copies STREAMINFO and every block HexNC does not own
 * byte for byte, replaces the VORBIS_COMMENT (and PICTURE) blocks, and copies the frames unchanged.
 * The lossless payload the user downloaded therefore never passes through a codec again.
 *
 * Written against the format instead of reusing jaudiotagger because the downloader only needs to
 * write, and a dependency-free writer is deterministic: the same [AudioTags] always produce the
 * same bytes. [read] parses that output back, which is how the tests — and the downloader's own
 * sanity check — prove the fields landed instead of trusting the offsets the writer reported.
 */
object FlacTags {

    /** A metadata block header: one flags/type byte and a three-byte big-endian payload length. */
    private const val HEADER_SIZE = 4

    private const val LAST_FLAG = 0x80
    private const val TYPE_MASK = 0x7F

    private const val TYPE_STREAMINFO = 0
    private const val TYPE_PADDING = 1
    private const val TYPE_VORBIS_COMMENT = 4
    private const val TYPE_PICTURE = 6

    private const val PICTURE_TYPE_FRONT_COVER = 3
    private const val JPEG_MIME = "image/jpeg"

    /** Every FLAC comment block names its writer; this is what a reader sees in the vendor field. */
    private const val VENDOR = "HexNC"

    private val MAGIC = "fLaC".toByteArray(Charsets.US_ASCII)

    /**
     * Returns a copy of [flac] with its VORBIS_COMMENT (and, when [AudioTags.coverJpeg] is present,
     * a PICTURE) block written. Audio frames are copied byte for byte.
     */
    fun withTags(flac: ByteArray, tags: AudioTags): ByteArray {
        val file = parse(flac)
        val kept = file.blocks.filter {
            it.type != TYPE_VORBIS_COMMENT && it.type != TYPE_PICTURE && it.type != TYPE_PADDING
        }

        // The comment block is always written, even when every field is blank: the vendor string is
        // still meaningful, and a file with no comment block at all is what a retagged file looked
        // like before HexNC touched it.
        val comment = Block(TYPE_VORBIS_COMMENT, commentPayload(tags))
        val cover = tags.coverJpeg?.takeIf { it.isNotEmpty() }
        val picture = cover?.let { Block(TYPE_PICTURE, picturePayload(it)) }
        val fresh = listOfNotNull(comment, picture)

        // The space the dropped comment/picture/padding blocks occupied. Whatever the new blocks do
        // not use is given back either as padding or as a shorter file.
        val audioSize = flac.size - file.audioStart
        val keptSize = kept.sumOf { HEADER_SIZE + it.payload.size }
        val freshSize = fresh.sumOf { HEADER_SIZE + it.payload.size }
        val reclaimed = flac.size - MAGIC.size - keptSize - audioSize
        val leftover = reclaimed - freshSize

        // Only a file that already had padding gets it back. Synthesising one for a file without
        // padding would not preserve the size either, so it would only add bytes the caller cannot
        // account for. A leftover of one to three bytes cannot hold a block header and is dropped
        // rather than written as a malformed block.
        val hadPadding = file.blocks.any { it.type == TYPE_PADDING }
        val padding = if (hadPadding && leftover >= HEADER_SIZE) {
            Block(TYPE_PADDING, ByteArray(leftover - HEADER_SIZE))
        } else {
            null
        }

        return assemble(flac, file.audioStart, kept + fresh + listOfNotNull(padding))
    }

    /** What [withTags] wrote, so a test (and the UI) can prove the tags landed. */
    fun read(flac: ByteArray): FlacTagInfo {
        val file = parse(flac)
        val fields = file.blocks.firstOrNull { it.type == TYPE_VORBIS_COMMENT }
            ?.let { parseFields(it.payload) }
            .orEmpty()

        return FlacTagInfo(
            title = field(fields, "TITLE"),
            artist = field(fields, "ARTIST"),
            album = field(fields, "ALBUM"),
            albumArtist = field(fields, "ALBUMARTIST"),
            trackNo = index(fields["TRACKNUMBER"]),
            discNo = index(fields["DISCNUMBER"]),
            year = field(fields, "DATE"),
            coverBytes = coverBytes(file.blocks),
        )
    }

    private class Block(val type: Int, val payload: ByteArray)

    private class FlacFile(val blocks: List<Block>, val audioStart: Int)

    /**
     * Splits [flac] into metadata blocks plus the offset where the audio begins. Every length is
     * validated against the buffer here, because a length field that is off by one turns a reader
     * into a corruptor of the audio that follows.
     */
    private fun parse(flac: ByteArray): FlacFile {
        require(flac.size >= MAGIC.size && MAGIC.indices.all { flac[it] == MAGIC[it] }) {
            "Not a FLAC stream: the buffer does not start with fLaC"
        }

        val blocks = ArrayList<Block>()
        var pos = MAGIC.size
        var last = false
        while (!last) {
            require(pos + HEADER_SIZE <= flac.size) { "Truncated FLAC metadata header at byte $pos" }
            val header = flac[pos].toInt() and 0xFF
            last = header and LAST_FLAG != 0
            val length = readBeUInt24(flac, pos + 1)
            val start = pos + HEADER_SIZE
            require(start.toLong() + length <= flac.size) {
                "FLAC metadata block at byte $pos claims $length bytes, but only ${flac.size - start} remain"
            }
            blocks += Block(header and TYPE_MASK, flac.copyOfRange(start, start + length))
            pos = start + length
        }

        require(blocks.first().type == TYPE_STREAMINFO) {
            "FLAC metadata must begin with STREAMINFO, not block type ${blocks.first().type}"
        }
        return FlacFile(blocks, pos)
    }

    /** Concatenates [blocks] in order, flags the final one as last, and appends the audio verbatim. */
    private fun assemble(original: ByteArray, audioStart: Int, blocks: List<Block>): ByteArray {
        val audioSize = original.size - audioStart
        val metadataSize = blocks.sumOf { HEADER_SIZE + it.payload.size }
        val out = ByteArray(MAGIC.size + metadataSize + audioSize)
        MAGIC.copyInto(out)

        var pos = MAGIC.size
        for ((index, block) in blocks.withIndex()) {
            // Exactly one block may carry isLast, and it must be the final one: the audio stream is
            // located by "the byte after the last metadata block", so a stray earlier flag would
            // make players start decoding in the middle of the metadata.
            val last = index == blocks.lastIndex
            out[pos] = ((if (last) LAST_FLAG else 0) or block.type).toByte()
            writeBeUInt24(out, pos + 1, block.payload.size)
            block.payload.copyInto(out, pos + HEADER_SIZE)
            pos += HEADER_SIZE + block.payload.size
        }
        original.copyInto(out, pos, audioStart, original.size)

        check(pos + audioSize == out.size) { "FLAC rebuild lost ${out.size - pos - audioSize} bytes" }
        return out
    }

    private fun commentPayload(tags: AudioTags): ByteArray {
        val fields = vorbisFields(tags)
        val vendor = VENDOR.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream(vendor.size + 8 + fields.sumOf { 4 + it.toByteArray(Charsets.UTF_8).size })
        writeLeUInt32(out, vendor.size)
        out.write(vendor)
        writeLeUInt32(out, fields.size)
        for (field in fields) {
            val bytes = field.toByteArray(Charsets.UTF_8)
            writeLeUInt32(out, bytes.size)
            out.write(bytes)
        }
        return out.toByteArray()
    }

    private fun vorbisFields(tags: AudioTags): List<String> = buildList {
        fun add(key: String, value: String?) {
            val text = value?.trim().orEmpty()
            // An empty KEY= is worse than a missing key: players render a blank album instead of
            // falling back to the file name, and it reads as data the user typed on purpose.
            if (text.isNotEmpty()) add("$key=$text")
        }

        add("TITLE", tags.title)
        add("ARTIST", tags.artist)
        add("ALBUM", tags.album)
        add("ALBUMARTIST", tags.albumArtist)
        if (tags.trackNo > 0) add("TRACKNUMBER", tags.trackNo.toString())
        if (tags.discNo > 0) add("DISCNUMBER", tags.discNo.toString())
        add("DATE", tags.year)
    }

    /**
     * Builds a PICTURE block payload. Width, height, colour depth and palette size are written as 0,
     * which the format defines as "unknown": the downloader hands over a JPEG and does not decode it,
     * so writing real dimensions would mean parsing the image's SOF marker and could only introduce
     * a disagreement with the bytes actually embedded.
     */
    private fun picturePayload(jpeg: ByteArray): ByteArray {
        val mime = JPEG_MIME.toByteArray(Charsets.US_ASCII)
        val out = ByteArray(8 * 4 + mime.size + jpeg.size)
        var pos = 0
        pos = writeBeUInt32(out, pos, PICTURE_TYPE_FRONT_COVER)
        pos = writeBeUInt32(out, pos, mime.size)
        mime.copyInto(out, pos)
        pos += mime.size
        pos = writeBeUInt32(out, pos, 0) // empty description
        pos = writeBeUInt32(out, pos, 0) // width
        pos = writeBeUInt32(out, pos, 0) // height
        pos = writeBeUInt32(out, pos, 0) // colour depth
        pos = writeBeUInt32(out, pos, 0) // palette size
        pos = writeBeUInt32(out, pos, jpeg.size)
        jpeg.copyInto(out, pos)
        pos += jpeg.size
        check(pos == out.size) { "PICTURE block is ${out.size} bytes but $pos were written" }
        return out
    }

    private fun coverBytes(blocks: List<Block>): Int {
        val pictures = blocks.filter { it.type == TYPE_PICTURE }
        // A front cover wins over whatever illustration came first, matching Id3v2Reader's choice.
        val front = pictures.firstOrNull {
            it.payload.size >= 4 && readBeUInt32(it.payload, 0) == PICTURE_TYPE_FRONT_COVER
        } ?: pictures.firstOrNull()
        return front?.let { pictureDataSize(it.payload) } ?: 0
    }

    /**
     * Length of the embedded image data. The block layout is walked field by field — never at fixed
     * offsets — because the MIME and description strings are variable length.
     */
    private fun pictureDataSize(payload: ByteArray): Int {
        var pos = 4 // picture type
        // MIME type, then description: each is a big-endian length followed by that many bytes.
        repeat(2) {
            if (payload.size - pos < 4) return 0
            val length = readBeUInt32(payload, pos)
            pos += 4
            if (length < 0 || length > payload.size - pos) return 0
            pos += length
        }
        // width, height, colour depth, palette size — four fixed 32-bit words we do not interpret.
        if (payload.size - pos < 16) return 0
        pos += 16
        if (payload.size - pos < 4) return 0
        val dataLength = readBeUInt32(payload, pos)
        pos += 4
        if (dataLength < 0) return 0
        return minOf(dataLength, payload.size - pos)
    }

    /**
     * Reads the comment payload into an uppercase-keyed map. Unknown or malformed fields are skipped
     * instead of aborting: a foreign tagger may add keys HexNC has never heard of, and the fields
     * HexNC does know are still worth recovering.
     */
    private fun parseFields(payload: ByteArray): Map<String, String> {
        if (payload.size < 8) return emptyMap()

        val vendorLength = readLeUInt32(payload, 0)
        if (vendorLength < 0 || vendorLength > payload.size - 8) return emptyMap()
        var pos = 4 + vendorLength
        val count = readLeUInt32(payload, pos)
        pos += 4

        val fields = LinkedHashMap<String, String>()
        var remaining = count
        while (remaining > 0 && payload.size - pos >= 4) {
            val length = readLeUInt32(payload, pos)
            pos += 4
            if (length < 0 || length > payload.size - pos) break
            val text = String(payload, pos, length, Charsets.UTF_8)
            pos += length
            remaining--
            val equals = text.indexOf('=')
            if (equals <= 0) continue
            // The spec only recommends uppercase keys, so matching is case-insensitive; a field
            // order different from ours is equally unremarkable and handled by the map.
            fields.putIfAbsent(text.substring(0, equals).uppercase(), text.substring(equals + 1))
        }
        return fields
    }

    private fun field(fields: Map<String, String>, key: String): String? =
        fields[key]?.trim()?.takeIf { it.isNotEmpty() }

    /** `3/12` is a legal TRACKNUMBER written by other taggers; the disc/track number is the 3. */
    private fun index(value: String?): Int? =
        value?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it > 0 }

    private fun readLeUInt32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun readBeUInt32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun readBeUInt24(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            (bytes[offset + 2].toInt() and 0xFF)

    private fun writeBeUInt32(bytes: ByteArray, offset: Int, value: Int): Int {
        bytes[offset] = ((value ushr 24) and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 3] = (value and 0xFF).toByte()
        return offset + 4
    }

    private fun writeBeUInt24(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 2] = (value and 0xFF).toByte()
    }

    private fun writeLeUInt32(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }
}

/** Fields [FlacTags.read] recovered from a FLAC file, or null when a field was never written. */
data class FlacTagInfo(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNo: Int? = null,
    val discNo: Int? = null,
    val year: String? = null,
    val coverBytes: Int = 0,
)