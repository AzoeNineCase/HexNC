package com.hexnc.music.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hexnc.music.di.AppContainer
import com.hexnc.music.playback.MESSAGE_NO_PLAYABLE
import com.hexnc.music.playback.MESSAGE_PLAYBACK_CLEARTEXT
import com.hexnc.music.playback.MESSAGE_PLAYBACK_DSD
import com.hexnc.music.playback.MESSAGE_PLAYBACK_DSD_DECODING
import com.hexnc.music.playback.MESSAGE_PLAYBACK_FAILED
import com.hexnc.music.playback.MESSAGE_PLAYBACK_FILE_MISSING
import com.hexnc.music.playback.MESSAGE_PLAYBACK_NETWORK
import com.hexnc.music.playback.MESSAGE_PLAYBACK_UNSUPPORTED
import com.hexnc.music.ui.components.AuroraWash
import com.hexnc.music.ui.components.MiniPlayerBar
import com.hexnc.music.ui.i18n.Language
import com.hexnc.music.ui.i18n.LocalStrings
import com.hexnc.music.ui.i18n.Strings
import com.hexnc.music.ui.screens.AlbumDetailScreen
import com.hexnc.music.ui.screens.ArtistDetailScreen
import com.hexnc.music.ui.screens.LibraryScreen
import com.hexnc.music.ui.screens.NowPlayingScreen
import com.hexnc.music.ui.screens.PlaylistDetailScreen
import com.hexnc.music.ui.screens.PlaylistsScreen
import com.hexnc.music.ui.screens.SearchScreen
import com.hexnc.music.ui.screens.SettingsScreen
import com.hexnc.music.ui.screens.SignInScreen
import com.hexnc.music.ui.theme.AuroraTheme
import com.hexnc.music.ui.theme.HexNCTheme
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import top.yukonga.miuix.kmp.basic.NavigationRailValue
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberNavigationRailState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Playlist
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Composition root.
 *
 * Reads the stored settings once, resolves the language and aurora palette, installs the theme and
 * the string catalog, then owns the single back stack. Every screen below receives its data from
 * the [AppContainer] rather than building repositories of its own.
 */
@Composable
fun HexNCRoot(container: AppContainer) {
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val language = Language.resolve(settings.language)
    val strings = remember(language) { Strings(language) }
    val aurora = remember(settings.auroraThemeKey) { AuroraTheme.fromKey(settings.auroraThemeKey) }

    HexNCTheme(
        themeMode = settings.themeMode,
        aurora = aurora,
        amoledBlack = settings.amoledBlack,
    ) {
        CompositionLocalProvider(LocalStrings provides strings) {
            val backStack = rememberNavBackStack<Route>(Route.Home)
            val snackbarHostState = remember { SnackbarHostState() }
            val scope = rememberCoroutineScope()
            val playback = container.playback
            val pendingMessage by playback.messages.collectAsStateWithLifecycle()
            val session by container.netease.session.collectAsStateWithLifecycle()

            // Signing in must actually bring the account's library in — the liked playlist, the
            // user's own playlists and the subscribed ones — instead of leaving an empty list until
            // the refresh button is found. `importLibraryOnce` is a no-op for an account that has
            // already been imported, so this cannot turn into a full sync on every launch.
            LaunchedEffect(session.userId) {
                if (!session.isSignedIn) return@LaunchedEffect
                val result = container.netease.importLibraryOnce() ?: return@LaunchedEffect
                result
                    .onSuccess { report ->
                        snackbarHostState.showSnackbar(
                            strings.importedLibrary(report.playlists, report.tracks)
                        )
                    }
                    .onFailure { snackbarHostState.showSnackbar(strings.syncFailed) }
            }

            // Playback reports failures as short codes so the player layer never needs the string
            // catalog; they are translated here, where both are in scope.
            LaunchedEffect(pendingMessage) {
                val code = pendingMessage ?: return@LaunchedEffect
                val text = when (code) {
                    MESSAGE_NO_PLAYABLE -> strings.trackUnavailable
                    MESSAGE_PLAYBACK_CLEARTEXT -> strings.playbackCleartext
                    MESSAGE_PLAYBACK_NETWORK -> strings.playbackNetwork
                    MESSAGE_PLAYBACK_FILE_MISSING -> strings.playbackFileMissing
                    MESSAGE_PLAYBACK_UNSUPPORTED -> strings.playbackUnsupported
                    MESSAGE_PLAYBACK_DSD -> strings.playbackDsd
                    MESSAGE_PLAYBACK_DSD_DECODING -> strings.playbackDsdDecoding
                    MESSAGE_PLAYBACK_FAILED -> strings.playbackUnknown
                    else -> code
                }
                snackbarHostState.showSnackbar(text)
                playback.consumeMessage()
            }

            val navigate: (Route) -> Unit = { route ->
                if (route.isRootTab) {
                    // Tabs are peers, not a stack: drop whatever detail screens are open, then
                    // swap the root entry instead of growing the stack on every tab tap.
                    while (backStack.size > 1) backStack.removeLastOrNull()
                    if (backStack.firstOrNull() != route) {
                        if (backStack.isEmpty()) backStack.add(route) else backStack[0] = route
                    }
                } else {
                    backStack.add(route)
                }
            }
            val goBack: () -> Unit = {
                if (backStack.size > 1) backStack.removeLastOrNull()
            }

            NavDisplay(backStack = backStack, onBack = goBack) {
                entry<Route.Home> {
                    LibraryScreen(container, snackbarHostState, navigate)
                }
                entry<Route.Playlists> {
                    PlaylistsScreen(container, snackbarHostState, navigate)
                }
                entry<Route.Search> {
                    SearchScreen(container, snackbarHostState, navigate)
                }
                entry<Route.Settings> {
                    SettingsScreen(container, snackbarHostState, navigate)
                }
                entry<Route.PlaylistDetail> { route ->
                    PlaylistDetailScreen(container, route.playlistId, snackbarHostState, navigate, goBack)
                }
                entry<Route.AlbumDetail> { route ->
                    AlbumDetailScreen(container, route.album, route.artist, snackbarHostState, navigate, goBack)
                }
                entry<Route.ArtistDetail> { route ->
                    ArtistDetailScreen(container, route.artist, snackbarHostState, navigate, goBack)
                }
                entry<Route.SignIn> {
                    SignInScreen(container, snackbarHostState, goBack)
                }
                entry<Route.NowPlaying> {
                    NowPlayingScreen(container, goBack)
                }
            }
        }
    }
}

