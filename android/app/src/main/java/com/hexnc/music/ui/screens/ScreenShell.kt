package com.hexnc.music.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.hexnc.music.ui.i18n.Strings
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Shell for every pushed (non-tab) destination.
 *
 * Deliberately renders its own [Scaffold] rather than reusing the tab shell: a detail screen covers
 * the navigation bar, and Miuix's `Overlay*` popups resolve against the nearest host scaffold, so
 * each screen needs to own one.
 *
 * [actions] is a slot instead of an overflow menu because the pushed screens only ever need one or
 * two verbs and a real list popup needs an anchor provider that would exist purely for its own sake.
 */
@Composable
fun DetailScaffold(
    title: String,
    strings: Strings,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    subtitle: String = "",
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            SmallTopAppBar(
                title = title,
                subtitle = subtitle,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = strings.back,
                            modifier = androidx.compose.ui.Modifier.size(24.dp),
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                },
                actions = actions,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        content = content,
    )
}
