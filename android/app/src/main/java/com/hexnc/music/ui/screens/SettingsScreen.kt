package com.hexnc.music.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hexnc.music.BuildConfig
import com.hexnc.music.data.prefs.AppLanguage
import com.hexnc.music.data.prefs.AppSettings
import com.hexnc.music.data.prefs.ThemeMode
import com.hexnc.music.di.AppContainer
import com.hexnc.music.ui.HexScaffold
import com.hexnc.music.ui.Route
import com.hexnc.music.ui.components.Caption
import com.hexnc.music.ui.components.HexList
import com.hexnc.music.ui.components.SectionHeader
import com.hexnc.music.ui.i18n.LocalStrings
import com.hexnc.music.ui.i18n.Strings
import com.hexnc.music.ui.theme.AuroraTheme
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Background
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.icon.extended.Translate
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val QUALITY_LEVELS = listOf("standard", "higher", "exhigh", "lossless", "hires")

/**
 * Every user-tunable preference, grouped so related switches stay together.
 *
 * Choices are rendered as rows that open an [OverlayBottomSheet] rather than Miuix's
 * `OverlayDropdownPreference`: the dropdown resolves its popup against the *root* scaffold, and
 * this screen's rows would then be positioned against a scaffold they are not inside.
 */
@Composable
fun SettingsScreen(
    container: AppContainer,
    snackbarHostState: SnackbarHostState,
    onNavigate: (Route) -> Unit,
) {
    val strings = LocalStrings.current
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val session by container.netease.session.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var choice by remember { mutableStateOf<Choice?>(null) }
    var confirmingSignOut by remember { mutableStateOf(false) }
    var confirmingClear by remember { mutableStateOf(false) }
    var scanningLyrics by remember { mutableStateOf(false) }

    fun message(text: String) {
        scope.launch { snackbarHostState.showSnackbar(text) }
    }

    /**
     * Looks for lyrics for every local song that has none: first a `.lrc` file next to the audio,
     * then the online sources.
     *
     * The second pass exists because a sidecar scan cannot help a library that never shipped `.lrc`
     * files. It is bounded ([OnlineLyricsRepository.DEFAULT_BATCH_LIMIT] songs per tap) so the
     * spinner cannot run for minutes on a large library, and it only stores matches it is sure about.
     */
    fun scanLocalLyrics() {
        if (scanningLyrics) return
        scope.launch {
            scanningLyrics = true
            runCatching {
                val pending = container.library.localSongsMissingLyrics()
                val found = if (pending.isEmpty()) emptyMap() else container.scanner.lyricsFor(pending)
                val stored = container.library.importLyricTexts(found)
                if (pending.size > stored) message(strings.scanLocalLyricsOnline)
                val online = container.online.autoCompleteAll()
                message(
                    when {
                        stored > 0 || online.imported > 0 ->
                            strings.lyricsScanSummary.format(stored, online.imported)
                        else -> strings.lyricsScanNothing
                    },
                )
            }.onFailure { message(strings.importFailed.format(it.message.orEmpty())) }
            scanningLyrics = false
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val scanned = runCatching { container.scanner.scanFolder(uri) }.getOrNull()
            if (scanned == null) {
                message(strings.importFailed.format(""))
                return@launch
            }
            // Persisted before anything else: the grant is what later lyric scans and playback use.
            container.scanner.rememberTreeAccess(uri)
            val imported = container.library.addSongs(scanned.songs)
            val lyricCount = container.library.importLyricTexts(scanned.lyrics)
            message(strings.importFound.format(imported, lyricCount))
        }
    }

    HexScaffold(
        container = container,
        strings = strings,
        selectedTab = 3,
        onNavigate = onNavigate,
        snackbarHostState = snackbarHostState,
    ) { padding ->
        HexList(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
        ) {
            // ---------------------------------------------------------------- account
            item(key = "accountHeader") { SectionHeader(strings.account) }
            item(key = "account") {
                SettingsRow(
                    title = if (session.isSignedIn) session.nickname else strings.notSignedIn,
                    summary = if (session.isSignedIn) {
                        strings.signedInAs.format(session.nickname)
                    } else {
                        strings.signInSubtitle
                    },
                    icon = MiuixIcons.Contacts,
                    onClick = { if (!session.isSignedIn) onNavigate(Route.SignIn) },
                )
            }
            if (session.isSignedIn) {
                item(key = "signOut") {
                    SettingsRow(
                        title = strings.signOut,
                        summary = strings.signOutConfirm,
                        icon = MiuixIcons.Back,
                        onClick = { confirmingSignOut = true },
                    )
                }
            }

            // ---------------------------------------------------------------- appearance
            item(key = "appearanceHeader") { SectionHeader(strings.appearance) }
            item(key = "theme") {
                ChoiceRow(
                    title = strings.theme,
                    value = when (settings.themeMode) {
                        ThemeMode.SYSTEM -> strings.themeSystem
                        ThemeMode.LIGHT -> strings.themeLight
                        ThemeMode.DARK -> strings.themeDark
                    },
                    icon = MiuixIcons.Theme,
                    onClick = {
                        choice = Choice(
                            title = strings.theme,
                            options = listOf(strings.themeSystem, strings.themeLight, strings.themeDark),
                            selected = ThemeMode.entries.indexOf(settings.themeMode),
                            onPick = { index ->
                                container.settings.setThemeMode(ThemeMode.entries[index])
                            },
                        )
                    },
                )
            }
            item(key = "aurora") {
                ChoiceRow(
                    title = strings.auroraTheme,
                    value = AuroraTheme.fromKey(settings.auroraThemeKey).label(strings.isChinese),
                    icon = MiuixIcons.Background,
                    onClick = {
                        choice = Choice(
                            title = strings.auroraTheme,
                            options = AuroraTheme.entries.map { it.label(strings.isChinese) },
                            selected = AuroraTheme.entries.indexOf(
                                AuroraTheme.fromKey(settings.auroraThemeKey),
                            ),
                            onPick = { index ->
                                container.settings.setAuroraTheme(AuroraTheme.entries[index].key)
                            },
                        )
                    },
                )
            }
            item(key = "amoled") {
                SwitchRow(
                    title = strings.amoledBlack,
                    summary = strings.amoledBlackSummary,
                    checked = settings.amoledBlack,
                    onCheckedChange = { container.settings.setAmoledBlack(it) },
                )
            }
            item(key = "language") {
                ChoiceRow(
                    title = strings.languageTitle,
                    value = when (settings.language) {
                        AppLanguage.SYSTEM -> strings.languageSystem
                        AppLanguage.CHINESE -> strings.languageChinese
                        AppLanguage.ENGLISH -> strings.languageEnglish
                    },
                    icon = MiuixIcons.Translate,
                    onClick = {
                        choice = Choice(
                            title = strings.languageTitle,
                            options = listOf(
                                strings.languageSystem,
                                strings.languageChinese,
                                strings.languageEnglish,
                            ),
                            selected = AppLanguage.entries.indexOf(settings.language),
                            onPick = { index ->
                                container.settings.setLanguage(AppLanguage.entries[index])
                            },
                        )
                    },
                )
            }

            // ---------------------------------------------------------------- playback
            item(key = "playbackHeader") { SectionHeader(strings.playback) }
            item(key = "quality") {
                ChoiceRow(
                    title = strings.audioQuality,
                    value = qualityLabel(settings, strings),
                    summary = strings.qualityHintNetease,
                    icon = MiuixIcons.Music,
                    onClick = {
                        choice = Choice(
                            title = strings.audioQuality,
                            options = QUALITY_LEVELS.map { qualityLabel(it, strings) },
                            selected = QUALITY_LEVELS.indexOf(settings.neteaseQuality).coerceAtLeast(0),
                            onPick = { index ->
                                container.settings.setNeteaseQuality(QUALITY_LEVELS[index])
                            },
                        )
                    },
                )
            }
            item(key = "crossfade") {
                SliderRow(
                    title = strings.crossfade,
                    valueText = if (settings.crossfadeMs == 0) {
                        strings.crossfadeOff
                    } else {
                        strings.seconds(settings.crossfadeMs / 1000)
                    },
                    value = settings.crossfadeMs / 1000f,
                    range = 0f..12f,
                    steps = 11,
                    onValueChange = { container.settings.setCrossfadeMs((it * 1000f).toInt()) },
                )
            }
            item(key = "autoNext") {
                SwitchRow(
                    title = strings.autoPlayNext,
                    checked = settings.autoPlayNext,
                    onCheckedChange = { container.settings.setAutoPlayNext(it) },
                )
            }
            item(key = "skipError") {
                SwitchRow(
                    title = strings.skipOnError,
                    checked = settings.skipOnError,
                    onCheckedChange = { container.settings.setSkipOnError(it) },
                )
            }

            // ---------------------------------------------------------------- lyrics
            item(key = "lyricsHeader") { SectionHeader(strings.lyrics) }
            item(key = "lyricSize") {
                SliderRow(
                    title = strings.lyricFontSize,
                    valueText = "${settings.lyricFontSizeSp.toInt()} sp",
                    value = settings.lyricFontSizeSp,
                    range = 12f..48f,
                    steps = 35,
                    onValueChange = { container.settings.setLyricFontSize(it) },
                )
            }
            item(key = "lyricSpacing") {
                SliderRow(
                    title = strings.lyricLineSpacing,
                    valueText = String.format(java.util.Locale.ROOT, "%.2f×", settings.lyricLineSpacing),
                    value = settings.lyricLineSpacing,
                    range = 1.0f..2.4f,
                    steps = 27,
                    onValueChange = { container.settings.setLyricLineSpacing(it) },
                )
            }
            item(key = "lyricTrans") {
                SwitchRow(
                    title = strings.lyricTranslation,
                    checked = settings.lyricShowTranslation,
                    onCheckedChange = { container.settings.setLyricShowTranslation(it) },
                )
            }
            item(key = "lyricRoman") {
                SwitchRow(
                    title = strings.lyricRoman,
                    checked = settings.lyricShowRoman,
                    onCheckedChange = { container.settings.setLyricShowRoman(it) },
                )
            }
            item(key = "lyricPreview") {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Text(
                        text = strings.lyricPreviewText,
                        style = MiuixTheme.textStyles.title4,
                        color = MiuixTheme.colorScheme.primary,
                    )
                    if (settings.lyricShowTranslation) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = strings.lyricPreviewTrans,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                        )
                    }
                }
            }

            // ---------------------------------------------------------------- library
            item(key = "libraryHeader") { SectionHeader(strings.librarySection) }
            item(key = "rescan") {
                SettingsRow(
                    title = strings.rescanLocal,
                    summary = strings.importFromFolderSummary,
                    icon = MiuixIcons.Folder,
                    onClick = { folderPicker.launch(null) },
                )
            }
            item(key = "scanLyrics") {
                SettingsRow(
                    title = strings.scanLocalLyrics,
                    summary = if (scanningLyrics) strings.scanLocalLyricsRunning else strings.scanLocalLyricsSummary,
                    icon = MiuixIcons.Notes,
                    onClick = ::scanLocalLyrics,
                )
            }
            item(key = "clear") {
                SettingsRow(
                    title = strings.clearLibrary,
                    summary = strings.clearLibraryConfirm,
                    icon = MiuixIcons.Delete,
                    onClick = { confirmingClear = true },
                )
            }

            // ---------------------------------------------------------------- about
            item(key = "aboutHeader") { SectionHeader(strings.about) }
            item(key = "version") {
                SettingsRow(
                    title = strings.version,
                    summary = BuildConfig.VERSION_NAME,
                    icon = MiuixIcons.Info,
                )
            }
            item(key = "disclaimer") {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Text(
                        text = strings.disclaimer,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Caption(strings.disclaimerBody)
                    Spacer(Modifier.height(8.dp))
                    Caption(strings.madeWith)
                }
            }
        }

        // ---------------------------------------------------------------- overlays
        val currentChoice = choice
        OverlayBottomSheet(
            show = currentChoice != null,
            title = currentChoice?.title.orEmpty(),
            onDismissRequest = { choice = null },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                currentChoice?.options?.forEachIndexed { index, option ->
                    BasicComponent(
                        title = option,
                        summary = if (index == currentChoice.selected) strings.apply else null,
                        onClick = {
                            currentChoice.onPick(index)
                            choice = null
                        },
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        OverlayDialog(
            show = confirmingSignOut,
            title = strings.signOut,
            summary = strings.signOutConfirm,
            onDismissRequest = { confirmingSignOut = false },
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                TextButton(text = strings.cancel, onClick = { confirmingSignOut = false })
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = strings.signOut,
                    onClick = {
                        confirmingSignOut = false
                        scope.launch {
                            container.netease.signOut()
                            message(strings.signedOut)
                        }
                    },
                )
            }
        }

        OverlayDialog(
            show = confirmingClear,
            title = strings.clearLibrary,
            summary = strings.clearLibraryConfirm,
            onDismissRequest = { confirmingClear = false },
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                TextButton(text = strings.cancel, onClick = { confirmingClear = false })
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = strings.confirm,
                    onClick = {
                        confirmingClear = false
                        scope.launch {
                            val ids = container.library.songs(limit = 10_000).map { it.id }
                            container.library.deleteSongs(ids)
                            message(strings.libraryCleared)
                        }
                    },
                )
            }
        }
    }
}

