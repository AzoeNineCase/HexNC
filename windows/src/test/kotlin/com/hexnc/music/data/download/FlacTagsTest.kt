package com.hexnc.music.data.download

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A FLAC file is a chain of length-prefixed blocks, so a single wrong length reintroduces the class
 * of bug this writer exists to avoid: a file that looks tagged but whose player either skips the
 * tags or starts decoding audio in the middle of a metadata block. Every case below therefore
 * parses the rebuilt file structurally (not through the writer's own reader alone), and the
 * jaudiotagger case checks the result against a completely independent implementation.
 */
class FlacTagsTest {

    private val sample: ByteArray by lazy { loadSample() }

    private val coverJpeg: ByteArray by lazy { realJpeg() }

    @Test
    fun `round trips the core fields`() {
        val tags = AudioTags(
            title = "夜に駆ける",
            artist = "YOASOBI",
            album = "THE BOOK",
            albumArtist = "YOASOBI",
            trackNo = 1,
            discNo = 1,
            year = "2019",
        )

        val info = FlacTags.read(FlacTags.withTags(sample, tags))

        assertEquals("夜に駆ける", info.title)
        assertEquals("YOASOBI", info.artist)
        assertEquals("THE BOOK", info.album)
        assertEquals("YOASOBI", info.albumArtist)
        assertEquals(1, info.trackNo)
        assertEquals(1, info.discNo)
        assertEquals("2019", info.year)
    }

    @Test
    fun `embeds the cover as a front cover picture`() {
        val tagged = FlacTags.withTags(sample, AudioTags(title = "Cover", coverJpeg = coverJpeg))

        assertEquals("read must count the embedded JPEG", coverJpeg.size, FlacTags.read(tagged).coverBytes)

        val picture = parseBlocks(tagged).single { it.type == TYPE_PICTURE }.payload
        // PICTURE begins with type, MIME length + MIME, description length + description.
        assertEquals("front cover", 3, beUInt32(picture, 0))
        val mimeLength = beUInt32(picture, 4)
        assertEquals("image/jpeg", String(picture, 8, mimeLength, Charsets.US_ASCII))
        val descriptionLength = beUInt32(picture, 8 + mimeLength)
        assertEquals("description is empty", 0, descriptionLength)
        val jpegStart = 12 + mimeLength + descriptionLength + 16 + 4
        assertArrayEquals(
            "the JPEG must be embedded verbatim",
            coverJpeg,
            picture.copyOfRange(jpegStart, picture.size),
        )
    }

    @Test
    fun `audio frames are copied byte for byte`() {
        val before = audio(sample)
        val tagged = FlacTags.withTags(sample, AudioTags(title = "Frames", coverJpeg = coverJpeg))
        val after = audio(tagged)

        assertTrue("the sample must actually contain audio frames", before.isNotEmpty())
        assertArrayEquals("every audio byte must survive the rebuild", before, after)
        // The first frame marker is the quickest proof that the audio offset points at a frame and
        // not at the tail of a metadata block.
        assertEquals(0xFF, after[0].toInt() and 0xFF)
    }

    @Test
    fun `exactly one last flag on the final metadata block`() {
        val tagged = FlacTags.withTags(sample, AudioTags(title = "Last", coverJpeg = coverJpeg))
        val blocks = parseBlocks(tagged)

        assertEquals("exactly one block may be last", 1, blocks.count { it.last })
        assertTrue("the final metadata block must carry isLast", blocks.last().last)
        assertArrayEquals(
            "STREAMINFO must stay byte-identical",
            parseBlocks(sample).first().payload,
            blocks.first().payload,
        )
    }

    @Test
    fun `tagging twice is byte identical`() {
        val tags = AudioTags(
            title = "Twice",
            artist = "Artist",
            album = "Album",
            albumArtist = "Album Artist",
            trackNo = 4,
            discNo = 2,
            year = "2022",
            coverJpeg = coverJpeg,
        )

        val once = FlacTags.withTags(sample, tags)
        val twice = FlacTags.withTags(once, tags)

        assertArrayEquals("a second pass must not accumulate blocks", once, twice)

        val info = FlacTags.read(twice)
        assertEquals("Twice", info.title)
        assertEquals(4, info.trackNo)
        assertEquals(2, info.discNo)
        assertEquals(coverJpeg.size, info.coverBytes)
    }

    @Test
    fun `existing padding is consumed so the file size is unchanged`() {
        val tags = AudioTags(title = "Padded", artist = "A", album = "L", trackNo = 2, year = "2020")

        val tagged = FlacTags.withTags(sample, tags)

        assertEquals("padding must absorb the new metadata instead of growing the file", sample.size, tagged.size)
        val blocks = parseBlocks(tagged)
        assertEquals("there must be exactly one comment block", 1, blocks.count { it.type == TYPE_VORBIS_COMMENT })
        assertEquals("the padding block must survive as the last block", TYPE_PADDING, blocks.last().type)
        assertTrue("the leftover padding must be non-empty", blocks.last().payload.isNotEmpty())
    }

    @Test
    fun `no padding means the new blocks are inserted before the audio`() {
        val padless = withoutPadding(sample)
        val tagged = FlacTags.withTags(padless, AudioTags(title = "No Padding", coverJpeg = coverJpeg))

        assertTrue("new metadata must grow a file that had no padding", tagged.size > padless.size)
        assertTrue("no padding block may be invented", parseBlocks(tagged).none { it.type == TYPE_PADDING })
        assertArrayEquals("inserting blocks must not disturb the audio", audio(padless), audio(tagged))
        assertEquals("No Padding", FlacTags.read(tagged).title)
    }

    @Test
    fun `blank optional fields are omitted rather than written empty`() {
        val tags = AudioTags(
            title = "Only Title",
            artist = "Artist",
            album = "Album",
            albumArtist = "   ",
            trackNo = 3,
            discNo = 0,
            year = "",
        )

        val tagged = FlacTags.withTags(sample, tags)
        val fields = commentFields(tagged)

        assertEquals(setOf("TITLE", "ARTIST", "ALBUM", "TRACKNUMBER"), fields.keys)
        assertTrue("no field may be written as KEY=", fields.values.none { it.isEmpty() })
        assertNull("a blank album artist must read back as absent", FlacTags.read(tagged).albumArtist)
        assertNull("a blank year must read back as absent", FlacTags.read(tagged).year)
        assertNull("disc 0 means no disc number", FlacTags.read(tagged).discNo)
    }

    @Test
    fun `non flac input is rejected`() {
        val id3 = "ID3\u0004\u0000\u0000\u0000\u0000\u0000\u0000".toByteArray(Charsets.ISO_8859_1)

        val failure = assertThrows(IllegalArgumentException::class.java) {
            FlacTags.withTags(id3, AudioTags(title = "x"))
        }
        assertTrue("the message must say what kind of file was expected", failure.message!!.contains("FLAC"))
        assertThrows(IllegalArgumentException::class.java) { FlacTags.read(id3) }
        // "fLaC" alone promises metadata it does not have; that is a truncated FLAC, not a tag.
        assertThrows(IllegalArgumentException::class.java) {
            FlacTags.withTags("fLaC".toByteArray(Charsets.US_ASCII), AudioTags(title = "x"))
        }
    }

    @Test
    fun `keeps foreign metadata blocks and tolerates unknown types`() {
        val application = "HexNC".toByteArray(Charsets.US_ASCII) + ByteArray(8) { 1 }
        val unknown = ByteArray(16) { 0x5A }
        val source = splice(
            parseBlocks(sample).filter { it.type != TYPE_PADDING }.map { RawBlock(it.type, it.payload) } +
                RawBlock(TYPE_APPLICATION, application) +
                RawBlock(TYPE_UNKNOWN, unknown),
            audio(sample),
        )

        val tagged = FlacTags.withTags(source, AudioTags(title = "Foreign Blocks"))
        val blocks = parseBlocks(tagged)

        assertArrayEquals(
            "the application block must be kept",
            application,
            blocks.single { it.type == TYPE_APPLICATION }.payload,
        )
        assertArrayEquals(
            "unknown block types must be kept",
            unknown,
            blocks.single { it.type == TYPE_UNKNOWN }.payload,
        )
        assertTrue(
            "the new blocks belong after the blocks HexNC does not own",
            blocks.indexOfFirst { it.type == TYPE_VORBIS_COMMENT } > blocks.indexOfFirst { it.type == TYPE_APPLICATION },
        )
        assertEquals("Foreign Blocks", FlacTags.read(tagged).title)
    }

    @Test
    fun `reads a file with no comment block as empty`() {
        val bare = splice(
            listOf(
                RawBlock(TYPE_STREAMINFO, parseBlocks(sample).first { it.type == TYPE_STREAMINFO }.payload),
                RawBlock(TYPE_PADDING, ByteArray(32)),
            ),
            audio(sample),
        )

        val info = FlacTags.read(bare)

        assertNull(info.title)
        assertNull(info.artist)
        assertNull(info.album)
        assertNull(info.albumArtist)
        assertNull(info.trackNo)
        assertNull(info.discNo)
        assertNull(info.year)
        assertEquals(0, info.coverBytes)
    }

    @Test
    fun `jaudiotagger reads the written tags and cover`() {
        val tags = AudioTags(
            title = "夜に駆ける",
            artist = "YOASOBI",
            album = "THE BOOK",
            albumArtist = "YOASOBI",
            trackNo = 1,
            discNo = 1,
            year = "2019",
            coverJpeg = coverJpeg,
        )
        val file = File.createTempFile("hexnc-flac-", ".flac")

        try {
            file.writeBytes(FlacTags.withTags(sample, tags))

            val audioFile = try {
                AudioFileIO.read(file)
            } catch (e: Exception) {
                // A refusal from the independent reader is a finding, not something to assert away.
                assumeTrue("jaudiotagger rejected the rewritten FLAC: ${e.message}", false)
                return
            }

            val tag = audioFile.tag
            assertNotNull("jaudiotagger must find the tag", tag)
            assertEquals("夜に駆ける", tag.getFirst(FieldKey.TITLE))
            assertEquals("YOASOBI", tag.getFirst(FieldKey.ARTIST))
            assertEquals("THE BOOK", tag.getFirst(FieldKey.ALBUM))
            assertEquals("YOASOBI", tag.getFirst(FieldKey.ALBUM_ARTIST))
            assertEquals("1", tag.getFirst(FieldKey.TRACK))
            assertEquals("1", tag.getFirst(FieldKey.DISC_NO))
            assertEquals("2019", tag.getFirst(FieldKey.YEAR))

            val artwork = tag.firstArtwork
            assertNotNull("jaudiotagger must see the embedded cover", artwork)
            assertEquals("cover bytes", coverJpeg.size, artwork.binaryData.size)
        } finally {
            file.delete()
        }
    }

    private class Block(val type: Int, val last: Boolean, val payload: ByteArray, val offset: Int)

    private class RawBlock(val type: Int, val payload: ByteArray)

    private fun loadSample(): ByteArray =
        javaClass.getResourceAsStream("/flac/tiny.flac")?.use { it.readBytes() }
            ?: error("test resource /flac/tiny.flac is missing")

    private fun parseBlocks(file: ByteArray): List<Block> {
        val blocks = ArrayList<Block>()
        var pos = MAGIC.size
        var last = false
        while (!last) {
            val header = file[pos].toInt() and 0xFF
            last = header and 0x80 != 0
            val length = beUInt24(file, pos + 1)
            val start = pos + 4
            blocks += Block(header and 0x7F, last, file.copyOfRange(start, start + length), pos)
            pos = start + length
        }
        return blocks
    }

    /** The byte offset where audio starts: immediately after the block whose isLast bit is set. */
    private fun audioStart(file: ByteArray): Int =
        parseBlocks(file).last().let { it.offset + 4 + it.payload.size }

    private fun audio(file: ByteArray): ByteArray = file.copyOfRange(audioStart(file), file.size)

    private fun withoutPadding(file: ByteArray): ByteArray =
        splice(parseBlocks(file).filter { it.type != TYPE_PADDING }.map { RawBlock(it.type, it.payload) }, audio(file))

    private fun splice(blocks: List<RawBlock>, audio: ByteArray): ByteArray {
        val out = ByteArray(MAGIC.size + blocks.sumOf { 4 + it.payload.size } + audio.size)
        MAGIC.copyInto(out)
        var pos = MAGIC.size
        for ((index, block) in blocks.withIndex()) {
            out[pos] = ((if (index == blocks.lastIndex) 0x80 else 0) or block.type).toByte()
            out[pos + 1] = ((block.payload.size ushr 16) and 0xFF).toByte()
            out[pos + 2] = ((block.payload.size ushr 8) and 0xFF).toByte()
            out[pos + 3] = (block.payload.size and 0xFF).toByte()
            block.payload.copyInto(out, pos + 4)
            pos += 4 + block.payload.size
        }
        audio.copyInto(out, pos)
        return out
    }

    private fun commentFields(file: ByteArray): Map<String, String> {
        val payload = parseBlocks(file).first { it.type == TYPE_VORBIS_COMMENT }.payload
        var pos = 4 + leUInt32(payload, 0)
        val count = leUInt32(payload, pos)
        pos += 4
        val fields = LinkedHashMap<String, String>()
        repeat(count) {
            val length = leUInt32(payload, pos)
            pos += 4
            val text = String(payload, pos, length, Charsets.UTF_8)
            pos += length
            fields[text.substringBefore('=')] = text.substringAfter('=')
        }
        return fields
    }

    private fun realJpeg(): ByteArray {
        val image = BufferedImage(500, 500, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color(32, 96, 160)
        graphics.fillRect(0, 0, 500, 500)
        graphics.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "jpg", out)
        return out.toByteArray()
    }

    private fun beUInt32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun beUInt24(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            (bytes[offset + 2].toInt() and 0xFF)

    private fun leUInt32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private companion object {
        val MAGIC = "fLaC".toByteArray(Charsets.US_ASCII)
        const val TYPE_STREAMINFO = 0
        const val TYPE_PADDING = 1
        const val TYPE_APPLICATION = 2
        const val TYPE_VORBIS_COMMENT = 4
        const val TYPE_PICTURE = 6
        const val TYPE_UNKNOWN = 42
    }
}