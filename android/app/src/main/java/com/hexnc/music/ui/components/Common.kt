package com.hexnc.music.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.hexnc.music.HexNCApp
import com.hexnc.music.data.model.Song
import com.hexnc.music.data.model.SongSource
import com.hexnc.music.di.AppContainer
import com.hexnc.music.playback.PlayerSnapshot
import com.hexnc.music.ui.theme.LocalAuroraTheme
import com.hexnc.music.ui.theme.LocalIsDarkTheme
import com.hexnc.music.ui.theme.auroraBrush
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Favorites
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.icon.extended.Pause
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.io.File

/** The process-wide object graph, resolved from the `Application` so screens never build one. */
@Composable
fun rememberContainer(): AppContainer {
    val context = LocalContext.current
    return remember(context) { (context.applicationContext as HexNCApp).container }
}

// ------------------------------------------------------------------ artwork

/**
 * Square artwork with a graceful fallback.
 *
 * Resolution order is user override → remote artwork → aurora gradient placeholder, which is the
 * order the user expects: a cover they picked themselves must beat whatever the service sent.
 */
@Composable
fun CoverArt(
    coverUrl: String?,
    overridePath: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    corner: Dp = 10.dp,
    shape: Boolean = false,
) {
    val model: Any? = remember(coverUrl, overridePath) {
        when {
            !overridePath.isNullOrBlank() -> File(overridePath).takeIf { it.exists() }?.let(Uri::fromFile)
            !coverUrl.isNullOrBlank() -> coverUrl
            else -> null
        }
    }
    val clip = if (shape) CircleShape else RoundedCornerShape(corner)
    Box(
        modifier = modifier
            .size(size)
            .clip(clip)
            .background(placeholderBrush()),
        contentAlignment = Alignment.Center,
    ) {
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                imageVector = MiuixIcons.Music,
                contentDescription = null,
                modifier = Modifier.size(size * 0.42f),
                tint = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

/** A soft two-stop wash used wherever artwork is missing, so gaps never read as broken. */
@Composable
private fun placeholderBrush(): Brush {
    val aurora = LocalAuroraTheme.current
    val dark = LocalIsDarkTheme.current
    val base = if (dark) 0.32f else 0.55f
    return Brush.linearGradient(
        listOf(
            aurora.gradientStops[0].copy(alpha = base),
            aurora.gradientStops[2].copy(alpha = base * 0.75f),
        ),
    )
}

// ------------------------------------------------------------------ list scaffolding

/**
 * The standard scrolling container.
 *
 * Miuix draws its own overscroll glow, so the platform effect is switched off to avoid two
 * competing animations fighting over the same gesture.
 */
@Composable
fun HexList(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(bottom = 24.dp),
    content: LazyListScope.() -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // A list that spans a very wide window leaves the title on the far left and the row's controls
        // hundreds of dp away on the right. Past a comfortable reading width the rows are centred.
        val wide = maxWidth > MAX_LIST_WIDTH
        LazyColumn(
            modifier = Modifier
                .then(if (wide) Modifier.width(MAX_LIST_WIDTH) else Modifier.fillMaxWidth())
                .fillMaxHeight()
                .align(Alignment.TopCenter)
                .overScrollVertical(),
            state = state,
            contentPadding = contentPadding,
            overscrollEffect = null,
            content = content,
        )
    }
}

/**
 * Reading width past which a list stops stretching across the window.
 *
 * `internal` rather than private so screens that lay out outside [HexList] — the playback detail
 * page — can line up with the list tabs instead of picking their own width.
 */
internal val MAX_LIST_WIDTH = 1120.dp

/** A small section heading with an optional trailing action. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.subtitle,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (actionLabel != null && onAction != null) {
            Text(
                text = actionLabel,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onAction).padding(4.dp),
            )
        }
    }
}

/** Full-screen placeholder used when a list has nothing to show. */
@Composable
fun EmptyState(
    title: String,
    body: String? = null,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(78.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(placeholderBrush()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MiuixIcons.Music,
                contentDescription = null,
                modifier = Modifier.size(34.dp),
                tint = Color.White,
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = title,
            style = MiuixTheme.textStyles.title4,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onBackground,
        )
        if (body != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = body,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            top.yukonga.miuix.kmp.basic.Button(onClick = onAction) {
                Text(actionLabel, style = MiuixTheme.textStyles.button)
            }
        }
    }
}

/** Centred spinner for the first load of a screen. */
@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator()
    }
}

// ------------------------------------------------------------------ rows

/**
 * One song in a list.
 *
 * The subtitle carries the source marker and, for NetEase tracks, the fact that a track exists
 * locally too — the two libraries genuinely overlap and the user needs to see which copy plays.
 */
@Composable
fun SongRow(
    song: Song,
    strings: com.hexnc.music.ui.i18n.Strings,
    modifier: Modifier = Modifier,
    showCover: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val subtitle = buildString {
        append(song.displayArtist.ifBlank { strings.unknownArtist })
        if (song.album.isNotBlank()) append(" · ").append(song.album)
        if (song.source == SongSource.NETEASE) append(" · ").append(strings.neteaseMusic)
    }
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.surfaceContainer,
        ),
        onClick = onClick,
        onLongPress = onLongClick,
        showIndication = true,
        pressFeedbackType = top.yukonga.miuix.kmp.utils.PressFeedbackType.Sink,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showCover) {
                CoverArt(
                    coverUrl = song.coverUrl,
                    overridePath = song.coverOverridePath,
                    size = 46.dp,
                    corner = 12.dp,
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (song.favorite) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = MiuixIcons.Favorites,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MiuixTheme.colorScheme.primary,
                )
            }
            if (trailing != null) {
                Spacer(Modifier.width(6.dp))
                trailing()
            }
            if (onLongClick != null) {
                Spacer(Modifier.width(2.dp))
                MoreActionsButton(contentDescription = strings.moreActions, onClick = onLongClick)
            }
        }
    }
}

