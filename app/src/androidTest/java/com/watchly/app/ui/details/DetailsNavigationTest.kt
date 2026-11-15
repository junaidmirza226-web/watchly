package com.watchly.app.ui.details

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.waitUntilNodeCount
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.watchly.app.data.library.WatchlistRepository
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Title
import com.watchly.app.ui.nav.WatchlyBottomBar
import com.watchly.app.ui.nav.WatchlyDestination
import com.watchly.app.ui.nav.WatchlyNavHost
import com.watchly.app.ui.nav.WatchlyRoutes
import com.watchly.app.ui.theme.WatchlyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Bug 2 (2026-11-06): back from Title Details was unreliable —
 * the handed-over title was cleared BEFORE the pop (blanking the
 * page mid-exit into a dead placeholder), nothing kept a fast
 * double-open from stacking two details entries, and leaving via a
 * bottom tab could leave a stale title behind.
 *
 * These tests compose the PRODUCTION NavHost and bottom bar
 * (WatchlyNavHost / WatchlyBottomBar) with a captured controller,
 * so the destination wiring, the pop-then-clear onBack path and
 * the tab-switch clearing they assert are the real code. Only the
 * opening trigger is replicated, because it is exactly the two
 * lines HomeScreen's poster tap runs (select + navigate with
 * launchSingleTop).
 *
 * Fixture titles carry TMDB ids but the test device has no TMDB
 * key saved, so details performs no network fetch.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class DetailsNavigationTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: NavHostController

    @Before
    fun setUp() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        appContext.getSharedPreferences(
            WatchlistRepository.PREFS_NAME,
            Context.MODE_PRIVATE
        ).edit().clear().commit()
        TitleDetailsStore.clear()
        val library = WatchlistRepository(appContext)
        library.toggleWatchlist(TITLE_A)
        library.toggleWatchlist(TITLE_B)

        composeTestRule.setContent {
            val nav = rememberNavController()
            navController = nav
            WatchlyTheme {
                Scaffold(bottomBar = { WatchlyBottomBar(nav) }) { innerPadding ->
                    WatchlyNavHost(
                        navController = nav,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun backFromDetailsReturnsHomeAndClearsTheStore() {
        openDetails(TITLE_A)

        assertEquals(WatchlyRoutes.DETAILS, currentRoute())
        composeTestRule.onNodeWithText(TITLE_A.name).assertIsDisplayed()

        // The real on-screen back button (the production onBack:
        // pop first, then clear the store).
        composeTestRule.onNodeWithContentDescription("Back").performClick()
        composeTestRule.waitForIdle()

        assertEquals(WatchlyDestination.HOME.route, currentRoute())
        assertNull(
            "store must be cleared after leaving details",
            TitleDetailsStore.take()
        )
    }

    @Test
    fun openingDetailsTwiceStacksOnlyOneEntry() {
        // A fast double-tap on a poster runs openDetails twice; the
        // production navigate uses launchSingleTop, so exactly one
        // DETAILS entry may exist — one back press must land home.
        openDetails(TITLE_A)
        openDetails(TITLE_A)

        assertEquals(WatchlyRoutes.DETAILS, currentRoute())
        composeTestRule.onNodeWithText(TITLE_A.name).assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("Back").performClick()
        composeTestRule.waitForIdle()

        assertEquals(
            "one back press must leave details entirely (no stacked copy)",
            WatchlyDestination.HOME.route,
            currentRoute()
        )
        assertNull(TitleDetailsStore.take())
    }

    @Test
    fun leavingViaTabClearsStoreAndNextOpenShowsTheFreshTitle() {
        openDetails(TITLE_A)
        composeTestRule.onNodeWithText(TITLE_A.name).assertIsDisplayed()

        // Leave through the bottom tab bar — the production tab
        // handler clears the handed-over title on this path.
        // (Cycle 8 follow-up 5: the run-42353320345 failure proved
        // this click can silently land as a no-op tap beside the
        // item on the starved SwiftShader emulator — the test then
        // waited on Library content while still on Details. Retry
        // the tap until the route actually changes; each retry is a
        // real dispatch, and the route assertion below remains the
        // arbiter — if Library is never reached, it still fails.)
        var attempts = 0
        while (currentRoute() != WatchlyDestination.LIBRARY.route &&
            attempts < 5
        ) {
            composeTestRule.onNodeWithText(
                WatchlyDestination.LIBRARY.label
            ).performClick()
            composeTestRule.waitForIdle()
            attempts++
        }

        assertEquals(WatchlyDestination.LIBRARY.route, currentRoute())
        assertNull(
            "tab exit from details must clear the store",
            TitleDetailsStore.take()
        )

        // Data-level arbiter first (cycle 8 follow-up 6): the
        // watchlist must still hold BOTH fixture titles at this
        // point. If an earlier test in the suite mutated the shared
        // app state, this names it directly instead of another
        // semantics-tree timeout downstream.
        val storedNames = WatchlistRepository(
            InstrumentationRegistry.getInstrumentation().targetContext
        ).getWatchlist().map { it.name }
        org.junit.Assert.assertTrue(
            "watchlist must contain both fixtures, was $storedNames",
            storedNames.containsAll(listOf(TITLE_A.name, TITLE_B.name))
        )

        // The Watchlist section sits several sections down My
        // Library's LazyColumn; on the emulator it is only composed
        // once the list scrolls. Bring it into view the way a user
        // does — with real swipe gestures on the list itself
        // (cycle 8: the new Downloads card pushed the section lower,
        // and CI run 42347944271 found no Beta card in the tree at
        // all). Semantics scroll actions proved insufficient here:
        // the dumps in runs 42355478997 / 42357720673 show the
        // boundary items measured with zero-size bounds at the list
        // origin — composed, never laid out — and only a real
        // scroll gesture runs the placement passes that lay them
        // out (run 42350673385 passed this whole test on swipes the
        // framework generated internally). Bounded loop, hard
        // assertion after: if the section truly never lays out, the
        // test still fails.
        var watchlistVisible = false
        var swipeRounds = 0
        while (!watchlistVisible && swipeRounds < 6) {
            watchlistVisible = try {
                composeTestRule.onNodeWithText("Watchlist")
                    .fetchSemanticsNode() != null
            } catch (t: Throwable) {
                false
            }
            if (!watchlistVisible) {
                try {
                    composeTestRule.onRoot().performTouchInput {
                        swipeUp()
                    }
                } catch (t: Throwable) {
                    // No root yet / mid-transition; next round retries.
                }
                composeTestRule.waitForIdle()
                swipeRounds++
            }
        }
        org.junit.Assert.assertTrue(
            "Watchlist section never laid out after $swipeRounds " +
                "swipes; route=${currentRoute()}",
            watchlistVisible
        )
        try {
            composeTestRule.onNodeWithText("Watchlist").performScrollTo()
        } catch (t: Throwable) {
            throw AssertionError(
                "Library tree at scroll failure:\n" +
                    composeTestRule.onRoot().printToString(),
                t
            )
        }
        composeTestRule.waitForIdle()

        // Open the OTHER watchlist title through its real poster
        // card on My Library: details must show B fresh, never the
        // stale A from the previous visit.
        // Unmerged tree: PosterCard merges its descendants'
        // semantics, so the clickable-with-descendant-text node
        // only exists unmerged (CI run 42211917469 proved the
        // merged finder finds nothing on the emulator).
        try {
            composeTestRule.waitUntil(timeoutMillis = 10_000) {
                composeTestRule.onAllNodes(
                    hasClickAction() and hasAnyDescendant(hasText(TITLE_B.name)),
                    useUnmergedTree = true
                ).fetchSemanticsNodes().size == 1
            }
        } catch (t: Throwable) {
            throw AssertionError(
                "Beta card never composed; route=${currentRoute()} " +
                    "tree:\n" +
                    composeTestRule.onRoot(useUnmergedTree = true)
                        .printToString(),
                t
            )
        }
        try {
            composeTestRule.onNode(
                hasClickAction() and hasAnyDescendant(hasText(TITLE_B.name)),
                useUnmergedTree = true
            ).performScrollTo()
        } catch (t: Throwable) {
            throw AssertionError(
                "Library tree at Beta-scroll failure:\n" +
                    composeTestRule.onRoot().printToString(),
                t
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNode(
            hasClickAction() and hasAnyDescendant(hasText(TITLE_B.name)),
            useUnmergedTree = true
        ).performClick()
        composeTestRule.waitForIdle()

        assertEquals(WatchlyRoutes.DETAILS, currentRoute())
        composeTestRule.onNodeWithText(TITLE_B.name).assertIsDisplayed()
        composeTestRule.onNodeWithText(TITLE_A.name).assertDoesNotExist()
    }

    /** The exact trigger a poster tap runs in production. */
    private fun openDetails(title: Title) {
        composeTestRule.runOnUiThread {
            TitleDetailsStore.select(title)
            navController.navigate(WatchlyRoutes.DETAILS) {
                launchSingleTop = true
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun currentRoute(): String? =
        composeTestRule.runOnIdle { navController.currentDestination?.route }

    private companion object {
        val TITLE_A = Title(
            tmdbId = 501,
            name = "Fixture Alpha",
            mediaType = MediaType.MOVIE,
            year = "2001"
        )
        val TITLE_B = Title(
            tmdbId = 502,
            name = "Fixture Beta",
            mediaType = MediaType.MOVIE,
            year = "2002"
        )
    }
}
