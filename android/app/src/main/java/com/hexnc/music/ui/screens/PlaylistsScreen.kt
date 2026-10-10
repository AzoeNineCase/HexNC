package com.hexnc.music.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hexnc.music.data.model.Playlist
import com.hexnc.music.data.model.PlaylistSource
import com.hexnc.music.data.netease.NcAlbum
import com.hexnc.music.di.AppContainer
import com.hexnc.music.ui.HexScaffold
import com.hexnc.music.ui.Route
import com.hexnc.music.ui.components.CollectionRow
import com.hexnc.music.ui.components.EmptyState
import com.hexnc.music.ui.components.HexList
import com.hexnc.music.ui.components.LoadingBox
import com.hexnc.music.ui.components.SectionHeader
import com.hexnc.music.ui.i18n.LocalStrings
import com.hexnc.music.ui.i18n.Strings
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The playlists tab.
 *
 * Local and NetEase playlists share one list type, so they are rendered as two labelled sections
 * rather than two screens; the row already carries the source through its cover and subtitle. The
 * albums favourited on NetEase are listed under them as a third section — they are a browse surface
 * rather than a library this app owns, so one is imported only when the user opens it.
 */
@Composable
fun PlaylistsScreen(
    container: AppContainer,
    snackbarHostState: SnackbarHostState,
    onNavigate: (Route) -> Unit,
) {
    val strings = LocalStrings.current
    val revision by container.library.revision.collectAsStateWithLifecycle()
    val session by container.netease.session.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var local by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var remote by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var syncing by remember { mutableStateOf(false) }
    var syncLine by remember { mutableStateOf("") }
    var favAlbums by remember { mutableStateOf<List<NcAlbum>>(emptyList()) }
    var importingAlbum by remember { mutableStateOf(false) }

    suspend fun reload() {
        local = container.library.playlistsOf(PlaylistSource.LOCAL)
        remote = container.library.playlistsOf(PlaylistSource.NETEASE)
        // Album favourites are a service read, so they are kept out of the playlist queries above:
        // the tab shows the playlists it already has while this round trip is in flight. A failed read
        // keeps the previous list rather than blanking the section — the service answering badly for a
        // moment is not a reason to make the user's albums disappear.
        if (session.isSignedIn) {
            container.netease.favouriteAlbums().onSuccess { favAlbums = it }
        } else {
            favAlbums = emptyList()
        }
        loaded = true
    }

    // Keyed on the sign-in state as well as [revision]: the album shelf is a service read that only
    // exists for a signed-in session, so signing in (or out) has to re-run the load.
    LaunchedEffect(session.isSignedIn, revision) { reload() }

    fun message(text: String) {
        scope.launch { snackbarHostState.showSnackbar(text) }
    }

    fun syncAll() {
        scope.launch {
            syncing = true
            syncLine = strings.syncing
            val result = container.netease.syncLibrary(withLyrics = false) { progress ->
                syncLine = "${strings.syncing} ${progress.done}/${progress.total}"
            }
            syncing = false
            result
                .onSuccess { report -> message("${strings.syncDone} · ${strings.songs(report.tracks)}") }
                .onFailure { message(strings.syncFailed) }
            container.library.notifyExternalChange()
        }
    }

    /**
     * Opens a favourited album: materialise its tracks, then show it as an ordinary local album.
     *
     * The album page is a local-library screen, so importing first is what makes it playable; an
     * album the service will not hand over is reported rather than opening a page whose rows fail.
     */
    fun openAlbum(album: NcAlbum) {
        if (importingAlbum) return
        scope.launch {
            importingAlbum = true
            val count = container.netease.importAlbum(album.id).getOrDefault(0)
            importingAlbum = false
            if (count > 0) {
                onNavigate(Route.AlbumDetail(album.name, album.artistName))
            } else {
                message(strings.networkError)
            }
        }
    }

    HexScaffold(
        container = container,
        strings = strings,
        selectedTab = 1,
        onNavigate = onNavigate,
        snackbarHostState = snackbarHostState,
        topBar = {
            TopAppBar(
                title = strings.playlistsTitle,
                actions = {
                    if (session.isSignedIn) {
                        IconButton(onClick = { if (!syncing) syncAll() }) {
                            Icon(
                                imageVector = MiuixIcons.Refresh,
                                contentDescription = strings.syncAllPlaylists,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                    IconButton(onClick = { newName = ""; showCreate = true }) {
                        Icon(
                            imageVector = MiuixIcons.Add,
                            contentDescription = strings.createPlaylist,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxWidth().padding(top = padding.calculateTopPadding())) {
            when {
                !loaded -> LoadingBox()
                local.isEmpty() && remote.isEmpty() && favAlbums.isEmpty() -> EmptyState(
                    title = strings.playlistEmpty,
                    body = strings.emptyPlaylistHint,
                    actionLabel = strings.createPlaylist,
                    onAction = { newName = ""; showCreate = true },
                )
                else -> HexList(
                    contentPadding = PaddingValues(
                        top = 8.dp,
                        bottom = padding.calculateBottomPadding() + 24.dp,
                    ),
                ) {
                    if (syncing) {
                        item(key = "sync") {
                            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                                Text(
                                    text = syncLine,
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                                )
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(progress = null, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }

                    if (local.isNotEmpty()) {
                        item(key = "local-header") { SectionHeader(strings.myPlaylists) }
                        items(local, key = { it.id }) { playlist ->
                            PlaylistRow(playlist, strings) {
                                onNavigate(Route.PlaylistDetail(playlist.id))
                            }
                        }
                    }

                    if (remote.isNotEmpty()) {
                        item(key = "remote-header") {
                            SectionHeader(
                                title = strings.neteasePlaylists,
                                actionLabel = if (session.isSignedIn) strings.syncAllPlaylists else null,
                                onAction = if (session.isSignedIn) ({ syncAll() }) else null,
                            )
                        }
                        items(remote, key = { it.id }) { playlist ->
                            PlaylistRow(playlist, strings) {
                                onNavigate(Route.PlaylistDetail(playlist.id))
                            }
                        }
                    } else if (session.isSignedIn) {
                        item(key = "remote-empty") {
                            EmptyState(
                                title = strings.neverSynced,
                                body = strings.syncAllPlaylists,
                                actionLabel = strings.syncPlaylist,
                                onAction = { syncAll() },
                            )
                        }
                    } else {
                        item(key = "remote-signin") {
                            EmptyState(
                                title = strings.notSignedIn,
                                body = strings.signInSubtitle,
                                actionLabel = strings.signInNetease,
                                onAction = { onNavigate(Route.SignIn) },
                            )
                        }
                    }

                    if (favAlbums.isNotEmpty()) {
                        item(key = "nc-album-header") { SectionHeader(strings.neteaseAlbums) }
                        items(favAlbums, key = { "nc-album-" + it.id }) { album ->
                            CollectionRow(
                                title = album.name,
                                subtitle = album.artistName.ifBlank { null },
                                coverUrl = album.picUrl,
                                overridePath = null,
                                strings = strings,
                                count = album.size,
                                onClick = { openAlbum(album) },
                            )
                        }
                    } else if (session.isSignedIn) {
                        item(key = "nc-album-empty") {
                            EmptyState(
                                title = strings.neteaseAlbums,
                                body = strings.neteaseAlbumsEmpty,
                            )
                        }
                    }
                }
            }
        }

        OverlayDialog(
            show = showCreate,
            title = strings.createPlaylist,
            onDismissRequest = { showCreate = false },
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                TextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = strings.playlistName,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(text = strings.cancel, onClick = { showCreate = false })
                    Spacer(Modifier.width(12.dp))
                    TextButton(
                        text = strings.save,
                        onClick = {
                            val name = newName.trim()
                            showCreate = false
                            if (name.isEmpty()) return@TextButton
                            scope.launch {
                                container.library.createPlaylist(name)
                                message(strings.playlistCreated)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistRow(playlist: Playlist, strings: Strings, onClick: () -> Unit) {
    CollectionRow(
        title = strings.playlistTitle(playlist),
        subtitle = if (playlist.isAllLocal) {
            strings.allLocalMusicSummary
        } else {
            playlist.creator.ifBlank {
                if (playlist.source == PlaylistSource.NETEASE) strings.neteaseMusic else null
            }
        },
        coverUrl = playlist.coverUrl,
        overridePath = playlist.coverOverridePath,
        strings = strings,
        count = playlist.trackCount,
        onClick = onClick,
    )
    if (playlist.subscribed) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 0.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = strings.subscribed,
                style = MiuixTheme.textStyles.footnote2,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.primary,
            )
        }
    }
}
