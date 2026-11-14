package com.watchly.app.ui.details

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.watchly.app.data.library.WatchlistRepository
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Playability
import com.watchly.app.data.model.PlayableFile
import com.watchly.app.data.model.Title
import com.watchly.app.data.model.playability
import com.watchly.app.data.remote.ArchiveApi
import io.mockk.coEvery
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bug-named regression suite (Phase D, user report 2026-11-06):
 * "Play button doesn't work on some Playable titles" — a Chaplin
 * film badged ▶ Playable on its card showed "Info only — no video
 * file" on the details page because a FAILED Archive files fetch
 * (slow network) was treated as "no files".
 *
 * The truth rule now: card and details share Title.playability
 * until the files check COMPLETES; a failure is a distinct
 * couldn't-check state with a working retry, never "Info only".
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DetailsPlayableFetchTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var library: WatchlistRepository

    private val chaplin = Title(
        archiveId = "his_new_job",
        name = "His New Job",
        mediaType = MediaType.MOVIE,
        year = "1915"
    )

    private val oneFile = PlayableFile(
        url = "https://archive.org/download/his_new_job/his_new_job.mp4",
        fileName = "his_new_job.mp4",
        format = "MPEG4",
        sizeBytes = 50_000_000L
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences(
            WatchlistRepository.PREFS_NAME, Context.MODE_PRIVATE
        ).edit().clear().commit()
        library = WatchlistRepository(context)
        mockkObject(ArchiveApi)
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    private fun viewModel() = TitleDetailsViewModel(
        title = chaplin,
        library = library
    )

    @Test
    fun `a failed files fetch is not read as no files`() = runTest(dispatcher) {
        coEvery { ArchiveApi.fetchPlayableFiles(any()) } throws
            java.io.IOException("timeout")

        val viewModel = viewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        // The card's truth and the page's truth agree: still PLAYABLE.
        assertEquals(Playability.PLAYABLE, chaplin.playability())
        assertEquals(Playability.PLAYABLE, state.playability)
        // …and the failure is its own visible state, with retry armed.
        assertTrue(state.filesCheckFailed)
        assertFalse(state.filesChecked)
        assertTrue(state.playableFiles.isEmpty())
    }

    @Test
    fun `retry after a failed check finds the files and play lights up`() =
        runTest(dispatcher) {
            coEvery { ArchiveApi.fetchPlayableFiles(any()) } throws
                java.io.IOException("timeout")
            val viewModel = viewModel()
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.filesCheckFailed)

            coEvery { ArchiveApi.fetchPlayableFiles(any()) } returns listOf(oneFile)
            viewModel.retryFilesCheck()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.filesCheckFailed)
            assertTrue(state.filesChecked)
            assertTrue(state.hasPlayableSource)
            assertEquals(Playability.PLAYABLE, state.playability)
        }

    @Test
    fun `a completed check that finds no files is honestly info only`() =
        runTest(dispatcher) {
            // Some Archive items genuinely hold no playable video
            // (texts, audio, metadata only) — that IS "Info only".
            coEvery { ArchiveApi.fetchPlayableFiles(any()) } returns emptyList()

            val viewModel = viewModel()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state.filesChecked)
            assertFalse(state.filesCheckFailed)
            assertEquals(Playability.INFO_ONLY, state.playability)
        }

    @Test
    fun `a completed check with files stays playable end to end`() =
        runTest(dispatcher) {
            coEvery { ArchiveApi.fetchPlayableFiles(any()) } returns listOf(oneFile)

            val viewModel = viewModel()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state.filesChecked)
            assertEquals(listOf(oneFile), state.playableFiles)
            assertEquals(Playability.PLAYABLE, state.playability)
        }
}
