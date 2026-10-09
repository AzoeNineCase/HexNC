package com.hexnc.music.data.netease

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Pins the API hosts.
 *
 * These are the kind of constants that look harmless and are not: the eapi gateway originally
 * pointed at `interfacepc.music.163.com`, which no NetEase client uses, and every request kept
 * working well enough locally to hide it. Nothing else in the codebase knows these strings, so a
 * unit test that repeats them is the only thing that would notice them changing.
 */
class NeteaseEndpointTest {

    @Test
    fun `eapi uses the mobile gateway every client uses`() {
        assertEquals("https://interface.music.163.com", NeteaseClient.EAPI_HOST)
    }

    @Test
    fun `eapi gateway is not the desktop-only host`() {
        assertFalse(NeteaseClient.EAPI_HOST.contains("interfacepc"))
    }

    @Test
    fun `plain api uses the web host`() {
        assertEquals("https://music.163.com", NeteaseClient.API_HOST)
    }
}
