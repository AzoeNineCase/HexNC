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
    @Serializable data class ArtistDetail(val artist: String) : Route
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
