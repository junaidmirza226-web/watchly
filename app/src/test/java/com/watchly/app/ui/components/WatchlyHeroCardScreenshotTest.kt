package com.watchly.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

/**
 * Paparazzi screenshot test for the black + gold home-screen hero card.
 * Renders on the JVM — no emulator, no device.
 *
 * Copy to: app/src/test/java/com/watchly/app/ui/components/
 * Golden images are written to: app/src/test/snapshots/  (commit them!)
 *
 * Commands (also in README.md in this folder):
 *   Record goldens:  ./gradlew :app:recordPaparazziDebug
 *   Verify (CI):     ./gradlew :app:verifyPaparazziDebug
 *
 * Dependencies: see gradle-snippet.kts in this folder.
 * NOTE: Paparazzi tests use the JUnit 4 rule API — another reason the
 * JUnit Vintage engine from ../unit/gradle-snippet.kts must be present.
 */
class WatchlyHeroCardScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
        showSystemUi = false,
    )

    @Test
    fun heroCard_featuredMovie() {
        paparazzi.snapshot {
            WatchlyThemeForTest {
                CardOnHomeBackground {
                    WatchlyHeroCard(
                        title = "Dune: Part Two",
                        metaLine = "2024  •  2h 46m  •  Sci-Fi",
                        overview = "Paul Atreides unites with Chani and the Fremen " +
                            "while seeking revenge against the conspirators who destroyed his family.",
                        badge = "#1 Trending",
                        artworkBrush = Brush.verticalGradient(
                            listOf(WatchlyColors.Surface, WatchlyColors.Gold.copy(alpha = 0.35f)),
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun heroCard_noBadge_longTitleIsEllipsised() {
        paparazzi.snapshot {
            WatchlyThemeForTest {
                CardOnHomeBackground {
                    WatchlyHeroCard(
                        title = "The Lord of the Rings: The Fellowship of the Ring — Extended Edition",
                        metaLine = "2001  •  3h 48m  •  Fantasy",
                        overview = "A meek Hobbit and eight companions set out on a journey " +
                            "to destroy the One Ring and defeat the Dark Lord Sauron.",
                        badge = null,
                    )
                }
            }
        }
    }
}

/** The card as it appears on the home screen: padded, on a black background. */
@androidx.compose.runtime.Composable
private fun CardOnHomeBackground(content: @androidx.compose.runtime.Composable () -> Unit) {
    Box(
        modifier = Modifier
            .background(WatchlyColors.Black)
            .padding(16.dp),
    ) {
        content()
    }
}

/**
 * Minimal dark Material 3 theme in Watchly black + gold.
 * If the app already has a WatchlyTheme, delete this and wrap the
 * snapshots in that instead — goldens must reflect the real theme.
 */
@androidx.compose.runtime.Composable
private fun WatchlyThemeForTest(content: @androidx.compose.runtime.Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = WatchlyColors.Gold,
            background = WatchlyColors.Black,
            surface = WatchlyColors.Surface,
            onBackground = WatchlyColors.TextPrimary,
            onSurface = WatchlyColors.TextPrimary,
        ),
    ) {
        Surface(color = WatchlyColors.Black, content = content)
    }
}
