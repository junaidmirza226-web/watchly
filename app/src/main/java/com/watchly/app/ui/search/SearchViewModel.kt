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
import com.watchly.app.data.model.PlayabilityFilter
import com.watchly.app.data.settings.TmdbKeyRepository
import com.watchly.app.data.settings.UiPreferencesRepository
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
    /**
     * True when the last search never happened at all — every
     * provider failed (cycle 13). Kept apart from "no results":
     * the screen shows a couldn't-search state with Retry, the
     * same separation Browse has had since cycle 7.
     */
    val searchFailedCompletely: Boolean = false,
    val recentSearches: List<String> = emptyList(),
    val trendingSearches: List<String> = emptyList(),
    /** The shared playability filter (Phase D), persisted. */
    val playabilityFilter: PlayabilityFilter = PlayabilityFilter.ALL,
    /**
     * Genre filter over the current results (cycle 5): session-only
     * (a new search clears it), composing with the playability
     * filter. The chips are built from [availableGenres].
     */
    val selectedGenre: String? = null,
    /**
     * True when a TMDB key is saved (cycle 14): without one TMDB is
     * not searched at all, and the screen says so plainly instead
     * of letting sparse results look like an empty app. Read from
     * the key store at start and refreshed on every search, so a
     * key pasted in My Library takes effect without reopening
     * Search.
     */
    val hasTmdbKey: Boolean = false
) {
    /** Genre labels present in the unfiltered results (chip row). */
    val availableGenres: List<String>
        get() = groups.allGenres

    /** Result groups with BOTH filters applied. */
    val visibleGroups: SearchGroups
        get() = groups.filteredBy(playabilityFilter).filteredByGenre(selectedGenre)

    /** True when a search ran and nothing (visible) came back. */
    val isEmptyResult: Boolean
        get() = hasSearched && !isSearching && visibleGroups.isEmpty

    /** Results exist but the playability filter hides all of them. */
    val isFilterEmpty: Boolean
        get() = hasSearched && !isSearching && !groups.isEmpty && visibleGroups.isEmpty

    /** True when a search ran but every provider failed (cycle 13). */
    val isSearchFailed: Boolean
        get() = hasSearched && !isSearching && searchFailedCompletely

    /**
     * Soft note when SOME providers failed: over results it reads
     * "showing the rest"; over an empty result (the rest answered
     * with nothing) it says that instead (cycle 13 — the note used
     * to require non-empty groups, so it could never appear on the
     * no-results page it was written for). A complete failure is
     * the error state's job, not this note's.
     */
    val partialFailureNote: String?
        get() = if (hasSearched && !isSearching && failedProviders.isNotEmpty() &&
            !searchFailedCompletely
        ) {
            val names = failedProviders.distinct().joinToString(" and ")
            if (groups.isEmpty) {
                "Couldn't reach $names — the other sources found nothing."
            } else {
                "Couldn't reach $names — showing the rest."
            }
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
    private val tmdbKeys: TmdbKeyRepository? = null,
    /** Phase D: the persisted playability filter. */
    private val uiPrefs: UiPreferencesRepository? = null
) : ViewModel() {

    private val queryFlow = MutableStateFlow("")

    private val _uiState = MutableStateFlow(
        SearchUiState(
            recentSearches = history?.load().orEmpty(),
            playabilityFilter = uiPrefs?.playabilityFilter()
                ?: PlayabilityFilter.ALL,
            hasTmdbKey = tmdbKeys?.hasKey() == true
        )
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

    /** Re-runs the current query (the couldn't-search state's Retry). */
    fun retrySearch() {
        val query = _uiState.value.query.trim()
        if (query.isEmpty()) return
        viewModelScope.launch { runSearch(query) }
    }

    /** Picks a playability filter (Phase D); persisted + shared. */
    fun setPlayabilityFilter(filter: PlayabilityFilter) {
        if (_uiState.value.playabilityFilter == filter) return
        uiPrefs?.setPlayabilityFilter(filter)
        _uiState.update { it.copy(playabilityFilter = filter) }
    }

    /** Picks a genre chip over the current results (cycle 5). */
    fun setGenre(genre: String?) {
        _uiState.update { it.copy(selectedGenre = genre) }
    }

    /** Clears BOTH result filters — the filter-empty state's way out. */
    fun clearResultFilters() {
        uiPrefs?.setPlayabilityFilter(PlayabilityFilter.ALL)
        _uiState.update {
            it.copy(playabilityFilter = PlayabilityFilter.ALL, selectedGenre = null)
        }
    }

    /** Clears the box and the results. */
    fun clearQuery() {
        onQueryChange("")
        _uiState.update {
            it.copy(
                hasSearched = false,
                groups = SearchGroups(),
                searchFailedCompletely = false
            )
        }
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
                it.copy(
                    isSearching = false,
                    hasSearched = false,
                    groups = SearchGroups(),
                    searchFailedCompletely = false
                )
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
                hasTmdbKey = tmdbKeys?.hasKey() == true,
                // A new result set starts unfiltered by genre: the
                // old chip's genre may not even exist in these.
                selectedGenre = null,
                groups = result.groups,
                failedProviders = result.failedProviders,
                searchFailedCompletely = result.allProvidersFailed,
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
