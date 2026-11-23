package com.hexnc.music.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Path rules are the part of downloading that fails silently on the other platform: a name Windows
 * accepts can be illegal on Android, and a trailing dot only fails at the moment the file is
 * written. Each case here pins one of those rules so a "cleanup" cannot quietly reintroduce a name
 * that one side refuses.
 */
class DownloadPathsTest {

    @Test
    fun `sanitizes characters both platforms reject`() {
        assertEquals(
            "a_b_c_d_e_f_g_h_i_j",
            DownloadPaths.sanitizeSegment("a/b\\c:d*e?f\"g<h>i|j"),
        )
        assertEquals("AC_DC_ Live", DownloadPaths.sanitizeSegment("AC/DC: Live"))
    }

    @Test
    fun `trims trailing dots and spaces`() {
        // Windows drops the trailing dot during creation and then cannot find the file again.
        assertEquals("Song", DownloadPaths.sanitizeSegment("Song..."))
        assertEquals("Song", DownloadPaths.sanitizeSegment("Song   "))
        assertEquals("Song", DownloadPaths.sanitizeSegment("Song. . ."))
    }

    @Test
    fun `truncates to the maximum length`() {
        assertEquals(120, DownloadPaths.sanitizeSegment("x".repeat(400)).length)
        assertEquals("abcdefghij", DownloadPaths.sanitizeSegment("abcdefghijklmno", maxLength = 10))
        // Truncation must not leave the illegal trailing dot behind.
        assertEquals("abcd", DownloadPaths.sanitizeSegment("abcd....", maxLength = 6))
    }

    @Test
    fun `all illegal characters become a single underscore`() {
        assertEquals("_", DownloadPaths.sanitizeSegment("*?\"<>|"))
        assertEquals("_", DownloadPaths.sanitizeSegment("///"))
        assertEquals("_", DownloadPaths.sanitizeSegment(""))
    }

    @Test
    fun `relative path joins sanitized segments and pads the track`() {
        assertEquals(
            "Artist/Album/03 - Title.mp3",
            DownloadPaths.relativePath("Artist", "Album", 3, "Title", "mp3"),
        )
        assertEquals(
            "Artist/Album/12 - Title.flac",
            DownloadPaths.relativePath("Artist", "Album", 12, "Title", "FLAC"),
        )
        // Track 0 is not "no track": it is the untracked track, and still needs a sortable prefix.
        assertEquals(
            "Artist/Album/00 - Title.mp3",
            DownloadPaths.relativePath("Artist", "Album", 0, "Title", "mp3"),
        )
    }

    @Test
    fun `relative path uses the unknown placeholders`() {
        assertEquals(
            "Unknown artist/Unknown album/00 - Song.mp3",
            DownloadPaths.relativePath("", "", 0, "Song", "mp3"),
        )
        assertEquals(
            "未知艺术家/未知专辑/05 - 歌.mp3",
            DownloadPaths.relativePath(
                artist = " ",
                album = "",
                trackNo = 5,
                title = "歌",
                extension = "mp3",
                unknownArtist = "未知艺术家",
                unknownAlbum = "未知专辑",
            ),
        )
        assertTrue(
            "a relative path must not start with a separator",
            !DownloadPaths.relativePath("", "", 0, "Song", "mp3").startsWith("/"),
        )
    }

    @Test
    fun `relative path sanitizes every segment`() {
        assertEquals(
            "AC_DC/Back_In Black/01 - Hells_Bells.mp3",
            DownloadPaths.relativePath("AC/DC", "Back:In Black", 1, "Hells?Bells", "mp3"),
        )
    }

    @Test
    fun `unique path keeps a free name`() {
        assertEquals(
            "Artist/Album/01 - Title.mp3",
            DownloadPaths.uniquePath("Artist/Album/01 - Title.mp3") { false },
        )
    }

    @Test
    fun `unique path counts up before the extension`() {
        val taken = setOf("Artist/Album/01 - Title.mp3", "Artist/Album/01 - Title (2).mp3")
        val next = DownloadPaths.uniquePath("Artist/Album/01 - Title.mp3") { it in taken }

        assertEquals("Artist/Album/01 - Title (3).mp3", next)
        assertTrue("the directory part must be preserved", next.startsWith("Artist/Album/"))

        // A name without an extension gets the counter appended, not a new ".ext".
        val noExtension = DownloadPaths.uniquePath("Artist/Album/Title") { it == "Artist/Album/Title" }
        assertEquals("Artist/Album/Title (2)", noExtension)
    }

    @Test
    fun `extension for known containers and the default`() {
        assertEquals("flac", DownloadPaths.extensionFor("FLAC"))
        assertEquals("flac", DownloadPaths.extensionFor(" flac "))
        assertEquals("m4a", DownloadPaths.extensionFor("m4a"))
        assertEquals("m4a", DownloadPaths.extensionFor("AAC"))
        assertEquals("mp3", DownloadPaths.extensionFor("MP3"))
        assertEquals("mp3", DownloadPaths.extensionFor(null))
        assertEquals("mp3", DownloadPaths.extensionFor(""))
        assertEquals("mp3", DownloadPaths.extensionFor("ogg"))
    }
}