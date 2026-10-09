package com.hexnc.music.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hexnc.music.data.lyrics.LyricCandidate
import com.hexnc.music.data.lyrics.ScoredCandidate
import com.hexnc.music.ui.i18n.Strings
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/**
 * The "search for lyrics elsewhere" dialog, opened from the lyric pane of a song that has none.
 *
 * The query is the song's own tags to begin with, so the common case is one tap on a result rather
 * than typing. Each row says where a candidate came from and whether its lyrics are timed, because
 * a plain-text sheet renders as static lines with no lyric scrolling — the user should know that
 * before choosing it over a synced one.
 */
@Composable
fun LyricSearchSheet(
    show: Boolean,
    query: String,
    results: List<ScoredCandidate>,
    searching: Boolean,
    status: String?,
    strings: Strings,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onPick: (LyricCandidate) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!show) return
    OverlayDialog(
        show = show,
        title = strings.lyricSearchTitle,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = query,
                    onValueChange = onQueryChange,
                    label = strings.lyricSearchHint,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                TextButton(text = strings.search, onClick = onSearch)
            }
            Spacer(Modifier.height(12.dp))
            when {
                searching -> Caption(strings.lyricSearchRunning)
                results.isEmpty() -> Caption(status ?: strings.lyricSearchEmpty)
                else -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    results.forEach { scored ->
                        val candidate = scored.candidate
                        BasicComponent(
                            title = candidate.title,
                            summary = summaryOf(candidate, strings),
                            onClick = { onPick(candidate) },
                        )
                    }
                }
            }
            if (status != null && results.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Caption(status)
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(text = strings.cancel, onClick = onDismiss)
            }
        }
    }
}

/** `artist · album · 3:45 · 网易云 · 逐行歌词`, skipping whatever the source did not provide. */
private fun summaryOf(candidate: LyricCandidate, strings: Strings): String = listOfNotNull(
    candidate.artist.takeIf { it.isNotBlank() },
    candidate.album.takeIf { it.isNotBlank() },
    candidate.durationMs.takeIf { it > 0L }?.let(::formatDuration),
    strings.lyricSourceName(candidate.source),
    when {
        candidate.instrumental -> strings.lyricInstrumentalTag
        candidate.hasSynced -> strings.lyricSyncedTag
        else -> strings.lyricPlainTag
    },
).joinToString(" · ")
