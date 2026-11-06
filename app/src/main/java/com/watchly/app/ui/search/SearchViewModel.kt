/*
 * Watchly — unified search state holder (spec §3.1).
 *
 * One search box across TMDB (when the user has saved their key) +
 * TVmaze + AniList + Internet Archive, merged and grouped by Movies /
 * Shows / Anime. Results appear after a 300 ms pause while typing.
 * Recent searches are kept for the session; trending searches come
 * from the live trending rows (real titles, not a canned list). A
 * provider failing only narrows the results — the others still show,
 * with a soft note naming the missing source.
 */
package com.watchly.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.watchly.app.data.remote.AniListApi
import com.watchly.app.data.remote.ArchiveApi
import com.watchly.app.data.remote.TvMazeApi
import com.watchly.app.data.search.SearchGroups
import com.watchly.app.data.search.SearchHistoryRepository
import com.watchly.app.data.search.UnifiedSearch
import com.watchly.app.data.settings.TmdbKeyRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val hasSearched: Boolean = false,
    val groups: SearchGroups = SearchGroups(),
    /** Names of providers that failed the last search, if any. */
    val failedProviders: List<String> = emptyList(),
    val recentSearches: List<String> = emptyList(),
    val trendingSearches: List<String> = emptyList()
) {
    /** True when a search ran and nothing came back from any provider. */
    val isEmptyResult: Boolean
        get() = hasSearched && !isSearching && groups.isEmpty

    /** Soft note when some providers failed but others returned results. */
    val partialFailureNote: String?
        get() = if (hasSearched && !isSearching && failedProviders.isNotEmpty() && !groups.isEmpty) {
            "Couldn't reach ${failedProviders.joinToString(" and ")} — showing the rest."
        } else {
            null
        }
}

@OptIn(FlowPreview::class)
class SearchViewModel(
    /** Null in tests: recent searches then live for the session only. */
    private val history: SearchHistoryRepository? = null,
    /**
     * The user's saved TMDB key store, or null in tests. The key is
     * read fresh on every search so a key added in My Library takes
     * effect without reopening Search; without a key the search runs
     * exactly as before (TVmaze + AniList + Archive).
     */
    private val tmdbKeys: TmdbKeyRepository? = null
) : ViewModel() {

    private val queryFlow = MutableStateFlow("")

    private val _uiState = MutableStateFlow(
        SearchUiState(recentSearches = history?.load().orEmpty())
    )
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    init {
        loadTrendingSearches()
        viewModelScope.launch {
            queryFlow
                .debounce(SEARCH_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collect { query -> runSearch(query) }
        }
    }

    /** Called on every keystroke (and by voice search). */
    fun onQueryChange(query: String) {
        queryFlow.value = query
        _uiState.update { it.copy(query = query) }
    }

    /** Runs a search immediately (used by tapping a recent/trending term). */
    fun searchNow(query: String) {
        onQueryChange(query)
        viewModelScope.launch { runSearch(query.trim()) }
    }

    /** Clears the box and the results. */
    fun clearQuery() {
        onQueryChange("")
        _uiState.update { it.copy(hasSearched = false, groups = SearchGroups()) }
    }

    /** Clears the session's recent searches. */
    fun clearRecentSearches() {
        history?.clear()
        _uiState.update { it.copy(recentSearches = emptyList()) }
    }

    private suspend fun runSearch(rawQuery: String) {
        val query = rawQuery.trim()
        if (query.isEmpty()) {
            _uiState.update {
                it.copy(isSearching = false, hasSearched = false, groups = SearchGroups())
            }
            return
        }
        _uiState.update { it.copy(isSearching = true) }
        val result = UnifiedSearch.search(query, tmdbKeys?.getKey())
        history?.save(query)
        _uiState.update {
            it.copy(
                isSearching = false,
                hasSearched = true,
                groups = result.groups,
                failedProviders = result.failedProviders,
                recentSearches = history?.load()
                    ?: (listOf(query) + it.recentSearches)
                        .distinct().take(MAX_RECENT_SEARCHES)
            )
        }
    }

    /** Trending searches = names of currently trending titles (real data). */
    private fun loadTrendingSearches() {
        viewModelScope.launch {
            val names = ArrayList<String>()
            runCatching { TvMazeApi.fetchTrendingShows(limit = 5) }
                .onSuccess { names.addAll(it.map { title -> title.name }) }
            runCatching { AniListApi.fetchTrendingAnime(limit = 5) }
                .onSuccess { names.addAll(it.map { title -> title.name }) }
            runCatching { ArchiveApi.fetchMoviesPage(page = 1, perPage = 5) }
                .onSuccess { names.addAll(it.map { title -> title.name }) }
            _uiState.update {
                it.copy(trendingSearches = names.distinct().take(MAX_TRENDING_SEARCHES))
            }
        }
    }

    companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
        const val MAX_RECENT_SEARCHES = 8
        const val MAX_TRENDING_SEARCHES = 10
    }
}
