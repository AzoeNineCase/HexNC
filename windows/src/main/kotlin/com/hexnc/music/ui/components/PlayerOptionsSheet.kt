package com.hexnc.music.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hexnc.music.ui.i18n.Strings
import com.hexnc.music.ui.theme.Spacing
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.VolumeOff
import top.yukonga.miuix.kmp.icon.extended.VolumeUp
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The playback page's overflow sheet: the controls that are used rarely enough that keeping them on
 * the page only added chrome.
 *
 * The volume slider lives here on desktop because a PC has no volume rocker, and it used to occupy a
 * permanent row under the transport — the single widest piece of decoration on a page whose job is
 * the artwork. Immersive mode is here for the same reason: it is a mode, not a control.
 */
@Composable
fun PlayerOptionsSheet(
    show: Boolean,
    strings: Strings,
    onDismiss: () -> Unit,
    onImmersive: () -> Unit,
    showVolume: Boolean = true,
    volume: Float = 1f,
    onVolume: (Float) -> Unit = {},
) {
    OverlayBottomSheet(
        show = show,
        title = strings.playerOptions,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.md)) {
            BasicComponent(
                title = strings.immersive,
                summary = strings.immersiveHint,
                onClick = {
                    onDismiss()
                    onImmersive()
                },
            )
            if (showVolume) {
                Spacer(Modifier.height(Spacing.sm))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    IconButton(
                        onClick = { onVolume(if (volume > 0f) 0f else 1f) },
                    ) {
                        Icon(
                            imageVector = if (volume > 0f) MiuixIcons.VolumeUp else MiuixIcons.VolumeOff,
                            contentDescription = strings.volume,
                            modifier = Modifier.width(20.dp),
                            tint = MiuixTheme.colorScheme.onBackgroundVariant,
                        )
                    }
                    Slider(
                        value = volume,
                        onValueChange = onVolume,
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${(volume * 100).toInt()}%",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }
            }
        }
    }
}