package com.hexnc.music.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hexnc.music.data.model.Ids
import com.hexnc.music.data.model.Playlist
import com.hexnc.music.data.model.Song
import com.hexnc.music.data.netease.NcAlbum
import com.hexnc.music.data.netease.NcArtist
import com.hexnc.music.data.netease.NcPlaylist
import com.hexnc.music.data.netease.NcSearchResult
import com.hexnc.music.data.netease.NcSong
import com.hexnc.music.di.AppContainer
import com.hexnc.music.ui.HexScaffold
import com.hexnc.music.ui.Route
import com.hexnc.music.ui.components.Caption
import com.hexnc.music.ui.components.CollectionRow
import com.hexnc.music.ui.components.EmptyState
import com.hexnc.music.ui.components.HexList
import com.hexnc.music.ui.components.LoadingBox
import com.hexnc.music.ui.components.SectionHeader
import com.hexnc.music.ui.components.SongRow
import com.hexnc.music.ui.components.TrackActionsSheet
import com.hexnc.music.ui.i18n.LocalStrings
import com.hexnc.music.ui.i18n.Strings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val SEARCH_DEBOUNCE_MS = 350L
private const val LOCAL_LIMIT = 60
private const val REMOTE_LIMIT = 30

/**
 * Catalogue search: the local library first, then NetEase.
 *
 * The local half filters what is already on the device and is instant; the NetEase half is a
 * network read, so it runs behind a short debounce and its results are imported on demand
 * rather than mirrored wholesale. Tapping a NetEase result therefore *materialises* it — an
 * album becomes a set of library songs, an artist becomes their top songs, a playlist becomes a
 * synced playlist — because nothing outside the library is playable.
 */
