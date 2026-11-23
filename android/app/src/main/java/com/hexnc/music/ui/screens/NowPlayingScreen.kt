package com.hexnc.music.ui.screens

import android.net.Uri
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hexnc.music.data.lyrics.ScoredCandidate
import com.hexnc.music.data.lyrics.searchKeyword
import com.hexnc.music.data.model.LyricLine
import com.hexnc.music.data.model.Lyrics
import com.hexnc.music.data.model.Song
import com.hexnc.music.data.model.SongSource
import com.hexnc.music.data.model.lineProgress
import com.hexnc.music.data.model.wordProgress
import com.hexnc.music.data.model.isJapaneseScript
import com.hexnc.music.data.prefs.PlayMode
import com.hexnc.music.data.prefs.AppSettings
import com.hexnc.music.data.repo.GrantedQuality
import com.hexnc.music.di.AppContainer
import com.hexnc.music.ui.components.AuroraWash
import com.hexnc.music.ui.components.Caption
import com.hexnc.music.ui.components.TimeCaption
import com.hexnc.music.ui.components.CoverArt
import com.hexnc.music.ui.components.EmptyState
import com.hexnc.music.ui.components.LyricSearchSheet
import com.hexnc.music.ui.components.PlayerOptionsSheet
import com.hexnc.music.ui.components.MAX_LIST_WIDTH
import com.hexnc.music.ui.components.SongInfoSheet
import com.hexnc.music.ui.components.formatDuration
import com.hexnc.music.ui.i18n.LocalStrings
import com.hexnc.music.ui.i18n.Strings
import com.hexnc.music.ui.theme.LocalAuroraTheme
import com.hexnc.music.ui.theme.Motion
import com.hexnc.music.ui.theme.Radii
import com.hexnc.music.ui.theme.Spacing
import com.hexnc.music.ui.theme.TypeScale
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.VerticalDivider
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Favorites
import top.yukonga.miuix.kmp.icon.extended.FavoritesFill
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Pause
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val COVER_SIZE = 268
private const val COMPACT_COVER_SIZE = 196
private const val QUEUE_MAX_HEIGHT = 430
private val PROGRESS_DOCK_HEIGHT = 48.dp

/**
 * Artwork on the player.
 *
 * The old fixed 268 dp read as a thumbnail on a tablet. In the two-column layout the cover is now
 * [WIDE_COVER_SIZE]; stacked, it is 62% of the window width capped at [COVER_MAX_SIZE], which keeps
 * the title and transport controls inside the first screenful on a phone in landscape while filling
 * a portrait phone.
 */
private val WIDE_COVER_SIZE = 380.dp
private val COVER_MAX_SIZE = 320.dp

/** Window width from which the player and the lyrics sit side by side instead of stacked. */
private val WIDE_PLAYER_MIN_WIDTH = 900.dp

/** How long a manual scroll suspends auto-follow before the sheet re-centres itself. */
private const val LYRIC_FOLLOW_RESUME_MS = 5_000L

/** The player reports its position four times a second; the lyric clock interpolates the gaps. */
private const val LYRIC_CLOCK_SMOOTH_MS = 320

/**
 * Full-screen player.
 *
 * Artwork, transport and lyrics live on this one page. A wide window shows them side by side; a
 * narrow one stacks the transport above the lyrics, so the lyrics are always part of the playback
 * detail page rather than a pane that replaces it.
 *
 * Owns its own [Scaffold] for the same reason every pushed screen does: Miuix `Overlay*` popups
 * resolve against the nearest host scaffold.
 */
