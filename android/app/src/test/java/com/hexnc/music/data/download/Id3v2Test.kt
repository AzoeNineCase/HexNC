package com.hexnc.music.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A downloaded MP3 is written once and then never touched again, so a tag that is subtly wrong —
 * a size off by one, a missing BOM, a frame nobody can parse — is only discovered when a player
 * shows the file as "Track 01" by an unknown artist. These tests therefore round-trip through
 * [Id3v2Reader] (and in the size case check the raw header bytes) instead of trusting the writer.
 */
class Id3v2Test {

    @Test
    fun `round trips the core fields`() {
        val tags = AudioTags(
            title = "Song Title",
            artist = "The Artist",
            album = "The Album",
            albumArtist = "Album Artist",
            trackNo = 7,
            discNo = 2,
            year = "2021",
        )

        val read = Id3v2Reader.read(Id3v2.buildTag(tags))

        assertNotNull("a built tag must be readable", read)
        assertEquals("Song Title", read!!.title)
        assertEquals("The Artist", read.artist)
        assertEquals("The Album", read.album)
        assertEquals("Album Artist", read.albumArtist)
        assertEquals(7, read.trackNo)
        assertEquals(2, read.discNo)
        assertEquals("2021", read.year)
    }

    @Test
    fun `round trips cjk text`() {
        // UTF-16 with a BOM is the only encoding ID3v2.3 players reliably agree on for CJK.
        val tags = AudioTags(
            title = "夜に駆ける",
            artist = "YOASOBI",
            album = "THE BOOK",
            albumArtist = "YOASOBI",
            trackNo = 1,
            year = "2019",
        )

        val read = Id3v2Reader.read(Id3v2.buildTag(tags))

        assertNotNull(read)
        assertEquals("夜に駆ける", read!!.title)
        assertEquals("YOASOBI", read.artist)
        assertEquals("THE BOOK", read.album)
    }

    @Test
    fun `embeds the cover art`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
            ByteArray(120) { (it * 7).toByte() } +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        val tag = Id3v2.buildTag(AudioTags(title = "Cover", coverJpeg = jpeg))

        // APIC's MIME is not surfaced by AudioTags, so check the frame payload itself.
        assertTrue(
            "the APIC MIME must be image/jpeg",
            String(tag, Charsets.ISO_8859_1).contains("image/jpeg"),
        )

        val read = Id3v2Reader.read(tag)