@Composable
fun SearchScreen(
    container: AppContainer,
    snackbarHostState: SnackbarHostState,
    onNavigate: (Route) -> Unit,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    var localSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var remote by remember { mutableStateOf<NcSearchResult?>(null) }
    var searching by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    var allPlaylists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var actionsSong by remember { mutableStateOf<Song?>(null) }
    var showActions by remember { mutableStateOf(false) }
    var showAddToPlaylist by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        allPlaylists = container.library.playlists()
    }

    LaunchedEffect(query, tab) {
        val text = query.trim()
        if (text.isEmpty()) {
            localSongs = emptyList()
            remote = null
            searching = false
            failed = false
            return@LaunchedEffect
        }
        searching = true
        failed = false
        delay(SEARCH_DEBOUNCE_MS)
        localSongs = runCatching { container.library.searchLibrary(text, LOCAL_LIMIT) }
            .getOrDefault(emptyList())
        container.netease.search(text, searchType(tab), REMOTE_LIMIT)
            .onSuccess { remote = it }
            .onFailure {
                remote = null
                failed = true
            }
        searching = false
    }

    fun message(text: String) {
        scope.launch { snackbarHostState.showSnackbar(text) }
    }

    HexScaffold(
        container = container,
        strings = strings,
        selectedTab = 2,
        onNavigate = onNavigate,
        snackbarHostState = snackbarHostState,
    ) { padding ->
        HexList(
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
        ) {
            item(key = "field") {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    label = strings.searchHint,
                    leadingIcon = {
                        Icon(
                            imageVector = MiuixIcons.Search,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                )
            }
            item(key = "tabs") {
                TabRow(
                    tabs = listOf(
                        strings.searchSongs,
                        strings.searchAlbums,
                        strings.searchArtists,
                        strings.searchPlaylists,
                    ),
                    selectedTabIndex = tab,
                    onTabSelected = { tab = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }

            when {
                query.isBlank() -> item(key = "idle") {
                    EmptyState(title = strings.searchStartHint, body = strings.searchNeteaseHint)
                }

                else -> {
                    item(key = "localHeader") { SectionHeader(strings.localResults) }
                    if (localSongs.isEmpty()) {
                        item(key = "localEmpty") {
                            Caption(strings.noResults, modifier = Modifier.padding(horizontal = 20.dp))
                        }
                    } else {
                        items(localSongs, key = { "local-${it.id}" }) { song ->
                            SongRow(
                                song = song,
                                strings = strings,
                                onClick = { scope.launch { container.playback.play(localSongs, localSongs.indexOf(song)) } },
                                onLongClick = {
                                    actionsSong = song
                                    showActions = true
                                },
                            )
                        }
                    }

                    item(key = "remoteHeader") {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            SectionHeader(strings.neteaseResults)
                            Caption(
                                text = strings.neteaseMusic,
                                modifier = Modifier.padding(horizontal = 20.dp),
                            )
                        }
                    }

                    if (searching || busy) {
                        item(key = "busy") { LoadingBox() }
                    } else if (failed) {
                        item(key = "failed") {
                            Caption(
                                text = strings.searchFailed,
                                modifier = Modifier.padding(horizontal = 20.dp),
                            )
                        }
                    } else {
                        remoteSection(
                            tab = tab,
                            result = remote,
                            strings = strings,
                            onSong = { nc ->
                                scope.launch {
                                    val imported = container.netease.importTrack(nc.id, withLyrics = true)
                                        .getOrNull()
                                    if (imported != null) {
                                        container.playback.play(listOf(imported), 0)
                                    } else {
                                        message(strings.trackUnavailable)
                                    }
                                }
                            },
                            onAlbum = { album ->
                                scope.launch {
                                    busy = true
                                    val count = container.netease.importAlbum(album.id).getOrDefault(0)
                                    busy = false
                                    if (count > 0) {
                                        onNavigate(Route.AlbumDetail(album.name, album.artist?.name.orEmpty()))
                                    } else {
                                        message(strings.networkError)
                                    }
                                }
                            },
                            onArtist = { artist ->
                                scope.launch {
                                    busy = true
                                    val count = container.netease
                                        .importArtistTopSongs(artist.id)
                                        .getOrDefault(0)
                                    busy = false
                                    if (count > 0) {
                                        onNavigate(Route.ArtistDetail(artist.name))
                                    } else {
                                        message(strings.networkError)
                                    }
                                }
                            },
                            onPlaylist = { playlist ->
                                scope.launch {
                                    busy = true
                                    val result = container.netease.syncPlaylist(playlist.id)
                                    busy = false
                                    if (result.isSuccess) {
                                        onNavigate(Route.PlaylistDetail(Ids.neteasePlaylistId(playlist.id)))
                                    } else {
                                        message(strings.syncFailed)
                                    }
                                }
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
            playlists = allPlaylists,
            showAddToPlaylist = showAddToPlaylist,
            onDismiss = { showActions = false },
            onDismissAddToPlaylist = { showAddToPlaylist = false },
            onOpenAddToPlaylist = { showAddToPlaylist = true },
            onSetCover = { message(strings.changeCover) },
            onImportLyric = { message(strings.importFromFiles) },
            onDeleted = { message(strings.delete) },
        )
    }
}

private fun searchType(tab: Int): Int = when (tab) {
    1 -> 10
    2 -> 100
    3 -> 1000
    else -> 1
}

/** Emits the remote result rows for the active scope tab into the enclosing `HexList`. */
private fun androidx.compose.foundation.lazy.LazyListScope.remoteSection(
    tab: Int,
    result: NcSearchResult?,
    strings: Strings,
    onSong: (NcSong) -> Unit,
    onAlbum: (NcAlbum) -> Unit,
    onArtist: (NcArtist) -> Unit,
    onPlaylist: (NcPlaylist) -> Unit,
) {
    when (tab) {
        1 -> {
            val albums = result?.albums.orEmpty()
            if (albums.isEmpty()) {
                item(key = "ra-empty") { Caption(strings.noResults, modifier = Modifier.padding(horizontal = 20.dp)) }
            } else {
                items(albums, key = { "ra-${it.id}" }) { album ->
                    CollectionRow(
                        title = album.name,
                        subtitle = album.artist?.name.orEmpty(),
                        coverUrl = album.picUrl,
                        overridePath = null,
                        strings = strings,
                        onClick = { onAlbum(album) },
                    )
                }
            }
        }

        2 -> {
            val artists = result?.artists.orEmpty()
            if (artists.isEmpty()) {
                item(key = "rt-empty") { Caption(strings.noResults, modifier = Modifier.padding(horizontal = 20.dp)) }
            } else {
                items(artists, key = { "rt-${it.id}" }) { artist ->
                    CollectionRow(
                        title = artist.name,
                        subtitle = strings.artistCount,
                        coverUrl = null,
                        overridePath = null,
                        strings = strings,
                        onClick = { onArtist(artist) },
                    )
                }
            }
        }

        3 -> {
            val playlists = result?.playlists.orEmpty()
            if (playlists.isEmpty()) {
                item(key = "rp-empty") { Caption(strings.noResults, modifier = Modifier.padding(horizontal = 20.dp)) }
            } else {
                items(playlists, key = { "rp-${it.id}" }) { playlist ->
                    CollectionRow(
                        title = playlist.name,
                        subtitle = playlist.creator.nickname,
                        coverUrl = playlist.coverImgUrl,
                        overridePath = null,
                        strings = strings,
                        count = playlist.trackCount,
                        onClick = { onPlaylist(playlist) },
                    )
                }
            }
        }

        else -> {
            val songs = result?.songs.orEmpty()
            if (songs.isEmpty()) {
                item(key = "rs-empty") { Caption(strings.noResults, modifier = Modifier.padding(horizontal = 20.dp)) }
            } else {
                items(songs, key = { "rs-${it.id}" }) { song ->
                    RemoteSongRow(song = song, strings = strings, onClick = { onSong(song) })
                }
            }
        }
    }
}

/**
 * A NetEase search hit, which is not a library [Song] yet.
 *
 * Rendered without [SongRow] because that component binds identity, cover overrides and
 * favourite state that only exist once the track has been imported.
 */
@Composable
private fun RemoteSongRow(
    song: NcSong,
    strings: Strings,
    onClick: () -> Unit,
) {
    top.yukonga.miuix.kmp.basic.BasicComponent(
        title = song.name,
        summary = listOf(song.artistNames, song.effectiveAlbum.name)
            .filter { it.isNotBlank() }
            .joinToString(" · "),
        startAction = {
            Icon(
                imageVector = MiuixIcons.Music,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MiuixTheme.colorScheme.primary,
            )
        },
        onClick = onClick,
    )
}
