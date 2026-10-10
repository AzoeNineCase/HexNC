package com.hexnc.music.ui.screens

import android.Manifest
import android.net.Uri
import android.os.Build
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hexnc.music.data.db.AlbumGroup
import com.hexnc.music.data.db.ArtistGroup
import com.hexnc.music.data.local.ScannedFolder
import com.hexnc.music.data.model.Playlist
import com.hexnc.music.data.model.Song
import com.hexnc.music.data.repo.CoverScope
import com.hexnc.music.data.repo.LibraryStats
import com.hexnc.music.di.AppContainer
import com.hexnc.music.ui.HexScaffold
import com.hexnc.music.ui.Route
import com.hexnc.music.ui.components.CollectionRow
import com.hexnc.music.ui.components.EmptyState
import com.hexnc.music.ui.components.HexList
import com.hexnc.music.ui.components.LoadingBox
import com.hexnc.music.ui.components.SectionHeader
import com.hexnc.music.ui.components.SongRow
import com.hexnc.music.ui.components.TrackActionsSheet
import com.hexnc.music.ui.i18n.LocalStrings
import com.hexnc.music.ui.i18n.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Album
import top.yukonga.miuix.kmp.icon.extended.CloudFill
import top.yukonga.miuix.kmp.icon.extended.Favorites
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.Import
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

private const val SECTION_LIMIT = 6

/**
 * How many rows each recent shelf shows.
 *
 * Deliberately shorter than [SECTION_LIMIT]: the library tab is a launch pad, not a history page —
 * everything past these rows is one tap away through the album and artist indexes, or the search tab.
 */
private const val RECENT_SHELF_LIMIT = 4

/**
 * The library tab: a scale summary, the play-history shelf, the recently-added shelf, the liked
 * shelf, then the album and artist indexes, with the import entry points behind one app-bar icon.
 */
