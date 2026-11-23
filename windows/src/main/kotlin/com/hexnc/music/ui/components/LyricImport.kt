package com.hexnc.music.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.hexnc.music.di.AppContainer
import com.hexnc.music.ui.i18n.Strings
import com.hexnc.music.ui.platform.DesktopDialogs
import kotlinx.coroutines.launch

/**
 * Extensions the lyric picker accepts.
 *
 * `.txt` is included on purpose: plenty of downloaded lyrics are plain text with the same
 * `[mm:ss.xx]` lines, and the parser falls back to untimed lines anyway.
 */
private val LYRIC_EXTENSIONS = setOf("lrc", "txt")

/**
 * The "import a `.lrc` for this song" action, shared by every screen that offers it.
 *
 * The library screen grew its own picker early; the track sheets on the detail and search screens
 * were left pointing at a placeholder that only showed the text "选择文件", so tapping the row did
 * nothing at all — which is one half of the reported "cannot import local lyrics". Keeping the
 * picker here means a new call site cannot regress to a no-op: it either calls this or has no action
 * to wire.
 *
 * The desktop dialog blocks, so it is opened straight from the returned lambda: a Compose click
 * handler already runs on the AWT event thread, which is the thread AWT dialogs must be created on.
 * Only the read and the write move to a coroutine. This mirrors `LibraryScreen.pickLyric`.
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

    // Keyed on `onMessage` as well: call sites build it with `::message` or a fresh lambda during
    // composition, and reusing the first one would report into a snackbar host that is long gone.
    return remember(container, strings, onMessage) {
        { songId: String ->
            val file = DesktopDialogs.chooseFile(strings.chooseLyricTitle, LYRIC_EXTENSIONS)
            if (file != null) {
                val name = container.scanner.displayName(file)
                scope.launch {
                    // `readText` already hops to Dispatchers.IO, so only the dialog stays on the
                    // AWT thread — blocking it for a file read would freeze the window.
                    val text = container.scanner.readText(file)
                    if (text == null) {
                        onMessage(strings.importFailed.format(name))
                        return@launch
                    }
                    // Picking a file is as explicit as tapping a candidate in the online lyric
                    // sheet, and that path is allowed to overwrite a deleted sheet: `saveLyrics`
                    // refuses unless the tombstone is cleared first. Without this the import
                    // silently returns false, which reads as "lyrics will not import".
                    container.library.allowLyricsAgain(songId)
                    val saved = container.library.importLyricText(songId, text)
                    onMessage(if (saved) strings.importDone else strings.importFailed.format(name))
                }
            }
        }
    }
}