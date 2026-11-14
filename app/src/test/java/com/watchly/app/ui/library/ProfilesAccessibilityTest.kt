package com.watchly.app.ui.library

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.watchly.app.data.library.WatchlyProfile
import com.watchly.app.ui.theme.WatchlyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Cycle 13 — accessibility regression tests for the cycle-12
 * Profiles UI, written against MEASURED bounds (the probe run that
 * preceded them, on the unfixed code):
 *  - the avatar-colour chips laid out 40x40dp with a raw clickable
 *    and no Material minimum-size behind them — the one provable
 *    sub-48dp target Test Lab run 12's touch findings could be;
 *  - every dialog TextButton (Rename / Delete / Add profile /
 *    Close) laid out 40dp tall, against the app's own 48dp floor
 *    for bare TextButtons (cycle 4's "See all");
 *  - "Rename" and "Delete" repeated identically under every
 *    profile — the duplicate-label class cycles 10/12 fixed for
 *    downloads and lists.
 * These tests pin the fixes: 48dp targets, per-profile spoken
 * labels, and a selected state on the colour chips.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfilesAccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val profiles = listOf(
        WatchlyProfile(id = "default", name = "Profile 1", colorIndex = 0),
        WatchlyProfile(id = "kids123", name = "Kids", colorIndex = 1)
    )

    private fun setCard() {
        composeRule.setContent {
            WatchlyTheme {
                ProfilesCard(
                    profiles = profiles,
                    activeProfile = profiles[0],
                    profileMessage = null,
                    onSwitchProfile = {},
                    onCreateProfile = { _, _ -> },
                    onRenameProfile = { _, _ -> },
                    onDeleteProfile = {}
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun openSwitcher() {
        setCard()
        composeRule.onNodeWithText("Switch profile").performClick()
        composeRule.waitForIdle()
    }

    /** Laid-out height of the (unique) node carrying [description]. */
    private fun heightDpOf(description: String): Float {
        val node = composeRule.onNodeWithContentDescription(description)
            .fetchSemanticsNode()
        return node.boundsInRoot.height / composeRule.density.density
    }

    private fun heightDpOfText(text: String): Float {
        val node = composeRule.onNodeWithText(text).fetchSemanticsNode()
        return node.boundsInRoot.height / composeRule.density.density
    }

    @Test
    fun `switcher rename and delete name their profile and meet 48dp`() {
        openSwitcher()

        // Visible text is unchanged (the row still reads Rename /
        // Delete), but each button's SPOKEN label names its profile,
        // so no two buttons on this dialog share a label.
        composeRule.onAllNodesWithText("Rename").assertCountEquals(2)
        composeRule.onAllNodesWithText("Delete").assertCountEquals(2)
        for (name in listOf("Profile 1", "Kids")) {
            assertTrue(heightDpOf("Rename $name") >= 48f)
            assertTrue(heightDpOf("Delete $name") >= 48f)
        }
        // The dialog's own buttons meet the same floor.
        assertTrue(heightDpOfText("Add profile") >= 48f)
        assertTrue(heightDpOfText("Close") >= 48f)
    }

    @Test
    fun `colour chips have 48dp targets and a spoken selected state`() {
        openSwitcher()
        composeRule.onNodeWithText("Add profile").performClick()
        composeRule.waitForIdle()

        val gold = composeRule.onNodeWithContentDescription("Avatar colour Gold")
            .fetchSemanticsNode()
        val density = composeRule.density.density
        assertTrue(gold.boundsInRoot.height / density >= 48f)
        assertTrue(gold.boundsInRoot.width / density >= 48f)
        // The first colour starts selected; the state is spoken,
        // not only shown by the check mark.
        assertEquals(
            true,
            gold.config[SemanticsProperties.Selected]
        )
        val blue = composeRule
            .onNodeWithContentDescription("Avatar colour Blue")
            .fetchSemanticsNode()
        assertEquals(
            false,
            blue.config[SemanticsProperties.Selected]
        )
        // The name dialog's buttons meet the 48dp floor too.
        assertTrue(heightDpOfText("Add") >= 48f)
        assertTrue(heightDpOfText("Cancel") >= 48f)
    }
}