@Composable
fun NowPlayingScreen(container: AppContainer, onBack: () -> Unit) {
    val strings = LocalStrings.current
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val snapshot by container.playback.snapshot.collectAsStateWithLifecycle()
    val song by container.playback.current.collectAsStateWithLifecycle()
    val queue by container.playback.queueFlow.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var showQueue by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(true) }
    var showInfo by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    // Immersive mode is a mode, not a control: it hides the chrome and keeps only the artwork and
    // the lyrics. It is left by tapping the cover, the one surface that stays on screen.
    var immersive by remember { mutableStateOf(false) }
    val grantedByTrack by container.netease.granted.collectAsStateWithLifecycle()

    Scaffold(
        topBar = topBar@{
            if (immersive) return@topBar
            SmallTopAppBar(
                title = strings.nowPlaying,
                subtitle = song?.album.orEmpty(),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = strings.back,
                            modifier = Modifier.size(24.dp),
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showLyrics = !showLyrics }) {
                        Icon(
                            imageVector = MiuixIcons.Notes,
                            contentDescription = strings.nowPlayingLyrics,
                            modifier = Modifier.size(24.dp),
                            tint = if (showLyrics) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(onClick = { showQueue = true }) {
                        Icon(
                            imageVector = MiuixIcons.ListView,
                            contentDescription = strings.upNext,
                            modifier = Modifier.size(24.dp),
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                    // The third action is the song-info sheet, which is the one thing a playback page
                    // can offer that neither the transport nor the queue can: where this track
                    // actually comes from.
                    IconButton(onClick = { showInfo = true }) {
                        Icon(
                            imageVector = MiuixIcons.Info,
                            contentDescription = strings.songInfo,
                            modifier = Modifier.size(24.dp),
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                    // Rarely used controls hide behind one sheet: the page should not grow a row per
                    // control just to keep a rarely used switch visible.
                    IconButton(onClick = { showOptions = true }) {
                        Icon(
                            imageVector = MiuixIcons.More,
                            contentDescription = strings.playerOptions,
                            modifier = Modifier.size(24.dp),
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            AuroraWash(
                modifier = Modifier.align(Alignment.TopCenter),
                height = 340.dp,
                strength = 0.95f,
            )
            val track = song
            // The blurred cover sits *under* every pane: it is what makes the page read as one
            // surface instead of a gradient with controls on it. It crossfades on a track change,
            // so skipping a song does not flash white.
            if (track != null) PlayerBackdrop(track)
            if (track == null) {
                EmptyState(
                    title = strings.queueEmpty,
                    body = strings.queueEmpty,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 32.dp),
                )
            } else if (immersive) {
                ImmersivePane(
                    container = container,
                    song = track,
                    strings = strings,
                    positionMs = snapshot.positionMs,
                    settings = settings,
                    onExit = { immersive = false },
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            } else {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        // Leaves the docked seek line its own gutter, so the transport never hides
                        // behind it when the column is scrolled to the end.
                        .padding(bottom = PROGRESS_DOCK_HEIGHT),
                ) {
                    // Resolved here because `maxWidth` is not reachable from inside the nested
                    // Column/Row scopes below (the DSL marker hides the BoxWithConstraints receiver).
                    val stackedCover = (maxWidth * 0.62f).coerceAtMost(COVER_MAX_SIZE)
                    val player: @Composable (Modifier, Boolean, Dp) -> Unit = { paneModifier, compact, coverSize ->
                        PlayerPane(
                            container = container,
                            song = track,
                            strings = strings,
                            isPlaying = snapshot.isPlaying,
                            positionMs = snapshot.positionMs,
                            durationMs = snapshot.durationMs,
                            playMode = snapshot.playMode,
                            hasPrevious = snapshot.hasPrevious,
                            hasNext = snapshot.hasNext,
                            compact = compact,
                            coverSize = coverSize,
                            modifier = paneModifier,
                        )
                    }
                    val lyrics: @Composable (Modifier) -> Unit = { paneModifier ->
                        LyricsPane(
                            container = container,
                            song = track,
                            strings = strings,
                            positionMs = snapshot.positionMs,
                            fontSizeSp = settings.lyricFontSizeSp,
                            lineSpacing = settings.lyricLineSpacing,
                            translationScale = settings.lyricTranslationScale,
                            showTranslation = settings.lyricShowTranslation,
                            showRoman = settings.lyricShowRoman,
                            romanScale = settings.lyricRomanScale,
                            onSeek = { container.playback.seekTo(it) },
                            modifier = paneModifier,
                        )
                    }
                    if (maxWidth >= WIDE_PLAYER_MIN_WIDTH) {
                        // Capped and centred at the same measure as the list tabs, so the player and
                        // the library agree on where the content column is on a wide window.
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                            Row(
                                Modifier
                                    .widthIn(max = MAX_LIST_WIDTH)
                                    .fillMaxWidth()
                                    .fillMaxHeight(),
                            ) {
                                player(Modifier.weight(1f).fillMaxHeight(), false, WIDE_COVER_SIZE)
                                if (showLyrics) {
                                    VerticalDivider(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .width(1.dp)
                                            .padding(vertical = 28.dp),
                                        color = MiuixTheme.colorScheme.outline,
                                    )
                                    lyrics(Modifier.weight(1f).fillMaxHeight())
                                }
                            }
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            player(
                                Modifier
                                    .fillMaxWidth()
                                    .weight(if (showLyrics) 0.56f else 1f),
                                true,
                                // Narrow window: the artwork is proportional to the width so a small
                                // window does not end up with a cover taller than the pane.
                                stackedCover,
                            )
                            if (showLyrics) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 28.dp),
                                    color = MiuixTheme.colorScheme.outline,
                                )
                                lyrics(Modifier.fillMaxWidth().weight(0.44f))
                            }
                        }
                    }
                }
                // Docked outside the scrolling column: inside it the seek line scrolled away with the
                // transport, so "where am I" was only reachable after scrolling back up.
                BottomProgressBar(
                    positionMs = snapshot.positionMs,
                    durationMs = snapshot.durationMs,
                    onSeek = { container.playback.seekTo(it) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp),
                )
            }
            // Both sheets are composed inside the Scaffold's content on purpose: Miuix `Overlay*`
            // popups resolve against the nearest host scaffold, and a sheet placed after the
            // Scaffold closes has no host to attach to — which is why the queue button looked dead.
            if (track != null) {
                QueueSheet(
                    show = showQueue,
                    queue = queue,
                    currentId = track.id,
                    strings = strings,
                    onDismiss = { showQueue = false },
                    onPick = { index ->
                        container.playback.seekToIndex(index)
                        showQueue = false
                    },
                )
                SongInfoSheet(
                    show = showInfo,
                    song = track,
                    strings = strings,
                    onDismiss = { showInfo = false },
                    granted = if (track.source == SongSource.NETEASE) {
                        grantedByTrack[track.neteaseId ?: 0L]
                    } else {
                        null
                    },
                )
                PlayerOptionsSheet(
                    show = showOptions,
                    strings = strings,
                    onDismiss = { showOptions = false },
                    onImmersive = { immersive = true; showLyrics = true },
                    // Android has a hardware volume rocker; the slider would be dead weight.
                    showVolume = false,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ transport pane

/**
 * Page backdrop: the current cover, blurred past legibility.
 *
 * Three layers, in order: the cover blown out so it reads as colour rather than as a second image, a
 * vertical scrim in the theme background, and an 8% accent wash borrowed from the aurora gradient so
 * the page still belongs to the active theme. Crossfaded rather than switched, because a hard cut on
 * skip flashes white.
 *
 * The cover is resolved the same way [CoverArt] resolves it — an override file first, then the remote
 * URL — so the backdrop can never disagree with the artwork in the middle of the page.
 */
@Composable
private fun PlayerBackdrop(song: Song) {
    val model: Any? = remember(song.coverUrl, song.coverOverridePath) {
        when {
            !song.coverOverridePath.isNullOrBlank() ->
                File(song.coverOverridePath).takeIf { it.exists() }?.let(Uri::fromFile)
            !song.coverUrl.isNullOrBlank() -> song.coverUrl
            else -> null
        }
    }
    val aurora = LocalAuroraTheme.current
    val background = MiuixTheme.colorScheme.background
    Crossfade(
        targetState = model,
        animationSpec = tween(Motion.coverMs, easing = Motion.standard),
        label = "player-backdrop",
    ) { current ->
        Box(Modifier.fillMaxSize()) {
            if (current != null) {
                AsyncImage(
                    model = current,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(72.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                        .alpha(0.62f),
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                background.copy(alpha = 0.55f),
                                background.copy(alpha = 0.78f),
                                background.copy(alpha = 0.94f),
                            ),
                        ),
                    ),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(aurora.accent).copy(alpha = 0.10f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
        }
    }
}

// ------------------------------------------------------------------ transport pane

@Composable
private fun PlayerPane(
    container: AppContainer,
    song: Song,
    strings: Strings,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    playMode: PlayMode,
    hasPrevious: Boolean,
    hasNext: Boolean,
    compact: Boolean = false,
    coverSize: Dp = COVER_SIZE.dp,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = if (compact) 20.dp else 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(if (compact) 12.dp else 28.dp))
        CoverArt(
            coverUrl = song.coverUrl,
            overridePath = song.coverOverridePath,
            size = if (compact) COMPACT_COVER_SIZE.dp else coverSize,
            corner = Radii.hero,
        )
        Spacer(Modifier.height(if (compact) 18.dp else 28.dp))
        Text(
            text = song.title,
            style = MiuixTheme.textStyles.title4.copy(
                fontSize = TypeScale.pageTitle,
                fontWeight = FontWeight.SemiBold,
            ),
            color = MiuixTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = song.displayArtist,
            style = MiuixTheme.textStyles.body2.copy(
                fontSize = TypeScale.artist,
                fontWeight = FontWeight.Medium,
            ),
            color = MiuixTheme.colorScheme.onBackgroundVariant.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(26.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    scope.launch {
                        container.library.bulkFavorite(listOf(song.id), !song.favorite)
                    }
                },
            ) {
                Icon(
                    imageVector = if (song.favorite) MiuixIcons.FavoritesFill else MiuixIcons.Favorites,
                    contentDescription = if (song.favorite) strings.removeFromFavorites
                    else strings.addToFavorites,
                    modifier = Modifier.size(26.dp),
                    tint = if (song.favorite) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.onBackgroundVariant,
                )
            }
            IconButton(
                onClick = { container.playback.previous() },
                enabled = hasPrevious,
                minWidth = 44.dp,
                minHeight = 44.dp,
            ) {
                Icon(
                    imageVector = MiuixIcons.ChevronBackward,
                    contentDescription = strings.previous,
                    modifier = Modifier.size(34.dp),
                    tint = MiuixTheme.colorScheme.onBackground,
                )
            }
            IconButton(
                onClick = { container.playback.toggle() },
                backgroundColor = MiuixTheme.colorScheme.primary,
                cornerRadius = Radii.pill,
                minWidth = 64.dp,
                minHeight = 68.dp,
            ) {
                Icon(
                    imageVector = if (isPlaying) MiuixIcons.Pause else MiuixIcons.Play,
                    contentDescription = if (isPlaying) strings.pause else strings.play,
                    modifier = Modifier.size(32.dp),
                    tint = MiuixTheme.colorScheme.onPrimary,
                )
            }
            IconButton(
                onClick = { container.playback.next() },
                enabled = hasNext,
                minWidth = 44.dp,
                minHeight = 44.dp,
            ) {
                Icon(
                    imageVector = MiuixIcons.ChevronForward,
                    contentDescription = strings.next,
                    modifier = Modifier.size(34.dp),
                    tint = MiuixTheme.colorScheme.onBackground,
                )
            }
            IconButton(
                onClick = { container.playback.setPlayMode(nextPlayMode(playMode)) },
                backgroundColor = MiuixTheme.colorScheme.surfaceContainerHigh,
                cornerRadius = 18.dp,
                minWidth = 44.dp,
                minHeight = 44.dp,
            ) {
                Text(
                    text = modeLabel(playMode, strings),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                )
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

private fun nextPlayMode(mode: PlayMode): PlayMode = when (mode) {
    PlayMode.SEQUENCE -> PlayMode.REPEAT_ALL
    PlayMode.REPEAT_ALL -> PlayMode.REPEAT_ONE
    PlayMode.REPEAT_ONE -> PlayMode.SHUFFLE
    PlayMode.SHUFFLE -> PlayMode.SEQUENCE
}

private fun modeLabel(mode: PlayMode, strings: Strings): String = when (mode) {
    PlayMode.SEQUENCE -> strings.repeatSequence
    PlayMode.REPEAT_ALL -> strings.repeatAll
    PlayMode.REPEAT_ONE -> strings.repeatOne
    PlayMode.SHUFFLE -> strings.shuffle
}

// ------------------------------------------------------------------ docked progress

/**
 * The seek line, docked to the bottom edge of the page.
 *
 * It is deliberately thin and outside the scrolling column: inside the column it scrolled away with
 * the transport, so the one control that answers "where am I" was only reachable after scrolling
 * back up. The 18 dp box is the touch target — the bar itself stays 3 dp.
 */
@Composable
private fun BottomProgressBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val total = durationMs.coerceAtLeast(1L)
    var dragMs by remember { mutableStateOf<Long?>(null) }
    val shownMs = (dragMs ?: positionMs).coerceIn(0L, total)
    val fraction = (shownMs.toFloat() / total).coerceIn(0f, 1f)

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.lg)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TimeCaption(formatDuration(shownMs))
            TimeCaption(formatDuration(durationMs))
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp)
                .pointerInput(total) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragMs = ((offset.x / size.width) * total).toLong().coerceIn(0L, total)
                        },
                        onDragEnd = {
                            dragMs?.let(onSeek)
                            dragMs = null
                        },
                        onDragCancel = { dragMs = null },
                        onHorizontalDrag = { change, _ ->
                            dragMs = ((change.position.x / size.width) * total)
                                .toLong()
                                .coerceIn(0L, total)
                        },
                    )
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh),
            )
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MiuixTheme.colorScheme.primary),
            )
        }
    }
}

