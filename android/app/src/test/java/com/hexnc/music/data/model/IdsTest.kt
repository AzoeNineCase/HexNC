package com.hexnc.music.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ids are the load-bearing invariant of the whole library: every DAO writes with
 * `INSERT … ON CONFLICT(id) DO UPDATE`, so re-importing a file or re-syncing a playlist is only
 * idempotent if the id is a pure, stable function of the source. These tests pin that down.
 */
class IdsTest {

    @Test
    fun `local song id is the sha1 of the normalised path`() {
        // Independently known SHA-1("abc").
        assertEquals("local:a9993e364706816aba3e25717850c26c9cd0d89d", Ids.localSongId("abc"))
    }

    @Test
    fun `local song id is stable across repeated calls`() {
        val path = "content://media/external/audio/media/1234"
        assertEquals(Ids.localSongId(path), Ids.localSongId(path))
    }

    @Test
    fun `windows path spelling differences collapse to one id`() {
        assertEquals(Ids.localSongId("C:\\Music\\Song.mp3"), Ids.localSongId("c:/music/song.mp3"))
        assertEquals(Ids.localSongId("D:\\a\\B\\c.FLAC"), Ids.localSongId("d:/a/b/c.flac"))
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertEquals(Ids.localSongId("/m/a.mp3"), Ids.localSongId("  /m/a.mp3  "))
    }

    @Test
    fun `content uri query parameters are stripped`() {
        assertEquals(
            Ids.localSongId("content://media/external/audio/media/42"),
            Ids.localSongId("content://media/external/audio/media/42?foo=1&bar=2")
        )
    }

    @Test
    fun `the uri scheme is case insensitive but the scheme is required`() {
        assertEquals(
            Ids.localSongId("CONTENT://media/external/audio/media/42"),
            Ids.localSongId("content://media/external/audio/media/42")
        )
        // A plain path keeps any `?` it happens to contain — only content:// is query-stripped.
        assertNotEquals(Ids.localSongId("/m/a?b.mp3"), Ids.localSongId("/m/a.mp3"))
    }

    @Test
    fun `different files get different ids`() {
        assertNotEquals(Ids.localSongId("/m/a.mp3"), Ids.localSongId("/m/b.mp3"))
    }

    @Test
    fun `netease ids are namespaced and readable`() {
        val songId = Ids.neteaseSongId(123456L)
        assertEquals("ne:123456", songId)
        assertTrue(Ids.isNetease(songId))
        assertEquals(songId, Ids.neteasePlaylistId(123456L))
        assertFalse(Ids.isNetease(Ids.localSongId("/m/a.mp3")))
        assertTrue(Ids.localSongId("/m/a.mp3").startsWith(Ids.LOCAL_PREFIX))
    }

    @Test
    fun `a local file and a netease stream of the same track never collide`() {
        assertNotEquals(Ids.localSongId("123456"), Ids.neteaseSongId(123456L))
    }

    @Test
    fun `local playlist ids are unique and prefixed`() {
        val a = Ids.localPlaylistId()
        val b = Ids.localPlaylistId()
        assertNotEquals(a, b)
        assertTrue(a.startsWith(Ids.LOCAL_PREFIX))
        assertFalse(Ids.isNetease(a))
        assertFalse(a.contains("-"))
    }

    @Test
    fun `the all-local playlist id can never be produced by a generated one`() {
        // The reserved id is written by hand, so it must stay outside the random UUID alphabet and
        // outside the NetEase namespace.
        assertTrue(Ids.ALL_LOCAL_PLAYLIST_ID.startsWith(Ids.LOCAL_PREFIX))
        assertFalse(Ids.isNetease(Ids.ALL_LOCAL_PLAYLIST_ID))
        assertFalse(Ids.ALL_LOCAL_PLAYLIST_ID == Ids.localPlaylistId())
        repeat(50) { assertNotEquals(Ids.ALL_LOCAL_PLAYLIST_ID, Ids.localPlaylistId()) }
    }

    @Test
    fun `only the reserved id is treated as the all-local playlist`() {
        val allLocal = Playlist(
            id = Ids.ALL_LOCAL_PLAYLIST_ID,
            name = "全部本地音乐",
            source = PlaylistSource.LOCAL,
        )
        assertTrue(allLocal.isAllLocal)
        assertFalse(allLocal.copy(id = Ids.localPlaylistId()).isAllLocal)
        assertFalse(allLocal.copy(id = Ids.neteasePlaylistId(5L)).isAllLocal)
    }
}
