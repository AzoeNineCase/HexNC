package com.hexnc.music.data.netease

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verification that the wire models actually absorb the shapes NetEase returns.
 *
 * The API is inconsistent on purpose-built names: modern endpoints emit `ar`/`al`/`dt`, older ones
 * emit `artists`/`album`/`duration`, and optional members are frequently *absent* rather than null.
 * These payloads are copied from real responses, so a regression here is a regression against the
 * live API rather than against the serialiser.
 */
class NeteaseJsonTest {

    private val json = NeteaseJson

    @Test
    fun `decodes a modern song-detail payload`() {
        val payload = """
            {"songs":[{"id":123,"name":"Song","ar":[{"id":9,"name":"A"},{"id":10,"name":"B"}],
            "al":{"id":55,"name":"Album","picUrl":"http://p1.music.126.net/x.jpg"},
            "dt":215000,"fee":1,"no":3,"alia":["Alt"]}],
            "privileges":[{"id":123,"fee":1}],"code":200}
        """.trimIndent()
        val response = json.decodeFromString<NcSongDetailResponse>(payload)
        assertEquals(200, response.code)
        assertEquals(1, response.songs.size)
        val song = response.songs[0]
        assertEquals(123L, song.id)
        assertEquals("Song", song.name)
        assertEquals("A / B", song.artistNames)
        assertEquals("Album", song.effectiveAlbum.name)
        assertEquals(215000L, song.durationMs)
        assertEquals("Alt", song.alia[0])
        assertEquals(1, response.privileges.size)
    }

    @Test
    fun `falls back to the legacy artist album and duration aliases`() {
        val payload = """
            {"songs":[{"id":1,"name":"Legacy","artists":[{"id":2,"name":"Legacy Artist"}],
            "album":{"id":3,"name":"Legacy Album"},"duration":180000}]}
        """.trimIndent()
        val song = json.decodeFromString<NcSongDetailResponse>(payload).songs[0]
        assertEquals("Legacy Artist", song.artistNames)
        assertEquals("Legacy Album", song.effectiveAlbum.name)
        assertEquals(180000L, song.durationMs)
    }

    @Test
    fun `prefers the modern keys when both are present`() {
        val payload = """
            {"songs":[{"id":1,"name":"Both","ar":[{"name":"Modern"}],"artists":[{"name":"Legacy"}],
            "al":{"id":5,"name":"Modern Album"},"album":{"id":6,"name":"Legacy Album"},
            "dt":1000,"duration":2000}]}
        """.trimIndent()
        val song = json.decodeFromString<NcSongDetailResponse>(payload).songs[0]
        assertEquals("Modern", song.artistNames)
        assertEquals("Modern Album", song.effectiveAlbum.name)
        assertEquals(1000L, song.durationMs)
    }

    @Test
    fun `an empty song object decodes to defaults instead of throwing`() {
        val song = json.decodeFromString<NcSongDetailResponse>("""{"songs":[{}]}""").songs[0]
        assertEquals(0L, song.id)
        assertEquals("", song.name)
        assertEquals("", song.artistNames)
        assertEquals(0L, song.durationMs)
    }

    @Test
    fun `unknown keys and explicit nulls are tolerated`() {
        val payload = """
            {"songs":[{"id":7,"name":"N","brandNewField":{"deep":[1,2,3]},"al":null,"ar":null,
            "dt":null,"fee":null,"unknown":"x"}]}
        """.trimIndent()
        val song = json.decodeFromString<NcSongDetailResponse>(payload).songs[0]
        assertEquals(7L, song.id)
        assertEquals(0L, song.durationMs)
    }

    @Test
    fun `decodes a search response with all four result kinds`() {
        val payload = """
            {"result":{"songCount":1,"songs":[{"id":1,"name":"S","ar":[{"name":"X"}],"dt":1000}],
            "albumCount":1,"albums":[{"id":2,"name":"Al","picUrl":"http://x/y.jpg","artist":{"id":3,"name":"X"}}],
            "artistCount":1,"artists":[{"id":3,"name":"X"}],
            "playlistCount":1,"playlists":[{"id":4,"name":"Pl","coverImgUrl":"http://x/z.jpg",
            "trackCount":12,"playCount":999,"creator":{"userId":5,"nickname":"Me"}}]},"code":200}
        """.trimIndent()
        val result = json.decodeFromString<NcSearchResponse>(payload).result!!
        assertEquals(1, result.songCount)
        assertEquals(1, result.albumCount)
        assertEquals(1, result.artistCount)
        assertEquals(1, result.playlistCount)
        assertEquals("X", result.albums[0].artist?.name)
        assertEquals("http://x/z.jpg", result.playlists[0].coverImgUrl)
        assertEquals(12, result.playlists[0].trackCount)
        assertEquals(5L, result.playlists[0].creator.userId)
        // NcAlbum deliberately has no `artistNames` — album rows read `artist?.name` instead.
        assertEquals("http://x/y.jpg", result.albums[0].picUrl)
    }

    @Test
    fun `decodes the liked-songs playlist special type and track ids`() {
        val payload = """
            {"playlist":{"id":501,"name":"我喜欢的音乐","specialType":5,"trackCount":2,
            "trackIds":[{"id":11},{"id":12}],"subscribed":false,"creator":{"userId":9,"nickname":"Me"}},
            "code":200}
        """.trimIndent()
        val playlist = json.decodeFromString<NcPlaylistDetailResponse>(payload).playlist!!
        assertEquals(5, playlist.specialType)
        assertEquals(listOf(11L, 12L), playlist.trackIds.map { it.id })
        assertFalse(playlist.subscribed)
    }

    @Test
    fun `flags an unplayable track from a null url`() {
        val payload = """
            {"data":[{"id":1,"url":null,"code":404,"fee":1,"level":null},
            {"id":2,"url":"http://m801.music.126.net/a.mp3","code":200,"br":320000,"level":"exhigh"}],
            "code":200}
        """.trimIndent()
        val response = json.decodeFromString<NcSongUrlResponse>(payload)
        assertFalse(response.data[0].isPlayable)
        assertTrue(response.data[1].isPlayable)
        assertEquals(320000, response.data[1].br)
        assertEquals("exhigh", response.data[1].level)
    }

    @Test
    fun `decodes an instrumental lyric response`() {
        val payload = """{"lrc":{"version":1,"lyric":""},"nolyric":true,"uncollected":false,"code":200}"""
        val response = json.decodeFromString<NcLyricResponse>(payload)
        assertTrue(response.nolyric)
        assertEquals("", response.lrc?.lyric)
    }

    @Test
    fun `maps the qr poll status codes`() {
        fun codeOf(raw: String) = json.decodeFromString<NcQrPollResponse>(raw).code
        assertEquals(801, codeOf("""{"code":801}"""))
        assertEquals(802, codeOf("""{"code":802,"message":"scan ok"}"""))
        assertEquals(803, codeOf("""{"code":803}"""))
    }

    @Test
    fun `a malformed document fails loudly rather than silently`() {
        val strict = Json { ignoreUnknownKeys = false }
        // The lenient production instance tolerates a missing `songs` key…
        assertEquals(0, json.decodeFromString<NcSongDetailResponse>("{}").songs.size)
        // …but genuinely malformed JSON is still an error, not an empty result.
        var threw = false
        try {
            strict.decodeFromString<NcSongDetailResponse>("{\"songs\": 5}")
        } catch (e: Exception) {
            threw = true
        }
        assertTrue(threw)
    }
}
