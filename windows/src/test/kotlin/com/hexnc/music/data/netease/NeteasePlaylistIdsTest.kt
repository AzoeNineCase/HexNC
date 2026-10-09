package com.hexnc.music.data.netease

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the playlist-import bug.
 *
 * `/api/user/playlist` lists the account's playlists but carries no usable track data at all: every
 * entry has `"tracks": null` and no `trackIds` key, so a sync that reads ids off the listing imports
 * each playlist with its correct `trackCount` but an empty membership. The ids only exist on
 * `/api/v6/playlist/detail`, and [NcPlaylist.trackIdList] is the single place that decides where
 * they come from.
 *
 * The JSON payloads below are trimmed copies of real responses, so a failure here is a failure
 * against the live API rather than against the serialiser.
 */
class NeteasePlaylistIdsTest {

    private val json = NeteaseJson

    // ---------------------------------------------------------------- wire shapes

    @Test
    fun `listing entries carry a track count but no ids`() {
        val payload = """
            {"more":false,"code":200,"playlist":[
            {"id":1,"name":"网易云音乐喜欢的音乐","trackCount":158,"specialType":5,
            "coverImgUrl":"http://p1.music.126.net/cover.jpg","tracks":null,
            "creator":{"userId":1,"nickname":"me"}},
            {"id":2,"name":"我的歌单","trackCount":12,"tracks":null,
            "creator":{"userId":1,"nickname":"me"}}]}
        """.trimIndent()

        val page = json.decodeFromString<NcUserPlaylistResponse>(payload)

        assertEquals(2, page.playlist.size)
        assertEquals(158, page.playlist[0].trackCount)
        assertTrue("the listing endpoint cannot supply ids", page.playlist[0].trackIdList().isEmpty())
        assertTrue(page.playlist[1].trackIdList().isEmpty())
    }

    @Test
    fun `the detail payload supplies the whole ordered id list`() {
        val ids = (1..40).joinToString(",") { """{"id":$it,"v":6,"at":0}""" }
        val payload = """
            {"code":200,"playlist":{"id":3,"name":"chill","trackCount":40,
            "tracks":[],"trackIds":[$ids]}}
        """.trimIndent()

        val detail = json.decodeFromString<NcPlaylistDetailResponse>(payload)
        val list = detail.playlist!!.trackIdList()

        assertEquals(40, list.size)
        assertEquals(1L, list.first())
        assertEquals(40L, list.last())
    }

    /**
     * The inline `tracks` array is a server-capped sample (`/api/v6/playlist/detail` holds it at 10
     * entries however large `n` gets), which is why the id list never depends on it.
     */
    @Test
    fun `trackIds win over the inline sample`() {
        val playlist = NcPlaylist(
            id = 1L,
            trackCount = 3,
            trackIds = listOf(NcTrackIdRef(11L), NcTrackIdRef(12L), NcTrackIdRef(13L)),
            tracks = listOf(NcSong(id = 99L), NcSong(id = 98L)),
        )

        assertEquals(listOf(11L, 12L, 13L), playlist.trackIdList())
    }

    @Test
    fun `inline tracks are the fallback for older shapes`() {
        val playlist = NcPlaylist(
            id = 1L,
            trackCount = 2,
            tracks = listOf(NcSong(id = 99L), NcSong(id = 98L)),
        )

        assertEquals(listOf(99L, 98L), playlist.trackIdList())
    }

    @Test
    fun `non-positive ids are dropped from either source`() {
        val fromTrackIds = NcPlaylist(
            id = 1L,
            trackIds = listOf(NcTrackIdRef(0L), NcTrackIdRef(-5L), NcTrackIdRef(7L)),
        )
        assertEquals(listOf(7L), fromTrackIds.trackIdList())

        val fromInline = NcPlaylist(id = 1L, tracks = listOf(NcSong(id = 0L), NcSong(id = 4L)))
        assertEquals(listOf(4L), fromInline.trackIdList())
    }

    @Test
    fun `an empty playlist yields nothing`() {
        assertTrue(NcPlaylist(id = 1L).trackIdList().isEmpty())
    }

    /**
     * The sync compares the two and refuses to reconcile when they disagree, because reconciling a
     * short list deletes the membership rows it does not mention.
     */
    @Test
    fun `a truncated id list is detectable against trackCount`() {
        val playlist = NcPlaylist(
            id = 1L,
            trackCount = 158,
            trackIds = List(10) { NcTrackIdRef(it + 1L) },
        )

        assertTrue(playlist.trackIdList().size < playlist.trackCount)
    }
}
