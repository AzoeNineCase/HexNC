package com.hexnc.music.ui

import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavKey

/**
 * Every place the app can navigate to.
 *
 * Routes carry only plain identifiers — the screens re-read the database, so a route stays valid
 * after the underlying rows change (a playlist can be renamed or re-synced while it is on screen).
 */
@Serializable
sealed interface Route : NavKey {

    /** The four bottom-bar destinations. */
    @Serializable data object Home : Route
    @Serializable data object Playlists : Route
    @Serializable data object Search : Route
    @Serializable data object Settings : Route

    /** Detail and full-screen destinations pushed on top of a tab. */
    @Serializable data class PlaylistDetail(val playlistId: String) : Route
    @Serializable data class AlbumDetail(val album: String, val artist: String = "") : Route
    @Serializable data class ArtistDetail(val artist: String, val neteaseArtistId: Long? = null) : Route

    /**
     * One library shelf opened as its own second-level page ("see all").
     *
     * The home tab only shows the first few rows of each shelf; every section header carries an
     * action that pushes this route, so the complete list is one tap away without the home tab
     * growing into an endless page. The shelf is carried as its [LibrarySection.key] string, which
     * keeps the route `@Serializable` without dragging the enum into the nav key type.
     */
    @Serializable data class LibrarySection(val section: String) : Route

    /** The NetEase favourited-albums list, opened from the playlists tab header action. */
    @Serializable data object NeteaseAlbums : Route

    @Serializable data object NowPlaying : Route
    @Serializable data object SignIn : Route

    /** True for the four destinations that show the bottom navigation bar. */
    val isRootTab: Boolean
        get() = this is Home || this is Playlists || this is Search || this is Settings
}

/** The index of this route in the bottom bar, or `-1` when it is not a tab. */
val Route.tabIndex: Int
    get() = when (this) {
        Route.Home -> 0
        Route.Playlists -> 1
        Route.Search -> 2
        Route.Settings -> 3
        else -> -1
    }

/** The four tabs in display order. */
val rootTabs: List<Route> = listOf(Route.Home, Route.Playlists, Route.Search, Route.Settings)

/**
 * The shelves the home tab can open as a second-level page.
 *
 * [key] is what travels in [Route.LibrarySection]; the label is resolved from the string catalog by
 * the page itself, so this enum stays free of localisation state.
 */
enum class LibrarySection(val key: String) {
    RECENTLY_PLAYED("recently_played"),
    RECENTLY_ADDED("recently_added"),
    FAVORITES("favorites"),
    ALBUMS("albums"),
    SONGS("songs");

    companion object {
        fun fromKey(key: String?): LibrarySection? = entries.firstOrNull { it.key == key }
    }
}