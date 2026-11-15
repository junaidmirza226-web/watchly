package com.watchly.app.ui.person
import com.watchly.app.data.settings.TestKeys

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Person
import com.watchly.app.data.model.Title
import com.watchly.app.data.remote.TmdbApi
import com.watchly.app.data.settings.TmdbKeyRepository
import io.mockk.coEvery
import io.mockk.coVerify
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Cycle 11 (§3.3): the person page's load contract.
 *  - details + known-for both land in state on success;
 *  - a details failure is a real error state (nothing fabricated);
 *  - a known-for failure degrades silently — the person still
 *    shows, the section is simply absent (details-page rule);
 *  - no TMDB key → the page says so, and TMDB is never called.
 * All network seams are mocked; no live calls.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PersonViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var tmdbKeys: TmdbKeyRepository

    private val person = Person(
        tmdbId = 94724,
        name = "N. T. Rama Rao Jr.",
        biography = "An Indian actor who works in Telugu cinema.",
        knownForDepartment = "Acting",
        photoUrl = "https://image.tmdb.org/t/p/w342/ntr.jpg"
    )
    private val knownFor = listOf(
        Title(
            tmdbId = 579974,
            name = "RRR",
            mediaType = MediaType.MOVIE,
            year = "2022"
        )
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences(
            TmdbKeyRepository.PREFS_NAME, Context.MODE_PRIVATE
        ).edit().clear().commit()
        tmdbKeys = TmdbKeyRepository(context)
        TestKeys.clearEmbeddedDefaults()
        mockkObject(TmdbApi)
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    @Test
    fun `loads the person and their known-for titles`() = runTest(dispatcher) {
        tmdbKeys.setKey("user-key")
        coEvery { TmdbApi.fetchPerson("user-key", 94724) } returns person
        coEvery {
            TmdbApi.fetchPersonKnownFor("user-key", 94724, any())
        } returns knownFor

        val viewModel = PersonViewModel(94724, "N. T. Rama Rao Jr.", tmdbKeys)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(person, state.person)
        assertEquals("N. T. Rama Rao Jr.", state.name)
        assertEquals(knownFor, state.knownFor)
        assertNull(state.errorMessage)
        assertEquals(false, state.isLoading)
    }

    @Test
    fun `a failed person load is an honest error state`() = runTest(dispatcher) {
        tmdbKeys.setKey("user-key")
        coEvery {
            TmdbApi.fetchPerson("user-key", 94724)
        } throws IOException("offline")
        coEvery {
            TmdbApi.fetchPersonKnownFor("user-key", 94724, any())
        } returns knownFor

        val viewModel = PersonViewModel(94724, "N. T. Rama Rao Jr.", tmdbKeys)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        // Nothing fabricated: no person, no known-for section —
        // just the plain failure the page renders with Retry.
        assertNull(state.person)
        assertTrue(state.knownFor.isEmpty())
        assertTrue(state.errorMessage?.contains("Couldn't load") == true)
        assertEquals(false, state.isLoading)
    }

    @Test
    fun `a failed known-for load still shows the person`() = runTest(dispatcher) {
        tmdbKeys.setKey("user-key")
        coEvery { TmdbApi.fetchPerson("user-key", 94724) } returns person
        coEvery {
            TmdbApi.fetchPersonKnownFor("user-key", 94724, any())
        } throws IOException("offline")

        val viewModel = PersonViewModel(94724, "N. T. Rama Rao Jr.", tmdbKeys)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(person, state.person)
        assertTrue(state.knownFor.isEmpty())
        assertNull(state.errorMessage)
    }

    @Test
    fun `without a tmdb key the page explains and never calls tmdb`() =
        runTest(dispatcher) {
            // No key saved in setUp for this test.
            val viewModel = PersonViewModel(94724, "N. T. Rama Rao Jr.", tmdbKeys)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertNull(state.person)
            assertTrue(state.errorMessage?.contains("TMDB key") == true)
            coVerify(exactly = 0) { TmdbApi.fetchPerson(any(), any()) }
            coVerify(exactly = 0) {
                TmdbApi.fetchPersonKnownFor(any(), any(), any())
            }
        }
}
