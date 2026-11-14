package com.watchly.app.ui.details

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.watchly.app.data.library.WatchlistRepository
import com.watchly.app.data.model.CastMember
import com.watchly.app.data.model.Episode
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Playability
import com.watchly.app.data.model.Title
import com.watchly.app.data.remote.ArchiveApi
import com.watchly.app.data.remote.TmdbApi
import com.watchly.app.data.remote.TvMazeApi
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
 * Robolectric test for the REAL [TitleDetailsViewModel]. TVmaze and
 * Archive calls are stubbed with MockK; the watchlist store is the
 * real SharedPreferences-backed repository.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TitleDetailsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var library: WatchlistRepository

    private val show = Title(
        tvmazeId = 169,
        imdbId = "tt0903747",
        name = "Breaking Bad",
        rating = 9.2,
        mediaType = MediaType.TV,
        year = "2008"
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences(
            WatchlistRepository.PREFS_NAME,
            android.content.Context.MODE_PRIVATE
        ).edit().clear().commit()
        library = WatchlistRepository(context)
        mockkObject(TvMazeApi)
        mockkObject(ArchiveApi)
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
    fun `loads episodes grouped by season plus cast`() = runTest(dispatcher) {
        coEvery { TvMazeApi.fetchShowDetails(169) } returns show.copy(
            runtimeMinutes = 60, status = "Ended"
        )
        coEvery { TvMazeApi.fetchShowEpisodes(169) } returns listOf(
            Episode(season = 1, number = 1, name = "Pilot"),
            Episode(season = 1, number = 2, name = "Cat's in the Bag..."),
            Episode(season = 2, number = 1, name = "Seven Thirty-Seven")
        )
        coEvery { TvMazeApi.fetchShowCast(169) } returns listOf(
            CastMember("Bryan Cranston", "Walter White")
        )

        val viewModel = TitleDetailsViewModel(show, library)
        advanceUntilIdle(); idle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(60, state.title.runtimeMinutes)
        assertEquals("Ended", state.title.status)
        assertEquals(listOf(1, 2), state.seasons)
        assertEquals(2, state.selectedEpisodes.size)
        assertEquals("Pilot", state.selectedEpisodes[0].name)
        assertEquals(listOf("Bryan Cranston"), state.cast.map { it.personName })
        assertEquals("TVmaze", state.ratingSourceLabel)
    }

    @Test
    fun `watchlist toggle persists and episode check toggles`() = runTest(dispatcher) {
        coEvery { TvMazeApi.fetchShowDetails(any()) } returns show
        coEvery { TvMazeApi.fetchShowEpisodes(any()) } returns listOf(
            Episode(season = 1, number = 1, name = "Pilot")
        )
        coEvery { TvMazeApi.fetchShowCast(any()) } returns emptyList()

        val viewModel = TitleDetailsViewModel(show, library)
        advanceUntilIdle(); idle()

        assertFalse(viewModel.uiState.value.isInWatchlist)
        viewModel.toggleWatchlist()
        assertTrue(viewModel.uiState.value.isInWatchlist)
        assertTrue(library.isInWatchlist(show))

        val episode = Episode(season = 1, number = 1, name = "Pilot")
        viewModel.toggleEpisodeWatched(episode)
        assertTrue(viewModel.uiState.value.watchedEpisodes.contains("S1E1"))
        assertTrue(library.isEpisodeWatched(show, 1, 1))
    }

    @Test
    fun `archive film loads playable files and play with no source explains plainly`() =
        runTest(dispatcher) {
            val film = Title(
                archiveId = "nightofthelivingdead_201910",
                name = "Night of the Living Dead",
                mediaType = MediaType.MOVIE,
                year = "1968"
            )
            coEvery { ArchiveApi.fetchPlayableFiles(any()) } returns listOf(
                com.watchly.app.data.model.PlayableFile(
                    url = "https://archive.org/download/x/film.mp4",
                    fileName = "film.mp4",
                    format = "MPEG4",
                    sizeBytes = 1_000_000L
                )
            )

            val viewModel = TitleDetailsViewModel(film, library)
            advanceUntilIdle(); idle()

            val state = viewModel.uiState.value
            assertTrue(state.hasPlayableSource)
            assertEquals(Playability.PLAYABLE, state.playability)
            assertEquals("1 MB", state.playableFiles[0].sizeLabel)

            // Play with a source now routes to the player (wired by the
            // screen); this callback is only reached with no source.
            val catalogueOnly = Title(
                tvmazeId = 169,
                name = "Breaking Bad",
                mediaType = MediaType.TV,
                year = "2008"
            )
            coEvery { TvMazeApi.fetchShowDetails(any()) } returns catalogueOnly
            coEvery { TvMazeApi.fetchShowEpisodes(any()) } returns emptyList()
            coEvery { TvMazeApi.fetchShowCast(any()) } returns emptyList()
            val noSourceViewModel = TitleDetailsViewModel(catalogueOnly, library)
            advanceUntilIdle(); idle()

            assertFalse(noSourceViewModel.uiState.value.hasPlayableSource)
            assertEquals(
                Playability.INFO_ONLY,
                noSourceViewModel.uiState.value.playability
            )
            noSourceViewModel.onPlayRequested()
            // Phase D: the note is honest catalogue wording now —
            // it must NOT promise a Jellyfin/Plex connection (that
            // feature is not built).
            assertTrue(
                noSourceViewModel.uiState.value.infoMessage!!
                    .contains("catalogue-only")
            )
            assertFalse(
                noSourceViewModel.uiState.value.infoMessage!!
                    .contains("Jellyfin")
            )
        }

    @Test
    fun `archive film with no files is honestly info only`() = runTest(dispatcher) {
        val film = Title(
            archiveId = "audioonly_2020",
            name = "Audio Only Item",
            mediaType = MediaType.MOVIE,
            year = "2020"
        )
        coEvery { ArchiveApi.fetchPlayableFiles(any()) } returns emptyList()

        val viewModel = TitleDetailsViewModel(film, library)
        advanceUntilIdle(); idle()

        val state = viewModel.uiState.value
        assertFalse(state.hasPlayableSource)
        // The files fetch ran and found nothing, so the badge must not
        // claim the title plays — even though it came from the Archive.
        assertEquals(Playability.INFO_ONLY, state.playability)
    }

    // Cycle 14 (first-run audit): the details page's where-to-watch
    // guidance keys off hasTmdbKey — it must distinguish "no key
    // saved" (guidance shows) from "key saved" (it never does).
    private val keylessShow = Title(
        tvmazeId = 169,
        name = "Breaking Bad",
        mediaType = MediaType.TV,
        year = "2008"
    )

    private fun stubKeylessShow() {
        coEvery { TvMazeApi.fetchShowDetails(169) } returns keylessShow
        coEvery { TvMazeApi.fetchShowEpisodes(169) } returns emptyList()
        coEvery { TvMazeApi.fetchShowCast(169) } returns emptyList()
    }

    private fun tmdbKeyRepo(): TmdbKeyRepository {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences(
            TmdbKeyRepository.PREFS_NAME,
            android.content.Context.MODE_PRIVATE
        ).edit().clear().commit()
        return TmdbKeyRepository(context)
    }

    @Test
    fun `no tmdb key means hasTmdbKey stays false after load`() = runTest(dispatcher) {
        stubKeylessShow()
        val viewModel = TitleDetailsViewModel(
            keylessShow, library, tmdbKeys = tmdbKeyRepo()
        )
        assertFalse(viewModel.uiState.value.hasTmdbKey)
        advanceUntilIdle(); idle()
        assertFalse(viewModel.uiState.value.hasTmdbKey)
        // TMDB was never asked, so there is no providers answer —
        // the screen renders the guidance line in its place.
        assertEquals(null, viewModel.uiState.value.watchProviders)
    }

    @Test
    fun `saved tmdb key means hasTmdbKey is true`() = runTest(dispatcher) {
        stubKeylessShow()
        mockkObject(TmdbApi)
        // The show has no IMDb id, so load() falls back to a
        // name+year search; an empty answer leaves the title
        // unresolved (no providers fetch follows).
        coEvery { TmdbApi.searchMulti(any(), any()) } returns emptyList()
        val keyRepo = tmdbKeyRepo()
        keyRepo.setKey("user-key")
        val viewModel = TitleDetailsViewModel(
            keylessShow, library, tmdbKeys = keyRepo
        )
        assertTrue(viewModel.uiState.value.hasTmdbKey)
        advanceUntilIdle(); idle()
        assertTrue(viewModel.uiState.value.hasTmdbKey)
    }
}
