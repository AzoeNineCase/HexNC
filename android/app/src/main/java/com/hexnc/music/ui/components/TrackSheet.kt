package com.hexnc.music.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hexnc.music.data.model.Playlist
import com.hexnc.music.data.model.Song
import com.hexnc.music.data.model.SongSource
import com.hexnc.music.di.AppContainer
import com.hexnc.music.ui.i18n.Strings
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Favorites
import top.yukonga.miuix.kmp.icon.extended.FavoritesFill
import top.yukonga.miuix.kmp.icon.extended.Import
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Playlist
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.launch

/**
 * Long-press actions for one track.
 *
 * Everything that mutates state goes through the repositories on the container's scope so that a
 * dismissed sheet never cancels the write it kicked off.
 */
@Composable
fun TrackActionsSheet(
    show: Boolean,
    song: Song?,
    container: AppContainer,
    strings: Strings,
    playlists: List<Playlist>,
    showAddToPlaylist: Boolean,
    onDismiss: () -> Unit,
    onDismissAddToPlaylist: () -> Unit,
    onOpenAddToPlaylist: () -> Unit,
    onSetCover: () -> Unit,
    onImportLyric: () -> Unit,
    onDeleted: () -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    if (song == null) return

    OverlayBottomSheet(
        show = show,
        title = song.title,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            BasicComponent(
                title = if (song.favorite) strings.removeFromFavorites else strings.addToFavorites,
                startAction = { SheetIcon(if (song.favorite) MiuixIcons.FavoritesFill else MiuixIcons.Favorites) },
                onClick = {
                    scope.launch {
                        container.library.bulkFavorite(listOf(song.id), !song.favorite)
                    }
                    onDismiss()
                },
            )
            BasicComponent(
                title = strings.addToQueue,
                startAction = { SheetIcon(MiuixIcons.Play) },
                onClick = {
                    scope.launch {
                        container.playback.enqueue(listOf(song))
                        container.playback.postMessage(strings.addedToQueue)
                    }
                    onDismiss()
                },
            )
            BasicComponent(
                title = strings.addToPlaylist,
                startAction = { SheetIcon(MiuixIcons.Add) },
                onClick = onOpenAddToPlaylist,
            )
            BasicComponent(
                title = strings.changeCover,
                startAction = { SheetIcon(MiuixIcons.Edit) },
                onClick = {
                    onSetCover()
                    onDismiss()
                },
            )
            BasicComponent(
                title = strings.importTitle,
                summary = strings.importFromFilesSummary,
                startAction = { SheetIcon(MiuixIcons.Import) },
                onClick = {
                    onImportLyric()
                    onDismiss()
                },
            )
            if (song.source == SongSource.NETEASE) {
                BasicComponent(
                    title = strings.refresh,
                    summary = strings.about,
                    startAction = { SheetIcon(MiuixIcons.Notes) },
                    onClick = {
                        scope.launch {
                            container.netease.refreshLyrics(song.id)
                            container.library.notifyExternalChange()
                        }
                        onDismiss()
                    },
                )
            }
            BasicComponent(
                title = strings.delete,
                summary = strings.cannotBeUndone,
                startAction = { SheetIcon(MiuixIcons.Delete) },
                onClick = {
                    scope.launch {
                        container.library.deleteSongs(listOf(song.id))
                        onDeleted()
                    }
                    onDismiss()
                },
            )
            Spacer(Modifier.height(12.dp))
        }
    }

    OverlayBottomSheet(
        show = showAddToPlaylist,
        title = strings.addToPlaylist,
        onDismissRequest = onDismissAddToPlaylist,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            if (playlists.isEmpty()) {
                BasicComponent(title = strings.playlistEmpty, enabled = false)
            } else {
                playlists.forEach { playlist ->
                    BasicComponent(
                        title = playlist.name,
                        summary = strings.songs(playlist.trackCount),
                        startAction = { SheetIcon(MiuixIcons.Playlist) },
                    onClick = {
                        scope.launch {
                            container.library.addToPlaylist(playlist.id, listOf(song.id))
                        }
                        onDismissAddToPlaylist()
                    },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SheetIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(22.dp).padding(end = 2.dp),
        tint = MiuixTheme.colorScheme.onSurface,
    )
}
