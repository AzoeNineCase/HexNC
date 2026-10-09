package com.hexnc.music.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hexnc.music.data.lyrics.ScoredCandidate
import com.hexnc.music.data.lyrics.searchKeyword
import com.hexnc.music.data.model.LyricLine
import com.hexnc.music.data.model.Lyrics
import com.hexnc.music.data.model.Song
import com.hexnc.music.data.model.lineProgress
import com.hexnc.music.data.model.wordProgress
import com.hexnc.music.data.prefs.PlayMode
import com.hexnc.music.di.AppContainer
import com.hexnc.music.ui.components.AuroraWash
import com.hexnc.music.ui.components.Caption
import com.hexnc.music.ui.components.CoverArt
import com.hexnc.music.ui.components.EmptyState
import com.hexnc.music.ui.components.LyricSearchSheet
import com.hexnc.music.ui.components.formatDuration
import com.hexnc.music.ui.i18n.LocalStrings
import com.hexnc.music.ui.i18n.Strings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Favorites
import top.yukonga.miuix.kmp.icon.extended.FavoritesFill
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Pause
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val COVER_SIZE = 268
private const val QUEUE_MAX_HEIGHT = 430

/** How long a manual scroll suspends auto-follow before the sheet re-centres itself. */
private const val LYRIC_FOLLOW_RESUME_MS = 5_000L

/** The player reports its position four times a second; the lyric clock interpolates the gaps. */
private const val LYRIC_CLOCK_SMOOTH_MS = 320

/**
 * Full-screen player.
 *
 * Two panes share one top bar: artwork + transport, and the scrolling lyric sheet. They are switched
 * rather than stacked because the lyric view wants the whole height for long lines, and a
 * half-height lyric strip would need its own scroll arbitration against the page scroll.
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
    var showLyrics by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
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
            if (track == null) {
                EmptyState(
                    title = strings.queueEmpty,
                    body = strings.queueEmpty,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 32.dp),
                )
            } else if (showLyrics) {
                LyricsPane(
                    container = container,
                    song = track,
                    strings = strings,
                    positionMs = snapshot.positionMs,
                    fontSizeSp = settings.lyricFontSizeSp,
                    lineSpacing = settings.lyricLineSpacing,
                    translationScale = settings.lyricTranslationScale,
                    showTranslation = settings.lyricShowTranslation,
                    onSeek = { container.playback.seekTo(it) },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                )
            } else {
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
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                )
            }
        }
    }

    if (song != null) {
        QueueSheet(
            show = showQueue,
            queue = queue,
            currentId = song?.id,
            strings = strings,
            onDismiss = { showQueue = false },
            onPick = { index ->
                container.playback.seekToIndex(index)
                showQueue = false
            },
        )
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
    modifier: Modifier = Modifier,
) {
    // While the thumb is held the slider follows the finger; the playhead keeps advancing underneath.
    var dragMs by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()
    val total = durationMs.coerceAtLeast(1L)
    val shownMs = (dragMs ?: positionMs).coerceIn(0L, total)

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(20.dp))
        CoverArt(
            coverUrl = song.coverUrl,
            overridePath = song.coverOverridePath,
            size = COVER_SIZE.dp,
            corner = 26.dp,
        )
        Spacer(Modifier.height(28.dp))
        Text(
            text = song.title,
            style = MiuixTheme.textStyles.title4,
            color = MiuixTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = song.displayArtist,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(24.dp))
        Slider(
            value = shownMs.toFloat(),
            onValueChange = { dragMs = it.toLong() },
            onValueChangeFinished = {
                dragMs?.let { container.playback.seekTo(it) }
                dragMs = null
            },
            valueRange = 0f..total.toFloat(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Caption(formatDuration(shownMs))
            Caption(formatDuration(durationMs))
        }

        Spacer(Modifier.height(18.dp))
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
                cornerRadius = 40.dp,
                minWidth = 68.dp,
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
            .padding(vertical = 8.dp),
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
        val translation = line.translation
        if (showTranslation && !translation.isNullOrBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = translation,
                fontSize = (size * translationScale).sp,
                lineHeight = (size * translationScale * lineSpacing).sp,
                color = if (isActive) MiuixTheme.colorScheme.onBackground
                else MiuixTheme.colorScheme.outline,
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
