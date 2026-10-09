package com.hexnc.music.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.hexnc.music.data.prefs.ThemeMode
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * The HexNC aurora palette family.
 *
 * Every entry is a three-stop aurora: a deep [accent] that carries the interactive surfaces, a
 * [glow] that acts as the second gradient stop / secondary role, and a [blush] that closes the
 * gradient. The light and dark schemes are *derived* from those three colours by
 * [lightColors] / [darkColors] rather than hand-written, which keeps the six themes provably
 * consistent with each other and makes adding a seventh a one-line change.
 */
enum class AuroraTheme(
    val key: String,
    val labelZh: String,
    val labelEn: String,
    val accent: Long,
    val accentDeep: Long,
    val glow: Long,
    val blush: Long,
) {
    AURORA("aurora", "极光", "Aurora", 0xFF6D5DFC, 0xFF4536C9, 0xFF2FD8D8, 0xFFFF6FB5),
    BOREALIS("borealis", "北境", "Borealis", 0xFF10B981, 0xFF0A7D5A, 0xFF38D6FF, 0xFF9BE870),
    POLAR("polar", "极夜", "Polar", 0xFF3B82F6, 0xFF1D4ED8, 0xFF22D3EE, 0xFF818CF8),
    FLAMINGO("flamingo", "霞光", "Flamingo", 0xFFF0508E, 0xFFC42A68, 0xFFFF9E6D, 0xFFC084FC),
    NEBULA("nebula", "星云", "Nebula", 0xFF8B5CF6, 0xFF6D28D9, 0xFFEC4899, 0xFF22D3EE),
    ABYSS("abyss", "深海", "Abyss", 0xFF0F9B8E, 0xFF0A6F66, 0xFF06B6D4, 0xFF34D399);

    /** Localised display name; [useChinese] comes from the active [com.hexnc.music.ui.i18n.Strings]. */
    fun label(useChinese: Boolean): String = if (useChinese) labelZh else labelEn

    /** The three-stop aurora gradient, left to right: blush → accent → glow. */
    val gradientStops: List<Color> get() = listOf(Color(blush), Color(accent), Color(glow))

    companion object {
        val DEFAULT = AURORA

        fun fromKey(key: String?): AuroraTheme =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** The aurora gradient as a [Brush], for headers, artwork placeholders and progress fills. */
fun AuroraTheme.auroraBrush(): Brush = Brush.linearGradient(gradientStops)

/** A two-stop variant used where a full three-stop gradient would be too busy. */
fun AuroraTheme.accentBrush(): Brush = Brush.linearGradient(listOf(Color(accent), Color(glow)))

// ------------------------------------------------------------------ colour maths

private const val WHITE = 0xFFFFFFFFL
private const val LIGHT_SURFACE = 0xFFFFFFFFL
private const val LIGHT_ON = 0xFF12141CL
private const val DARK_SURFACE = 0xFF0B0E17L
private const val DARK_SURFACE_BASE = 0xFF12161FL
private const val DARK_ON = 0xFFEDF0F7L
private const val AMOLED = 0xFF000000L

/**
 * Linear interpolation between two opaque ARGB colours.
 *
 * Deliberately interpolates in sRGB rather than a perceptual space: the input deltas here are all
 * small tints (≤ 30 %) applied to a near-neutral base, where the difference between spaces is
 * imperceptible, and staying in sRGB keeps this a pure function with no colour-science dependency.
 */
private fun mix(a: Long, b: Long, t: Float): Long {
    val f = t.coerceIn(0f, 1f)
    fun channel(shift: Int): Long {
        val ca = ((a shr shift) and 0xFF).toFloat()
        val cb = ((b shr shift) and 0xFF).toFloat()
        return (ca + (cb - ca) * f).toLong().coerceIn(0L, 255L)
    }
    return (0xFFL shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

private fun tint(base: Long, accent: Long, amount: Float): Color = Color(mix(base, accent, amount))

// ------------------------------------------------------------------ scheme builders

/** Build the light scheme for [theme]. */
fun lightColors(theme: AuroraTheme): Colors {
    val accent = theme.accent
    val glow = theme.glow
    val blush = theme.blush
    val deep = theme.accentDeep
    return lightColorScheme(
        primary = Color(accent),
        onPrimary = Color(WHITE),
        primaryVariant = Color(deep),
        onPrimaryVariant = tint(deep, WHITE, 0.82f),
        primaryContainer = tint(LIGHT_SURFACE, accent, 0.16f),
        onPrimaryContainer = tint(deep, 0xFF000000, 0.05f),
        secondary = Color(glow),
        onSecondary = Color(mix(glow, 0xFF000000, 0.80f)),
        secondaryVariant = tint(glow, 0xFF000000, 0.18f),
        onSecondaryVariant = tint(glow, 0xFF000000, 0.38f),
        secondaryContainer = tint(LIGHT_SURFACE, glow, 0.20f),
        onSecondaryContainer = tint(glow, 0xFF000000, 0.62f),
        secondaryContainerVariant = tint(LIGHT_SURFACE, glow, 0.12f),
        onSecondaryContainerVariant = tint(glow, 0xFF000000, 0.50f),
        tertiaryContainer = tint(LIGHT_SURFACE, blush, 0.18f),
        onTertiaryContainer = tint(blush, 0xFF000000, 0.62f),
        tertiaryContainerVariant = tint(LIGHT_SURFACE, blush, 0.10f),
        background = Color(LIGHT_SURFACE),
        onBackground = Color(LIGHT_ON),
        onBackgroundVariant = tint(LIGHT_ON, accent, 0.45f),
        surface = Color(LIGHT_SURFACE),
        onSurface = Color(LIGHT_ON),
        surfaceVariant = tint(LIGHT_SURFACE, accent, 0.06f),
        onSurfaceSecondary = tint(LIGHT_ON, accent, 0.34f),
        onSurfaceVariantSummary = tint(LIGHT_ON, accent, 0.50f),
        onSurfaceVariantActions = tint(LIGHT_ON, accent, 0.30f),
        surfaceContainer = tint(LIGHT_SURFACE, accent, 0.05f),
        onSurfaceContainer = Color(LIGHT_ON),
        onSurfaceContainerVariant = tint(LIGHT_ON, accent, 0.40f),
        surfaceContainerHigh = tint(LIGHT_SURFACE, accent, 0.10f),
        onSurfaceContainerHigh = Color(LIGHT_ON),
        surfaceContainerHighest = tint(LIGHT_SURFACE, accent, 0.15f),
        onSurfaceContainerHighest = Color(LIGHT_ON),
        outline = tint(LIGHT_SURFACE, accent, 0.26f),
        dividerLine = tint(LIGHT_SURFACE, accent, 0.14f),
        sliderKeyPoint = Color(accent),
        sliderKeyPointForeground = Color(WHITE),
        sliderBackground = tint(LIGHT_SURFACE, accent, 0.22f),
        disabledPrimary = tint(LIGHT_SURFACE, accent, 0.42f),
        disabledOnPrimary = Color(WHITE),
        disabledPrimaryButton = tint(LIGHT_SURFACE, accent, 0.30f),
        disabledOnPrimaryButton = tint(LIGHT_SURFACE, LIGHT_ON, 0.38f),
        disabledPrimarySlider = tint(LIGHT_SURFACE, accent, 0.30f),
        disabledSecondary = tint(LIGHT_SURFACE, glow, 0.42f),
        disabledOnSecondary = Color(WHITE),
        disabledSecondaryVariant = tint(LIGHT_SURFACE, glow, 0.60f),
        disabledOnSecondaryVariant = tint(LIGHT_SURFACE, LIGHT_ON, 0.40f),
        disabledOnSurface = tint(LIGHT_SURFACE, LIGHT_ON, 0.35f),
    )
}

/** Build the dark scheme for [theme]; [amoled] collapses the base layer to true black. */
fun darkColors(theme: AuroraTheme, amoled: Boolean = false): Colors {
    val accent = theme.accent
    val glow = theme.glow
    val blush = theme.blush
    val base = if (amoled) AMOLED else DARK_SURFACE
    val layer = if (amoled) mix(AMOLED, accent, 0.10f) else mix(DARK_SURFACE_BASE, accent, 0.12f)
    val raised = mix(layer, accent, 0.10f)
    val highest = mix(layer, accent, 0.18f)
    return darkColorScheme(
        primary = tint(accent, WHITE, 0.22f),
        onPrimary = Color(mix(base, 0xFF000000, 0.85f)),
        primaryVariant = tint(accent, WHITE, 0.06f),
        onPrimaryVariant = tint(accent, WHITE, 0.78f),
        primaryContainer = tint(base, accent, 0.55f),
        onPrimaryContainer = tint(accent, WHITE, 0.80f),
        secondary = tint(glow, WHITE, 0.12f),
        onSecondary = Color(mix(base, 0xFF000000, 0.85f)),
        secondaryVariant = tint(glow, WHITE, 0.02f),
        onSecondaryVariant = tint(glow, WHITE, 0.70f),
        secondaryContainer = tint(base, glow, 0.34f),
        onSecondaryContainer = tint(glow, WHITE, 0.74f),
        secondaryContainerVariant = tint(base, glow, 0.24f),
        onSecondaryContainerVariant = tint(glow, WHITE, 0.62f),
        tertiaryContainer = tint(base, blush, 0.32f),
        onTertiaryContainer = tint(blush, WHITE, 0.74f),
        tertiaryContainerVariant = tint(base, blush, 0.22f),
        background = Color(base),
        onBackground = Color(DARK_ON),
        onBackgroundVariant = tint(DARK_ON, accent, 0.42f),
        surface = Color(base),
        onSurface = Color(DARK_ON),
        surfaceVariant = tint(layer, accent, 0.16f),
        onSurfaceSecondary = tint(DARK_ON, accent, 0.34f),
        onSurfaceVariantSummary = tint(DARK_ON, accent, 0.48f),
        onSurfaceVariantActions = tint(DARK_ON, accent, 0.28f),
        surfaceContainer = Color(layer),
        onSurfaceContainer = Color(DARK_ON),
        onSurfaceContainerVariant = tint(DARK_ON, accent, 0.40f),
        surfaceContainerHigh = Color(raised),
        onSurfaceContainerHigh = Color(DARK_ON),
        surfaceContainerHighest = Color(highest),
        onSurfaceContainerHighest = Color(DARK_ON),
        outline = tint(layer, accent, 0.42f),
        dividerLine = tint(layer, accent, 0.26f),
        windowDimming = Color(0x99000000),
        sliderKeyPoint = tint(accent, WHITE, 0.22f),
        sliderKeyPointForeground = Color(mix(base, 0xFF000000, 0.85f)),
        sliderBackground = tint(layer, accent, 0.40f),
        disabledPrimary = tint(base, accent, 0.40f),
        disabledOnPrimary = tint(base, DARK_ON, 0.45f),
        disabledPrimaryButton = tint(base, accent, 0.32f),
        disabledOnPrimaryButton = tint(base, DARK_ON, 0.40f),
        disabledPrimarySlider = tint(base, accent, 0.32f),
        disabledSecondary = tint(base, glow, 0.34f),
        disabledOnSecondary = tint(base, DARK_ON, 0.45f),
        disabledSecondaryVariant = tint(base, glow, 0.24f),
        disabledOnSecondaryVariant = tint(base, DARK_ON, 0.40f),
        disabledOnSurface = tint(base, DARK_ON, 0.34f),
    )
}

// ------------------------------------------------------------------ entry point

/**
 * The active aurora palette, exposed so that decoration (gradients, glow washes, the now-playing
 * backdrop) can read it without every screen having to thread the value through its parameters.
 */
val LocalAuroraTheme = staticCompositionLocalOf { AuroraTheme.DEFAULT }

/** Whether the effective scheme is dark, for the places that need to branch on it directly. */
val LocalIsDarkTheme = staticCompositionLocalOf { true }

/** Resolve the effective dark/light decision for a stored [ThemeMode]. */
@Composable
fun isDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * Applies the HexNC aurora theme.
 *
 * Uses Miuix's `colors`-parameter overload rather than the `controller` overload on purpose: the
 * `colors` overload also installs `LocalContentColor`, so Miuix's own `Text` default
 * (`color = Color.Unspecified` → `LocalContentColor`) resolves correctly. With the `controller`
 * overload the content colour is left untouched and dark-mode text renders black on black.
 */
@Composable
fun HexNCTheme(
    themeMode: ThemeMode,
    aurora: AuroraTheme,
    amoledBlack: Boolean,
    content: @Composable () -> Unit,
) {
    val dark = isDarkTheme(themeMode)
    val colors = remember(aurora, dark, amoledBlack) {
        if (dark) darkColors(aurora, amoledBlack) else lightColors(aurora)
    }
    CompositionLocalProvider(
        LocalAuroraTheme provides aurora,
        LocalIsDarkTheme provides dark,
    ) {
        MiuixTheme(colors = colors, content = content)
    }
}
