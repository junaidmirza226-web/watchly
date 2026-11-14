package com.watchly.app.ui.home

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.watchly.app.ui.theme.WatchlyTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Cycle 14 — first-run audit: Home tracked `hasTmdbKey` in its
 * state but never rendered anything from it, so on a fresh install
 * (no key pasted yet) the industry rows were silently absent and
 * nothing pointed at the one-sitting key paste. The unlock card
 * is that pointer; HomeContent renders it only in the no-key
 * state. This pins the card's copy and its way out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeUnlockCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `unlock card names what the key turns on and its button fires`() {
        var clicks = 0
        composeRule.setContent {
            WatchlyTheme {
                TmdbUnlockCard(onClick = { clicks++ })
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Unlock every industry").assertExists()
        composeRule.onNodeWithText("Add TMDB Key").performClick()
        assertEquals(1, clicks)
    }
}
