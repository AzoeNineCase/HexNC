package com.hexnc.music.data.repo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the favourited-album paging bug.
 *
 * The listing used to be one `limit = 200` request. The service caps a page at 100 entries and does
 * not say so, so an account with more than 100 favourite albums was truncated silently — the screen
 * looked fine and simply dropped the tail. The walk now pages at 100 and stops on whichever signal
 * the reply actually carries, and [isLastAlbumPage] is the single place that decides it. These tests
 * pin the precedence between those signals, because getting it wrong means either an infinite loop
 * (never stop) or a silent truncation (stop too early), and both are invisible to the UI.
 *
 * The cases below are keyed to reply shapes actually observed from `/api/album/sublist`: `eapi`
 * answers with `total` and `more`, the plain gateway answers with `hasMore` only, and on some
 * versions `hasMore` is `false` for every page regardless of position.
 */
class NeteaseAlbumPagingTest {

    private val pageSize = 100

    @Test
    fun `an empty page always ends the walk`() {
        // Even a reply that (wrongly) claims there is more cannot continue from nothing.
        assertTrue(isLastAlbumPage(received = 0, total = null, more = true, offset = 0, pageSize = pageSize))
        assertTrue(isLastAlbumPage(received = 0, total = 500, more = true, offset = 300, pageSize = pageSize))
    }

    @Test
    fun `reaching the reported total ends the walk even when more is true`() {
        // `total` is the only signal that survives a wrong `more` flag, so it outranks it.
        assertTrue(
            isLastAlbumPage(received = 100, total = 200, more = true, offset = 200, pageSize = pageSize),
        )
        // Overshooting (the service can report total below what it actually returns) also ends it.
        assertTrue(
            isLastAlbumPage(received = 100, total = 150, more = true, offset = 200, pageSize = pageSize),
        )
    }

    @Test
    fun `a known total that is not reached keeps the walk going`() {
        assertFalse(
            isLastAlbumPage(received = 100, total = 200, more = false, offset = 100, pageSize = pageSize),
        )
        assertFalse(
            isLastAlbumPage(received = 100, total = 2000, more = false, offset = 100, pageSize = pageSize),
        )
    }

    @Test
    fun `a short page with no more flag ends the walk`() {
        // This is the shape the plain gateway returns for the final page.
        assertTrue(
            isLastAlbumPage(received = 42, total = null, more = false, offset = 142, pageSize = pageSize),
        )
    }

    @Test
    fun `a short page with more true keeps walking`() {
        // A server-side filter can hand back fewer rows than asked for while still having more.
        assertFalse(
            isLastAlbumPage(received = 42, total = null, more = true, offset = 42, pageSize = pageSize),
        )
    }

    @Test
    fun `a full page with no more flag cannot be trusted as the end`() {
        // Versions that always answer `hasMore = false` would truncate the listing at page one if a
        // full page were accepted as final.
        assertFalse(
            isLastAlbumPage(received = 100, total = null, more = false, offset = 100, pageSize = pageSize),
        )
    }

    @Test
    fun `a full page with more true keeps walking`() {
        assertFalse(
            isLastAlbumPage(received = 100, total = null, more = true, offset = 100, pageSize = pageSize),
        )
    }

    @Test
    fun `a total of zero is not treated as an authoritative end`() {
        // `total = 0` shows up when the transport omits the field into a default; it must not stop a
        // walk that has already received rows.
        assertFalse(
            isLastAlbumPage(received = 100, total = 0, more = true, offset = 100, pageSize = pageSize),
        )
    }
}