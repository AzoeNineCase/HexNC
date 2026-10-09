package com.hexnc.music.ui.screens

import android.net.Uri
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
import com.hexnc.music.ui.platform.DesktopDialogs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
 * Extensions the lyric picker accepts.
 *
 * `.txt` is included on purpose: plenty of downloaded lyrics are plain text with the same
 * `[mm:ss.xx]` lines, and the parser does not care about the file name.
 */
private val LYRIC_EXTENSIONS = setOf("lrc", "txt")

/**
 * The library tab: a scale summary, the recent shelf, the liked shelf, then the album and artist
 * indexes, with the import entry points behind a single app-bar icon.
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
    val scope = rememberCoroutineScope()

    var stats by remember { mutableStateOf<LibraryStats?>(null) }
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var albums by remember { mutableStateOf<List<AlbumGroup>>(emptyList()) }
    var artists by remember { mutableStateOf<List<ArtistGroup>>(emptyList()) }
    var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var actionsSong by remember { mutableStateOf<Song?>(null) }
    var showActions by remember { mutableStateOf(false) }
    var showAddToPlaylist by remember { mutableStateOf(false) }

    suspend fun reload() {
        stats = container.library.libraryStats()
        songs = container.library.songs(limit = SECTION_LIMIT * 3)
        albums = container.library.albums()
        artists = container.library.artists()
        playlists = container.library.playlists()
        loaded = true
    }

    LaunchedEffect(revision) { reload() }

    // Deliberately not part of reload(): the sync bumps the revision reload is keyed on, so doing it
    // there would reschedule itself forever. Once per screen instance is enough to backfill a device
    // whose library was scanned before the playlist existed.
    LaunchedEffect(Unit) { runCatching { container.library.ensureAllLocalPlaylist() } }

    fun message(text: String) {
        scope.launch { snackbarHostState.showSnackbar(text) }
    }

    /**
     * Scans the standard Windows music folder without asking for anything.
     *
     * The Android build had to request `READ_MEDIA_AUDIO` before it could read the system library;
     * on Windows the user's own files are readable, and the picker-free path is the one most people
     * want, so this is a plain scan of `%USERPROFILE%\Music`.
     */
    fun importFromDevice() {
        scope.launch {
            importing = true
            runCatching {
                val found = container.scanner.scanDefaultLibrary()
                val added = container.library.addSongs(found)
                // The folder scanner hands back the sidecars it paired; this picker-free path only
                // returns songs, so the same `.lrc` lookup is run over what it found.
                val lyricCount = container.library.importLyricTexts(container.scanner.lyricsFor(found))
                message(if (added > 0) strings.importFound.format(added, lyricCount) else strings.importNothing)
            }.onFailure { message(strings.importFailed.format(it.message.orEmpty())) }
            importing = false
        }
    }

    fun importFolder(dir: File) {
        scope.launch {
            importing = true
            runCatching {
                val scanned: ScannedFolder = container.scanner.scanFolder(dir)
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

    /**
     * Desktop replacement for the four `rememberLauncherForActivityResult` blocks this screen used
     * on Android.
     *
     * A Windows process has no Activity to hand a result back to, and it needs no runtime
     * permission for the user's own files — so every picker collapses into a plain modal call.
     * [DesktopDialogs] blocks until the dialog closes and is invoked from a click handler that
     * already runs on the AWT event thread, so the chosen path is available on the very next line:
     * no callbacks, and no `pending*` id left dangling between a request and its result.
     */
    fun pickFolder() {
        val dir = DesktopDialogs.chooseDirectory(strings.importFromFolder) ?: return
        importFolder(dir)
    }

    /** Imports an explicit multi-selection, which the desktop picker can return in one go. */
    fun importFiles(files: List<File>) {
        if (files.isEmpty()) return
        scope.launch {
            importing = true
            runCatching {
                val scanned: ScannedFolder = container.scanner.scanFiles(files)
                val added = container.library.addSongs(scanned.songs)
                var lyricCount = 0
                scanned.songs.forEach { song ->
                    val text = scanned.lyrics[song.id] ?: return@forEach
                    if (container.library.importLyricText(song.id, text)) lyricCount++
                }
                message(strings.importFound.format(added, lyricCount))
            }.onFailure { message(strings.importFailed.format(it.message.orEmpty())) }
            importing = false
        }
    }

    fun pickFiles() {
        importFiles(DesktopDialogs.chooseFiles(strings.importFromFiles, DesktopDialogs.AUDIO_EXTENSIONS))
    }

    fun pickCover(songId: String) {
        val file = DesktopDialogs.chooseFile(strings.chooseCoverTitle, DesktopDialogs.IMAGE_EXTENSIONS)
            ?: return
        scope.launch {
            // `setCover` still speaks in `Uri` on desktop: the `android.net.Uri` shim turns the
            // picked file into a `file:` URI and `CoverStore` reads the path straight back out.
            val ok = container.library.setCover(songId, Uri.fromFile(file), CoverScope.SONG)
            message(if (ok) strings.coverUpdated else strings.coverFailed)
        }
    }

    fun pickLyric(songId: String) {
        val file = DesktopDialogs.chooseFile(strings.chooseLyricTitle, LYRIC_EXTENSIONS) ?: return
        scope.launch {
            val text = withContext(Dispatchers.IO) { container.scanner.readText(file) }
            val ok = text != null && container.library.importLyricText(songId, text)
            message(if (ok) strings.importDone else strings.importFailed.format("lrc"))
        }
    }

    fun requestDeviceScan() = importFromDevice()

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

                        item(key = "recent-header") { SectionHeader(strings.recentlyAdded) }
                        items(all.take(SECTION_LIMIT), key = { "recent-" + it.id }) { song ->
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
                pickFolder()
            },
            onPickFiles = {
                showImport = false
                pickFiles()
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
                actionsSong?.id?.let(::pickCover)
            },
            onImportLyric = {
                actionsSong?.id?.let(::pickLyric)
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
    onPickFiles: () -> Unit,
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
            BasicComponent(
                title = strings.importFromFiles,
                summary = strings.importFromFilesSummary,
                startAction = {
                    Icon(
                        imageVector = MiuixIcons.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                },
                onClick = onPickFiles,
            )
            BasicComponent(title = strings.supportsFormats, enabled = false)
            Spacer(Modifier.height(12.dp))
        }
    }
}
