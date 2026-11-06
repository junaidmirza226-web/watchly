/*
 * Watchly — browse tabs state holder (Movies / Shows / Anime).
 *
 * One ViewModel per tab kind, created with a BrowseKind factory (see
 * BrowseViewModelFactory below). Loading strategy differs per source:
 *  - Movies WITH a TMDB key: TMDB discover is the catalogue — paged,
 *    with TMDB genre filters, sorts, and per-industry (original
 *    language) rows via the industry chips. TMDB updates itself
 *    daily, so the catalogue is always current.
 *  - Movies WITHOUT a key: Internet Archive public-domain films
 *    (server-side genre filter + sort + paging), plus an honest note
 *    that a TMDB key unlocks the full catalogue. Nothing crashes and
 *    the tab still works.
 *  - Shows (TVmaze): pages of the full catalogue are cached locally and
 *    filtered/sorted client-side (TVmaze has no server-side filter/sort).
 *  - Anime (AniList): server-side genre filter + sort + paging.
 *
 * Every failure is folded into BrowseUiState — the ViewModel never
 * crashes the UI and a failed "load more" keeps the titles already shown.
 */
package com.watchly.app.ui.browse

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.watchly.app.data.browse.BrowseSort
import com.watchly.app.data.browse.INDUSTRIES
import com.watchly.app.data.browse.Industry
import com.watchly.app.data.browse.applyBrowseFilter
import com.watchly.app.data.model.Title
import com.watchly.app.data.remote.AniListApi
import com.watchly.app.data.remote.ArchiveApi
import com.watchly.app.data.remote.TmdbApi
import com.watchly.app.data.remote.TvMazeApi
import com.watchly.app.data.settings.TmdbKeyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which browse tab this ViewModel serves. */
enum class BrowseKind {
    MOVIES, SHOWS, ANIME
}

data class BrowseUiState(
    val kind: BrowseKind,
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val titles: List<Title> = emptyList(),
    val genres: List<String> = emptyList(),
    val selectedGenre: String? = null,
    val sort: BrowseSort = BrowseSort.POPULAR,
    val availableSorts: List<BrowseSort> = BrowseSort.entries,
    val hasMore: Boolean = true,
    /** Movies tab only: TMDB is the catalogue source (key saved). */
    val tmdbActive: Boolean = false,
    /** Movies tab only: no key saved — show the "add your key" note. */
    val showTmdbPrompt: Boolean = false,
    /** Movies tab with TMDB: industry chips (original-language rows). */
    val industries: List<Industry> = emptyList(),
    val selectedIndustry: Industry? = null,
    /** Non-null when something failed; safe to show directly to the user. */
    val errorMessage: String? = null
) {
    /** True when loading finished and the grid is empty. */
    val isEmpty: Boolean
        get() = !isLoading && titles.isEmpty()
}

