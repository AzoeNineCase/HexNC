package com.hexnc.music.ui.screens

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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hexnc.music.data.db.CachedAlbum
import com.hexnc.music.data.netease.NcAlbum
import com.hexnc.music.data.netease.NcArtist
import com.hexnc.music.di.AppContainer
import com.hexnc.music.ui.Route
import com.hexnc.music.ui.components.CollectionRow
import com.hexnc.music.ui.components.EmptyState
import com.hexnc.music.ui.components.HexList
import com.hexnc.music.ui.components.LoadingBox
import com.hexnc.music.ui.components.rememberAlbumOpener
import com.hexnc.music.ui.i18n.LocalStrings
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The albums favourited on the NetEase account, as their own page.
 *
 * Rendered from the cache the library sync fills, not from a read on entry. The previous version read
 * one page of at most 200 albums on every visit and showed nothing at all when that read failed,
 * which made a large collection look truncated and a flaky service look like an empty account.
 *
 * The rules here follow from that:
 *  - what is cached is shown immediately, and a refresh only replaces it once a reply actually lands;
 *  - a failed refresh keeps the list on screen and reports the failure, instead of blanking it;
 *  - "never synced" and "synced, empty" are distinct states, because they need different actions.
 *
 * Opening an album still imports it into the local library; nothing is imported in the background.
 */
@Composable
fun NeteaseAlbumsScreen(
    container: AppContainer,
    snackbarHostState: SnackbarHostState,
    onNavigate: (Route) -> Unit,
    onBack: () -> Unit,
) {
    val strings = LocalStrings.current
    val session by container.netease.session.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var albums by remember { mutableStateOf(container.netease.cachedFavouriteAlbums()) }
    var syncedAt by remember { mutableLongStateOf(container.netease.albumsSyncedAt()) }
    var refreshing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    fun reloadCache() {
        albums = container.netease.cachedFavouriteAlbums()
        syncedAt = container.netease.albumsSyncedAt()
    }

    fun refresh() {
        if (refreshing) return
        scope.launch {
            refreshing = true
            failed = false
            container.netease.refreshFavouriteAlbums()
                .onSuccess { reloadCache() }
                .onFailure {
                    // Keep what is on screen: a failed read means "stale", never "no albums".
                    failed = true
                    snackbarHostState.showSnackbar(strings.syncFailed)
                }
            refreshing = false
        }
    }

    val openAlbum = rememberAlbumOpener(
        container,
        strings,
        { text -> scope.launch { snackbarHostState.showSnackbar(text) } },
        onNavigate,
    )

    LaunchedEffect(session.isSignedIn) {
        if (!session.isSignedIn) {
            albums = emptyList()
            syncedAt = 0L
            failed = false
            return@LaunchedEffect
        }
        reloadCache()
        // Only a page with nothing to show pays for a network read; a cached page stays instant.
        if (albums.isEmpty()) refresh()
    }

    DetailScaffold(
        title = strings.neteaseAlbums,
        subtitle = if (albums.isEmpty()) "" else strings.albums(albums.size),
        strings = strings,
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        actions = {
            if (session.isSignedIn) {
                IconButton(onClick = { refresh() }) {
                    Icon(
                        imageVector = MiuixIcons.Refresh,
                        contentDescription = strings.refresh,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxWidth().padding(top = padding.calculateTopPadding())) {
            when {
                !session.isSignedIn -> EmptyState(
                    title = strings.notSignedIn,
                    body = strings.neteaseAlbumsEmpty,
                    actionLabel = strings.signInNetease,
                    onAction = { onNavigate(Route.SignIn) },
                )
                // Spinner only while there is genuinely nothing cached to show.
                refreshing && albums.isEmpty() -> LoadingBox()
                albums.isEmpty() && failed -> EmptyState(
                    title = strings.networkError,
                    body = strings.neteaseAlbumsEmpty,
                    actionLabel = strings.retry,
                    onAction = { refresh() },
                )
                albums.isEmpty() && syncedAt == 0L -> EmptyState(
                    title = strings.neverSynced,
                    body = strings.neteaseAlbumsEmpty,
                    actionLabel = strings.syncAllPlaylists,
                    onAction = { refresh() },
                )
                albums.isEmpty() -> EmptyState(
                    title = strings.neteaseAlbums,
                    body = strings.neteaseAlbumsEmpty,
                )
                else -> HexList(
                    contentPadding = PaddingValues(
                        top = 8.dp,
                        bottom = padding.calculateBottomPadding() + 24.dp,
                    ),
                ) {
                    item(key = "nc-albums-status") {
                        SyncStatusLine(syncedAt = syncedAt, refreshing = refreshing, failed = failed)
                    }
                    items(albums, key = { "nc-album-" + it.id }) { album ->
                        CollectionRow(
                            title = album.name,
                            subtitle = album.artistName.ifBlank { null },
                            coverUrl = album.coverUrl,
                            overridePath = null,
                            strings = strings,
                            count = album.trackCount,
                            onClick = { openAlbum(album.asNcAlbum()) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * One line under the title saying when the cache was last written, or that the last attempt failed.
 *
 * The page has to be able to say "this is old" — a list that silently keeps yesterday's albums looks
 * identical to one that just refreshed.
 */
@Composable
private fun SyncStatusLine(syncedAt: Long, refreshing: Boolean, failed: Boolean) {
    val strings = LocalStrings.current
    val text = when {
        refreshing -> strings.syncing
        failed -> strings.syncFailed
        syncedAt <= 0L -> strings.neverSynced
        else -> strings.lastSyncedAt(formatTimestamp(syncedAt))
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
        )
    }
}

private fun formatTimestamp(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

/** Rebuild the wire model the album opener expects from the cached row. */
private fun CachedAlbum.asNcAlbum(): NcAlbum = NcAlbum(
    id = id,
    name = name,
    picUrl = coverUrl,
    artists = listOf(NcArtist(name = artistName)),
    size = trackCount,
)