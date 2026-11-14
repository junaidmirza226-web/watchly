package com.watchly.app.ui.details

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.watchly.app.data.library.ListsRepository
import com.watchly.app.data.library.WatchlistRepository
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Title Details "Add to List…" (spec §9.2): the picker state must
 * reflect real membership, toggling must add AND remove through
 * the real store, and "create & add" must make the list with the
 * title already inside.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TitleDetailsListsTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var library: WatchlistRepository
    private lateinit var lists: ListsRepository

    private val title = Title(
        name = "The Clockwork Meridian",
        mediaType = MediaType.MOVIE,
        year = "2024"
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences(
            WatchlistRepository.PREFS_NAME,
            Context.MODE_PRIVATE
        ).edit().clear().commit()
        library = WatchlistRepository(context)
        lists = ListsRepository(context)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = TitleDetailsViewModel(
        title = title,
        library = library,
        userLists = lists
    )

    @Test
    fun `picker opens with real membership and closes cleanly`() {
        val weekend = lists.createList("Weekend")
        lists.createList("Best Anime")

        val vm = viewModel()
        assertNull(vm.uiState.value.listChoices)

        vm.onAddToListRequested()
        val choices = vm.uiState.value.listChoices
        assertEquals(listOf("Weekend", "Best Anime"), choices?.map { it.listName })
        assertEquals(listOf(weekend.id), choices?.map { it.listId }?.take(1))
        assertTrue(choices!!.none { it.containsTitle })

        vm.onAddToListDismissed()
        assertNull(vm.uiState.value.listChoices)
    }

    @Test
    fun `toggling a choice adds then removes the title`() {
        val weekend = lists.createList("Weekend")
        val vm = viewModel()

        vm.onAddToListRequested()
        vm.onListMembershipToggled(weekend.id)

        assertTrue(lists.containsTitle(weekend.id, vm.uiState.value.title))
        assertTrue(
            vm.uiState.value.listChoices!!.first { it.listId == weekend.id }.containsTitle
        )

        vm.onListMembershipToggled(weekend.id)

        assertFalse(lists.containsTitle(weekend.id, vm.uiState.value.title))
        assertFalse(
            vm.uiState.value.listChoices!!.first { it.listId == weekend.id }.containsTitle
        )
    }

    @Test
    fun `create and add makes the list with the title inside`() {
        val vm = viewModel()
        vm.onAddToListRequested()
        vm.onCreateListAndAdd("Rainy Day")

        val created = lists.getLists().single()
        assertEquals("Rainy Day", created.name)
        assertTrue(lists.containsTitle(created.id, vm.uiState.value.title))

        // The picker immediately reflects the new list, checked.
        val choice = vm.uiState.value.listChoices!!.single()
        assertEquals(created.id, choice.listId)
        assertTrue(choice.containsTitle)
    }

    @Test
    fun `without a lists store the button explains instead of crashing`() {
        val vm = TitleDetailsViewModel(title = title, library = library)
        vm.onAddToListRequested()
        assertNull(vm.uiState.value.listChoices)
        assertTrue(vm.uiState.value.infoMessage != null)
    }
}
