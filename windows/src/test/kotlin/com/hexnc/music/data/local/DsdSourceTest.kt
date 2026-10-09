package com.hexnc.music.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transport refuses DSD before Java Sound ever sees it, and this predicate is what decides.
 *
 * A wrong answer here is expensive in opposite directions: a missed `.dsf` becomes the opaque
 * decode error the predicate exists to prevent, while matching too eagerly would skip ordinary
 * tracks. That is why the extension is read from the very end of the path and never from a
 * directory name.
 */
class DsdSourceTest {

    @Test
    fun dsdExtensionsAreRecognisedRegardlessOfCase() {
        assertTrue(isDsdSource("/storage/Music/Album/track.dsf"))
        assertTrue(isDsdSource("/storage/Music/Album/track.DFF"))
        assertTrue(isDsdSource("/storage/Music/Album/track.Dsd"))
        assertTrue(isDsdSource("C:\\Music\\Album\\track.dsf"))
    }

    @Test
    fun otherFilesAreNotDsd() {
        assertFalse(isDsdSource("/m/a.mp3"))
        assertFalse(isDsdSource("/m/a.flac"))
        assertFalse(isDsdSource("C:\\Music\\Album\\track.wav"))
        assertFalse(isDsdSource(null))
        assertFalse(isDsdSource(""))
        assertFalse(isDsdSource("   "))
    }

    @Test
    fun aDotInADirectoryNameDoesNotCount() {
        assertFalse(isDsdSource("/storage/Music/Album 1.5/track"))
        assertFalse(isDsdSource("/storage/Music/Album 1.5.6/track"))
        assertFalse(isDsdSource("/storage/Music/Album 1.5/track."))
    }

    @Test
    fun aContentUriWithoutExtensionIsNotDsd() {
        assertFalse(isDsdSource("content://media/external/audio/media/123"))
        assertFalse(isDsdSource("file:///C:/Music/Album/track"))
    }

    @Test
    fun aQueryOrFragmentIsStrippedBeforeLookingAtTheExtension() {
        assertTrue(isDsdSource("file:///C:/Music/Album/track.dsf?token=abc"))
        assertTrue(isDsdSource("file:///C:/Music/Album/track.dff#start"))
        assertFalse(isDsdSource("content://media/external/audio/media/123?track.mp3"))
    }
}
