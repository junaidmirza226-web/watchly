package com.watchly.app.ui.details

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.intent.Intents
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Title
import com.watchly.app.ui.theme.WatchlyTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Bug 3 (2026-11-06): the details Share button sent only the
 * title's name — no link at all. The pure shareTextFor() fix is
 * unit-tested on the JVM (ShareTextTest); this instrumented test
 * asserts the outward EFFECT on a real runtime: tapping Share
 * fires a chooser whose ACTION_SEND intent carries text with the
 * title's real public link in it.
 *
 * Espresso Intents intercepts startActivity, so no chooser UI
 * actually opens and nothing leaves the test.
 */
@RunWith(AndroidJUnit4::class)
class ShareIntentTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        Intents.init()
    }

    @After
    fun tearDown() {
        Intents.release()
    }

    @Test
    fun shareIntentCarriesTextWithTheTitlesRealLink() {
        composeTestRule.setContent {
            WatchlyTheme { TitleDetailsScreen(title = FIXTURE) }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Share")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        val chooser = Intents.getIntents()
            .lastOrNull { it.action == Intent.ACTION_CHOOSER }
        assertNotNull("tapping Share must fire a chooser intent", chooser)

        @Suppress("DEPRECATION")
        val sendIntent = chooser!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull("chooser must wrap an ACTION_SEND intent", sendIntent)
        assertEquals(Intent.ACTION_SEND, sendIntent!!.action)
        assertEquals("text/plain", sendIntent.type)

        val text = sendIntent.getStringExtra(Intent.EXTRA_TEXT)
        assertNotNull("share text must be present", text)
        assertTrue(
            "share text must contain the title's real link, was: $text",
            text!!.contains(EXPECTED_LINK)
        )
        assertTrue(
            "share text must name the title, was: $text",
            text.contains(FIXTURE.name)
        )
    }

    private companion object {
        /** TMDB id 155 is The Dark Knight — shareLinkFor maps it to this URL. */
        const val EXPECTED_LINK = "https://www.themoviedb.org/movie/155"

        val FIXTURE = Title(
            tmdbId = 155,
            name = "The Dark Knight",
            overview = "Batman raises the stakes in his war on crime.",
            mediaType = MediaType.MOVIE,
            year = "2008",
            genres = listOf("Action", "Crime"),
            runtimeMinutes = 152
        )
    }
}
