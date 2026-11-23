package com.hexnc.music.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.hexnc.music.di.AppContainer
import com.hexnc.music.ui.i18n.Strings
import kotlinx.coroutines.launch

/**
 * MIME filter for the lyric picker.
 *
 * `.lrc` has no registered MIME type on most devices, so a `text/*`-only filter greys the file out
 * in the document picker. `text/plain` is offered first because it is what a `.txt` sheet reports,
 * and `*/*` second so the `.lrc` is always selectable; the filter is a hint, not a guarantee.
 */
private val LYRIC_MIME_TYPES = arrayOf("text/plain", "*/*")

/**
 * The "import a `.lrc` for this song" action, shared by every screen that offers it.
 *
 * The library screen grew its own picker early; the track sheets on the detail and search screens
 * were left pointing at a placeholder that only showed the text "选择文件", so tapping the row did
 * nothing at all — which is one half of the reported "cannot import local lyrics". Keeping the
 * picker here means a new call site cannot regress to a no-op: it either calls this or has no action
 * to wire.
 *
 * The document picker hands its result back to a callback that runs outside this composition, so the
 * song id has to be parked in state across the activity round trip — the same `pendingLyricSongId`
 * pattern `LibraryScreen` uses for its own pickers.
 *
 * @return a lambda taking the song id to attach the picked sheet to. Invoke it from the click
 *   handler of the import row; it reports the outcome through [onMessage].
 */
@Composable
fun rememberLyricImporter(
    container: AppContainer,
    strings: Strings,
    onMessage: (String) -> Unit,
): (String) -> Unit {
    val scope = rememberCoroutineScope()
    var pendingSongId by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val songId = pendingSongId
        pendingSongId = null
        // A dismissed picker is not an error; only a real pick with a pending song is handled.
        if (uri == null || songId == null) return@rememberLauncherForActivityResult
        scope.launch {
            // `readText(uri)` opens the document through the content resolver (and already hops to
            // Dispatchers.IO), so the `content://` URI the picker returns is read directly — no
            // path, no storage permission, and no `File(uri)` attempt, which could never work for a
            // document URI.
            val text = container.scanner.readText(uri)
            // The display name query can come back empty; the path segment is a readable enough
            // label for the failure message.
            val name = container.scanner.displayName(uri) ?: uri.lastPathSegment ?: "lrc"
            if (text == null) {
                onMessage(strings.importFailed.format(name))
                return@launch
            }
            // Picking a file is as explicit as tapping a candidate in the online lyric
            // sheet, and that path is allowed to overwrite a deleted sheet: `saveLyrics` refuses
            // unless the tombstone is cleared first. Without this the import silently returns
            // false, which reads as "lyrics will not import".
            container.library.allowLyricsAgain(songId)
            val saved = container.library.importLyricText(songId, text)
            onMessage(if (saved) strings.importDone else strings.importFailed.format(name))
        }
    }

    return remember(picker) {
        { songId: String ->
            pendingSongId = songId
            picker.launch(LYRIC_MIME_TYPES)
        }
    }
}