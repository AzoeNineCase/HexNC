package com.hexnc.music.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The design tokens M5 was specified against: one place for the numbers that used to be scattered
 * across screens as magic values.
 *
 * These are deliberately not a theme (`MaterialTheme`-style) — HexNC has no dynamic type scale and
 * the accent already lives in [AuroraTheme]. This is just the vocabulary list: spacing steps, corner
 * radii, row metrics, motion timings and the handful of type sizes that carry hierarchy. Anything a
 * screen needs twice belongs here rather than being retyped.
 */

/** The 4 dp spacing ramp. Every gap and padding in the app is one of these. */
object Spacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
}

/**
 * Corner radii.
 *
 * A list cover is [row], a surface card is [card], the hero artwork on the player is [hero]. A
 * capsule is [pill] rather than an arbitrary 50 dp so it stays a capsule at any height.
 */
object Radii {
    val row = 10.dp
    val card = 16.dp
    val hero = 20.dp
    val pill = 999.dp
    val sheet = 24.dp
}

/**
 * List rhythm.
 *
 * [rowHeight] is a minimum, not a fixed height: a row with a badge or a second action line may need
 * more. [dividerInset] is where a separator starts so it lines up with the text, not the artwork —
 * the detail that makes a list read as one column instead of two.
 */
object RowMetrics {
    val rowHeight = 64.dp
    val cover = 48.dp
    val dividerInset = 76.dp
    val horizontalPadding = 16.dp
}

/** Type sizes that carry hierarchy. Sizes Miuix already provides are used as-is; these are the gaps. */
object TypeScale {
    val pageTitle = 22.sp
    val artist = 14.sp
    val rowTitle = 15.sp
    val rowSubtitle = 12.5.sp
    val sectionLabel = 13.sp
    /** Letter spacing for the small all-caps-ish section labels. */
    val sectionLabelTracking = 0.4.sp
}

/**
 * Motion timings and curves.
 *
 * Nothing here exceeds 400 ms, and displacement always uses a decelerating curve — [press] is the one
 * place [LinearEasing] is allowed, because a 90 ms colour/fade acknowledgement is not a movement.
 */
object Motion {
    const val pageMs = 260
    const val coverMs = 320
    const val lyricMs = 180
    const val pressMs = 90
    const val sheetMs = 240
    const val shimmerMs = 1200

    /** Pages and sheets: the standard decelerate-in, settle-out curve. */
    val standard: Easing = FastOutSlowInEasing

    /** Lyric line highlight: arrives quickly, settles gently. */
    val lyric: Easing = LinearOutSlowInEasing

    /** Press acknowledgement and the skeleton shimmer sweep. */
    val press: Easing = LinearEasing

    /**
     * A slightly stronger ease-out for the shared-element cover hand-off, where the travel is long
     * enough that a plain FastOutSlowIn reads as a stall at the end.
     */
    val cover: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}