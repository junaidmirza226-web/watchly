package com.watchly.app.ui.details

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Title
import com.watchly.app.ui.theme.WatchlyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * Bug 1 (2026-11-06): the Title Details back button lived INSIDE the
 * scrolling header and scrolled off the top of the page. The fix made
 * it a fixed overlay sibling of the scrolling list.
 *
 * This is the instrumented twin of the Roborazzi scrolled-state
 * golden (WatchlyScreenshotTest.details_scrolled): on a real runtime
 * it scrolls the details list hard and asserts the observable effect
 * — the content moved a long way, the back button did not move at
 * all, and it is still displayed.
 *
 * The fixture title carries no provider ids, so the details
 * ViewModel performs no network fetch: the test is fully
 * deterministic and offline.
 */
@RunWith(AndroidJUnit4::class)
class DetailsChromeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun backButtonStaysAnchoredWhileDetailsContentScrolls() {
        val listState = LazyListState()
        composeTestRule.setContent {
            WatchlyTheme {
                TitleDetailsScreen(title = FIXTURE, listState = listState)
            }
        }
        composeTestRule.waitForIdle()

        val backButton = composeTestRule.onNodeWithContentDescription(BACK_DESCRIPTION)
        backButton.assertIsDisplayed()
        val boundsBefore = backButton.fetchSemanticsNode()?.boundsInRoot
        assertTrue("back button must be laid out before scrolling", boundsBefore != null)
        assertEquals(0, listState.firstVisibleItemIndex)
        assertEquals(0, listState.firstVisibleItemScrollOffset)

        // Scroll to the last item (the Sources body): the page is far
        // taller than the viewport, so the list must travel hundreds
        // of pixels — the exact motion that used to carry the back
        // button off-screen.
        runBlocking {
            withContext(Dispatchers.Main) {
                listState.scrollToItem(LAST_ITEM_INDEX)
            }
        }
        composeTestRule.waitForIdle()

        assertTrue(
            "content must actually have scrolled (offset was " +
                "${listState.firstVisibleItemScrollOffset}px)",
            listState.firstVisibleItemScrollOffset > MIN_SCROLL_PIXELS
        )
        backButton.assertIsDisplayed()
        val boundsAfter: Rect? = backButton.fetchSemanticsNode()?.boundsInRoot
        assertEquals(
            "back button must stay anchored at the same position",
            boundsBefore,
            boundsAfter
        )
    }

    private companion object {
        const val BACK_DESCRIPTION = "Back"

        /** Header, "Sources" heading, sources body. */
        const val LAST_ITEM_INDEX = 2

        /** ~150dp on the CI emulator — far beyond a layout nudge. */
        const val MIN_SCROLL_PIXELS = 400

        /**
         * No provider ids on purpose (no network). The long overview
         * makes the header alone taller than any phone viewport, so
         * the page is guaranteed scrollable.
         */
        val FIXTURE = Title(
            name = "The Clockwork Meridian",
            overview = "A cartographer who charts coastlines that move at night " +
                "takes one last commission: map the meridian where lost hours " +
                "wash ashore. Every chart she draws changes the coastline she " +
                "has just measured, and every harbour town along the route " +
                "swears the sea was somewhere else yesterday. Her instruments " +
                "disagree with each other, her crew keeps two different " +
                "logbooks, and the tide tables she bought in the capital are " +
                "printed in a language nobody aboard can read. What begins " +
                "as a surveying job becomes a slow chase along a coast that " +
                "is quietly erasing itself, one inlet at a time, while the " +
                "commission that hired her denies ever sending the order. " +
                "She has to decide whether finishing the map is worth what " +
                "the map is doing to the places on it, and whether the " +
                "meridian she is hunting is a line on the water at all — " +
                "or the moment, years ago, when she first chose not to go " +
                "home. The further north she sails, the more the stars " +
                "settle into patterns she almost recognises, and the more " +
                "her own handwriting appears in logbooks she has never " +
                "opened, in ports she has never visited, dated tomorrow.",
            mediaType = MediaType.MOVIE,
            year = "2024",
            genres = listOf("Drama", "Mystery", "Adventure"),
            runtimeMinutes = 118
        )
    }
}