// ------------------------------------------------------------------ immersive mode

/**
 * Immersive mode: artwork on top, scrolling lyrics underneath, nothing else.
 *
 * The cover is the way out — the mode removes every affordance that could carry a button, and a
 * floating close icon would be exactly the chrome the mode exists to hide — and the tap target is
 * the artwork itself, which is the largest thing on screen.
 */
@Composable
private fun ImmersivePane(
    container: AppContainer,
    song: Song,
    strings: Strings,
    positionMs: Long,
    settings: AppSettings,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val artSize = (maxWidth * 0.42f).coerceIn(140.dp, 300.dp)
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(28.dp))
            CoverArt(
                coverUrl = song.coverUrl,
                overridePath = song.coverOverridePath,
                modifier = Modifier.clickable(onClick = onExit),
                size = artSize,
                corner = Radii.hero,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = song.title,
                style = MiuixTheme.textStyles.subtitle.copy(fontWeight = FontWeight.SemiBold),
                color = MiuixTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = song.displayArtist,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onBackgroundVariant.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
            Spacer(Modifier.height(20.dp))
            LyricsPane(
                container = container,
                song = song,
                strings = strings,
                positionMs = positionMs,
                fontSizeSp = settings.lyricFontSizeSp,
                lineSpacing = settings.lyricLineSpacing,
                translationScale = settings.lyricTranslationScale,
                showTranslation = settings.lyricShowTranslation,
                showRoman = settings.lyricShowRoman,
                romanScale = settings.lyricRomanScale,
                onSeek = { container.playback.seekTo(it) },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}

// ------------------------------------------------------------------ lyrics pane

@Composable
private fun LyricsPane(
    container: AppContainer,
    song: Song,
    strings: Strings,
    positionMs: Long,
    fontSizeSp: Float,
    lineSpacing: Float,
    translationScale: Float,
    showTranslation: Boolean,
    showRoman: Boolean,
    romanScale: Float,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Lyrics are read from the database first. A NetEase track with no stored sheet is fetched; a
    // local one is looked up on disk once, so a sidecar `.lrc` shows up even for a song that was
    // imported before the scanner learned to find them.
    var lyrics by remember(song.id) { mutableStateOf<Lyrics?>(null) }
    var loading by remember(song.id) { mutableStateOf(true) }

    // The online fallback: a search that runs by itself, the results it produced, and the state of
    // the picker those results are shown in.
    var searchOpen by remember(song.id) { mutableStateOf(false) }
    var searchQuery by remember(song.id) { mutableStateOf("") }
    var searchResults by remember(song.id) { mutableStateOf<List<ScoredCandidate>>(emptyList()) }
    var searching by remember(song.id) { mutableStateOf(false) }
    var searchStatus by remember(song.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun runSearch(keyword: String?) {
        searching = true
        searchStatus = null
        scope.launch {
            val found = runCatching { container.online.candidates(song, keyword) }.getOrNull()
            searching = false
            if (found == null) {
                searchStatus = strings.lyricSearchFailed
            } else {
                searchResults = found
                searchStatus = if (found.isEmpty()) strings.lyricSearchEmpty else null
            }
        }
    }

    LaunchedEffect(song.id) {
        lyrics = container.library.lyricsFor(song.id)
        if (lyrics == null) {
            if (song.neteaseId != null) {
                runCatching { container.netease.refreshLyrics(song.id) }
            } else {
                runCatching {
                    val found = container.scanner.lyricsFor(listOf(song))
                    if (found.isNotEmpty()) container.library.importLyricTexts(found)
                }
            }
            lyrics = container.library.lyricsFor(song.id)
            // Still nothing: the file has no sidecar and no NetEase id, so search both online
            // sources. Only a match that is beyond doubt is stored without asking; anything else
            // waits for the user in the picker below.
            if (lyrics == null) {
                val outcome = runCatching { container.online.autoComplete(song) }.getOrNull()
                if (outcome?.imported == true) lyrics = container.library.lyricsFor(song.id)
            }
        }
        loading = false
    }

    val sheet = lyrics
    if (sheet == null || sheet.isEmpty) {
        val body = when {
            loading -> strings.lyricsLoading
            sheet?.isPureMusic == true -> strings.pureMusic
            sheet?.isUncollected == true -> strings.uncollectedLyrics
            else -> strings.noLyrics
        }
        // Nothing to load yet — offering the search while the first attempt is still running would
        // just produce an empty dialog.
        val openSearch: (() -> Unit)? = if (loading) {
            null
        } else {
            {
                searchOpen = true
                if (searchQuery.isBlank()) searchQuery = searchKeyword(song.title, song.artist)
                runSearch(null)
            }
        }
        EmptyState(
            title = strings.nowPlayingLyrics,
            body = body,
            modifier = modifier.padding(horizontal = 32.dp),
            actionLabel = if (loading) null else strings.lyricSearchAction,
            onAction = openSearch,
        )
        LyricSearchSheet(
            show = searchOpen,
            query = searchQuery,
            results = searchResults,
            searching = searching,
            status = searchStatus,
            strings = strings,
            onQueryChange = { searchQuery = it },
            onSearch = { runSearch(searchQuery) },
            onPick = { candidate ->
                scope.launch {
                    val stored = runCatching { container.online.importCandidate(song, candidate) }
                        .getOrDefault(false)
                    if (stored) {
                        searchOpen = false
                        lyrics = container.library.lyricsFor(song.id)
                    } else {
                        searchStatus = strings.lyricSearchFailed
                    }
                }
            },
            onDismiss = { searchOpen = false },
        )
        return
    }

    // An untimed sheet has nothing to follow: `indexAt` would point at the last line and the wipe
    // would have no timing to track, so it is shown as plain scrollable text — no highlight, no
    // follow button and no seek, which still beats the empty pane it used to produce.
    if (sheet.isUnsynced) {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        ) {
            itemsIndexed(sheet.lines) { _, line ->
                LyricLineRow(
                    line = line,
                    nextStartMs = null,
                    isActive = false,
                    clockMs = 0L,
                    offsetMs = sheet.offsetMs,
                    fontSizeSp = fontSizeSp,
                    lineSpacing = lineSpacing,
                    translationScale = translationScale,
                    showTranslation = showTranslation,
                    showRoman = showRoman,
                    romanScale = romanScale,
                    onSeek = null,
                )
            }
        }
        return
    }

    // The player only reports its position every 250 ms, which is far too coarse for a wipe that is
    // supposed to look like it is tracking the voice, so the clock glides towards each new report.
    val clock by animateFloatAsState(
        targetValue = positionMs.toFloat(),
        animationSpec = tween(durationMillis = LYRIC_CLOCK_SMOOTH_MS, easing = LinearEasing),
        label = "lyric-clock",
    )
    val clockMs = clock.toLong()
    val activeLine = sheet.indexAt(clockMs)

    val listState = rememberLazyListState()
    val density = LocalDensity.current
    var viewportPx by remember { mutableIntStateOf(0) }
    var autoFollow by remember { mutableStateOf(true) }
    var dragTick by remember { mutableIntStateOf(0) }

    // Our own animated scrolling and a finger on the screen both flip `isScrollInProgress`, and only
    // one of them means "the user wants to read somewhere else".
    val selfScroll = remember { mutableStateOf(false) }

    // Symmetric padding of half the viewport is what makes `animateScrollToItem` land the active row
    // in the *middle* of the screen instead of at the top; the half-line correction centres the line
    // box rather than its top edge.
    val halfLinePx = with(density) { (fontSizeSp * lineSpacing).sp.toPx() / 2f }
    val sidePad = with(density) { (viewportPx / 2f - halfLinePx).coerceAtLeast(0f).toDp() }

    LaunchedEffect(activeLine, sidePad, autoFollow) {
        if (!autoFollow || activeLine < 0 || sidePad.value <= 0f) return@LaunchedEffect
        selfScroll.value = true
        try {
            listState.animateScrollToItem(activeLine)
        } finally {
            selfScroll.value = false
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling && !selfScroll.value) {
                autoFollow = false
                dragTick++
            }
        }
    }

    // Following resumes on its own, so a stray drag does not silently disable it for the rest of the
    // song; a fresh drag restarts the countdown.
    LaunchedEffect(dragTick) {
        if (dragTick == 0) return@LaunchedEffect
        delay(LYRIC_FOLLOW_RESUME_MS)
        autoFollow = true
    }

    Box(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { viewportPx = it.height },
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = sidePad),
        ) {
            itemsIndexed(sheet.lines) { index, line ->
                LyricLineRow(
                    line = line,
                    nextStartMs = sheet.lines.getOrNull(index + 1)?.timeMs,
                    isActive = index == activeLine,
                    clockMs = clockMs,
                    offsetMs = sheet.offsetMs,
                    fontSizeSp = fontSizeSp,
                    lineSpacing = lineSpacing,
                    translationScale = translationScale,
                    showTranslation = showTranslation,
                    showRoman = showRoman,
                    romanScale = romanScale,
                    onSeek = onSeek,
                )
            }
        }

        if (!autoFollow) {
            TextButton(
                text = strings.lyricsFollowCurrent,
                onClick = { autoFollow = true },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp),
            )
        }
    }
}

