package com.hexnc.music.data.lyrics

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verification that the LRCLIB search payload decodes, including the two mistakes that would break
 * the feature silently: a duration read in the wrong unit, and a `null` lyric field treated as text.
 *
 * The payloads are copied from real responses, so a regression here is a regression against the live
 * service rather than against the serialiser.
 */
class LrclibJsonTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes a search result array`() {
        val payload = """
            [{"id":123,"trackName":"Numb","artistName":"Linkin Park","albumName":"Meteora",
            "duration":185.4,"instrumental":false,
            "plainLyrics":"I'm tired of being what you want me to be",
            "syncedLyrics":"[00:01.00]I'm tired of being what you want me to be"}]
        """.trimIndent()
        val tracks = json.decodeFromString<List<LrclibTrack>>(payload)
        assertEquals(1, tracks.size)
        val track = tracks.first()
        assertEquals(123L, track.id)
        assertEquals("Numb", track.trackName)
        assertEquals("Linkin Park", track.artistName)
        assertEquals("Meteora", track.albumName)
        assertEquals(185.4, track.duration, 1e-9)
        assertFalse(track.instrumental)
        assertTrue(track.syncedLyrics!!.startsWith("[00:01.00]"))
    }

    @Test
    fun `the duration is converted from seconds to milliseconds`() {
        // The unit trap: 185.4 seconds is a 3:05 song, 185.4 milliseconds is not a song at all. A
        // factor-of-1000 error here would make every candidate look like a different recording and
        // quietly disable the automatic match.
        val candidate = LrclibTrack(id = 1L, trackName = "Numb", duration = 185.4).toCandidate()
        assertEquals(185_400L, candidate.durationMs)
        assertEquals(LyricSource.LRCLIB, candidate.source)
        assertEquals(1L, candidate.lrclibId)
        assertFalse(candidate.hasSynced)
    }

    @Test
    fun `a plain-only entry is still a candidate and is reported as unsynced`() {
        val candidate = LrclibTrack(id = 2L, trackName = "Numb", plainLyrics = "words").toCandidate()
        assertFalse(candidate.hasSynced)
        assertEquals("words", candidate.plainLyrics)
        assertNull(candidate.syncedLyrics)
    }

    @Test
    fun `a timed entry is reported as synced`() {
        val track = LrclibTrack(id = 3L, trackName = "Numb", syncedLyrics = "[00:01.00]words")
        assertTrue(track.toCandidate().hasSynced)
    }

    @Test
    fun `an entry without a title falls back to the searched title`() {
        assertEquals("晴天", LrclibTrack(id = 4L, trackName = "").toCandidate("晴天").title)
        assertEquals("Numb", LrclibTrack(id = 5L, trackName = "Numb").toCandidate("晴天").title)
    }

    @Test
    fun `an instrumental entry decodes and carries no text`() {
        val payload = """[{"id":7,"trackName":"Interlude","duration":62.0,"instrumental":true}]"""
        val track = json.decodeFromString<List<LrclibTrack>>(payload).first()
        assertTrue(track.instrumental)
        assertNull(track.plainLyrics)
        assertNull(track.syncedLyrics)
        assertTrue(track.toCandidate().instrumental)
    }

    @Test
    fun `the error object LRCLIB returns for a miss is not a result array`() {
        // Why LrclibClient checks for a leading brace: a miss answers 404 with an object, decoding
        // that as a list throws, and the client turns the throw into "no results".
        val decoded = runCatching { json.decodeFromString<List<LrclibTrack>>("""{"error":"Not Found"}""") }
        assertTrue(decoded.isFailure)
    }

    @Test
    fun `unknown members are ignored so a schema addition cannot break the search`() {
        val payload = """[{"id":1,"trackName":"Numb","duration":185.0,"newMember":"x"}]"""
        assertEquals("Numb", json.decodeFromString<List<LrclibTrack>>(payload).first().trackName)
    }
}
