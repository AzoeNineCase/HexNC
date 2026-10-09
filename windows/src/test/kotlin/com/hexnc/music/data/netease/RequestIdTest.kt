package com.hexnc.music.data.netease

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Pins the shape of the eapi `requestId`.
 *
 * The underscore is the part that is easy to lose and impossible to notice: the field is only ever
 * read by the server, so a wrong format looks like an ordinary successful call until risk control
 * decides the client is not a client.
 */
class RequestIdTest {

    @Test
    fun `formats as millis underscore four digits`() {
        assertEquals("1700000000000_3333", requestIdAt(1_700_000_000_000L) { 3 })
    }

    @Test
    fun `matches the client format`() {
        val id = requestIdAt(1_700_000_000_000L) { 7 }
        assertTrue("unexpected shape: $id", Regex("^\\d+_\\d{4}$").matches(id))
    }

    @Test
    fun `carries exactly one underscore`() {
        val id = requestIdAt(1_700_000_000_000L) { 0 }
        assertEquals(1, id.count { it == '_' })
    }

    @Test
    fun `suffix is always four characters`() {
        for (millis in listOf(0L, 1L, 999_999_999_999L, 1_700_000_000_000L)) {
            val suffix = requestIdAt(millis) { 5 }.substringAfter('_')
            assertEquals(4, suffix.length)
        }
    }

    @Test
    fun `consumes one digit per position in order`() {
        val digits = ArrayDeque(listOf(1, 2, 3, 4))
        assertEquals("42_1234", requestIdAt(42L) { digits.removeFirst() })
    }

    @Test
    fun `clamps out of range digits instead of widening the field`() {
        assertEquals("1_9999", requestIdAt(1L) { 42 })
        assertEquals("1_0000", requestIdAt(1L) { -7 })
    }

    @Test
    fun `stays ascii under a non-latin default locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            val id = requestIdAt(1_700_000_000_000L) { 6 }
            assertEquals("1700000000000_6666", id)
            assertTrue("non-ascii digits leaked: $id", id.all { it in "0123456789_" })
        } finally {
            Locale.setDefault(previous)
        }
    }
}
