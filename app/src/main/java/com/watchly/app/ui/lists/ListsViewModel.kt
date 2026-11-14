/*
 * Watchly — Lists screen + List Detail state holders (spec §9.2,
 * screens §6.2).
 *
 * Everything is local (ListsRepository over SharedPreferences), so
 * both ViewModels are synchronous: every action writes through the
 * repository and re-reads, and the UI state always shows exactly
 * what is stored. No network, no loading states.
 */
package com.watchly.app.ui.lists

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import com.watchly.app.data.library.ListsRepository
import com.watchly.app.data.library.UserList
import com.watchly.app.data.library.WatchlistRepository
import com.watchly.app.data.model.Title
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ListsUiState(
    val lists: List<UserList> = emptyList()
)

class ListsViewModel(
    private val lists: ListsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ListsUiState(lists.getLists()))
    val uiState: StateFlow<ListsUiState> = _uiState.asStateFlow()

    /** Re-reads the store (called when the screen is shown). */
    fun refresh() {
        _uiState.update { it.copy(lists = lists.getLists()) }
    }

    /** Creates a list with [name]; blank names are ignored. */
    fun createList(name: String) {
        if (name.isBlank()) return
        lists.createList(name)
        refresh()
    }

    /** Renames a list; blank names are ignored. */
    fun renameList(listId: String, name: String) {
        if (name.isBlank()) return
        lists.renameList(listId, name)
        refresh()
    }

    /** Deletes a list (and its contents). */
    fun deleteList(listId: String) {
        lists.deleteList(listId)
        refresh()
    }
}

class ListsViewModelFactory(
    private val context: Context
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ListsViewModel::class.java)) {
            return ListsViewModel(ListsRepository(context.applicationContext)) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: $modelClass")
    }
}

// ---------------------------------------------------------------------
// List Detail
// ---------------------------------------------------------------------

data class ListDetailUiState(
    /** The list being shown; null once deleted (screen pops). */
    val list: UserList? = null,
    /** Watchlist titles not yet in this list (the "add" picker). */
    val addableFromWatchlist: List<Title> = emptyList()
)

class ListDetailViewModel(
    private val listId: String,
    private val lists: ListsRepository,
    private val watchlist: WatchlistRepository,
    /** Cycle 5: downloads, for resolving a list item's offline copy. */
    private val downloads: com.watchly.app.data.downloads.DownloadsRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(ListDetailUiState())
    val uiState: StateFlow<ListDetailUiState> = _uiState.asStateFlow()

    /**
     * One-shot play event (cycle 5): set by [playFrom], consumed by
     * the screen, which hands it to the nav/player and clears it.
     */
    private val _playRequest =
        MutableStateFlow<com.watchly.app.ui.player.PlaybackRequest?>(null)
    val playRequest: StateFlow<com.watchly.app.ui.player.PlaybackRequest?> =
        _playRequest.asStateFlow()

    init {
        refresh()
    }

    /**
     * Plays [title] from this list (cycle 5): the title is resolved
     * for real (download → Archive files → YouTube); when it plays,
     * the REST of the list rides along as the autoplay queue, so
     * playback continues down the list. A title that resolves to
     * nothing playable produces no event (its badge was a lie the
     * resolver refuses to repeat).
     */
    fun playFrom(title: Title) {
        val list = _uiState.value.list ?: return
        val index = list.titles.indexOfFirst { it.stableKey == title.stableKey }
        if (index < 0) return
        viewModelScope.launch {
            val resolved = com.watchly.app.data.playback.PlayableSourceResolver
                .resolve(title, downloads)
            if (!resolved.isPlayable) return@launch
            _playRequest.value = com.watchly.app.ui.player.PlaybackRequest(
                title = title,
                files = resolved.files,
                youtubeVideoId = resolved.youtubeVideoId,
                upNext = list.titles.drop(index + 1).map {
                    com.watchly.app.data.playback.UpNextItem(it)
                }
            )
        }
    }

    /** Clears the one-shot play event after the screen consumed it. */
    fun playRequestConsumed() {
        _playRequest.value = null
    }

    /** Re-reads the list + the add-picker candidates. */
    fun refresh() {
        val list = lists.getList(listId)
        _uiState.update {
            it.copy(
                list = list,
                addableFromWatchlist = if (list == null) {
                    emptyList()
                } else {
                    val inList = list.titles.map { title -> title.stableKey }.toSet()
                    watchlist.getWatchlist().filter { title ->
                        title.stableKey !in inList
                    }
                }
            )
        }
    }

    /** Removes one title from the list. */
    fun removeTitle(title: Title) {
        lists.removeTitle(listId, title)
        refresh()
    }

    /** Persists one drag step (the drag gesture calls this per swap). */
    fun moveTitle(fromIndex: Int, toIndex: Int) {
        lists.moveTitle(listId, fromIndex, toIndex)
        refresh()
    }

    /** Adds a watchlist title picked in the "add" picker. */
    fun addFromWatchlist(title: Title) {
        lists.addTitle(listId, title)
        refresh()
    }

    /** Renames the list; blank names are ignored. */
    fun rename(name: String) {
        if (name.isBlank()) return
        lists.renameList(listId, name)
        refresh()
    }

    /** Deletes the list; state.list becomes null and the screen pops. */
    fun deleteList() {
        lists.deleteList(listId)
        refresh()
    }
}

class ListDetailViewModelFactory(
    private val listId: String,
    private val context: Context
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ListDetailViewModel::class.java)) {
            val app = context.applicationContext
            return ListDetailViewModel(
                listId = listId,
                lists = ListsRepository(app),
                watchlist = WatchlistRepository(app),
                downloads = com.watchly.app.data.downloads.DownloadsRepository(app)
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: $modelClass")
    }
}
