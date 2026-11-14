package com.watchly.app.ui.nav

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.watchly.app.ui.details.TitleDetailsStore
import com.watchly.app.ui.theme.WatchlyTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cycle 7, Phase A — the Home-tab-from-Search investigation.
 *
 * In CI run 42306084287 a bottom-bar "Home" tap issued from the
 * Search results screen completed in Maestro but the tab did
 * not switch within 30 s (flagged in progress-report-13; it
 * rhymes with the user's cycle-1 "unreliable back navigation"
 * report). Code inspection found the nav path itself sound and
 * the physical tap eaten by the software keyboard window,
 * which overlays the bottom bar because the app consumed no
 * IME insets — fixed by lifting the app scaffold above the IME
 * (WatchlyApp's imePadding).
 *
 * These tests guard the half that lives in app code: they
 * compose the PRODUCTION NavHost and bottom bar (the pattern
 * DetailsNavigationTest established), drive the real Search
 * screen with text in its field — the state the failing tap
 * was issued from — and switch tabs through the real bottom
 * bar, repeatedly. If the navigate call, the popUpTo /
 * saveState / restoreState options, or the Search screen's
 * BackHandler ever regress so a tab tap from Search stops
 * switching, these fail.
 *
 * The physical-coordinate half (a tap landing on the IME
 * window instead of the app) cannot be exercised from
 * semantics — androidx.compose.ui.test dispatches into the
 * hierarchy directly. That half is covered by Maestro flow
 * 04-search-home-tab.yaml in CI, which injects real screen
 * coordinates exactly like the original failure.
 *
 * Network independence: assertions never wait on results —
 * only on the destination route after each tab tap. The query
 * text just puts the field into its typed, focused state.
 */
@RunWith(AndroidJUnit4::class)
class SearchHomeTabNavigationTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: NavHostController

    @Before
    fun setUp() {
        TitleDetailsStore.clear()
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
    fun homeTabTapFromSearchWithTypedQuerySwitchesToHome() {
        openSearchAndType("Night")

        tapTab(WatchlyDestination.HOME)

        assertEquals(
            "Home tab tap from Search must land on Home",
            WatchlyDestination.HOME.route,
            currentRoute()
        )
    }

    @Test
    fun tabTapsFromSearchSwitchRepeatedly() {
        // The Maestro observation was intermittent, so one
        // success proves little: switch away from Search three
        // times in a row, to two different tabs and back.
        openSearchAndType("Night")
        tapTab(WatchlyDestination.LIBRARY)
        assertEquals(WatchlyDestination.LIBRARY.route, currentRoute())

        openSearchAndType("Dead")
        tapTab(WatchlyDestination.HOME)
        assertEquals(WatchlyDestination.HOME.route, currentRoute())

        openSearchAndType("Ghost")
        tapTab(WatchlyDestination.MOVIES)
        assertEquals(WatchlyDestination.MOVIES.route, currentRoute())
    }

    /** Opens the real Search destination and types into its field. */
    private fun openSearchAndType(query: String) {
        composeTestRule.runOnUiThread {
            navController.navigate(WatchlyRoutes.SEARCH)
        }
        composeTestRule.waitForIdle()
        assertEquals(WatchlyRoutes.SEARCH, currentRoute())
        composeTestRule.onNode(hasSetTextAction()).performTextInput(query)
        composeTestRule.waitForIdle()
    }

    /** Taps a bottom-bar tab through its real label, as a user does. */
    private fun tapTab(destination: WatchlyDestination) {
        composeTestRule.onNodeWithText(destination.label).performClick()
        composeTestRule.waitForIdle()
    }

    private fun currentRoute(): String? =
        composeTestRule.runOnIdle { navController.currentDestination?.route }
}
