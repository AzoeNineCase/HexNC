package com.hexnc.music.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.hexnc.music.data.netease.NcAlbum
import com.hexnc.music.di.AppContainer

/**
 * The NetEase releases of one artist, as the artist detail page needs them.
 *
 * A holder, not a screen: the page owns its layout and its track list, so this file only answers
 * "which albums, is the answer still on its way, and did the last attempt fail". The rows are the
 * caller's business, which is also why nothing here touches playback or the local database.
 */
class ArtistAlbumsState(
    val loading: Boolean,
    val albums: List<NcAlbum>,
    val failed: Boolean,
    val retry: () -> Unit,
)

/**
 * Loads the discography of [artistName] and keeps it across recompositions.
 *
 * Failure is a flag rather than an empty list on purpose: a transient failure that blanked the
 * section would look like "this artist has released nothing", and the previous page of albums is
 * usually still the best thing on screen. [retry] therefore re-runs the same request instead of
 * letting the caller rebuild the whole screen.
 *
 * [neteaseArtistId] is optional because a local-only artist has no NetEase identity; the repository
 * then falls back to an album search by name. An empty [artistName] is not an error — it is what an
 * untagged file looks like — and resolves to an empty, settled state without a request.
 */
@Composable
fun rememberArtistAlbums(
    container: AppContainer,
    artistName: String,
    neteaseArtistId: Long? = null,
): ArtistAlbumsState {
    // Keyed on the artist so that navigating to a different one starts from a clean slate instead
    // of showing the previous artist's albums for a frame.
    var attempt by remember(artistName, neteaseArtistId) { mutableIntStateOf(0) }
    var albums by remember(artistName, neteaseArtistId) { mutableStateOf<List<NcAlbum>>(emptyList()) }
    // Seeded from the name so the first frame already reads as "loading" for a real artist; a zero
    // would flash the empty state before the effect gets a chance to run.
    var loading by remember(artistName, neteaseArtistId) { mutableStateOf(artistName.trim().isNotEmpty()) }
    var failed by remember(artistName, neteaseArtistId) { mutableStateOf(false) }

    LaunchedEffect(artistName, neteaseArtistId, attempt) {
        val name = artistName.trim()
        if (name.isEmpty()) {
            albums = emptyList()
            loading = false
            failed = false
            return@LaunchedEffect
        }
        loading = true
        failed = false
        val result = container.netease.artistAlbums(name, neteaseArtistId)
        // On failure `albums` is deliberately left as it was: the section keeps its last good
        // content and surfaces `failed` so the caller can offer the retry.
        result.onSuccess { albums = it }
        result.onFailure { failed = true }
        loading = false
    }

    return ArtistAlbumsState(
        loading = loading,
        albums = albums,
        failed = failed,
        retry = { attempt++ },
    )
}