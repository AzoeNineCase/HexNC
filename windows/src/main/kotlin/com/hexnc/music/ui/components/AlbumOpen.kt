package com.hexnc.music.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.hexnc.music.data.netease.NcAlbum
import com.hexnc.music.di.AppContainer
import com.hexnc.music.ui.Route
import com.hexnc.music.ui.i18n.Strings
import kotlinx.coroutines.launch

/**
 * Opens a NetEase album from a browse surface.
 *
 * The album page is a local-library screen, so the album's tracks are materialised first; opening
 * the page before the import would show rows that cannot be played. A single instance guards against
 * double taps because the import is a network round trip with no immediate visual feedback.
 */
@Composable
fun rememberAlbumOpener(
    container: AppContainer,
    strings: Strings,
    onMessage: (String) -> Unit,
    onOpen: (Route) -> Unit,
): (NcAlbum) -> Unit {
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    return remember(container, strings, onMessage, onOpen, importing) {
        { album ->
            if (!importing) {
                scope.launch {
                    importing = true
                    val count = container.netease.importAlbum(album.id).getOrDefault(0)
                    importing = false
                    if (count > 0) {
                        onOpen(Route.AlbumDetail(album.name, album.artistName))
                    } else {
                        onMessage(strings.networkError)
                    }
                }
            }
            Unit
        }
    }
}