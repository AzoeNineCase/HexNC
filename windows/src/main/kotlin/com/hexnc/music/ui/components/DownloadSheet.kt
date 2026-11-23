package com.hexnc.music.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hexnc.music.data.download.DownloadConfig
import com.hexnc.music.data.download.DownloadState
import com.hexnc.music.data.model.Song
import com.hexnc.music.di.AppContainer
import com.hexnc.music.ui.i18n.Strings
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Snapshot of the download preferences, read when a run starts rather than when the screen is built,
 * so changing the tier in settings applies to the very next download.
 */
fun downloadConfig(container: AppContainer, strings: Strings): DownloadConfig = DownloadConfig(
    level = container.settings.current.downloadLevel,
    embedCover = container.settings.current.downloadEmbedCover,
    writeLyric = container.settings.current.downloadWriteLyric,
    unknownArtist = strings.unknownArtist,
    unknownAlbum = strings.unknownAlbum,
)

/**
 * The download queue panel.
 *
 * It stays up after a run ends on purpose: "3 saved, 1 skipped, 1 had no copyright" is the part a
 * user actually needs to read, and a toast that disappears after two seconds cannot carry a failure
 * reason. While a run is in flight the primary action is Cancel; afterwards it becomes Close, with
 * Retry appearing only when something failed.
 */
@Composable
fun DownloadSheet(
    show: Boolean,
    state: DownloadState,
    strings: Strings,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    OverlayBottomSheet(
        show = show,
        title = if (state.running) strings.downloading else strings.downloadDone,
        onDismissRequest = { if (state.running) onCancel() else onClose() },
    ) {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                val current = state.currentTitle
                Text(
                    text = current ?: strings.downloadDone,
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = state.progress,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = strings.downloadProgress(
                            state.finished.coerceAtMost(state.total),
                            state.total,
                        ),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                    Text(
                        text = strings.downloadCounts(state.completed, state.skipped, state.failed.size),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }
                if (state.formats.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = buildString {
                            append(strings.downloadFormats(state.formats))
                            if (state.belowLossless > 0) {
                                append(" · ").append(strings.downloadBelowLossless(state.belowLossless))
                            }
                        },
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }
            }
            if (state.failed.isNotEmpty()) {
                state.failed.take(MAX_FAILURE_ROWS).forEach { failure ->
                    BasicComponent(
                        title = failure.title,
                        summary = strings.downloadError(failure.error),
                        enabled = false,
                    )
                }
            }
            if (state.running) {
                BasicComponent(title = strings.downloadCancel, onClick = onCancel)
            } else {
                if (state.failed.isNotEmpty()) {
                    BasicComponent(title = strings.downloadRetry, onClick = onRetry)
                }
                BasicComponent(title = strings.close, onClick = onClose)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Long queues only need the first few reasons; the rest are the same few sentences repeated. */
private const val MAX_FAILURE_ROWS = 4

/**
 * Tracks that can actually be exported.
 *
 * Only NetEase rows have a remote source to fetch; local files are already on disk, so offering a
 * download for them would produce a list of "not supported" failures.
 */
fun List<Song>.downloadable(): List<Song> =
    filter { it.source == com.hexnc.music.data.model.SongSource.NETEASE && (it.neteaseId ?: 0L) > 0L }