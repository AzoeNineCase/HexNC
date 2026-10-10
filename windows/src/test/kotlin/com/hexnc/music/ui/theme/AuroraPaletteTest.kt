package com.hexnc.music.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the palette pass behind "整体饱和度下降一些，色调增加点灰度".
 *
 * The softening is a numeric transformation, so it can be checked without a display: every raw stop
 * must come out *less* saturated, must not lose contrast against the surfaces the app actually uses,
 * and the gradients must be built from the softened stops rather than the raw enum colours (reading
 * the raw values is the easy mistake — it would leave the wash and the artwork placeholders neon
 * while the schemes turned grey).
 */
class AuroraPaletteTest {

    private val roles: List<Pair<String, (AuroraTheme) -> Pair<Long, Long>>> = listOf(
        "accent" to { theme -> theme.accent to theme.softAccent },
        "accentDeep" to { theme -> theme.accentDeep to theme.softAccentDeep },
        "glow" to { theme -> theme.glow to theme.softGlow },
        "blush" to { theme -> theme.blush to theme.softBlush },
    )

    @Test
    fun `every stop of every theme loses saturation`() {
        AuroraTheme.entries.forEach { theme ->
            roles.forEach { (role, pair) ->
                val (raw, soft) = pair(theme)
                val before = saturation(raw)
                val after = saturation(soft)
                assertTrue(
                    "${theme.key} $role got more saturated: $before -> $after",
                    after < before,
                )
            }
        }
    }

    @Test
    fun `softening does not cost contrast on either surface`() {
        AuroraTheme.entries.forEach { theme ->
            val onWhite = contrast(theme.softAccent, WHITE)
            val onWhiteBefore = contrast(theme.accent, WHITE)
            // A luma-preserving desaturation lifts the contrast on a white surface...
            assertTrue(
                "${theme.key} accent on white: $onWhiteBefore -> $onWhite",
                onWhite >= onWhiteBefore - 0.05,
            )
            assertTrue("${theme.key} accent is too faint on white: $onWhite", onWhite >= 3.0)

            val onDark = contrast(theme.softAccent, DARK_SURFACE)
            val onDarkBefore = contrast(theme.accent, DARK_SURFACE)
            // ...and can cost a little on the dark surface, because the raw stop sat at a saturated
            // extreme. 1.6:1 is the widest dip measured across the six themes (Borealis, 7.60 -> 6.07);
            // the floor below still clears the 4:1 the accent needs as an interactive colour.
            assertTrue(
                "${theme.key} accent on the dark surface: $onDarkBefore -> $onDark",
                onDark >= onDarkBefore - 1.6,
            )
            assertTrue("${theme.key} accent is too faint on the dark surface: $onDark", onDark >= 4.0)
        }
    }

    @Test
    fun `the gradients read the softened stops`() {
        AuroraTheme.entries.forEach { theme ->
            assertEquals(
                "the aurora gradient of ${theme.key} must use the softened stops",
                listOf(Color(theme.softBlush), Color(theme.softAccent), Color(theme.softGlow)),
                theme.gradientStops,
            )
            // A no-op pass would leave the wash, the artwork placeholders and the schemes at the raw
            // chroma, which is exactly the state the GUI ask was complaining about.
            listOf(
                theme.accent to theme.softAccent,
                theme.accentDeep to theme.softAccentDeep,
                theme.glow to theme.softGlow,
                theme.blush to theme.softBlush,
            ).forEach { (raw, soft) ->
                assertTrue("${theme.key}: the softening pass did nothing", raw != soft)
            }
        }
    }

    @Test
    fun `softening is stable for repeated reads`() {
        AuroraTheme.entries.forEach { theme ->
            assertEquals(theme.softAccent, theme.softAccent)
            assertEquals(theme.softAccentDeep, theme.softAccentDeep)
            assertEquals(theme.softGlow, theme.softGlow)
            assertEquals(theme.softBlush, theme.softBlush)
        }
    }

    // ------------------------------------------------------------------ colour maths (test-local)

    private fun saturation(argb: Long): Float {
        val r = ((argb shr 16) and 0xFF).toFloat()
        val g = ((argb shr 8) and 0xFF).toFloat()
        val b = (argb and 0xFF).toFloat()
        val max = maxOf(r, g, b)
        return if (max == 0f) 0f else (max - minOf(r, g, b)) / max
    }

    private fun contrast(a: Long, b: Long): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun relativeLuminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val value = ((argb shr shift) and 0xFF) / 255.0
            return if (value <= 0.03928) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private companion object {
        const val WHITE = 0xFFFFFFFFL
        const val DARK_SURFACE = 0xFF0B0E17L
    }
}