/**
 * The visible half of the row action model.
 *
 * Long-press (touch) opens the action sheet, but that is not discoverable on its own — so every row
 * that has actions also carries this button. It is deliberately quiet (transparent, muted tint) so a
 * list of them does not turn into a wall of dots.
 */
@Composable
internal fun MoreActionsButton(contentDescription: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        minWidth = 36.dp,
        minHeight = 36.dp,
        backgroundColor = Color.Transparent,
    ) {
        Icon(
            imageVector = MiuixIcons.More,
            contentDescription = contentDescription,
            modifier = Modifier.size(18.dp),
            tint = MiuixTheme.colorScheme.onBackgroundVariant,
        )
    }
}

/** A playlist / album entry with a chevron and the track count. */
@Composable
fun CollectionRow(
    title: String,
    subtitle: String?,
    coverUrl: String?,
    overridePath: String?,
    strings: com.hexnc.music.ui.i18n.Strings,
    modifier: Modifier = Modifier,
    count: Int? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.surfaceContainer,
        ),
        onClick = onClick,
        onLongPress = onLongClick,
        showIndication = true,
        pressFeedbackType = top.yukonga.miuix.kmp.utils.PressFeedbackType.Sink,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CoverArt(coverUrl = coverUrl, overridePath = overridePath, size = 52.dp, corner = 14.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val line = listOfNotNull(
                    subtitle?.takeIf { it.isNotBlank() },
                    count?.let { strings.songs(it) },
                ).joinToString(" · ")
                if (line.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = line,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                imageVector = MiuixIcons.Basic.ArrowRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MiuixTheme.colorScheme.onBackgroundVariant,
            )
            if (onLongClick != null) {
                Spacer(Modifier.width(2.dp))
                MoreActionsButton(contentDescription = strings.moreActions, onClick = onLongClick)
            }
        }
    }
}

// ------------------------------------------------------------------ mini player

/**
 * The persistent transport strip above the bottom bar.
 *
 * Renders nothing until something is playing, so the library is never pushed up by an empty bar.
 */
@Composable
fun MiniPlayerBar(
    snapshot: PlayerSnapshot,
    song: Song?,
    strings: com.hexnc.music.ui.i18n.Strings,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
) {
    if (!snapshot.hasTrack || song == null) return
    Column(modifier = modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = snapshot.progress,
            modifier = Modifier.fillMaxWidth().height(3.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverArt(
                coverUrl = song.coverUrl,
                overridePath = song.coverOverridePath,
                size = 46.dp,
                corner = 12.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = song.displayArtist.ifBlank { strings.unknownArtist },
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onPrevious, minWidth = 40.dp, minHeight = 40.dp) {
                Icon(
                    imageVector = MiuixIcons.ChevronBackward,
                    contentDescription = strings.previous,
                    modifier = Modifier.size(24.dp),
                    tint = MiuixTheme.colorScheme.onBackground,
                )
            }
            IconButton(onClick = onToggle, minWidth = 40.dp, minHeight = 40.dp) {
                Icon(
                    imageVector = if (snapshot.isPlaying) MiuixIcons.Pause else MiuixIcons.Play,
                    contentDescription = if (snapshot.isPlaying) strings.pause else strings.play,
                    modifier = Modifier.size(26.dp),
                    tint = MiuixTheme.colorScheme.onBackground,
                )
            }
            IconButton(onClick = onNext, minWidth = 40.dp, minHeight = 40.dp) {
                Icon(
                    imageVector = MiuixIcons.ChevronForward,
                    contentDescription = strings.next,
                    modifier = Modifier.size(24.dp),
                    tint = MiuixTheme.colorScheme.onBackground,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ decoration

/**
 * A very restrained aurora wash pinned behind top-of-screen content.
 *
 * Kept to blended 6–10% alpha: the brief asks for an aurora theme, and the 2026 look is a tinted
 * neutral surface rather than a saturated gradient panel. Anything stronger fights legibility.
 */
@Composable
fun AuroraWash(modifier: Modifier = Modifier, height: Dp = 220.dp, strength: Float = 1f) {
    val aurora = LocalAuroraTheme.current
    val dark = LocalIsDarkTheme.current
    val alpha = (if (dark) 0.16f else 0.10f) * strength
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(
                Brush.verticalGradient(
                    listOf(
                        aurora.gradientStops[0].copy(alpha = alpha),
                        aurora.gradientStops[1].copy(alpha = alpha * 0.55f),
                        Color.Transparent,
                    ),
                ),
            ),
    )
}

/** Format a millisecond duration the way a player does (`m:ss`, or `h:mm:ss` past an hour). */
fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "--:--"
    val total = ms / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

/** Small caption text used under titles in dense lists. */
@Composable
fun Caption(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    Text(
        text = text,
        modifier = modifier,
        style = MiuixTheme.textStyles.footnote2,
        color = color ?: MiuixTheme.colorScheme.onBackgroundVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Convenience for the "12 songs · 48 min" line that appears on several screens. */
@Composable
fun CountLine(count: Int, durationMs: Long, strings: com.hexnc.music.ui.i18n.Strings, modifier: Modifier = Modifier) {
    val minutes = durationMs / 60_000L
    val suffix = if (minutes > 0) " · ${minutes}min" else ""
    Text(
        text = strings.songs(count) + suffix,
        modifier = modifier,
        style = MiuixTheme.textStyles.footnote2,
        fontSize = 12.sp,
        color = MiuixTheme.colorScheme.onBackgroundVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
