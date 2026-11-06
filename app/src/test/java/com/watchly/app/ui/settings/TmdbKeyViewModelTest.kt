package com.watchly.app.ui.settings

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.watchly.app.data.remote.TmdbApi
import com.watchly.app.data.settings.TmdbKeyRepository
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The key screen tests the key against TMDB before saving: accepted
 * keys persist, rejected keys and network failures never do.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TmdbKeyViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repo: TmdbKeyRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences(TmdbKeyRepository.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        repo = TmdbKeyRepository(context)
        mockkObject(TmdbApi)
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `accepted key is saved and state shows connected`() = runTest(dispatcher) {
        coEvery { TmdbApi.validateKey("good-key") } returns true

        val viewModel = TmdbKeyViewModel(repo)
        assertFalse(viewModel.uiState.value.hasSavedKey)

        viewModel.onInputChange("good-key")
        viewModel.testAndSave()
        advanceUntilIdle(); idle()

        val state = viewModel.uiState.value
        assertTrue(state.hasSavedKey)
        assertEquals("good-key", repo.getKey())
        assertTrue(state.message!!.contains("Saved"))
    }

    @Test
    fun `rejected key is not saved and shows an error`() = runTest(dispatcher) {
        coEvery { TmdbApi.validateKey("bad-key") } returns false

        val viewModel = TmdbKeyViewModel(repo)
        viewModel.onInputChange("bad-key")
        viewModel.testAndSave()
        advanceUntilIdle(); idle()

        val state = viewModel.uiState.value
        assertFalse(state.hasSavedKey)
        assertFalse(repo.hasKey())
        assertTrue(state.errorMessage!!.contains("didn't accept"))
    }

    @Test
    fun `network failure does not save and says the key was not saved`() =
        runTest(dispatcher) {
            coEvery { TmdbApi.validateKey(any()) } throws
                IllegalStateException("offline")

            val viewModel = TmdbKeyViewModel(repo)
            viewModel.onInputChange("some-key")
            viewModel.testAndSave()
            advanceUntilIdle(); idle()

            val state = viewModel.uiState.value
            assertFalse(repo.hasKey())
            assertTrue(state.errorMessage!!.contains("not saved"))
        }

    @Test
    fun `remove key clears the store`() = runTest(dispatcher) {
        repo.setKey("stored")
        val viewModel = TmdbKeyViewModel(repo)
        assertTrue(viewModel.uiState.value.hasSavedKey)

        viewModel.removeKey()

        assertFalse(viewModel.uiState.value.hasSavedKey)
        assertFalse(repo.hasKey())
    }
}
