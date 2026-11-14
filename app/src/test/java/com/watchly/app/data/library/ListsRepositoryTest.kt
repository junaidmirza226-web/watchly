package com.watchly.app.data.library

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Title
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric test for the SharedPreferences-backed custom lists
 * store (spec §9.2): the full create / rename / delete / add /
 * remove / reorder surface, persistence across instances, and
 * honest false returns for missing lists and duplicates.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ListsRepositoryTest {

    private lateinit var context: Application
    private lateinit var repo: ListsRepository

    private val film = Title(
        archiveId = "nightofthelivingdead_201910",
        name = "Night of the Living Dead",
        mediaType = MediaType.MOVIE,
        year = "1968"
    )
    private val show = Title(
        tvmazeId = 169,
        name = "Breaking Bad",
        mediaType = MediaType.TV,
        year = "2008"
    )
    private val anime = Title(
        anilistId = 20958,
        name = "Shingeki no Kyojin",
        mediaType = MediaType.ANIME,
        year = "2013"
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(
            ListsRepository.PREFS_NAME,
            Context.MODE_PRIVATE
        ).edit().clear().commit()
        repo = ListsRepository(context)
    }

    @Test
    fun `create rename and delete lists`() {
        assertTrue(repo.getLists().isEmpty())

        val weekend = repo.createList(" Weekend ")
        assertEquals("Weekend", weekend.name)
        assertTrue(weekend.titles.isEmpty())
        val animeList = repo.createList("Best Anime")

        assertEquals(
            listOf("Weekend", "Best Anime"),
            repo.getLists().map { it.name }
        )

        assertTrue(repo.renameList(weekend.id, "Weekend Picks"))
        assertEquals("Weekend Picks", repo.getList(weekend.id)?.name)
        assertFalse(repo.renameList(weekend.id, "   "))
        assertFalse(repo.renameList("no-such-id", "Nope"))

        assertTrue(repo.deleteList(weekend.id))
        assertEquals(listOf("Best Anime"), repo.getLists().map { it.name })
        assertFalse(repo.deleteList(weekend.id))
        assertEquals(null, repo.getList(weekend.id))
        assertEquals("Best Anime", repo.getList(animeList.id)?.name)
    }

    @Test
    fun `blank list name falls back to a placeholder`() {
        val list = repo.createList("   ")
        assertEquals("Untitled list", list.name)
    }

    @Test
    fun `add and remove titles, duplicates refused`() {
        val list = repo.createList("Weekend")

        assertTrue(repo.addTitle(list.id, film))
        assertTrue(repo.addTitle(list.id, show))
        assertTrue(repo.containsTitle(list.id, film))
        assertFalse(repo.containsTitle(list.id, anime))

        // Same title again: refused, no duplicate.
        assertFalse(repo.addTitle(list.id, film))
        assertEquals(2, repo.getList(list.id)?.size)

        // Missing list: everything is an honest false.
        assertFalse(repo.addTitle("no-such-id", anime))
        assertFalse(repo.removeTitle("no-such-id", film))
        assertFalse(repo.containsTitle("no-such-id", film))

        assertTrue(repo.removeTitle(list.id, film))
        assertFalse(repo.removeTitle(list.id, film))
        assertEquals(listOf("Breaking Bad"), repo.getList(list.id)?.titles?.map { it.name })
    }

    @Test
    fun `order is preserved and moveTitle reorders`() {
        val list = repo.createList("Ranked")
        repo.addTitle(list.id, film)
        repo.addTitle(list.id, show)
        repo.addTitle(list.id, anime)

        assertEquals(
            listOf("Night of the Living Dead", "Breaking Bad", "Shingeki no Kyojin"),
            repo.getList(list.id)?.titles?.map { it.name }
        )

        // Drag the last item to the front.
        assertTrue(repo.moveTitle(list.id, fromIndex = 2, toIndex = 0))
        assertEquals(
            listOf("Shingeki no Kyojin", "Night of the Living Dead", "Breaking Bad"),
            repo.getList(list.id)?.titles?.map { it.name }
        )

        // Out-of-range moves are refused and change nothing.
        assertFalse(repo.moveTitle(list.id, fromIndex = 0, toIndex = 5))
        assertFalse(repo.moveTitle(list.id, fromIndex = -1, toIndex = 0))
        assertFalse(repo.moveTitle("no-such-id", fromIndex = 0, toIndex = 1))
        assertEquals(
            listOf("Shingeki no Kyojin", "Night of the Living Dead", "Breaking Bad"),
            repo.getList(list.id)?.titles?.map { it.name }
        )
    }

    @Test
    fun `titles restore their provider ids from the stable key`() {
        val list = repo.createList("Mixed")
        repo.addTitle(list.id, film)
        repo.addTitle(list.id, show)
        repo.addTitle(list.id, anime)

        val restored = repo.getList(list.id)?.titles.orEmpty()
        assertEquals("nightofthelivingdead_201910", restored[0].archiveId)
        assertEquals(169, restored[1].tvmazeId)
        assertEquals(20958, restored[2].anilistId)
        assertEquals(listOf("1968", "2008", "2013"), restored.map { it.year })
    }

    @Test
    fun `lists survive a fresh repository instance (persistence)`() {
        val list = repo.createList("Keep Me")
        repo.addTitle(list.id, film)
        repo.addTitle(list.id, show)

        val fresh = ListsRepository(context)
        val restored = fresh.getList(list.id)
        assertEquals("Keep Me", restored?.name)
        assertEquals(
            listOf("Night of the Living Dead", "Breaking Bad"),
            restored?.titles?.map { it.name }
        )
    }

    @Test
    fun `a list title without year or poster reads back with real nulls, never the string null`() {
        // Same Phase D "null" bug class as the watchlist store.
        val undated = Title(
            tvmazeId = 42,
            name = "Undated Mystery Show",
            mediaType = MediaType.TV,
            year = null,
            posterUrl = null
        )
        val list = repo.createList("Mysteries")
        repo.addTitle(list.id, undated)

        val readBack = repo.getList(list.id)?.titles?.single()
        assertEquals(null, readBack?.year)
        assertEquals(null, readBack?.posterUrl)
    }

    @Test
    fun `deleting one list leaves the others untouched`() {
        val a = repo.createList("A")
        val b = repo.createList("B")
        repo.addTitle(a.id, film)
        repo.addTitle(b.id, show)

        repo.deleteList(a.id)

        assertEquals(listOf("B"), repo.getLists().map { it.name })
        assertEquals(listOf("Breaking Bad"), repo.getList(b.id)?.titles?.map { it.name })
    }
}
