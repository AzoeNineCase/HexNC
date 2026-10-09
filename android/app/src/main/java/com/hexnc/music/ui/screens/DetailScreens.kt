package com.hexnc.music.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hexnc.music.data.model.Playlist
import com.hexnc.music.data.model.PlaylistSource
import com.hexnc.music.data.model.Song
import com.hexnc.music.data.prefs.PlayMode
import com.hexnc.music.di.AppContainer
import com.hexnc.music.ui.Route
import com.hexnc.music.ui.components.CoverArt
import com.hexnc.music.ui.components.EmptyState
import com.hexnc.music.ui.components.HexList
import com.hexnc.music.ui.components.LoadingBox
import com.hexnc.music.ui.components.SongRow
import com.hexnc.music.ui.components.TrackActionsSheet
import com.hexnc.music.ui.i18n.LocalStrings
import com.hexnc.music.ui.i18n.Strings
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Rename
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

// ------------------------------------------------------------------ playlist

@Composable
fun PlaylistDetailScreen(
    container: AppContainer,
    playlistId: String,
    snackbarHostState: SnackbarHostState,
    onNavigate: (Route) -> Unit,
    onBack: () -> Unit,
) {
    val strings = LocalStrings.current
    val revision by container.library.revision.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var playlist by remember { mutableStateOf<Playlist?>(null) }
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var manage by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf("") }
    var confirmingDelete by remember { mutableStateOf(false) }
    var coverTarget by remember { mutableStateOf<String?>(null) }
    var actionsSong by remember { mutableStateOf<Song?>(null) }
    var showActions by remember { mutableStateOf(false) }
    var showAddToPlaylist by remember { mutableStateOf(false) }
    var allPlaylists by remember { mutableStateOf<List<Playlist>>(emptyList()) }

    LaunchedEffect(playlistId, revision) {
        playlist = container.library.playlist(playlistId)
        songs = container.library.playlistSongs(playlistId)
        allPlaylists = container.library.playlists()
        loaded = true
    }

    fun message(text: String) {
        scope.launch { snackbarHostState.showSnackbar(text) }
    }

    val coverPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        val target = coverTarget
        coverTarget = null
        if (uri != null && target != null) {
            scope.launch {
                val ok = container.library.setPlaylistCover(target, uri)
                message(if (ok) strings.coverUpdated else strings.coverFailed)
            }
        }
    }

    val current = playlist
    DetailScaffold(
        title = current?.let(strings::playlistTitle) ?: strings.loading,
        strings = strings,
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        actions = {
            if (current?.source == PlaylistSource.NETEASE) {
                IconButton(onClick = {
                    scope.launch {
                        val remoteId = current.neteaseId ?: return@launch
                        val result = container.netease.syncPlaylist(remoteId)
                        result
                            .onSuccess { message(strings.syncDone) }
                            .onFailure { message(strings.syncFailed) }
                        container.library.notifyExternalChange()
                    }
                }) {
                    Icon(
                        imageVector = MiuixIcons.Refresh,
                        contentDescription = strings.syncPlaylist,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            if (current != null) {
                IconButton(onClick = { manage = true }) {
                    Icon(
                        imageVector = MiuixIcons.Edit,
                        contentDescription = strings.edit,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxWidth().padding(top = padding.calculateTopPadding())) {
            when {
                !loaded -> LoadingBox()
                current == null -> EmptyState(title = strings.somethingWentWrong)
                songs.isEmpty() -> EmptyState(
                    title = strings.playlistEmpty,
                    body = strings.emptyPlaylistHint,
                )
                else -> SongCollection(
                    songs = songs,
                    strings = strings,
                    bottomPadding = padding.calculateBottomPadding(),
                    header = {
                        CollectionHeader(
                            title = strings.playlistTitle(current),
                            subtitle = current.creator.ifBlank {
                                if (current.source == PlaylistSource.NETEASE) strings.neteaseMusic else null
                            },
                            description = current.description,
                            coverUrl = current.coverUrl,
                            overridePath = current.coverOverridePath,
                            songs = songs,
                            strings = strings,
                        )
                    },
                    onPlayAll = {
                        scope.launch { container.playback.play(songs, 0) }
                    },
                    onShuffle = {
                        scope.launch {
                            container.playback.setPlayMode(PlayMode.SHUFFLE)
                            container.playback.play(songs, songs.indices.random())
                        }
                    },
                    onClickIndex = { index -> scope.launch { container.playback.play(songs, index) } },
                    onLongClick = { song ->
                        actionsSong = song
                        showActions = true
                    },
                )
            }
        }

        OverlayBottomSheet(show = manage, title = strings.playlistName, onDismissRequest = { manage = false }) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // The auto-maintained playlist is titled and filled by the app, so renaming or
                // deleting it is refused by the repository; hiding the rows keeps the sheet honest.
                if (current?.isAllLocal != true) {
                    BasicComponent(
                        title = strings.renamePlaylist,
                        startAction = { SheetActionIcon(MiuixIcons.Rename) },
                        onClick = {
                            nameDraft = current?.name.orEmpty()
                            manage = false
                            renaming = true
                        },
                    )
                }
                BasicComponent(
                    title = strings.playlistCover,
                    startAction = { SheetActionIcon(MiuixIcons.Edit) },
                    onClick = {
                        coverTarget = playlistId
                        manage = false
                        coverPicker.launch("image/*")
                    },
                )
                if (current?.coverOverridePath != null) {
                    BasicComponent(
                        title = strings.resetCover,
                        onClick = {
                            manage = false
                            scope.launch {
                                container.library.clearPlaylistCover(playlistId)
                                message(strings.coverUpdated)
                            }
                        },
                    )
                }
                if (current?.isAllLocal != true) {
                    BasicComponent(
                        title = strings.deletePlaylist,
                        summary = strings.cannotBeUndone,
                        startAction = { SheetActionIcon(MiuixIcons.Delete) },
                        onClick = {
                            manage = false
                            confirmingDelete = true
                        },
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        OverlayDialog(
            show = renaming,
            title = strings.renamePlaylist,
            onDismissRequest = { renaming = false },
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                TextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it },
                    label = strings.playlistName,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(text = strings.cancel, onClick = { renaming = false })
                    Spacer(Modifier.width(12.dp))
                    TextButton(
                        text = strings.save,
                        onClick = {
                            val name = nameDraft.trim()
                            renaming = false
                            if (name.isEmpty()) return@TextButton
                            scope.launch {
                                container.library.renamePlaylist(playlistId, name)
                                message(strings.playlistRenamed)
                            }
                        },
                    )
                }
            }
        }

        OverlayDialog(
            show = confirmingDelete,
            title = strings.deletePlaylist,
            summary = strings.deletePlaylistConfirm,
            onDismissRequest = { confirmingDelete = false },
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                TextButton(text = strings.cancel, onClick = { confirmingDelete = false })
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = strings.delete,
                    onClick = {
                        confirmingDelete = false
                        scope.launch {
                            container.library.deletePlaylist(playlistId)
                            message(strings.playlistDeleted)
                            onBack()
                        }
                    },
                )
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

// ------------------------------------------------------------------ album

@Composable
fun AlbumDetailScreen(
    container: AppContainer,
    album: String,
    artist: String,
    snackbarHostState: SnackbarHostState,
    onNavigate: (Route) -> Unit,
    onBack: () -> Unit,
) {
    val strings = LocalStrings.current
    val revision by container.library.revision.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var actionsSong by remember { mutableStateOf<Song?>(null) }
    var showActions by remember { mutableStateOf(false) }
    var showAddToPlaylist by remember { mutableStateOf(false) }
    var allPlaylists by remember { mutableStateOf<List<Playlist>>(emptyList()) }

    LaunchedEffect(album, artist, revision) {
        songs = container.library.albumSongs(album)
        allPlaylists = container.library.playlists()
        loaded = true
    }

    fun message(text: String) {
        scope.launch { snackbarHostState.showSnackbar(text) }
    }

    DetailScaffold(
        title = album.ifBlank { strings.unknownAlbum },
        subtitle = artist.ifBlank { strings.unknownArtist },
        strings = strings,
        onBack = onBack,
        snackbarHostState = snackbarHostState,
    ) { padding ->
        Box(modifier = Modifier.fillMaxWidth().padding(top = padding.calculateTopPadding())) {
            when {
                !loaded -> LoadingBox()
                songs.isEmpty() -> EmptyState(title = strings.noResults)
                else -> SongCollection(
                    songs = songs,
                    strings = strings,
                    bottomPadding = padding.calculateBottomPadding(),
                    header = {
                        CollectionHeader(
                            title = album.ifBlank { strings.unknownAlbum },
                            subtitle = artist.ifBlank { strings.unknownArtist },
                            description = null,
                            coverUrl = songs.firstOrNull()?.coverUrl,
                            overridePath = songs.firstOrNull()?.coverOverridePath,
                            songs = songs,
                            strings = strings,
                        )
                    },
                    onPlayAll = { scope.launch { container.playback.play(songs, 0) } },
                    onShuffle = {
                        scope.launch {
                            container.playback.setPlayMode(PlayMode.SHUFFLE)
                            container.playback.play(songs, songs.indices.random())
                        }
                    },
                    onClickIndex = { index -> scope.launch { container.playback.play(songs, index) } },
                    onLongClick = { song ->
                        actionsSong = song
                        showActions = true
                    },
                )
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

// ------------------------------------------------------------------ artist

@Composable
fun ArtistDetailScreen(
    container: AppContainer,
    artist: String,
    snackbarHostState: SnackbarHostState,
    onNavigate: (Route) -> Unit,
    onBack: () -> Unit,
) {
    val strings = LocalStrings.current
    val revision by container.library.revision.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var actionsSong by remember { mutableStateOf<Song?>(null) }
    var showActions by remember { mutableStateOf(false) }
    var showAddToPlaylist by remember { mutableStateOf(false) }
    var allPlaylists by remember { mutableStateOf<List<Playlist>>(emptyList()) }

    LaunchedEffect(artist, revision) {
        songs = container.library.artistSongs(artist)
        allPlaylists = container.library.playlists()
        loaded = true
    }

    fun message(text: String) {
        scope.launch { snackbarHostState.showSnackbar(text) }
    }

    DetailScaffold(
        title = artist.ifBlank { strings.unknownArtist },
        strings = strings,
        onBack = onBack,
        snackbarHostState = snackbarHostState,
    ) { padding ->
        Box(modifier = Modifier.fillMaxWidth().padding(top = padding.calculateTopPadding())) {
            when {
                !loaded -> LoadingBox()
                songs.isEmpty() -> EmptyState(title = strings.noResults)
                else -> SongCollection(
                    songs = songs,
                    strings = strings,
                    bottomPadding = padding.calculateBottomPadding(),
                    header = {
                        CollectionHeader(
                            title = artist.ifBlank { strings.unknownArtist },
                            subtitle = strings.artistCount,
                            description = null,
                            coverUrl = songs.firstOrNull()?.coverUrl,
                            overridePath = songs.firstOrNull()?.coverOverridePath,
                            songs = songs,
                            strings = strings,
                        )
                    },
                    onPlayAll = { scope.launch { container.playback.play(songs, 0) } },
                    onShuffle = {
                        scope.launch {
                            container.playback.setPlayMode(PlayMode.SHUFFLE)
                            container.playback.play(songs, songs.indices.random())
                        }
                    },
                    onClickIndex = { index -> scope.launch { container.playback.play(songs, index) } },
                    onLongClick = { song ->
                        actionsSong = song
                        showActions = true
                    },
                )
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

// ------------------------------------------------------------------ shared list body

/**
 * Song list with a cover header and the two "start listening" verbs.
 *
 * Kept private and shared by all three detail screens: they differ only in where the song list
 * comes from, and duplicating the header three times would guarantee they drift apart.
 */
@Composable
private fun SongCollection(
    songs: List<Song>,
    strings: Strings,
    bottomPadding: androidx.compose.ui.unit.Dp,
    header: @Composable () -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onClickIndex: (Int) -> Unit,
    onLongClick: (Song) -> Unit,
) {
    HexList(
        contentPadding = PaddingValues(top = 8.dp, bottom = bottomPadding + 24.dp),
    ) {
        item(key = "header") { header() }
        item(key = "verbs") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(onClick = onPlayAll) {
                    Text(strings.playAll, style = MiuixTheme.textStyles.button)
                }
                TextButton(text = strings.shuffleAll, onClick = onShuffle)
            }
        }
        items(songs, key = { it.id }) { song ->
            SongRow(
                song = song,
                strings = strings,
                onClick = { onClickIndex(songs.indexOf(song).coerceAtLeast(0)) },
                onLongClick = { onLongClick(song) },
            )
        }
    }
}

/** Cover, title and metadata block shared by the three detail screens. */
@Composable
private fun CollectionHeader(
    title: String,
    subtitle: String?,
    description: String?,
    coverUrl: String?,
    overridePath: String?,
    songs: List<Song>,
    strings: Strings,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CoverArt(
                coverUrl = coverUrl,
                overridePath = overridePath,
                size = 124.dp,
                corner = 20.dp,
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.title4,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = strings.songs(songs.size),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
        }
        if (!description.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = description,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SheetActionIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(22.dp),
        tint = MiuixTheme.colorScheme.onSurface,
    )
}