/**
 * One lyric line.
 *
 * The active line grows slightly and is then filled left-to-right up to the point playback has
 * reached, which is what turns a plain highlight into a wipe. Tapping a line seeks to it.
 */
@Composable
private fun LyricLineRow(
    line: LyricLine,
    nextStartMs: Long?,
    isActive: Boolean,
    clockMs: Long,
    offsetMs: Long,
    fontSizeSp: Float,
    lineSpacing: Float,
    translationScale: Float,
    showTranslation: Boolean,
    showRoman: Boolean,
    romanScale: Float,
    onSeek: ((Long) -> Unit)?,
) {
    val emphasis by animateFloatAsState(
        targetValue = if (isActive) 1f else 0f,
        animationSpec = tween(durationMillis = if (isActive) 200 else 420),
        label = "lyric-emphasis",
    )
    val size = fontSizeSp * (0.94f + 0.06f * emphasis)
    val activeColor = MiuixTheme.colorScheme.primary
    val idleColor = MiuixTheme.colorScheme.onBackgroundVariant

    // Word timings when the sheet has them (`yrc`), otherwise a sweep towards the next line — plain
    // `lrc` carries no per-word data at all.
    val wipe = when {
        !isActive -> 1f
        line.words.isNotEmpty() -> wordProgress(line.words, clockMs, offsetMs)
        else -> lineProgress(line.timeMs, nextStartMs, clockMs, offsetMs)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Seek to where the line *starts*, which is the raw stamp minus the sheet offset, because
            // `indexAt` adds the offset back when it picks the line. An untimed sheet has no stamp to
            // seek to, so it gets no click at all rather than a ripple that does nothing.
            .then(
                if (onSeek == null) {
                    Modifier
                } else {
                    Modifier.clickable { onSeek((line.timeMs - offsetMs).coerceAtLeast(0L)) }
                },
            )
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LyricText(
            text = line.text.ifBlank { "♪" },
            wipe = wipe,
            isActive = isActive,
            fontSize = size,
            lineSpacing = lineSpacing,
            activeColor = activeColor,
            idleColor = idleColor,
            weight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
        )
        // Two optional sub-lines under the original. Each is laid out only when it exists, so a
        // Japanese sheet with no translation leaves no gap where one would have been, and a Chinese
        // sheet with a translation reserves no room for a romaji line it never has.
        val strings = LocalStrings.current
        val translation = line.translation
        val hasTranslation = showTranslation && !translation.isNullOrBlank()
        val roman = line.roman
        val hasRoman = showRoman && !roman.isNullOrBlank()
        // A Japanese line whose service entry carries no `romalrc` says so instead of silently
        // showing two lines: an incomplete sheet and a broken setting look identical otherwise.
        val romanPlaceholder = showRoman && !hasRoman && line.isJapaneseScript

        // The active line is the one being sung, so its sub-lines brighten with it and fall back when
        // it passes. Romaji sits a shade below the translation: it is a reading aid for the line
        // above, not a second translation.
        val translationAlpha = if (isActive) 0.78f else 0.62f
        val romanAlpha = if (isActive) 0.70f else 0.52f

        if (hasTranslation) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = translation.orEmpty(),
                fontSize = (size * translationScale).sp,
                lineHeight = (size * translationScale * lineSpacing).sp,
                color = MiuixTheme.colorScheme.onBackground.copy(alpha = translationAlpha),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (hasRoman || romanPlaceholder) {
            Spacer(Modifier.height(if (hasTranslation) 2.dp else 4.dp))
            Text(
                text = if (hasRoman) roman.orEmpty() else strings.noRomanYet,
                fontSize = (size * romanScale).sp,
                lineHeight = (size * romanScale * lineSpacing).sp,
                color = MiuixTheme.colorScheme.onBackground.copy(alpha = romanAlpha),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Draws one lyric line, filled from left to right up to [wipe].
 *
 * The fill is a gradient on the text style rather than two stacked copies of the string, so the
 * glyphs are shaped once and the edge lands between glyphs instead of over them. `fillMaxWidth` is
 * deliberately absent: the brush is measured against the text's own box, and a full-width box would
 * put the wipe edge in the middle of the screen rather than in the middle of the line. Short lines
 * are centred by the parent column instead.
 */
@Composable
private fun LyricText(
    text: String,
    wipe: Float,
    isActive: Boolean,
    fontSize: Float,
    lineSpacing: Float,
    activeColor: Color,
    idleColor: Color,
    weight: FontWeight,
) {
    val style = MiuixTheme.textStyles.main.copy(
        fontSize = fontSize.sp,
        lineHeight = (fontSize * lineSpacing).sp,
        fontWeight = weight,
    )
    when {
        // Nothing has been sung yet, including the first instant of the active line: karaoke fill
        // starts at the left edge, it does not appear already lit.
        !isActive || wipe <= 0f -> Text(
            text = text,
            color = idleColor,
            style = style,
            textAlign = TextAlign.Center,
        )
        wipe >= 1f -> Text(
            text = text,
            color = activeColor,
            style = style,
            textAlign = TextAlign.Center,
        )
        else -> Text(
            text = text,
            style = style.copy(
                brush = Brush.horizontalGradient(
                    0f to activeColor,
                    wipe to activeColor,
                    wipe to idleColor,
                    1f to idleColor,
                ),
            ),
            textAlign = TextAlign.Center,
        )
    }
}

// ------------------------------------------------------------------ queue

@Composable
private fun QueueSheet(
    show: Boolean,
    queue: List<Song>,
    currentId: String?,
    strings: Strings,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    OverlayBottomSheet(
        show = show,
        title = strings.upNext,
        onDismissRequest = onDismiss,
    ) {
        if (queue.isEmpty()) {
            Caption(strings.queueEmpty, Modifier.padding(horizontal = 20.dp, vertical = 24.dp))
            return@OverlayBottomSheet
        }
        LazyColumn(modifier = Modifier.heightIn(max = QUEUE_MAX_HEIGHT.dp)) {
            itemsIndexed(queue, key = { index, item -> "$index:${item.id}" }) { index, item ->
                BasicComponent(
                    title = if (item.id == currentId) "▶ ${item.title}" else item.title,
                    summary = item.displayArtist,
                    onClick = { onPick(index) },
                )
            }
        }
    }
}
