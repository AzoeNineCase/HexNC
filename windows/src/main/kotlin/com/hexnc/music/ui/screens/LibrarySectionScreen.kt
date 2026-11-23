package com.hexnc.music.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hexnc.music.data.db.AlbumGroup
import com.hexnc.music.data.model.Playlist
import com.hexnc.music.data.model.Song
import com.hexnc.music.di.AppContainer
import com.hexnc.music.ui.LibrarySection
import com.hexnc.music.ui.Route
import com.hexnc.music.ui.components.CollectionRow
import com.hexnc.music.ui.components.EmptyState
import com.hexnc.music.ui.components.HexList
import com.hexnc.music.ui.components.LoadingBox
import com.hexnc.music.ui.components.SongRow
import com.hexnc.music.ui.components.TrackActionsSheet
import com.hexnc.music.ui.components.rememberLyricImporter
import com.hexnc.music.ui.i18n.LocalStrings
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * One library shelf as a full page — the second level behind every shelf on the home tab.
 *
 * The home tab is a launch pad: it shows a few rows per shelf and a "see all" action on each header.
 * This page owns the complete list for one of those shelves, so the home tab never has to choose
 * between showing everything (unusable) and truncating without a way to reach the rest (what the
 * library tab used to do).
 *
 * It is deliberately one screen for all five shelves: they differ only in which repository call
 * fills the list, and keeping them together means the row behaviour — tap to play from this list,
 * long-press for actions, right-click on desktop — is identical everywhere.
 */
@Composable
fun LibrarySectionScreen(
    container: AppContainer,
    sectionKey: String,
    snackbarHostState: SnackbarHostState,
    onNavigate: (Route) -> Unit,
    onBack: () -> Unit,
) {
    val strings = LocalStrings.current
    val section = LibrarySection.fromKey(sectionKey)
    val revision by container.library.revision.collectAsStateWithLifecycle()
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var songs by remember(section) { mutableStateOf<List<Song>>(emptyList()) }
    var albums by remember(section) { mutableStateOf<List<AlbumGroup>>(emptyList()) }
    var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var loaded by remember(section) { mutableStateOf(false) }
    var actionsSong by remember { mutableStateOf<Song?>(null) }
    var showActions by remember { mutableStateOf(false) }
    var showAddToPlaylist by remember { mutableStateOf(false) }

    suspend fun reload() {
        when (section) {
            LibrarySection.SONGS -> songs = container.library.songs()
            LibrarySection.FAVORITES -> songs = container.library.favoriteSongs()
            LibrarySection.RECENTLY_ADDED -> songs = container.library.recentlyAdded(limit = 200)
            LibrarySection.RECENTLY_PLAYED ->
                songs = container.library.recentlyPlayed(settings.recentlyPlayedIds, limit = 50)
            LibrarySection.ALBUMS -> albums = container.library.albums()
            null -> Unit
        }
        playlists = container.library.playlists()
        loaded = true
    }

    LaunchedEffect(section, revision) { reload() }

    // Play history is stored in preferences, not in the database, so [revision] never reports it.
    LaunchedEffect(settings.recentlyPlayedIds) {
        if (section == LibrarySection.RECENTLY_PLAYED) {
            songs = container.library.recentlyPlayed(settings.recentlyPlayedIds, limit = 50)
        }
    }

    fun message(text: String) {
        scope.launch { snackbarHostState.showSnackbar(text) }
    }

    // The section pages list the same songs as the home tab, so they get the same working lyric
    // picker rather than a placeholder snackbar.
    val importLyric = rememberLyricImporter(container, strings, ::message)

    fun playFrom(list: List<Song>, index: Int) {
        scope.launch { container.playback.play(list, index) }
    }

    val title = when (section) {
        LibrarySection.SONGS -> strings.allSongs
        LibrarySection.FAVORITES -> strings.favorites
        LibrarySection.RECENTLY_ADDED -> strings.recentlyAdded
        LibrarySection.RECENTLY_PLAYED -> strings.recentlyPlayed
        LibrarySection.ALBUMS -> strings.albums
        null -> strings.library
    }
    val subtitle = when (section) {
        LibrarySection.ALBUMS -> strings.albums(albums.size)
        LibrarySection.SONGS, LibrarySection.FAVORITES,
        LibrarySection.RECENTLY_ADDED, LibrarySection.RECENTLY_PLAYED,
        -> strings.songs(songs.size)
        null -> ""
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = title,
                subtitle = subtitle,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = strings.back,
                            modifier = Modifier.size(24.dp),
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when {
            !loaded -> LoadingBox()
            section == LibrarySection.ALBUMS -> if (albums.isEmpty()) {
                EmptyState(title = title, body = strings.libraryEmptyBody)
            } else {
                HexList(
                    contentPadding = PaddingValues(
                        top = 8.dp,
                        bottom = padding.calculateBottomPadding() + 24.dp,
                    ),
                ) {
                    items(albums, key = { "album-" + it.album }) { group ->
                        CollectionRow(
                            title = group.album.ifBlank { strings.unknownAlbum },
                            subtitle = group.artist.ifBlank { strings.unknownArtist },
                            coverUrl = group.coverUrl,
                            overridePath = group.coverOverridePath,
                            strings = strings,
                            count = group.trackCount,
                            onClick = { onNavigate(Route.AlbumDetail(group.album, group.artist)) },
                        )
                    }
                }
            }

            songs.isEmpty() -> EmptyState(title = title, body = strings.libraryEmptyBody)
            else -> HexList(
                contentPadding = PaddingValues(
                    top = 8.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
            ) {
                items(songs, key = { "song-" + it.id }) { song ->
                    SongRow(
                        song = song,
                        strings = strings,
                        onClick = { playFrom(songs, songs.indexOf(song).coerceAtLeast(0)) },
                        onLongClick = {
                            actionsSong = song
                            showActions = true
                        },
                    )
                }
            }
        }
    }

    TrackActionsSheet(
        show = showActions,
        song = actionsSong,
        container = container,
        strings = strings,
        playlists = playlists,
        showAddToPlaylist = showAddToPlaylist,
        onDismiss = { showActions = false },
        onDismissAddToPlaylist = { showAddToPlaylist = false },
        onOpenAddToPlaylist = { showAddToPlaylist = true },
        // Cover replacement stays on the home tab, where the file picker plumbing already lives;
        // the lyric picker is shared and therefore works here too.
        onSetCover = { message(strings.changeCover) },
        onImportLyric = { actionsSong?.id?.let(importLyric) },
        onDeleted = { message(strings.delete) },
    )
}