package com.hexnc.music.data.netease

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NetEase hands back `http://` media URLs while the same paths serve fine over TLS, and an app that
 * targets a modern SDK has cleartext blocked by the platform — so this rewrite is what makes NetEase
 * playback work at all. Every case here is a regression guard for a silent failure: a URL that is not
 * upgraded does not crash, it just never plays.
 */
class StreamUrlsTest {

    @Test
    fun `upgrades netease media urls to https`() {
        assertEquals(
            "https://m802.music.126.net/2026/abc/def.mp3",
            httpsStreamUrl("http://m802.music.126.net/2026/abc/def.mp3"),
        )
    }

    @Test
    fun `keeps query and port`() {
        assertEquals(
            "https://m801.music.126.net:8080/x.mp3?auth=abc&x=1",
            httpsStreamUrl("http://m801.music.126.net:8080/x.mp3?auth=abc&x=1"),
        )
    }

    @Test
    fun `leaves secure urls alone`() {
        val url = "https://m701.music.126.net/x.flac"
        assertEquals(url, httpsStreamUrl(url))
    }

    @Test
    fun `does not touch third party hosts`() {
        // Forcing TLS on somebody else's host could break a stream that only speaks plain HTTP.
        assertEquals("http://example.com/x.mp3", httpsStreamUrl("http://example.com/x.mp3"))
        assertEquals("http://cdn.not126.net/x.mp3", httpsStreamUrl("http://cdn.not126.net/x.mp3"))
    }

    @Test
    fun `blank input is null`() {
        assertNull(httpsStreamUrl(null))
        assertNull(httpsStreamUrl(""))
        assertNull(httpsStreamUrl("   "))
    }

    @Test
    fun `is case insensitive and scheme tolerant`() {
        assertEquals(
            "https://M802.MUSIC.126.NET/x.mp3",
            httpsStreamUrl("HTTP://M802.MUSIC.126.NET/x.mp3"),
        )
    }

    @Test
    fun `strips user info before matching the host`() {
        assertEquals(
            "https://user:pw@m802.music.126.net/x.mp3",
            httpsStreamUrl("http://user:pw@m802.music.126.net/x.mp3"),
        )
    }

    @Test
    fun `recognises only netease media domains`() {
        assertTrue(isNeteaseMediaHost("126.net"))
        assertTrue(isNeteaseMediaHost("163.com"))
        assertTrue(isNeteaseMediaHost("m802.music.126.net"))
        assertTrue(isNeteaseMediaHost("M802.MUSIC.126.NET"))
        assertFalse(isNeteaseMediaHost(""))
        assertFalse(isNeteaseMediaHost("not126.net"))
        assertFalse(isNeteaseMediaHost("126.net.evil.com"))
        assertFalse(isNeteaseMediaHost("evil163.com"))
    }
}
