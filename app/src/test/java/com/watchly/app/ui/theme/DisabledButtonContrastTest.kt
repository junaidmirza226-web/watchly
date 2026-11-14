package com.watchly.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Cycle 7, Phase C — pins the fix for the disabled-button
 * low-contrast class behind part of Test Lab's recurring
 * low-contrast items (runs 4–6).
 *
 * The bug class: gold primary buttons that can render DISABLED
 * (details Play for catalogue-only titles, the API-key save
 * buttons with an empty field) set only container/content
 * colours, so Material3's DEFAULT disabled colours applied —
 * onSurface composited at 12% (container) and 38% (content),
 * which lands near 3:1 on Watchly's near-black surfaces, below
 * the WCAG AA 4.5:1 bar for text. The fix is
 * [watchlyGoldButtonColors] + [WatchlyDisabledContent] on
 * [WatchlySurfaceVariant]; these tests pin the ratios so a
 * future colour tweak cannot silently re-break them.
 *
 * The math is WCAG 2.x relative luminance over the sRGB
 * channels, computed from the theme constants — no rendering
 * needed, so this runs in the plain unit suite.
 */
class DisabledButtonContrastTest {

    @Test
    fun disabledGoldButtonContentMeetsAaOnItsContainer() {
        val ratio = contrastRatio(WatchlyDisabledContent, WatchlySurfaceVariant)
        assertTrue(
            "disabled content on its container must meet AA (was $ratio)",
            ratio >= AA_NORMAL_TEXT
        )
    }

    @Test
    fun disabledGoldButtonContentMeetsAaOnAppBackground() {
        // The same button can sit directly on the app background
        // (details header area) — the pair must hold there too.
        val ratio = contrastRatio(WatchlyDisabledContent, WatchlyBlack)
        assertTrue(
            "disabled content on the app background must meet AA (was $ratio)",
            ratio >= AA_NORMAL_TEXT
        )
    }

    @Test
    fun materialDefaultDisabledColoursWouldFailAa() {
        // Documents the bug being fixed: Material3's defaults
        // composite onSurface (#EDEDED in this scheme) at 12%
        // for the container and 38% for the content over the
        // app background. That effective pair must NOT meet AA
        // — if a future Material version changes the defaults
        // this test says the explicit colours may be revisited.
        val container = composite(WatchlyOnSurface, 0.12f, WatchlyBlack)
        val content = composite(WatchlyOnSurface, 0.38f, container)
        val ratio = contrastRatio(content, container)
        assertTrue(
            "Material's default disabled pair should fail AA (was $ratio)",
            ratio < AA_NORMAL_TEXT
        )
    }

    @Test
    fun mutedTextMeetsAaOnTheSurfacesItSitsOn() {
        // Regression pin for the cycle-4 brightening: the muted
        // secondary text must stay AA on both surfaces it is
        // rendered on (cards and the app background).
        assertTrue(
            "muted on surfaceVariant must meet AA",
            contrastRatio(WatchlyMuted, WatchlySurfaceVariant) >= AA_NORMAL_TEXT
        )
        assertTrue(
            "muted on background must meet AA",
            contrastRatio(WatchlyMuted, WatchlyBlack) >= AA_NORMAL_TEXT
        )
    }

    private fun contrastRatio(foreground: Color, background: Color): Double {
        val l1 = relativeLuminance(foreground)
        val l2 = relativeLuminance(background)
        return (max(l1, l2) + 0.05) / (min(l1, l2) + 0.05)
    }

    private fun relativeLuminance(color: Color): Double {
        fun linear(channel: Float): Double {
            val s = channel.toDouble()
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(color.red) +
            0.7152 * linear(color.green) +
            0.0722 * linear(color.blue)
    }

    /** Alpha-composites [foreground] at [alpha] over [background] (sRGB space, as rendered). */
    private fun composite(foreground: Color, alpha: Float, background: Color): Color =
        Color(
            red = foreground.red * alpha + background.red * (1f - alpha),
            green = foreground.green * alpha + background.green * (1f - alpha),
            blue = foreground.blue * alpha + background.blue * (1f - alpha)
        )

    private companion object {
        const val AA_NORMAL_TEXT = 4.5
    }
}
