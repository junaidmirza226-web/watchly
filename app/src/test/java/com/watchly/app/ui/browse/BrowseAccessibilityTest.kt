package com.watchly.app.ui.browse

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.watchly.app.data.model.PlayabilityFilter
import com.watchly.app.ui.components.PlayabilityFilterBar
import com.watchly.app.ui.theme.WatchlyTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Cycle 14 — accessibility pins for Test Lab run 13's element-level
 * findings on the Browse screens:
 *  - Touch target (warning): the sort chips measured 28dp tall on
 *    a real device. In this harness the same FilterChip measures
 *    48dp with EITHER the old minimum-height or the new fixed
 *    height (the probe is recorded in progress-report-21), so this
 *    test pins the floor on the REAL GenreChip rather than
 *    reproducing the device number: if the chip ever drops below
 *    48dp here, the floor is gone everywhere.
 *  - Labeling (minor): two chips read "All" on the Movies screen
 *    (availability row + genre row) and shared one spoken label.
 *    Visible text stays "All" on both; the spoken labels now name
 *    their row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrowseAccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `sort chip meets the 48dp touch floor`() {
        composeRule.setContent {
            WatchlyTheme {
                Row {
                    GenreChip(
                        label = "Popular",
                        selected = true,
                        onClick = {}
                    )
                }
            }
        }
        composeRule.waitForIdle()
        val node = composeRule.onNodeWithText("Popular").fetchSemanticsNode()
        val heightDp = node.boundsInRoot.height / composeRule.density.density
        assertTrue("sort chip height was ${heightDp}dp", heightDp >= 48f)
    }

    @Test
    fun `the two All chips speak distinct labels`() {
        composeRule.setContent {
            WatchlyTheme {
                androidx.compose.foundation.layout.Column {
                    PlayabilityFilterBar(
                        selected = PlayabilityFilter.ALL,
                        onSelect = {}
                    )
                    Row {
                        GenreChip(
                            label = "All",
                            spokenLabel = "All, genre filter",
                            selected = true,
                            onClick = {}
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        // Visible text is "All" on both chips, unchanged.
        composeRule.onAllNodesWithText("All").assertCountEquals(2)
        // Spoken labels name their row, so no two "All"s collide.
        composeRule.onNodeWithContentDescription("All, availability filter")
            .assertExists()
        composeRule.onNodeWithContentDescription("All, genre filter")
            .assertExists()
    }
}
