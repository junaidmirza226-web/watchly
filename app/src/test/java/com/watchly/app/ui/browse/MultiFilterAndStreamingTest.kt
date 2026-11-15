package com.watchly.app.ui.browse

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.watchly.app.data.browse.BrowseRatingFilter
import com.watchly.app.data.browse.BrowseSort
import com.watchly.app.data.browse.BrowseYearFilter
import com.watchly.app.data.browse.GenreMatchMode
import com.watchly.app.data.browse.INDUSTRIES
import com.watchly.app.data.browse.applyAdvancedBrowseFilter
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Title
import com.watchly.app.data.playback.PlayableSourceResolver
import com.watchly.app.data.remote.TmdbApi
import com.watchly.app.data.remote.providers.StreamAggregator
import com.watchly.app.data.settings.TestKeys
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MultiFilterAndStreamingTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        TestKeys.clearEmbeddedDefaults()
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    @Test
    fun `industries catalogue covers all regional and global cinema hubs`() {
        val labels = INDUSTRIES.map { it.label }
        assertTrue(labels.contains("Bollywood"))
        assertTrue(labels.contains("Hollywood"))
        assertTrue(labels.contains("South Indian"))
        assertTrue(labels.contains("Tollywood"))
        assertTrue(labels.contains("Kollywood"))
        assertTrue(labels.contains("Mollywood"))
        assertTrue(labels.contains("Sandalwood"))
        assertTrue(labels.contains("Punjabi Cinema"))
        assertTrue(labels.contains("Pakistani & Urdu"))
        assertTrue(labels.contains("Korean Cinema"))

        val south = INDUSTRIES.first { it.label == "South Indian" }
        assertTrue(south.languageCodes.contains("te"))
        assertTrue(south.languageCodes.contains("ta"))
        assertTrue(south.languageCodes.contains("ml"))
        assertTrue(south.languageCodes.contains("kn"))
        assertFalse(south.languageCodes.contains("en"))
    }

    @Test
    fun `advanced multi-filter composes industries, genres ALL or ANY, era, rating and sort`() {
        val hindiHit = Title(
            tmdbId = 1, name = "Jawan", mediaType = MediaType.MOVIE,
            year = "2023", rating = 7.8, originalLanguage = "hi",
            genres = listOf("Action", "Thriller")
        )
        val teluguHit = Title(
            tmdbId = 2, name = "RRR", mediaType = MediaType.MOVIE,
            year = "2022", rating = 8.1, originalLanguage = "te",
            genres = listOf("Action", "Drama")
        )
        val malayalamGem = Title(
            tmdbId = 3, name = "Manjummel Boys", mediaType = MediaType.MOVIE,
            year = "2024", rating = 8.4, originalLanguage = "ml",
            genres = listOf("Thriller", "Adventure")
        )
        val oldEnglish = Title(
            tmdbId = 4, name = "Heat", mediaType = MediaType.MOVIE,
            year = "1995", rating = 8.3, originalLanguage = "en",
            genres = listOf("Action", "Thriller")
        )
        val all = listOf(hindiHit, teluguHit, malayalamGem, oldEnglish)

        val bollywood = INDUSTRIES.first { it.label == "Bollywood" }
        val southIndian = INDUSTRIES.first { it.label == "South Indian" }

        // Multi-industry (Bollywood + South Indian) + 2020s + 7.5+ + ANY(Action, Thriller) -> 3 hits sorted Top Rated
        val anyMatch = applyAdvancedBrowseFilter(
            titles = all,
            selectedGenres = setOf("Action", "Thriller"),
            genreMatchMode = GenreMatchMode.ANY,
            selectedIndustries = setOf(bollywood, southIndian),
            yearFilter = BrowseYearFilter.DECADE_2020S,
            ratingFilter = BrowseRatingFilter.ACCLAIMED_75,
            sort = BrowseSort.TOP_RATED
        )
        assertEquals(listOf("Manjummel Boys", "RRR", "Jawan"), anyMatch.map { it.name })

        // Switch genreMatchMode to ALL(Action AND Thriller) -> keeps only Jawan among 2020s Indian films!
        val allMatch = applyAdvancedBrowseFilter(
            titles = all,
            selectedGenres = setOf("Action", "Thriller"),
            genreMatchMode = GenreMatchMode.ALL,
            selectedIndustries = setOf(bollywood, southIndian),
            yearFilter = BrowseYearFilter.DECADE_2020S,
            ratingFilter = BrowseRatingFilter.ACCLAIMED_75,
            sort = BrowseSort.TOP_RATED
        )
        assertEquals(listOf("Jawan"), allMatch.map { it.name })
    }

    @Test
    fun `browse viewmodel executes simultaneous multi-industry and multi-genre tmdb query`() =
        runTest(dispatcher) {
            val context = ApplicationProvider.getApplicationContext<Application>()
            context.getSharedPreferences(TmdbKeyRepository.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit()
            val keyRepo = TmdbKeyRepository(context)
            keyRepo.setKey("test-key")

            mockkObject(TmdbApi)
            coEvery {
                TmdbApi.discoverMovies(any(), any(), any(), any(), any())
            } returns emptyList()
            coEvery {
                TmdbApi.discoverMoviesAdvanced(any(), any(), any(), any(), any(), any(), any())
            } returns listOf(
                Title(
                    tmdbId = 10, name = "Kalki 2898 AD",
                    mediaType = MediaType.MOVIE, year = "2024", rating = 7.6,
                    originalLanguage = "te"
                )
            )

            val vm = BrowseViewModel(kind = BrowseKind.MOVIES, tmdbKeys = keyRepo)
            advanceUntilIdle()

            val bollywood = INDUSTRIES.first { it.label == "Bollywood" }
            val tollywood = INDUSTRIES.first { it.label == "Tollywood" }
            vm.toggleIndustry(bollywood)
            vm.toggleIndustry(tollywood)
            vm.toggleGenre("Action")
            vm.toggleGenre("Science Fiction")
            vm.selectYearFilter(BrowseYearFilter.DECADE_2020S)
            vm.selectRatingFilter(BrowseRatingFilter.ACCLAIMED_75)
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(setOf(bollywood, tollywood), state.selectedIndustries)
            assertEquals(setOf("Action", "Science Fiction"), state.selectedGenres)
            assertEquals(6, state.activeFilterCount)
            assertNotNull(state.activeFilterSummary)
            assertEquals(listOf("Kalki 2898 AD"), state.visibleTitles.map { it.name })

            coVerify {
                TmdbApi.discoverMoviesAdvanced(
                    apiKey = "test-key",
                    languageQuery = "hi|te",
                    genresQuery = "28|878",
                    yearFilter = BrowseYearFilter.DECADE_2020S,
                    minRating = 7.5,
                    sort = any(),
                    page = 1
                )
            }
        }

    @Test
    fun `instant embed servers exclude non-indexed file hosts and build valid movie and tv urls`() {
        val movie = Title(tmdbId = 155, imdbId = "tt0468569", name = "The Dark Knight", mediaType = MediaType.MOVIE)
        val movieServers = StreamAggregator.getInstantEmbedServers(movie)
        assertTrue(movieServers.isNotEmpty())
        assertTrue(movieServers.none { it.url.contains("streamwish") || it.url.contains("dood") || it.url.contains("filemoon") || it.url.contains("vidcloud9") })
        assertTrue(movieServers.any { it.url.contains("vidlink.pro/movie/155") })
        assertTrue(movieServers.any { it.url.contains("multiembed.mov/?video_id=155&tmdb=1") })

        val show = Title(tmdbId = 1399, name = "Game of Thrones", mediaType = MediaType.TV)
        val epServers = StreamAggregator.getInstantEmbedServers(show, season = 2, episode = 5)
        assertTrue(epServers.any { it.url.contains("vidlink.pro/tv/1399/2/5") })
        assertTrue(epServers.any { it.url.contains("multiembed.mov/?video_id=1399&tmdb=1&s=2&e=5") })
    }

    @Test
    fun `season episode parser handles all label formats`() {
        assertEquals(1 to 2, PlayableSourceResolver.parseSeasonEpisode("S1E2 - Pilot"))
        assertEquals(1 to 2, PlayableSourceResolver.parseSeasonEpisode("S1 E2"))
        assertEquals(3 to 12, PlayableSourceResolver.parseSeasonEpisode("s03:e12"))
    }

    @Test
    fun `tmdb tv details and season episode parsers extract seasons and episodes cleanly`() {
        val tvJson = """
            {
              "id": 93405,
              "name": "Squid Game",
              "original_language": "ko",
              "backdrop_path": "/squid_bg.jpg",
              "poster_path": "/squid_poster.jpg",
              "first_air_date": "2021-09-17",
              "vote_average": 7.9,
              "status": "Returning Series",
              "episode_run_time": [55],
              "genres": [{"id": 18, "name": "Drama"}],
              "seasons": [
                {"season_number": 0, "episode_count": 2},
                {"season_number": 1, "episode_count": 9},
                {"season_number": 2, "episode_count": 7}
              ]
            }
        """.trimIndent()
        val bundle = TmdbApi.parseTvDetailsBundle(tvJson)!!
        assertEquals("Squid Game", bundle.title.name)
        assertEquals("ko", bundle.title.originalLanguage)
        assertEquals("KOREAN", bundle.title.languageBadge)
        assertEquals("https://image.tmdb.org/t/p/w780/squid_bg.jpg", bundle.title.backdropUrl)
        assertEquals(listOf(1, 2), bundle.seasonNumbers)

        val seasonJson = """
            {
              "episodes": [
                {
                  "id": 1001,
                  "episode_number": 1,
                  "name": "Red Light, Green Light",
                  "air_date": "2021-09-17",
                  "runtime": 60,
                  "overview": "456 players enter the first game.",
                  "still_path": "/ep1.jpg"
                }
              ]
            }
        """.trimIndent()
        val episodes = TmdbApi.parseTvSeasonEpisodes(seasonJson, fallbackSeason = 1)
        assertEquals(1, episodes.size)
        assertEquals("Red Light, Green Light", episodes[0].name)
        assertEquals("https://image.tmdb.org/t/p/w500/ep1.jpg", episodes[0].imageUrl)
    }
}
