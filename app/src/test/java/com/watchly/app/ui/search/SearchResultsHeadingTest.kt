package com.watchly.app.ui.search

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Title
import com.watchly.app.ui.theme.WatchlyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Cycle 14 — Test Lab run 13 labeling finding: the search-results
 * "Shows" section heading spoke identically to the bottom-nav
 * "Shows" tab. The heading's visible text is unchanged; its spoken
 * name now says it is a results section. (No Maestro flow taps
 * either "Shows" — flows tap Home / My Library / Search and title
 * names — so the nav tab keeps its plain label and only the
 * heading moves.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchResultsHeadingTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `shows heading speaks as a search results section`() {
        composeRule.setContent {
            WatchlyTheme {
                ResultGroup(
                    heading = "Shows",
                    titles = listOf(
                        Title(
                            tvmazeId = 1,
                            name = "Person of Interest",
                            mediaType = MediaType.TV
                        )
                    ),
                    onTitleClick = {}
                )
            }
        }
        composeRule.waitForIdle()
        // Visible text unchanged…
        composeRule.onNodeWithText("Shows").assertExists()
        // …spoken name disambiguated from the bottom-nav tab.
        composeRule.onNodeWithContentDescription("Shows in search results")
            .assertExists()
    }
}
