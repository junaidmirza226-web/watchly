package com.watchly.app.ui.screenshots

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.watchly.app.data.library.ListsRepository
import com.watchly.app.data.model.CastMember
import com.watchly.app.data.model.Episode
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Title
import com.watchly.app.data.remote.AniListApi
import com.watchly.app.data.remote.ArchiveApi
import com.watchly.app.data.remote.TvMazeApi
import com.watchly.app.data.remote.YouTubeApi
import com.watchly.app.ui.details.TitleDetailsScreen
import com.watchly.app.ui.home.HomeScreen
import com.watchly.app.ui.library.LibraryScreen
import com.watchly.app.ui.lists.ListsScreen
import com.watchly.app.ui.search.SearchScreen
import com.watchly.app.ui.theme.WatchlyTheme
import io.mockk.coEvery
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Roborazzi screenshot goldens (testing-rd-plan Upgrade 3): full
 * screens rendered by Robolectric's NATIVE graphics inside ordinary
 * unit tests, compared against the committed PNGs in
 * `app/src/test/screenshots/`.
 *
 * Why these four shots:
 *  - details_top / details_scrolled — the details page at rest and
 *    scrolled. The scrolled golden is the Bug 1 regression shot
 *    (2026-11-06: the back button lived inside the scrolling header
 *    and scrolled away): the gold back arrow must be visible in the
 *    top-left of BOTH images, because the button is now a fixed
 *    overlay sibling of the scrolling list.
 *  - home / search / library — the three other tab roots.
 *
 * Determinism rules (a golden that flakes is worse than none):
 *  - All network APIs are MockK objects returning fixed fixtures;
 *    no real network is touched.
 *  - Every fixture has posterUrl == null, so Coil never loads an
 *    image: cards render the gold monogram placeholder instead.
 *    (This also disables the home hero rotation, which needs a
 *    poster; the hero's empty state is what gets captured.)
 *  - ViewModel loads run on a StandardTestDispatcher that the test
 *    advances by hand before capturing.
 *
 * Modes (system properties, wired by the Gradle test task and by
 * tools/run-robolectric-tests.sh): verify (default) compares against
 * the goldens and fails on any difference; record rewrites them.
 * NEVER re-record to make a failure pass without looking at the
 * diff — the golden is the contract.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [34],
    qualifiers = RobolectricDeviceQualifiers.Pixel5,
    application = Application::class
)
class WatchlyScreenshotTest {

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        com.watchly.app.data.settings.TestKeys.clearEmbeddedDefaults()
        mockkObject(TvMazeApi)
        mockkObject(AniListApi)
        mockkObject(ArchiveApi)
        mockkObject(YouTubeApi)
        stubCatalogueApis()
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    // -----------------------------------------------------------------
    // Title Details — top and scrolled (Bug 1 regression shot)
    // -----------------------------------------------------------------

    @Test
    fun details_top() {
        stubDetailsApis()
        val activity = launchContent {
            WatchlyTheme { TitleDetailsScreen(title = SHOW) }
        }
        settle()
        capture(activity, "details_top")
    }

    @Test
    fun details_scrolled_backButtonStaysVisible() {
        stubDetailsApis()
        val listState = LazyListState()
        val activity = launchContent {
            WatchlyTheme {
                TitleDetailsScreen(title = SHOW, listState = listState)
            }
        }
        settle()
        // Jump deep into the list (first episode row to the top):
        // header, title and buttons are far off-screen, episodes and
        // Sources fill the page — and the fixed back button must
        // still be sitting in the top-left corner of the golden.
        runBlocking { listState.scrollToItem(SCROLLED_TO_ITEM) }
        settle()
        capture(activity, "details_scrolled")
    }

    // -----------------------------------------------------------------
    // Tab roots
    // -----------------------------------------------------------------

    @Test
    fun home() {
        val activity = launchContent {
            WatchlyTheme { HomeScreen() }
        }
        settle()
        capture(activity, "home")
    }

    @Test
    fun search() {
        val activity = launchContent {
            WatchlyTheme { SearchScreen() }
        }
        settle()
        capture(activity, "search")
    }

    @Test
    fun library() {
        val activity = launchContent {
            WatchlyTheme { LibraryScreen() }
        }
        settle()
        capture(activity, "library")
    }

    /**
     * Lists screen (spec §9.2): the store is seeded with fixed
     * fixture lists first — a full one (4 titles → full collage),
     * a two-title one, and an empty one. List ids are random UUIDs
     * but never rendered; names/counts/monograms are fixed.
     */
    @Test
    fun lists() {
        seedLists()
        val activity = launchContent {
            WatchlyTheme { ListsScreen() }
        }
        settle()
        capture(activity, "lists")
    }

    // -----------------------------------------------------------------
    // Harness
    // -----------------------------------------------------------------

