package com.hexnc.music.ui.platform

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.PointerMatcher
import androidx.compose.foundation.onClick
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton

/**
 * The desktop stand-in for a long press.
 *
 * A mouse has no long press: the gesture that means "show me everything I can do with this row" on a
 * touch screen is the secondary button on a PC. This modifier runs [onSecondary] when the row is
 * right-clicked, and does nothing at all when [enabled] is false, so a caller can attach it
 * unconditionally and let a null handler mean "no actions".
 *
 * The touch gesture stays wired as well — a Windows tablet and a touchpad both still work — so the
 * only thing that changes on the desktop is that there is now a discoverable mouse equivalent.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.secondaryClick(enabled: Boolean = true, onSecondary: () -> Unit): Modifier =
    if (!enabled) {
        this
    } else {
        onClick(matcher = PointerMatcher.mouse(PointerButton.Secondary)) { onSecondary() }
    }
