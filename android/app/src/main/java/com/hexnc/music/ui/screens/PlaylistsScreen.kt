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
import androidx.compose.runtime.mutableIntStateOf
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
import com.hexnc.music.di.AppContainer
import com.hexnc.music.ui.HexScaffold
import com.hexnc.music.ui.Route
import com.hexnc.music.ui.components.CollectionRow
import com.hexnc.music.ui.components.EmptyState
import com.hexnc.music.ui.components.HexList
import com.hexnc.music.ui.components.LoadingBox
import com.hexnc.music.ui.components.NavRow
import com.hexnc.music.ui.components.SectionHeader
import com.hexnc.music.ui.i18n.LocalStrings
import com.hexnc.music.ui.i18n.Strings
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
import top.yukonga.miuix.kmp.icon.extended.Album
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The playlists tab.
 *
 * Local and NetEase playlists share one list type, so they are rendered as two labelled sections
 * rather than two screens; the row already carries the source through its cover and subtitle. The
 * albums favourited on NetEase now have their own page, reached through one entry at the bottom of
 * this list — inline they sat below the fold, and a failed read made the section vanish entirely.
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
    var favAlbumCount by remember { mutableIntStateOf(0) }

    suspend fun reload() {
        local = container.library.playlistsOf(PlaylistSource.LOCAL)
        remote = container.library.playlistsOf(PlaylistSource.NETEASE)
        loaded = true
    }

    LaunchedEffect(revision) { reload() }

    // The tab has to answer "how fresh is this?" without the user opening a page: the sync line is
    // seeded from the cached timestamp and replaced by the result summary after a manual sync.
    LaunchedEffect(session.isSignedIn, revision) {
        favAlbumCount = if (session.isSignedIn) container.netease.cachedFavouriteAlbums().size else 0
        if (syncLine.isEmpty()) {
            val at = container.netease.albumsSyncedAt()
            syncLine = if (at <= 0L) strings.neverSynced else strings.lastSyncedAt(formatSyncTime(at))
        }
    }

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
                .onSuccess { report ->
                    syncLine = strings.syncSummary(report.albums, report.skipped, report.failures.size)
                    message("${strings.syncDone} · ${strings.songs(report.tracks)}")
                }
                .onFailure {
                    syncLine = strings.syncFailed
                    message(strings.syncFailed)
                }
            container.library.notifyExternalChange()
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
                // A signed-in account always gets the list, even when it is empty: the favourited
                // albums entry lives at the bottom of it and must stay reachable.
                local.isEmpty() && remote.isEmpty() && !session.isSignedIn -> EmptyState(
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

                    // Favourited albums get their own page instead of a third inline shelf: inline
                    // they sat below the fold, and a failed read made the section vanish entirely.
                    item(key = "nc-album-entry") {
                        NavRow(
                            title = strings.neteaseAlbums,
                            subtitle = if (favAlbumCount == 0) {
                                strings.neverSynced
                            } else {
                                strings.albums(favAlbumCount)
                            },
                            icon = MiuixIcons.Album,
                            onClick = { onNavigate(Route.NeteaseAlbums) },
                        )
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

/** Short local time for the sync line: long enough to judge staleness, short enough for a subtitle. */
private fun formatSyncTime(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))