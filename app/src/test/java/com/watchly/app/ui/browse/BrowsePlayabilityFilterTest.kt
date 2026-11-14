package com.watchly.app.ui.browse

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Playability
import com.watchly.app.data.model.PlayabilityFilter
import com.watchly.app.data.model.Title
import com.watchly.app.data.model.playability
import com.watchly.app.data.remote.AniListApi
import com.watchly.app.data.remote.ArchiveApi
import com.watchly.app.data.remote.TmdbApi
import com.watchly.app.data.remote.TvMazeApi
import com.watchly.app.data.remote.YouTubeApi
import com.watchly.app.data.settings.TmdbKeyRepository
import com.watchly.app.data.settings.UiPreferencesRepository
import io.mockk.coEvery
import io.mockk.every
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase D (user point 1): the playability filter on a Browse tab.
 * The Shows tab is all catalogue (info-only) titles, so
 * PLAYABLE_ONLY must empty the grid and INFO_ONLY must keep it —
 * and the choice persists across ViewModels via UiPreferences.
 *
 * Cycle 7 extension (user report 2026-11-07: "the Playable tabs
 * are actually empty"): every tab must HAVE a playable source.
 * The fixture tests below feed the REAL parsers real response
 * shapes (Archive advancedsearch JSON, YouTube Atom RSS) and
 * prove the parsed items survive the filter on their tabs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BrowsePlayabilityFilterTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var uiPrefs: UiPreferencesRepository
    private lateinit var keyRepo: TmdbKeyRepository

    private val showA = Title(
        tvmazeId = 1, name = "Show A",
        mediaType = MediaType.TV, year = "2020", rating = 8.0
    )
    private val showB = Title(
        tvmazeId = 2, name = "Show B",
        mediaType = MediaType.TV, year = "2021", rating = 7.0
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences(
            UiPreferencesRepository.PREFS_NAME,
            android.content.Context.MODE_PRIVATE
        ).edit().clear().commit()
        uiPrefs = UiPreferencesRepository(context)
        context.getSharedPreferences(
            TmdbKeyRepository.PREFS_NAME,
            android.content.Context.MODE_PRIVATE
        ).edit().clear().commit()
        keyRepo = TmdbKeyRepository(context)
        mockkObject(TvMazeApi)
        coEvery { TvMazeApi.fetchShowsPage(any()) } returns listOf(showA, showB)
        // Cycle 7: Archive is a source on every tab now. The
        // parser stays REAL (callOriginal) so fixture tests parse
        // genuine response shapes; the fetches default to empty
        // and are re-stubbed per test.
        mockkObject(ArchiveApi)
        every { ArchiveApi.parseSearchResponse(any(), any()) } answers {
            callOriginal()
        }
        coEvery {
            ArchiveApi.fetchCollectionPage(any(), any(), any())
        } returns emptyList()
        coEvery {
            ArchiveApi.fetchMoviesPage(any(), any(), any(), any())
        } returns emptyList()
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    @Test
    fun `playable only empties an all-catalogue tab, info only keeps it`() =
        runTest(dispatcher) {
            val viewModel = BrowseViewModel(
                kind = BrowseKind.SHOWS,
                uiPrefs = uiPrefs
            )
            advanceUntilIdle()
            assertEquals(2, viewModel.uiState.value.visibleTitles.size)

            viewModel.setPlayabilityFilter(PlayabilityFilter.PLAYABLE_ONLY)
            assertTrue(viewModel.uiState.value.visibleTitles.isEmpty())
            assertTrue(viewModel.uiState.value.isEmpty)

            viewModel.setPlayabilityFilter(PlayabilityFilter.INFO_ONLY)
            assertEquals(2, viewModel.uiState.value.visibleTitles.size)

            // The persisted choice greets the next ViewModel.
            assertEquals(
                PlayabilityFilter.INFO_ONLY,
                uiPrefs.playabilityFilter()
            )
            val fresh = BrowseViewModel(
                kind = BrowseKind.SHOWS,
                uiPrefs = uiPrefs
            )
            assertEquals(
                PlayabilityFilter.INFO_ONLY,
                fresh.uiState.value.playabilityFilter
            )
        }

    // --------------------------------------------------------------
    // Cycle 7: every tab has a playable source (real fixtures)
    // --------------------------------------------------------------

    /** Real advancedsearch shape for the classic_tv collection. */
    private val classicTvBody = """
        {"response": {"numFound": 11023, "docs": [
          {"identifier": "theloneranger_201705",
           "title": "The Lone Ranger TV Show", "year": 1949},
          {"identifier": "get-smart", "title": "Get Smart", "year": 1965}
        ]}}
    """.trimIndent()

    /** Real advancedsearch shape for feature_films. */
    private val featureFilmsBody = """
        {"response": {"numFound": 6017, "docs": [
          {"identifier": "night_of_the_living_dead",
           "title": "Night of the Living Dead", "year": 1968},
          {"identifier": "the-last-man-on-earth-1964",
           "title": "The Last Man on Earth", "year": 1964}
        ]}}
    """.trimIndent()

    @Test
    fun `archive classic tv fixtures appear on shows tab under playable only`() =
        runTest(dispatcher) {
            val archiveShows = ArchiveApi.parseSearchResponse(
                classicTvBody, MediaType.TV
            )
            // Fixture sanity: the real parser marks them playable.
            assertTrue(archiveShows.isNotEmpty())
            assertTrue(
                archiveShows.all { it.playability() == Playability.PLAYABLE }
            )
            coEvery {
                ArchiveApi.fetchCollectionPage(any(), any(), any())
            } returns archiveShows

            val viewModel = BrowseViewModel(
                kind = BrowseKind.SHOWS,
                uiPrefs = uiPrefs
            )
            advanceUntilIdle()
            // Both spines in one grid under All.
            assertEquals(4, viewModel.uiState.value.visibleTitles.size)

            viewModel.setPlayabilityFilter(PlayabilityFilter.PLAYABLE_ONLY)
            advanceUntilIdle()
            val state = viewModel.uiState.value
            // The grid shows exactly the playable Archive shows…
            assertEquals(
                setOf("The Lone Ranger TV Show", "Get Smart"),
                state.visibleTitles.map { it.name }.toSet()
            )
            assertTrue(
                state.visibleTitles.all {
                    it.playability() == Playability.PLAYABLE
                }
            )
            // …and the raw list still holds the TVmaze catalogue,
            // so the screen can tell "filter hid the rest" from
            // "catalogue is empty" (the cycle-7 state contract).
            assertTrue(state.titles.isNotEmpty())
            assertTrue(!state.isEmpty)
        }

    @Test
    fun `movies tab with a key flips to archive fixtures under playable only`() =
        runTest(dispatcher) {
            keyRepo.setKey("user-key")
            mockkObject(TmdbApi)
            every { TmdbApi.genreIdFor(any()) } returns null
            coEvery {
                TmdbApi.discoverMovies(any(), any(), any(), any(), any())
            } returns listOf(
                Title(tmdbId = 1, name = "TMDB Film", mediaType = MediaType.MOVIE)
            )
            val archiveFilms = ArchiveApi.parseSearchResponse(
                featureFilmsBody, MediaType.MOVIE
            )
            assertTrue(
                archiveFilms.all { it.playability() == Playability.PLAYABLE }
            )
            coEvery {
                ArchiveApi.fetchMoviesPage(any(), any(), any(), any())
            } returns archiveFilms

            val viewModel = BrowseViewModel(
                kind = BrowseKind.MOVIES,
                tmdbKeys = keyRepo,
                uiPrefs = uiPrefs
            )
            advanceUntilIdle()
            // Under All, TMDB is the catalogue.
            assertEquals(
                listOf("TMDB Film"),
                viewModel.uiState.value.visibleTitles.map { it.name }
            )

            // Under ▶ Playable the source flips to the Archive and
            // the real parsed films are the grid.
            viewModel.setPlayabilityFilter(PlayabilityFilter.PLAYABLE_ONLY)
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertEquals(
                setOf("Night of the Living Dead", "The Last Man on Earth"),
                state.visibleTitles.map { it.name }.toSet()
            )
            assertTrue(
                state.visibleTitles.all {
                    it.playability() == Playability.PLAYABLE
                }
            )
            // Chip rows now speak the Archive's language.
            assertEquals(
                ArchiveApi.MOVIE_GENRES.keys.toList(), state.genres
            )
            assertTrue(state.industries.isEmpty())

            // Flipping back restores the TMDB catalogue.
            viewModel.setPlayabilityFilter(PlayabilityFilter.ALL)
            advanceUntilIdle()
            assertEquals(
                listOf("TMDB Film"),
                viewModel.uiState.value.visibleTitles.map { it.name }
            )
        }

    @Test
    fun `movies tab born under a persisted playable filter starts on the archive presentation`() =
        runTest(dispatcher) {
            // The filter was set on another tab (it persists
            // app-wide) BEFORE this ViewModel exists: the tab's
            // very first state must already pair the Archive grid
            // with the Archive chip rows — not TMDB's.
            uiPrefs.setPlayabilityFilter(PlayabilityFilter.PLAYABLE_ONLY)
            keyRepo.setKey("user-key")
            mockkObject(TmdbApi)
            every { TmdbApi.genreIdFor(any()) } returns null
            coEvery {
                TmdbApi.discoverMovies(any(), any(), any(), any(), any())
            } returns listOf(
                Title(tmdbId = 1, name = "TMDB Film", mediaType = MediaType.MOVIE)
            )
            val archiveFilms = ArchiveApi.parseSearchResponse(
                featureFilmsBody, MediaType.MOVIE
            )
            coEvery {
                ArchiveApi.fetchMoviesPage(any(), any(), any(), any())
            } returns archiveFilms

            val viewModel = BrowseViewModel(
                kind = BrowseKind.MOVIES,
                tmdbKeys = keyRepo,
                uiPrefs = uiPrefs
            )
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertEquals(
                PlayabilityFilter.PLAYABLE_ONLY, state.playabilityFilter
            )
            assertEquals(
                setOf("Night of the Living Dead", "The Last Man on Earth"),
                state.visibleTitles.map { it.name }.toSet()
            )
            assertEquals(ArchiveApi.MOVIE_GENRES.keys.toList(), state.genres)
            assertTrue(state.industries.isEmpty())
        }

    @Test
    fun `youtube anime fixtures appear on anime tab under playable only`() =
        runTest(dispatcher) {
            // Parse the real Atom feed shape BEFORE mocking the
            // object, so the titles come from the real parser.
            val museAsia = YouTubeApi.CHANNELS.first {
                it.displayName == "Muse Asia"
            }
            val rss = """
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015"
                      xmlns:media="http://search.yahoo.com/mrss/"
                      xmlns="http://www.w3.org/2005/Atom">
                  <title>Muse Asia</title>
                  <entry>
                    <id>yt:video:abcDEF123_-</id>
                    <yt:videoId>abcDEF123_-</yt:videoId>
                    <title>Episode 12 [English Sub]</title>
                    <published>2026-10-30T12:00:00+00:00</published>
                  </entry>
                </feed>
            """.trimIndent()
            val channelVideos = YouTubeApi.parseFeed(rss, museAsia)
            assertEquals(1, channelVideos.size)
            assertEquals(
                Playability.PLAYABLE, channelVideos[0].playability()
            )

            mockkObject(YouTubeApi)
            every { YouTubeApi.CHANNELS } returns listOf(museAsia)
            coEvery { YouTubeApi.fetchChannelVideos(any()) } returns channelVideos
            mockkObject(AniListApi)
            coEvery {
                AniListApi.fetchAnimePage(any(), any(), any(), any())
            } returns listOf(
                Title(
                    anilistId = 21, name = "Catalogue Anime",
                    mediaType = MediaType.ANIME
                )
            )

            val viewModel = BrowseViewModel(
                kind = BrowseKind.ANIME,
                uiPrefs = uiPrefs
            )
            advanceUntilIdle()
            // Extras lead the list under All (genre = All, sort =
            // Popular defaults — the fixture-survival conditions).
            assertEquals(
                listOf("Episode 12 [English Sub]", "Catalogue Anime"),
                viewModel.uiState.value.visibleTitles.map { it.name }
            )

            viewModel.setPlayabilityFilter(PlayabilityFilter.PLAYABLE_ONLY)
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertEquals(
                listOf("Episode 12 [English Sub]"),
                state.visibleTitles.map { it.name }
            )
            assertTrue(
                state.visibleTitles.all {
                    it.playability() == Playability.PLAYABLE
                }
            )
        }
}