        assertNotNull(read)
        val cover = read!!.coverJpeg
        assertNotNull("the cover must survive the round trip", cover)
        assertEquals("cover length", jpeg.size, cover!!.size)
        assertEquals(0xFF, cover[0].toInt() and 0xFF)
        assertEquals(0xD8, cover[1].toInt() and 0xFF)
        assertTrue("cover bytes must match", cover.contentEquals(jpeg))
    }

    @Test
    fun `empty optional fields produce no frames`() {
        val full = Id3v2.buildTag(
            AudioTags(
                title = "T",
                artist = "A",
                album = "L",
                albumArtist = "AA",
                trackNo = 3,
                discNo = 1,
                year = "2020",
                coverJpeg = ByteArray(64) { 1 },
            ),
        )
        val minimal = Id3v2.buildTag(AudioTags(title = "T"))

        assertTrue("omitting every optional field must shrink the tag", minimal.size < full.size)

        val read = Id3v2Reader.read(minimal)
        assertNotNull(read)
        assertEquals("T", read!!.title)
        assertEquals("", read.artist)
        assertEquals("", read.album)
        assertEquals("", read.albumArtist)
        assertEquals(0, read.trackNo)
        assertEquals(0, read.discNo)
        assertEquals("", read.year)
        assertNull("no APIC frame means no cover", read.coverJpeg)
    }

    @Test
    fun `encodes the tag size as a syncsafe integer`() {
        // A cover large enough to push the frame area past 127 bytes, where a naive size byte would
        // set the high bit and stop every parser dead.
        val tag = Id3v2.buildTag(AudioTags(title = "Big", coverJpeg = ByteArray(300) { 0x5A }))

        for (i in 6..9) {
            assertTrue("size byte $i must not set the high bit", (tag[i].toInt() and 0x80) == 0)
        }
        val declared = ((tag[6].toInt() and 0xFF) shl 21) or
            ((tag[7].toInt() and 0xFF) shl 14) or
            ((tag[8].toInt() and 0xFF) shl 7) or
            (tag[9].toInt() and 0xFF)
        assertTrue("the frame area must be at least 128 bytes for this test to mean anything", declared >= 128)
        assertEquals("declared size must cover exactly the frame area", tag.size - 10, declared)
    }

    @Test
    fun `attach prepends only when the payload has no tag`() {
        val tag = Id3v2.buildTag(AudioTags(title = "Fresh"))
        val payload = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64.toByte()) + ByteArray(32) { 0x11 }

        val tagged = Id3v2.attach(payload, tag)

        assertEquals("tag + payload", tag.size + payload.size, tagged.size)
        assertTrue("must start with an ID3 header", Id3v2Reader.hasTag(tagged))
        assertTrue(
            "the audio bytes must be untouched",
            tagged.copyOfRange(tag.size, tagged.size).contentEquals(payload),
        )
    }

    @Test
    fun `attach leaves an already tagged payload identical`() {
        val original = Id3v2.attach(ByteArray(48) { 0x22 }, Id3v2.buildTag(AudioTags(title = "First")))
        val replacement = Id3v2.buildTag(AudioTags(title = "Second"))

        val result = Id3v2.attach(original, replacement)

        assertSame("re-tagging must not copy or strip", original, result)
        assertEquals(original.size, result.size)

        // Even a damaged header is still a tag: the payload starts with "ID3", so it is left alone
        // rather than having a second tag stacked in front of it.
        val damaged = byteArrayOf(0x49, 0x44, 0x33, 0x03, 0x00, 0x00, 0x7F, 0x7F, 0x7F, 0x7F)
        assertSame(damaged, Id3v2.attach(damaged, replacement))
    }

    @Test
    fun `reader rejects a non id3 prefix`() {
        assertNull(Id3v2Reader.read("RIFF....WAVEfmt ".toByteArray(Charsets.US_ASCII)))
        assertNull(Id3v2Reader.read(ByteArray(0)))
        assertNull(Id3v2Reader.read(byteArrayOf(0x49, 0x44))) // "ID" only
        assertTrue(Id3v2Reader.hasTag(Id3v2.buildTag(AudioTags(title = "x"))))
        assertTrue(!Id3v2Reader.hasTag("ID3".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun `reader survives a truncated tag`() {
        val tag = Id3v2.buildTag(
            AudioTags(title = "Truncated", artist = "Someone", album = "Somewhere", trackNo = 4),
        )

        // Header only: nothing to read, but it is still a valid tag and must not throw.
        val headerOnly = Id3v2Reader.read(tag.copyOf(10))
        assertNotNull(headerOnly)
        assertEquals("", headerOnly!!.title)

        // Cut in the middle of the frames: return what is there rather than blowing up.
        val cut = Id3v2Reader.read(tag.copyOf(tag.size - 12))
        assertNotNull(cut)

        // A buffer shorter than a header cannot be a tag at all.
        assertNull(Id3v2Reader.read(tag.copyOf(5)))
    }

    @Test
    fun `reader skips frames it does not know`() {
        val frames = Id3v2.buildTag(AudioTags(title = "Known", artist = "Also Known"))
        val body = ByteArray(6) { 0x33 }
        val unknown = ByteArray(10 + body.size)
        "PRIV".toByteArray(Charsets.US_ASCII).copyInto(unknown, 0)
        unknown[4] = 0
        unknown[5] = 0
        unknown[6] = 0
        unknown[7] = body.size.toByte()
        body.copyInto(unknown, 10)

        val rewritten = ByteArray(10 + unknown.size + frames.size - 10)
        frames.copyInto(rewritten, 0, 0, 10)
        unknown.copyInto(rewritten, 10)
        frames.copyInto(rewritten, 10 + unknown.size, 10, frames.size)
        val frameArea = rewritten.size - 10
        rewritten[6] = ((frameArea ushr 21) and 0x7F).toByte()
        rewritten[7] = ((frameArea ushr 14) and 0x7F).toByte()
        rewritten[8] = ((frameArea ushr 7) and 0x7F).toByte()
        rewritten[9] = (frameArea and 0x7F).toByte()

        val read = Id3v2Reader.read(rewritten)

        assertNotNull(read)
        assertEquals("Known", read!!.title)
        assertEquals("Also Known", read.artist)
    }

    @Test
    fun `reader accepts v2_4 sizes and latin1 text`() {
        // A v2.4 tag written by somebody else: syncsafe frame sizes, ISO-8859-1 text. The 200-byte
        // PRIV frame is what makes the size encoding matter, since below 128 bytes the two are
        // identical.
        val titleBody = byteArrayOf(0x00) + "Café".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0x00)
        val frames = frameBytes("TIT2", titleBody, syncsafe = true) +
            frameBytes("PRIV", ByteArray(200) { 0x41 }, syncsafe = true)

        val tag = ByteArray(10 + frames.size)
        tag[0] = 'I'.code.toByte()
        tag[1] = 'D'.code.toByte()
        tag[2] = '3'.code.toByte()
        tag[3] = 4 // major: ID3v2.4
        tag[4] = 0
        tag[5] = 0
        writeSyncsafe(tag, 6, frames.size)
        frames.copyInto(tag, 10)

        assertTrue(Id3v2Reader.hasTag(tag))
        val read = Id3v2Reader.read(tag)
        assertNotNull(read)
        assertEquals("Café", read!!.title)
    }

    private fun frameBytes(id: String, body: ByteArray, syncsafe: Boolean): ByteArray {
        val out = ByteArray(10 + body.size)
        id.toByteArray(Charsets.US_ASCII).copyInto(out, 0)
        if (syncsafe) {
            writeSyncsafe(out, 4, body.size)
        } else {
            out[4] = ((body.size ushr 24) and 0xFF).toByte()
            out[5] = ((body.size ushr 16) and 0xFF).toByte()
            out[6] = ((body.size ushr 8) and 0xFF).toByte()
            out[7] = (body.size and 0xFF).toByte()
        }
        body.copyInto(out, 10)
        return out
    }

    private fun writeSyncsafe(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value ushr 21) and 0x7F).toByte()
        target[offset + 1] = ((value ushr 14) and 0x7F).toByte()
        target[offset + 2] = ((value ushr 7) and 0x7F).toByte()
        target[offset + 3] = (value and 0x7F).toByte()
    }
}