class BrowseViewModel(
    private val kind: BrowseKind,
    private val tmdbKeys: TmdbKeyRepository? = null
) : ViewModel() {

    /** Movies tab only: is TMDB the active catalogue source right now? */
    private var tmdbActive: Boolean =
        kind == BrowseKind.MOVIES && tmdbKeys?.hasKey() == true

    private val _uiState = MutableStateFlow(moviesAwareInitialState())
    val uiState: StateFlow<BrowseUiState> = _uiState.asStateFlow()

    /** Initial state, shaped by which Movies source is active. */
    private fun moviesAwareInitialState(): BrowseUiState = BrowseUiState(
        kind = kind,
        genres = when {
            kind == BrowseKind.MOVIES && tmdbActive ->
                TmdbApi.MOVIE_GENRES.values.toList()
            kind == BrowseKind.MOVIES -> ArchiveApi.MOVIE_GENRES.keys.toList()
            kind == BrowseKind.SHOWS -> SHOW_GENRES
            else -> AniListApi.ANIME_GENRES
        },
        // Archive has no rating field, so "Top Rated" is meaningless there.
        availableSorts = if (kind == BrowseKind.MOVIES && !tmdbActive) {
            listOf(BrowseSort.POPULAR, BrowseSort.NEWEST, BrowseSort.A_Z)
        } else {
            BrowseSort.entries
        },
        tmdbActive = tmdbActive,
        showTmdbPrompt = kind == BrowseKind.MOVIES && !tmdbActive,
        industries = if (tmdbActive) INDUSTRIES else emptyList()
    )

    // Server-paged tabs (Movies / Anime): next page to request, 1-based.
    private var nextPage = 1

    // Shows tab: full pages cached locally; the grid shows the first
    // [visibleCount] of the filtered list and grows on "load more".
    private var showsCache: List<Title> = emptyList()
    private var showsPage = 0
    private var visibleCount = PAGE_SIZE

    init {
        load()
    }

    /** (Re)loads the first page. Safe to call from a Retry button. */
    fun load() {
        nextPage = 1
        showsPage = 0
        showsCache = emptyList()
        visibleCount = PAGE_SIZE
        _uiState.update {
            it.copy(isLoading = true, titles = emptyList(), errorMessage = null, hasMore = true)
        }
        viewModelScope.launch {
            when (kind) {
                BrowseKind.MOVIES, BrowseKind.ANIME -> loadServerPage(reset = true)
                BrowseKind.SHOWS -> loadShowsPages()
            }
        }
    }

    /** Loads the next chunk when the user scrolls near the end of the grid. */
    fun loadMore() {
        val state = _uiState.value
        if (state.isLoading || state.isLoadingMore || !state.hasMore) return
        viewModelScope.launch {
            when (kind) {
                BrowseKind.MOVIES, BrowseKind.ANIME -> loadServerPage(reset = false)
                BrowseKind.SHOWS -> loadShowsPages()
            }
        }
    }

    /**
     * Called when the tab becomes visible: if the TMDB key situation
     * changed (key added/removed since this ViewModel loaded), switch
     * the Movies source and reload. No-op when nothing changed.
     */
    fun refreshKeyState() {
        if (kind != BrowseKind.MOVIES) return
        val activeNow = tmdbKeys?.hasKey() == true
        if (activeNow == tmdbActive) return
        tmdbActive = activeNow
        _uiState.update {
            moviesAwareInitialState().copy(
                isLoading = it.isLoading,
                titles = it.titles,
                sort = it.sort
            )
        }
        load()
    }

    /** Picks a genre chip; reloads from page 1. */
    fun selectGenre(genre: String?) {
        if (_uiState.value.selectedGenre == genre) return
        _uiState.update { it.copy(selectedGenre = genre) }
        load()
    }

    /** Picks an industry chip (Movies + TMDB only); reloads from page 1. */
    fun selectIndustry(industry: Industry?) {
        if (_uiState.value.selectedIndustry == industry) return
        _uiState.update { it.copy(selectedIndustry = industry) }
        load()
    }

    /** Picks a sort order; reloads from page 1. */
    fun selectSort(sort: BrowseSort) {
        if (_uiState.value.sort == sort) return
        _uiState.update { it.copy(sort = sort) }
        load()
    }

    /** Clears the transient error banner once the user dismisses it. */
    fun onErrorShown() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    // ------------------------------------------------------------------
    // Server-paged tabs (Movies / Anime)
    // ------------------------------------------------------------------

    private suspend fun loadServerPage(reset: Boolean) {
        val state = _uiState.value
        if (!reset) _uiState.update { it.copy(isLoadingMore = true) }
        val page = if (reset) 1 else nextPage
        val result = runCatching {
            when (kind) {
                BrowseKind.MOVIES -> {
                    val apiKey = if (tmdbActive) tmdbKeys?.getKey() else null
                    if (apiKey != null) {
                        // TMDB is the catalogue: discover with the
                        // selected industry (original language) and
                        // genre filters. TMDB's page size is 20.
                        TmdbApi.discoverMovies(
                            apiKey = apiKey,
                            language = state.selectedIndustry?.languageCode,
                            genreId = TmdbApi.genreIdFor(state.selectedGenre),
                            sort = when (state.sort) {
                                BrowseSort.POPULAR -> TmdbApi.MovieSort.POPULAR
                                BrowseSort.TOP_RATED -> TmdbApi.MovieSort.TOP_RATED
                                BrowseSort.NEWEST -> TmdbApi.MovieSort.NEWEST
                                BrowseSort.A_Z -> TmdbApi.MovieSort.A_Z
                            },
                            page = page
                        )
                    } else {
                        ArchiveApi.fetchMoviesPage(
                            page = page,
                            perPage = PAGE_SIZE,
                            sort = when (state.sort) {
                                BrowseSort.POPULAR, BrowseSort.TOP_RATED ->
                                    ArchiveApi.MovieSort.POPULAR
                                BrowseSort.NEWEST -> ArchiveApi.MovieSort.NEWEST
                                BrowseSort.A_Z -> ArchiveApi.MovieSort.A_Z
                            },
                            genre = state.selectedGenre
                        )
                    }
                }
                BrowseKind.ANIME -> AniListApi.fetchAnimePage(
                    page = page,
                    perPage = PAGE_SIZE,
                    sort = when (state.sort) {
                        BrowseSort.POPULAR -> AniListApi.AnimeSort.POPULAR
                        BrowseSort.TOP_RATED -> AniListApi.AnimeSort.TOP_RATED
                        BrowseSort.NEWEST -> AniListApi.AnimeSort.NEWEST
                        BrowseSort.A_Z -> AniListApi.AnimeSort.A_Z
                    },
                    genre = state.selectedGenre
                )
                BrowseKind.SHOWS -> error("Shows tab does not use server paging")
            }
        }
        result.fold(
            onSuccess = { pageTitles ->
                nextPage = page + 1
                // TMDB pages hold 20; the keyless sources use PAGE_SIZE.
                val expectedPageSize =
                    if (kind == BrowseKind.MOVIES && tmdbActive) TMDB_PAGE_SIZE else PAGE_SIZE
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        titles = if (reset) pageTitles else it.titles + pageTitles,
                        // A short page means the catalogue ran out.
                        hasMore = pageTitles.size >= expectedPageSize,
                        errorMessage = null
                    )
                }
            },
            onFailure = {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        errorMessage = if (reset && it.titles.isEmpty()) {
                            "Couldn't load content. Check your connection and try again."
                        } else {
                            "Couldn't load more. Pull down to try again."
                        }
                    )
                }
            }
        )
    }

    // ------------------------------------------------------------------
    // Shows tab (client-side filter/sort over cached TVmaze pages)
    // ------------------------------------------------------------------

    private suspend fun loadShowsPages() {
        val state = _uiState.value
        val isFirstLoad = showsCache.isEmpty()
        if (!isFirstLoad) _uiState.update { it.copy(isLoadingMore = true) }

        // Fetch one more catalogue page (240 shows) and merge into the cache.
        val result = runCatching { TvMazeApi.fetchShowsPage(showsPage) }
        result.fold(
            onSuccess = { pageTitles ->
                showsPage += 1
                showsCache = (showsCache + pageTitles).distinctBy { it.stableKey }
                val filtered = applyBrowseFilter(showsCache, state.selectedGenre, state.sort)
                // If the visible slice is still short and the catalogue has
                // more pages, the next loadMore() call fetches another page.
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        titles = filtered.take(visibleCount),
                        hasMore = pageTitles.isNotEmpty(),
                        errorMessage = null
                    )
                }
            },
            onFailure = {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        errorMessage = if (isFirstLoad) {
                            "Couldn't load content. Check your connection and try again."
                        } else {
                            "Couldn't load more. Pull down to try again."
                        }
                    )
                }
            }
        )
        // Growing the visible slice happens even when the fetch just
        // happened: the grid shows more of the filtered cache.
        if (result.isSuccess && !isFirstLoad) {
            visibleCount += PAGE_SIZE
            val filtered = applyBrowseFilter(
                showsCache,
                _uiState.value.selectedGenre,
                _uiState.value.sort
            )
            _uiState.update { it.copy(titles = filtered.take(visibleCount)) }
        }
    }

    companion object {
        const val PAGE_SIZE = 30

        /** TMDB discover returns 20 results per page (not configurable). */
        const val TMDB_PAGE_SIZE = 20

        /** TVmaze genre labels used as chips on the Shows tab. */
        val SHOW_GENRES: List<String> = listOf(
            "Drama", "Comedy", "Action", "Science-Fiction", "Horror",
            "Romance", "Crime", "Thriller", "Fantasy", "Adventure",
            "Mystery", "Animation", "Family", "Supernatural"
        )
    }
}

/** Factory so NavHost destinations can create a BrowseViewModel per kind. */
class BrowseViewModelFactory(
    private val kind: BrowseKind,
    private val context: Context? = null
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BrowseViewModel::class.java)) {
            val keys = context?.let { TmdbKeyRepository(it.applicationContext) }
            return BrowseViewModel(kind, keys) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: $modelClass")
    }
}