    /** Starts a plain ComponentActivity hosting [content]. */
    private fun launchContent(content: @Composable () -> Unit): ComponentActivity {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java)
            .setup()
            .get()
        activity.setContent { content() }
        return activity
    }

    /**
     * Runs every queued ViewModel coroutine and every pending main
     * looper task (composition, layout) until both are quiet.
     */
    private fun settle() {
        repeat(SETTLE_ROUNDS) {
            scheduler.advanceUntilIdle()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun capture(activity: ComponentActivity, name: String) {
        activity.window.decorView.captureRoboImage(
            File("src/test/screenshots/$name.png")
        )
    }

    // -----------------------------------------------------------------
    // Fixtures — fixed, poster-free, identical on every machine
    // -----------------------------------------------------------------

    /** Rows used by Home (all rows) and Search (trending names). */
    private fun stubCatalogueApis() {
        coEvery { ArchiveApi.fetchMoviesPage(any(), any(), any(), any()) } returns MOVIES
        coEvery { TvMazeApi.fetchTrendingShows(any()) } returns SHOWS
        coEvery { AniListApi.fetchTrendingAnime(any()) } returns ANIME
        // Cycle-4 Home rows stay empty in the goldens: the feeds and
        // the wider Archive collections are stubbed to nothing, so
        // the captured layout is the deterministic catalogue layout.
        io.mockk.every { YouTubeApi.CHANNELS } returns emptyList()
        coEvery { YouTubeApi.fetchChannelVideos(any(), any()) } returns emptyList()
        coEvery {
            ArchiveApi.fetchCollectionPage(any(), any(), any(), any())
        } returns emptyList()
    }

    private fun stubDetailsApis() {
        coEvery { TvMazeApi.fetchShowDetails(SHOW_ID) } returns SHOW
        coEvery { TvMazeApi.fetchShowEpisodes(SHOW_ID) } returns EPISODES
        coEvery { TvMazeApi.fetchShowCast(SHOW_ID) } returns CAST
    }

    /** Seeds the lists store the Lists screen reads (poster-free). */
    private fun seedLists() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences(
            ListsRepository.PREFS_NAME,
            Context.MODE_PRIVATE
        ).edit().clear().commit()
        val repo = ListsRepository(context)
        val weekend = repo.createList("Weekend")
        MOVIES.forEach { repo.addTitle(weekend.id, it) }
        SHOWS.forEach { repo.addTitle(weekend.id, it) }
        val animeList = repo.createList("Best Anime")
        ANIME.forEach { repo.addTitle(animeList.id, it) }
        repo.createList("Watch Later")
    }

    private companion object {
        const val SETTLE_ROUNDS = 3

        /**
         * Details LazyColumn item index of the first episode row:
         * 0 header, 1 "Cast" heading, 2 cast row, 3 spacer,
         * 4 "Episodes" heading, 5 season chips, 6 = first episode.
         */
        const val SCROLLED_TO_ITEM = 6

        const val SHOW_ID = 42

        val SHOW = Title(
            tvmazeId = SHOW_ID,
            name = "The Golden Hour",
            overview = "A clockmaker in a flooded city discovers she can " +
                "repair broken moments — but every minute she fixes is " +
                "taken from somewhere else.",
            rating = 8.7,
            mediaType = MediaType.TV,
            year = "2024",
            genres = listOf("Drama", "Mystery"),
            runtimeMinutes = 52,
            status = "Running"
        )

        val EPISODES = listOf(
            Episode(
                id = 1, season = 1, number = 1, name = "First Tick",
                airDate = "2024-01-05", runtimeMinutes = 52,
                overview = "Every clock in the city stops at midnight."
            ),
            Episode(
                id = 2, season = 1, number = 2, name = "Second Hand",
                airDate = "2024-01-12", runtimeMinutes = 52,
                overview = "Mara finds a gear that should not exist."
            ),
            Episode(
                id = 3, season = 1, number = 3, name = "The Longest Minute",
                airDate = "2024-01-19", runtimeMinutes = 52,
                overview = "A borrowed minute comes due."
            ),
            Episode(
                id = 4, season = 2, number = 1, name = "Rewind",
                airDate = "2025-02-01", runtimeMinutes = 54,
                overview = "The city wakes up yesterday."
            )
        )

        val CAST = listOf(
            CastMember(personName = "Ana Serra", characterName = "Mara"),
            CastMember(personName = "Tomás Rey", characterName = "Ivo"),
            CastMember(personName = "Lena Vogt", characterName = "The Archivist")
        )

        val MOVIES = listOf(
            Title(
                archiveId = "night_of_the_living_dead",
                name = "Night of the Living Dead",
                mediaType = MediaType.MOVIE,
                year = "1968"
            ),
            Title(
                archiveId = "plan_9_from_outer_space",
                name = "Plan 9 from Outer Space",
                mediaType = MediaType.MOVIE,
                year = "1959"
            )
        )

        val SHOWS = listOf(
            Title(
                tvmazeId = 101,
                name = "Harbour Lights",
                rating = 8.9,
                mediaType = MediaType.TV,
                year = "2023"
            ),
            Title(
                tvmazeId = 102,
                name = "The Long Platform",
                rating = 8.4,
                mediaType = MediaType.TV,
                year = "2024"
            )
        )

        val ANIME = listOf(
            Title(
                anilistId = 201,
                name = "Paper Crane Club",
                rating = 9.1,
                mediaType = MediaType.ANIME,
                year = "2025"
            ),
            Title(
                anilistId = 202,
                name = "Mecha Botanist",
                rating = 8.8,
                mediaType = MediaType.ANIME,
                year = "2024"
            )
        )
    }
}