/**
 * The chrome shared by the four tab destinations.
 *
 * Below [WIDE_LAYOUT_MIN_WIDTH] this is a phone shell: the mini player sits directly above the
 * navigation bar inside one `bottomBar` slot so the two cannot drift apart. Above it the navigation
 * moves to a permanent rail on the left — a tablet or a desktop-class window has the width to spare,
 * and a bottom bar on a large display is a thumb target nobody can reach.
 *
 * Hosts the snackbar that the playback layer reports through in both shapes.
 */
@Composable
fun HexScaffold(
    container: AppContainer,
    strings: Strings,
    selectedTab: Int,
    onNavigate: (Route) -> Unit,
    snackbarHostState: SnackbarHostState,
    topBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val playback = container.playback
    val snapshot by playback.snapshot.collectAsStateWithLifecycle()
    val currentSong by playback.current.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wide = maxWidth >= WIDE_LAYOUT_MIN_WIDTH
        // A soft aurora tint behind every tab screen; the playback page paints its own, stronger one.
        val screenContent: @Composable (PaddingValues) -> Unit = { padding ->
            Box(modifier = Modifier.fillMaxSize()) {
                AuroraWash(
                    modifier = Modifier.align(Alignment.TopCenter),
                    height = 260.dp,
                    strength = 0.5f,
                )
                content(padding)
            }
        }
        if (wide) {
            Row(modifier = Modifier.fillMaxSize()) {
                HexNavigationRail(
                    selectedTab = selectedTab,
                    onNavigate = onNavigate,
                    strings = strings,
                )
                Box(modifier = Modifier.weight(1f)) {
                    Scaffold(
                        topBar = topBar,
                        snackbarHost = { SnackbarHost(snackbarHostState) },
                        bottomBar = {
                            MiniPlayerBar(
                                snapshot = snapshot,
                                song = currentSong,
                                strings = strings,
                                onOpen = { onNavigate(Route.NowPlaying) },
                                onPrevious = { playback.previous() },
                                onToggle = { playback.toggle() },
                                onNext = { playback.next() },
                            )
                        },
                        content = screenContent,
                    )
                }
            }
        } else {
            Scaffold(
                topBar = topBar,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        MiniPlayerBar(
                            snapshot = snapshot,
                            song = currentSong,
                            strings = strings,
                            onOpen = { onNavigate(Route.NowPlaying) },
                            onPrevious = { playback.previous() },
                            onToggle = { playback.toggle() },
                            onNext = { playback.next() },
                        )
                        NavigationBar {
                            RootTab.entries.forEachIndexed { index, tab ->
                                NavigationBarItem(
                                    selected = selectedTab == index,
                                    onClick = { onNavigate(tab.route) },
                                    icon = tab.icon(),
                                    label = tab.label(strings),
                                )
                            }
                        }
                    }
                },
                content = screenContent,
            )
        }
    }
}

/** Window width from which the shell trades the bottom bar for a permanent side rail. */
private val WIDE_LAYOUT_MIN_WIDTH = 840.dp

/** The wide-layout side rail: app name on top, the four destinations under it. */
@Composable
private fun HexNavigationRail(
    selectedTab: Int,
    onNavigate: (Route) -> Unit,
    strings: Strings,
) {
    NavigationRail(
        state = rememberNavigationRailState(NavigationRailValue.Expanded),
        modifier = Modifier
            .fillMaxHeight()
            .width(216.dp),
    ) {
        Text(
            text = strings.appName,
            style = MiuixTheme.textStyles.title4,
            color = MiuixTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 10.dp),
        )
        RootTab.entries.forEachIndexed { index, tab ->
            NavigationRailItem(
                selected = selectedTab == index,
                onClick = { onNavigate(tab.route) },
                icon = tab.icon(),
                label = tab.label(strings),
            )
        }
    }
}

/** The bottom-bar destinations, kept next to their icons and labels. */
private enum class RootTab(val route: Route) {
    LIBRARY(Route.Home),
    PLAYLISTS(Route.Playlists),
    SEARCH(Route.Search),
    SETTINGS(Route.Settings);

    fun label(strings: Strings): String = when (this) {
        LIBRARY -> strings.tabLibrary
        PLAYLISTS -> strings.tabPlaylists
        SEARCH -> strings.tabSearch
        SETTINGS -> strings.tabSettings
    }

    @Composable
    fun icon() = when (this) {
        LIBRARY -> MiuixIcons.Home
        PLAYLISTS -> MiuixIcons.Playlist
        SEARCH -> MiuixIcons.Search
        SETTINGS -> MiuixIcons.Settings
    }
}

/** Placeholder kept for screens that render nothing but a background while loading. */
@Composable
fun BlankSurface(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize())
}