@Composable
fun LibraryScreen(
    container: AppContainer,
    snackbarHostState: SnackbarHostState,
    onNavigate: (Route) -> Unit,
) {
    val strings = LocalStrings.current
    val revision by container.library.revision.collectAsStateWithLifecycle()
    val session by container.netease.session.collectAsStateWithLifecycle()
    val settingsState by container.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var stats by remember { mutableStateOf<LibraryStats?>(null) }
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var recentlyPlayed by remember { mutableStateOf<List<Song>>(emptyList()) }
    var albums by remember { mutableStateOf<List<AlbumGroup>>(emptyList()) }
    var artists by remember { mutableStateOf<List<ArtistGroup>>(emptyList()) }
    var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var actionsSong by remember { mutableStateOf<Song?>(null) }
    var showActions by remember { mutableStateOf(false) }
    var showAddToPlaylist by remember { mutableStateOf(false) }
    var pendingCoverSongId by remember { mutableStateOf<String?>(null) }
    var pendingLyricSongId by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        stats = container.library.libraryStats()
        songs = container.library.songs(limit = SECTION_LIMIT * 3)
        // The history is a list of ids this device remembers; deleted or unimported ids simply drop
        // out of the lookup, so the shelf never shows a row that cannot be played.
        recentlyPlayed = container.library.recentlyPlayed(container.settings.current.recentlyPlayedIds)
        albums = container.library.albums()
        artists = container.library.artists()
        playlists = container.library.playlists()
        loaded = true
    }

    LaunchedEffect(revision) { reload() }

    // Play history lives in preferences, not in the database, so it is not covered by [revision]:
    // without this key, playing a track and coming back would show a stale shelf. It is keyed on the
    // id list alone so that an unrelated preference change does not reload the whole tab.
    LaunchedEffect(settingsState.recentlyPlayedIds) {
        recentlyPlayed = container.library.recentlyPlayed(settingsState.recentlyPlayedIds)
    }

    // Deliberately not part of reload(): the sync bumps the revision reload is keyed on, so doing it
    // there would reschedule itself forever. Once per screen instance is enough to backfill a device
    // whose library was scanned before the playlist existed.
    LaunchedEffect(Unit) { runCatching { container.library.ensureAllLocalPlaylist() } }

    fun message(text: String) {
        scope.launch { snackbarHostState.showSnackbar(text) }
    }

    fun importFromDevice() {
        scope.launch {
            importing = true
            runCatching {
                val found: ScannedFolder = container.scanner.scanMediaStore()
                val added = container.library.addSongs(found.songs)
                // The media store does not index `.lrc` files, so the scanner looks for them itself
                // and hands back the pairs it found; one call stores them all.
                val lyricCount = container.library.importLyricTexts(found.lyrics)
                message(if (added > 0) strings.importFound.format(added, lyricCount) else strings.importNothing)
            }.onFailure { message(strings.importFailed.format(it.message.orEmpty())) }
            importing = false
        }
    }

    fun importFolder(uri: Uri) {
        scope.launch {
            importing = true
            runCatching {
                val scanned: ScannedFolder = container.scanner.scanFolder(uri)
                val added = container.library.addSongs(scanned.songs)
                // The scanner already paired each `.lrc` sidecar to its track's id, so this is a
                // direct lookup rather than a second pass over file names.
                val lyricCount = container.library.importLyricTexts(scanned.lyrics)
                message(strings.importFound.format(added, lyricCount))
            }.onFailure { message(strings.importFailed.format(it.message.orEmpty())) }
            importing = false
        }
    }

    fun playFrom(list: List<Song>, index: Int) {
        scope.launch { container.playback.play(list, index) }
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        uri?.let {
            // Persist the grant first: without it the songs become unreadable — no lyrics, no
            // playback — as soon as the task is killed.
            container.scanner.rememberTreeAccess(it)
            importFolder(it)
        }
    }

    val audioPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) importFromDevice() else message(strings.permissionDenied) }

    val coverPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        val id = pendingCoverSongId
        pendingCoverSongId = null
        if (uri != null && id != null) {
            scope.launch {
                val ok = container.library.setCover(id, uri, CoverScope.SONG)
                message(if (ok) strings.coverUpdated else strings.coverFailed)
            }
        }
    }

    val lyricPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        val id = pendingLyricSongId
        pendingLyricSongId = null
        if (uri != null && id != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) { container.scanner.readText(uri) }
                val ok = text != null && container.library.importLyricText(id, text)
                message(if (ok) strings.importDone else strings.importFailed.format("lrc"))
            }
        }
    }

    fun requestDeviceScan() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        audioPermission.launch(permission)
    }

    HexScaffold(
        container = container,
        strings = strings,
        selectedTab = 0,
        onNavigate = onNavigate,
        snackbarHostState = snackbarHostState,
        topBar = {
            TopAppBar(
                title = strings.library,
                subtitle = session.takeIf { it.isSignedIn }?.nickname.orEmpty(),
                actions = {
                    IconButton(onClick = { showImport = true }) {
                        Icon(
                            imageVector = MiuixIcons.Import,
                            contentDescription = strings.importTitle,
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
                songs.isEmpty() -> EmptyState(
                    title = strings.libraryEmptyTitle,
                    body = strings.libraryEmptyBody,
                    actionLabel = strings.importMusic,
                    onAction = { showImport = true },
                )
                else -> {
                    val liked = songs.filter { it.favorite }.take(SECTION_LIMIT)
                    val all = songs
                    HexList(
                        contentPadding = PaddingValues(
                            top = 8.dp,
                            bottom = padding.calculateBottomPadding() + 24.dp,
                        ),
                    ) {
                        stats?.let { current ->
                            item(key = "stats") {
                                StatsCard(
                                    stats = current,
                                    albumCount = albums.size,
                                    artistCount = artists.size,
                                    strings = strings,
                                )
                            }
                        }

                        if (recentlyPlayed.isNotEmpty()) {
                            item(key = "played-header") { SectionHeader(strings.recentlyPlayed) }
                            items(recentlyPlayed.take(RECENT_SHELF_LIMIT), key = { "played-" + it.id }) { song ->
                                SongRow(
                                    song = song,
                                    strings = strings,
                                    onClick = {
                                        playFrom(recentlyPlayed, recentlyPlayed.indexOf(song).coerceAtLeast(0))
                                    },
                                    onLongClick = {
                                        actionsSong = song
                                        showActions = true
                                    },
                                )
                            }
                        }

                        item(key = "recent-header") { SectionHeader(strings.recentlyAdded) }
                        items(all.take(RECENT_SHELF_LIMIT), key = { "recent-" + it.id }) { song ->
                            SongRow(
                                song = song,
                                strings = strings,
                                onClick = { playFrom(all, all.indexOf(song).coerceAtLeast(0)) },
                                onLongClick = {
                                    actionsSong = song
                                    showActions = true
                                },
                            )
                        }

                        if (liked.isNotEmpty()) {
                            item(key = "fav-header") { SectionHeader(strings.favorites) }
                            items(liked, key = { "fav-" + it.id }) { song ->
                                SongRow(
                                    song = song,
                                    strings = strings,
                                    onClick = { playFrom(liked, liked.indexOf(song).coerceAtLeast(0)) },
                                    onLongClick = {
                                        actionsSong = song
                                        showActions = true
                                    },
                                )
                            }
                        }

                        if (albums.isNotEmpty()) {
                            item(key = "album-header") { SectionHeader(strings.albums) }
                            items(albums.take(SECTION_LIMIT), key = { "album-" + it.album }) { group ->
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

                        if (artists.isNotEmpty()) {
                            item(key = "artist-header") { SectionHeader(strings.artists) }
                            items(artists.take(SECTION_LIMIT), key = { "artist-" + it.artist }) { group ->
                                CollectionRow(
                                    title = group.artist.ifBlank { strings.unknownArtist },
                                    subtitle = strings.playlists(group.albumCount),
                                    coverUrl = null,
                                    overridePath = null,
                                    strings = strings,
                                    count = group.trackCount,
                                    onClick = { onNavigate(Route.ArtistDetail(group.artist)) },
                                )
                            }
                        }
                    }
                }
            }

            if (importing) LoadingBox()
        }

        ImportSheet(
            show = showImport,
            strings = strings,
            onDismiss = { showImport = false },
            onScanDevice = {
                showImport = false
                requestDeviceScan()
            },
            onPickFolder = {
                showImport = false
                folderPicker.launch(null)
            },
        )

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
            onSetCover = {
                pendingCoverSongId = actionsSong?.id
                coverPicker.launch("image/*")
            },
            onImportLyric = {
                pendingLyricSongId = actionsSong?.id
                lyricPicker.launch("*/*")
            },
            onDeleted = { message(strings.delete) },
        )
    }
}

/** The numeric summary that gives the library a sense of scale at a glance. */
@Composable
private fun StatsCard(stats: LibraryStats, albumCount: Int, artistCount: Int, strings: Strings) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        cornerRadius = 20.dp,
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer),
        pressFeedbackType = PressFeedbackType.None,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatCell(strings.songs, stats.songCount.toString(), MiuixIcons.Music)
            StatCell(strings.albums, albumCount.toString(), MiuixIcons.Album)
            StatCell(strings.artists, artistCount.toString(), MiuixIcons.Folder)
            StatCell(strings.favorites, stats.favoriteCount.toString(), MiuixIcons.Favorites)
            if (stats.neteaseCount > 0) {
                StatCell(strings.tabPlaylists, stats.playlistCount.toString(), MiuixIcons.CloudFill)
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, icon: ImageVector) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MiuixTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        Text(text = value, style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.SemiBold)
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
        )
    }
}

/** Import entry points, presented as a sheet so the app bar stays a single icon. */
@Composable
private fun ImportSheet(
    show: Boolean,
    strings: Strings,
    onDismiss: () -> Unit,
    onScanDevice: () -> Unit,
    onPickFolder: () -> Unit,
) {
    OverlayBottomSheet(show = show, title = strings.importTitle, onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            BasicComponent(
                title = strings.importFromDevice,
                summary = strings.importFromDeviceSummary,
                startAction = {
                    Icon(
                        imageVector = MiuixIcons.Music,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                },
                onClick = onScanDevice,
            )
            BasicComponent(
                title = strings.importFromFolder,
                summary = strings.importFromFolderSummary,
                startAction = {
                    Icon(
                        imageVector = MiuixIcons.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                },
                onClick = onPickFolder,
            )
            BasicComponent(title = strings.supportsFormats, enabled = false)
            Spacer(Modifier.height(12.dp))
        }
    }
}
