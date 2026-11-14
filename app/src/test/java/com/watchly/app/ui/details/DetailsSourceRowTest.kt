package com.watchly.app.ui.details

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.watchly.app.data.model.PlayableFile
import com.watchly.app.ui.theme.WatchlyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Cycle 14 — Test Lab run 13 labeling tip: every row of a title's
 * Sources list spoke only "Internet Archive" (14 examples),
 * though the rows differ by format and size. Each row now merges
 * into one spoken description carrying format + size when the
 * file has them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DetailsSourceRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `source row speaks format and size`() {
        composeRule.setContent {
            WatchlyTheme {
                SourceRow(
                    file = PlayableFile(
                        url = "https://archive.org/download/house/house.mp4",
                        fileName = "house.mp4",
                        format = "MPEG4",
                        sizeBytes = 507_000_000L
                    )
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(
            "Internet Archive, MPEG4, 507 MB"
        ).assertExists()
    }

    @Test
    fun `source row without format or size speaks the provider only`() {
        composeRule.setContent {
            WatchlyTheme {
                SourceRow(
                    file = PlayableFile(
                        url = "https://archive.org/download/x/x.mp4",
                        fileName = "x.mp4"
                    )
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Internet Archive")
            .assertExists()
    }
}