/** A pending single-choice prompt; kept in state because the sheet is driven by the row tapped. */
private data class Choice(
    val title: String,
    val options: List<String>,
    val selected: Int,
    val onPick: (Int) -> Unit,
)

private fun qualityLabel(settings: AppSettings, strings: Strings): String =
    qualityLabel(settings.neteaseQuality, strings)

private fun qualityLabel(level: String, strings: Strings): String = when (level) {
    "standard" -> strings.qualityStandard
    "higher" -> strings.qualityHigher
    "exhigh" -> strings.qualityExhigh
    "lossless" -> strings.qualityLossless
    "hires" -> strings.qualityHires
    else -> level
}

@Composable
private fun SettingsRow(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    BasicComponent(
        title = title,
        summary = summary,
        startAction = icon?.let {
            {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MiuixTheme.colorScheme.primary,
                )
            }
        },
        onClick = onClick,
    )
}

@Composable
private fun ChoiceRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    summary: String? = null,
    icon: ImageVector? = null,
) {
    BasicComponent(
        title = title,
        summary = summary ?: value,
        startAction = icon?.let {
            {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MiuixTheme.colorScheme.primary,
                )
            }
        },
        endActions = {
            Text(
                text = value,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
            )
        },
        onClick = onClick,
    )
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    summary: String? = null,
) {
    BasicComponent(
        title = title,
        summary = summary,
        endActions = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
    )
}

@Composable
private fun SliderRow(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = title, style = MiuixTheme.textStyles.main, modifier = Modifier.weight(1f))
            Text(
                text = valueText,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(4.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
        )
    }
}
