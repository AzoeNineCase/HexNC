package com.hexnc.music.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hexnc.music.data.model.Song
import com.hexnc.music.data.model.SongSource
import com.hexnc.music.data.repo.GrantedQuality
import com.hexnc.music.ui.i18n.Strings
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The "song info" sheet behind the playback page's expand action.
 *
 * Only facts the library actually stores are shown — nothing is inferred, fetched or guessed — so
 * the sheet is correct offline, for local files and for NetEase streams alike. Values that the row
 * genuinely does not know (a stream has no bitrate, a manual import has no file size) collapse into
 * an em dash instead of disappearing, which keeps the label column stable between tracks.
 */
@Composable
fun SongInfoSheet(
    show: Boolean,
    song: Song?,
    strings: Strings,
    onDismiss: () -> Unit,
    granted: GrantedQuality? = null,
) {
    if (song == null) return
    OverlayBottomSheet(
        show = show,
        title = strings.songInfo,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverArt(
                    coverUrl = song.coverUrl,
                    overridePath = song.coverOverridePath,
                    size = 56.dp,
                    corner = 12.dp,
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        style = MiuixTheme.textStyles.subtitle,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = song.displayArtist,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            SongInfoRow(strings.album, song.album.ifBlank { DASH })
            SongInfoRow(strings.artist, song.displayArtist)
            if (song.trackNo > 0) {
                SongInfoRow(strings.trackNumber, song.trackNo.toString())
            }
            SongInfoRow(strings.duration, formatDuration(song.durationMs))
            SongInfoRow(strings.source, sourceLabel(song, strings))
            // What the service actually granted beats what the library recorded when a stream was
            // imported: a track fetched today as FLAC still carries the bitrate it had last month.
            SongInfoRow(strings.quality, qualityLabel(song, granted, strings))
            SongInfoRow(strings.fileFormat, formatLabel(song, granted))
            SongInfoRow(strings.fileSize, sizeLabel(song.size))
            SongInfoRow(
                strings.lyricsAvailable,
                if (song.hasLyrics) strings.yes else strings.no,
            )
            if (song.addedAt > 0L) {
                SongInfoRow(strings.addedAtLabel, dateLabel(song.addedAt))
            }
            val path = song.localPath?.takeIf { it.isNotBlank() }
                ?: song.mediaUri?.takeIf { it.isNotBlank() }
            if (path != null) {
                SongInfoRow(strings.filePath, path, wrap = true)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** One `label → value` line; [wrap] lets a long value (a path) take as many lines as it needs. */
@Composable
private fun SongInfoRow(label: String, value: String, wrap: Boolean = false) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = label,
                modifier = Modifier.width(84.dp),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                maxLines = 1,
            )
            Text(
                text = value,
                modifier = Modifier.weight(1f),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onBackground,
                maxLines = if (wrap) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 20.dp),
            thickness = 0.5.dp,
            color = MiuixTheme.colorScheme.outline.copy(alpha = 0.35f),
        )
    }
}

private const val DASH = "—"

/** `local:` songs are files; `ne:` songs are streamed from NetEase. */
private fun sourceLabel(song: Song, strings: Strings): String = when (song.source) {
    SongSource.LOCAL -> strings.localFile
    SongSource.NETEASE -> strings.onlineStream
}

private fun bitrateLabel(song: Song): String =
    if (song.bitrate > 0) "${song.bitrate / 1000} kbps" else DASH

/**
 * The granted tier wins over the stored bitrate, because the stored value describes the *import*,
 * not the stream playing now: a track imported as a 320 kbps MP3 and played today as FLAC would
 * otherwise keep claiming 320 kbps forever.
 */
private fun qualityLabel(song: Song, granted: GrantedQuality?, strings: Strings): String {
    if (granted != null && granted.container.isNotBlank() && granted.bitrate > 0) {
        val base = strings.qualityChip(granted.container, granted.bitrate / 1000)
        return if (granted.belowLossless) "$base · ${strings.qualityBelowLossless}" else base
    }
    return bitrateLabel(song)
}

/** The container extension of the file behind this song, or the format the service granted. */
private fun formatLabel(song: Song, granted: GrantedQuality?): String {
    val name = song.localPath?.takeIf { it.isNotBlank() } ?: song.mediaUri.orEmpty()
    val extension = name.substringAfterLast('.', missingDelimiterValue = "").trim()
    if (extension.isNotEmpty()) {
        // A signed stream URL carries query parameters after the extension; cut them first.
        return extension.substringBefore('?').substringBefore('&').uppercase(Locale.ROOT)
    }
    val container = granted?.container?.takeIf { it.isNotBlank() }
    if (container != null) return container.uppercase(Locale.ROOT)
    return if (song.source == SongSource.NETEASE) "MP3" else DASH
}

private fun sizeLabel(size: Long): String = when {
    size <= 0L -> DASH
    size >= 1L shl 30 -> String.format(Locale.ROOT, "%.2f GB", size / 1073741824.0)
    size >= 1L shl 20 -> String.format(Locale.ROOT, "%.1f MB", size / 1048576.0)
    else -> String.format(Locale.ROOT, "%d KB", maxOf(1L, size / 1024))
}

private fun dateLabel(epochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))