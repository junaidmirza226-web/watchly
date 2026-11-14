package com.watchly.app.ui.lists

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.watchly.app.data.library.ListsRepository
import com.watchly.app.data.library.WatchlistRepository
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Title
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Lists + List Detail ViewModels (spec §9.2): creating, renaming
 * and deleting lists, and the detail screen's add / remove /
 * reorder / watchlist-picker behaviour — all over the real local
 * repositories under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ListsViewModelTest {

    private lateinit var listsRepo: ListsRepository
    private lateinit var watchlistRepo: WatchlistRepository

    private val film = Title(
        archiveId = "film_a",
        name = "Film A",
        mediaType = MediaType.MOVIE,
        year = "1970"
    )
    private val show = Title(
        archiveId = "film_b",
        name = "Film B",
        mediaType = MediaType.MOVIE,
        year = "1971"
    )
    private val other = Title(
        archiveId = "film_c",
        name = "Film C",
        mediaType = MediaType.MOVIE,
        year = "1972"
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences(
            ListsRepository.PREFS_NAME,
            Context.MODE_PRIVATE
        ).edit().clear().commit()
        listsRepo = ListsRepository(context)
        watchlistRepo = WatchlistRepository(context)
    }

    private fun listsViewModel() = ListsViewModel(listsRepo)

    private fun detailViewModel(listId: String) =
        ListDetailViewModel(listId, listsRepo, watchlistRepo)

    // -----------------------------------------------------------------
    // Lists screen ViewModel
    // -----------------------------------------------------------------

    @Test
    fun `create rename delete flow through the lists view model`() {
        val vm = listsViewModel()
        assertTrue(vm.uiState.value.lists.isEmpty())

        vm.createList("Weekend")
        vm.createList("Best Anime")
        assertEquals(
            listOf("Weekend", "Best Anime"),
            vm.uiState.value.lists.map { it.name }
        )

        // Blank names never reach the store.
        vm.createList("   ")
        assertEquals(2, vm.uiState.value.lists.size)

        val weekend = vm.uiState.value.lists[0]
        vm.renameList(weekend.id, "Weekend Picks")
        assertEquals("Weekend Picks", vm.uiState.value.lists[0].name)

        vm.deleteList(weekend.id)
        assertEquals(listOf("Best Anime"), vm.uiState.value.lists.map { it.name })
    }

    @Test
    fun `refresh picks up lists created elsewhere`() {
        val vm = listsViewModel()
        listsRepo.createList("From Details")
        assertTrue(vm.uiState.value.lists.isEmpty())
        vm.refresh()
        assertEquals(listOf("From Details"), vm.uiState.value.lists.map { it.name })
    }

    // -----------------------------------------------------------------
    // List Detail ViewModel
    // -----------------------------------------------------------------

    @Test
    fun `detail shows contents and removes titles`() {
        val list = listsRepo.createList("Weekend")
        listsRepo.addTitle(list.id, film)
        listsRepo.addTitle(list.id, show)

        val vm = detailViewModel(list.id)
        assertEquals(
            listOf("Film A", "Film B"),
            vm.uiState.value.list?.titles?.map { it.name }
        )

        vm.removeTitle(film)
        assertEquals(listOf("Film B"), vm.uiState.value.list?.titles?.map { it.name })
    }

    @Test
    fun `detail move persists the drag order`() {
        val list = listsRepo.createList("Ranked")
        listsRepo.addTitle(list.id, film)
        listsRepo.addTitle(list.id, show)
        listsRepo.addTitle(list.id, other)

        val vm = detailViewModel(list.id)
        vm.moveTitle(fromIndex = 2, toIndex = 0)

        assertEquals(
            listOf("Film C", "Film A", "Film B"),
            vm.uiState.value.list?.titles?.map { it.name }
        )
        assertEquals(
            listOf("Film C", "Film A", "Film B"),
            listsRepo.getList(list.id)?.titles?.map { it.name }
        )
    }

    @Test
    fun `add picker offers watchlist titles not already in the list`() {
        val list = listsRepo.createList("Weekend")
        listsRepo.addTitle(list.id, film)
        watchlistRepo.toggleWatchlist(film)
        watchlistRepo.toggleWatchlist(show)
        watchlistRepo.toggleWatchlist(other)

        val vm = detailViewModel(list.id)
        // Film A is in the list already, so the picker offers B + C.
        assertEquals(
            setOf("Film B", "Film C"),
            vm.uiState.value.addableFromWatchlist.map { it.name }.toSet()
        )

        vm.addFromWatchlist(show)
        assertEquals(
            listOf("Film A", "Film B"),
            vm.uiState.value.list?.titles?.map { it.name }
        )
        assertEquals(
            listOf("Film C"),
            vm.uiState.value.addableFromWatchlist.map { it.name }
        )
    }

    @Test
    fun `rename and delete through the detail view model`() {
        val list = listsRepo.createList("Old Name")
        val vm = detailViewModel(list.id)

        vm.rename("New Name")
        assertEquals("New Name", vm.uiState.value.list?.name)

        vm.deleteList()
        assertEquals(null, vm.uiState.value.list)
        assertTrue(listsRepo.getLists().isEmpty())
    }

    @Test
    fun `detail for a missing list is null, not a crash`() {
        val vm = detailViewModel("no-such-id")
        assertEquals(null, vm.uiState.value.list)
        assertTrue(vm.uiState.value.addableFromWatchlist.isEmpty())
    }
}
