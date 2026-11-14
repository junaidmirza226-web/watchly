package com.watchly.app.ui.components

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Title
import com.watchly.app.ui.theme.WatchlyTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Cycle 14 — Test Lab run 13 labeling finding: the "▶ Playable"
 * badge was a separate speakable node on EVERY poster card (7
 * examples in one crawl). The badge is now decorative and the
 * playability is spoken once, as part of the card's own
 * description. The title's name stays its own text node —
 * Maestro's journeys tap titles by that exact visible text, so
 * it must not move or merge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PosterCardAccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `playable card speaks playability once, on the card`() {
        var clicks = 0
        val title = Title(
            archiveId = "night-of-the-living-dead",
            name = "Night of the Living Dead",
            mediaType = MediaType.MOVIE,
            year = "1968"
        )
        composeRule.setContent {
            WatchlyTheme {
                PosterCard(
                    title = title,
                    onClick = { clicks++ },
                    modifier = Modifier.width(120.dp)
                )
            }
        }
        composeRule.waitForIdle()
        // No separately-speakable badge node anymore.
        composeRule.onNodeWithText("▶ Playable").assertDoesNotExist()
        // The card itself carries name + playability…
        composeRule.onNodeWithContentDescription(
            "Night of the Living Dead, Playable"
        ).assertExists()
        // …the name is still its own visible text node (Maestro)…
        composeRule.onNodeWithText("Night of the Living Dead").assertExists()
        // …and the card still clicks.
        composeRule.onNodeWithContentDescription(
            "Night of the Living Dead, Playable"
        ).performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `info-only card speaks info only, on the card`() {
        val title = Title(
            tmdbId = 155,
            name = "The Dark Knight",
            mediaType = MediaType.MOVIE,
            year = "2008"
        )
        composeRule.setContent {
            WatchlyTheme {
                PosterCard(
                    title = title,
                    onClick = {},
                    modifier = Modifier.width(120.dp)
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Info only").assertDoesNotExist()
        composeRule.onNodeWithContentDescription(
            "The Dark Knight, Info only"
        ).assertExists()
        composeRule.onNodeWithText("The Dark Knight").assertExists()
    }
}
