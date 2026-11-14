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
 *  - Movies WITH a key + the ▶ Playable filter: the Archive again —
 *    TMDB titles are catalogue-only, so a TMDB-sourced Playable view
 *    would be empty by construction (cycle 7; the filter flips the
 *    tab's source, see applyPlayabilityFilter).
 *  - Shows: TVmaze pages PLUS the Archive's classic-TV collection
 *    (the tab's playable source, cycle 7) are cached locally and
 *    filtered/sorted client-side (TVmaze has no server-side
 *    filter/sort).
 *  - Anime (AniList): server-side genre filter + sort + paging, with
 *    the official YouTube anime channels' latest videos merged in
 *    (the tab's playable source, cycle 7).
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
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.PlayabilityFilter
import com.watchly.app.data.model.Title
import com.watchly.app.data.model.filteredBy
import com.watchly.app.data.remote.AniListApi
import com.watchly.app.data.remote.ArchiveApi
import com.watchly.app.data.remote.TmdbApi
import com.watchly.app.data.remote.TvMazeApi
import com.watchly.app.data.remote.YouTubeApi
import com.watchly.app.data.settings.TmdbKeyRepository
import com.watchly.app.data.settings.UiPreferencesRepository
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
    /** The shared playability filter (Phase D), persisted. */
    val playabilityFilter: PlayabilityFilter = PlayabilityFilter.ALL,
    /** Non-null when something failed; safe to show directly to the user. */
    val errorMessage: String? = null
) {
    /** The grid's titles: [titles] with the playability filter applied. */
    val visibleTitles: List<Title>
        get() = titles.filteredBy(playabilityFilter)

    /** True when loading finished and the grid is empty. */
    val isEmpty: Boolean
        get() = !isLoading && visibleTitles.isEmpty()
}

class BrowseViewModel(
    private val kind: BrowseKind,
    private val tmdbKeys: TmdbKeyRepository? = null,
    /** Phase D: the persisted playability filter. */
    private val uiPrefs: UiPreferencesRepository? = null
) : ViewModel() {

    /** Movies and Shows: is TMDB the active catalogue source right now? */
    private var tmdbActive: Boolean =
        (kind == BrowseKind.MOVIES || kind == BrowseKind.SHOWS) && tmdbKeys?.hasKey() == true

    private val _uiState = MutableStateFlow(moviesAwareInitialState())
    val uiState: StateFlow<BrowseUiState> = _uiState.asStateFlow()

    /** Initial state, shaped by which Movies/Shows source is active. */
    private fun moviesAwareInitialState(): BrowseUiState = BrowseUiState(
        kind = kind,
        genres = when {
            kind == BrowseKind.MOVIES && tmdbActive ->
                TmdbApi.MOVIE_GENRES.values.toList()
            kind == BrowseKind.MOVIES -> ArchiveApi.MOVIE_GENRES.keys.toList()
            kind == BrowseKind.SHOWS && tmdbActive ->
                TmdbApi.TV_GENRES.values.toList()
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
        industries = if (kind == BrowseKind.MOVIES && tmdbActive) INDUSTRIES else emptyList(),
        playabilityFilter = uiPrefs?.playabilityFilter() ?: PlayabilityFilter.ALL,
        // Cycle 5: the tab's genre choice is persisted per tab.
        selectedGenre = uiPrefs?.genreFor(genreScope)
    ).withMoviesSourcePresentation()

    /** Prefs scope for this tab's saved genre (cycle 5). */
    private val genreScope: String
        get() = when (kind) {
            BrowseKind.MOVIES -> "movies"
            BrowseKind.SHOWS -> "shows"
            BrowseKind.ANIME -> "anime"
        }

    // Server-paged tabs (Movies / Anime): next page to request, 1-based.
    private var nextPage = 1

    // Phase D: consecutive extra pages fetched only because the
    // playability filter left the grid short (bounded — a filter
    // that matches nothing must not page the whole catalogue).
    private var filterChainLoads = 0

    // Shows tab: full pages of BOTH sources cached locally and
    // filtered/sorted client-side (see loadShowsPages).
    private var showsCache: List<Title> = emptyList()
    private var showsPage = 0
    private var showsArchivePage = 1
    private var showsHasMore = true
    private var showsArchiveHasMore = true

    // Anime tab: latest videos from the official YouTube anime
    // channels, merged into the list on every (re)load (cycle 7).
    private var youtubeExtras: List<Title> = emptyList()

    init {
        load()
    }

    /** (Re)loads the first page. Safe to call from a Retry button. */
    fun load() {
        nextPage = 1
        filterChainLoads = 0
        showsPage = 0
        showsArchivePage = 1
        showsHasMore = true
        showsArchiveHasMore = true
        showsCache = emptyList()
        _uiState.update {
            it.copy(isLoading = true, titles = emptyList(), errorMessage = null, hasMore = true)
        }
        viewModelScope.launch {
            when (kind) {
                BrowseKind.MOVIES -> loadServerPage(reset = true)
                BrowseKind.ANIME -> {
                    loadYouTubeExtras()
                    loadServerPage(reset = true)
                }
                BrowseKind.SHOWS -> {
                    if (useTmdb(_uiState.value)) {
                        loadServerPage(reset = true)
                    } else {
                        loadShowsPages()
                    }
                }
            }
        }
    }

    /**
     * Anime only: refreshes [youtubeExtras] from the official
     * YouTube anime channels (Muse Asia, Ani-One Asia). Each
     * channel is fetched under its own runCatching — a channel
     * that fails simply contributes nothing; AniList remains the
     * tab's spine and its failure remains the tab's error.
     */
    private suspend fun loadYouTubeExtras() {
        youtubeExtras = YouTubeApi.CHANNELS
            .filter { it.mediaType == MediaType.ANIME }
            .flatMap { channel ->
                runCatching { YouTubeApi.fetchChannelVideos(channel) }
                    .getOrDefault(emptyList())
            }
            .distinctBy { it.stableKey }
    }

    /**
     * TMDB is the active catalogue when a key is present for Movies & Shows.
     */
    private fun useTmdb(state: BrowseUiState): Boolean =
        (kind == BrowseKind.MOVIES || kind == BrowseKind.SHOWS) && tmdbActive

    /** Loads the next chunk when the user scrolls near the end of the grid. */
    fun loadMore() {
        val state = _uiState.value
        if (state.isLoading || state.isLoadingMore || !state.hasMore) return
        viewModelScope.launch {
            when (kind) {
                BrowseKind.MOVIES, BrowseKind.ANIME -> loadServerPage(reset = false)
                BrowseKind.SHOWS -> {
                    if (useTmdb(_uiState.value)) {
                        loadServerPage(reset = false)
                    } else {
                        loadShowsPages()
                    }
                }
            }
        }
    }

    /**
     * Called when the tab becomes visible: syncs the persisted
     * playability filter (it may have been changed on Search or
     * Home), and if the TMDB key situation changed
     * (key added/removed since this ViewModel loaded), switch the
     * source and reload.
     */
    fun refreshKeyState() {
        val persisted = uiPrefs?.playabilityFilter()
        if (persisted != null && persisted != _uiState.value.playabilityFilter) {
            applyPlayabilityFilter(persisted)
        }
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

    /** Picks a playability filter (Phase D); persisted + shared. */
    fun setPlayabilityFilter(filter: PlayabilityFilter) {
        if (_uiState.value.playabilityFilter == filter) return
        uiPrefs?.setPlayabilityFilter(filter)
        applyPlayabilityFilter(filter)
    }

    /**
     * Applies a filter value to the current tab. Most tabs just
     * re-render (the getter applies the filter) and top up from
     * the next pages when the filter leaves the grid short. The
     * one structural case is Movies with a TMDB key crossing the
     * ▶ Playable boundary: the tab's SOURCE flips (TMDB ↔ the
     * playable Archive catalogue), so it reloads with the new
     * source's genre/sort lists. The genre selection resets on a
     * flip — chips belong to the source, and a TMDB genre label
     * means nothing to the Archive (and vice versa).
     */
    private fun applyPlayabilityFilter(filter: PlayabilityFilter) {
        val previous = _uiState.value.playabilityFilter
        filterChainLoads = 0
        _uiState.update { it.copy(playabilityFilter = filter) }
        if (kind == BrowseKind.MOVIES && tmdbActive &&
            (previous == PlayabilityFilter.PLAYABLE_ONLY) !=
            (filter == PlayabilityFilter.PLAYABLE_ONLY)
        ) {
            uiPrefs?.setGenreFor(genreScope, null)
            _uiState.update {
                it.copy(selectedGenre = null).withMoviesSourcePresentation()
            }
            load()
            return
        }
        val state = _uiState.value
        if (!state.isLoading && state.visibleTitles.size < PAGE_SIZE && state.hasMore) {
            loadMore()
        }
    }

    /** Picks a genre chip; persists it for this tab and reloads. */
    fun selectGenre(genre: String?) {
        if (_uiState.value.selectedGenre == genre) return
        uiPrefs?.setGenreFor(genreScope, genre)
        _uiState.update { it.copy(selectedGenre = genre) }
        load()
    }

    /** Clears BOTH filters (genre + playability) — the empty state's way out. */
    fun clearAllFilters() {
        uiPrefs?.setGenreFor(genreScope, null)
        uiPrefs?.setPlayabilityFilter(PlayabilityFilter.ALL)
        _uiState.update {
            it.copy(selectedGenre = null, playabilityFilter = PlayabilityFilter.ALL)
                .withMoviesSourcePresentation()
        }
        load()
    }

    /**
     * Movies only: swaps the chip rows (genres, industries, sorts)
     * to match the tab's EFFECTIVE source for the current filter —
     * TMDB presentation normally, Archive presentation under the
     * ▶ Playable filter with a key saved (see useTmdb). Also drops
     * a TOP_RATED sort the Archive cannot honour.
     */
    private fun BrowseUiState.withMoviesSourcePresentation(): BrowseUiState {
        if (kind != BrowseKind.MOVIES || !tmdbActive) return this
        val archiveSource =
            playabilityFilter == PlayabilityFilter.PLAYABLE_ONLY
        return copy(
            genres = if (archiveSource) {
                ArchiveApi.MOVIE_GENRES.keys.toList()
            } else {
                TmdbApi.MOVIE_GENRES.values.toList()
            },
            industries = if (archiveSource) emptyList() else INDUSTRIES,
            availableSorts = if (archiveSource) {
                listOf(BrowseSort.POPULAR, BrowseSort.NEWEST, BrowseSort.A_Z)
            } else {
                BrowseSort.entries
            },
            sort = if (archiveSource && sort == BrowseSort.TOP_RATED) {
                BrowseSort.POPULAR
            } else {
                sort
            }
        )
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
                    val apiKey = if (useTmdb(state)) tmdbKeys?.getKey() else null
                    val tmdbResult = if (apiKey != null) {
                        runCatching {
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
                        }.getOrNull()
                    } else null

                    if (!tmdbResult.isNullOrEmpty()) {
                        tmdbResult
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
                BrowseKind.SHOWS -> {
                    val apiKey = if (useTmdb(state)) tmdbKeys?.getKey() else null
                    val tmdbResult = if (apiKey != null) {
                        runCatching {
                            TmdbApi.discoverTv(
                                apiKey = apiKey,
                                genreId = TmdbApi.tvGenreIdFor(state.selectedGenre),
                                sort = when (state.sort) {
                                    BrowseSort.POPULAR -> TmdbApi.MovieSort.POPULAR
                                    BrowseSort.TOP_RATED -> TmdbApi.MovieSort.TOP_RATED
                                    BrowseSort.NEWEST -> TmdbApi.MovieSort.NEWEST
                                    BrowseSort.A_Z -> TmdbApi.MovieSort.A_Z
                                },
                                page = page
                            )
                        }.getOrNull()
                    } else null

                    if (!tmdbResult.isNullOrEmpty()) {
                        tmdbResult
                    } else {
                        TvMazeApi.fetchShowsPage(page)
                    }
                }
            }
        }
        var chainAnotherPage = false
        result.fold(
            onSuccess = { pageTitles ->
                nextPage = page + 1
                // TMDB pages hold 20; the keyless sources use PAGE_SIZE.
                val expectedPageSize =
                    if (useTmdb(state)) TMDB_PAGE_SIZE else PAGE_SIZE
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        // Anime resets lead with the YouTube
                        // channel extras (latest uploads first) —
                        // but only under "All genres": the extras
                        // carry no genre metadata, so claiming them
                        // for a specific genre would be a lie.
                        // (StableKeys can't collide: youtube:… vs
                        // anilist:… vs tmdb:… vs archive:… .)
                        titles = if (reset) {
                            if (kind == BrowseKind.ANIME &&
                                state.selectedGenre == null
                            ) {
                                youtubeExtras + pageTitles
                            } else {
                                pageTitles
                            }
                        } else {
                            it.titles + pageTitles
                        },
                        // A short page means the catalogue ran out.
                        hasMore = pageTitles.size >= expectedPageSize,
                        errorMessage = null
                    )
                }
                // Phase D chain: with a playability filter on, a
                // page can add nothing the grid can show. Top up
                // from the next pages (bounded) so the grid isn't
                // stranded empty while the catalogue still has
                // matching titles.
                val now = _uiState.value
                chainAnotherPage =
                    now.playabilityFilter != PlayabilityFilter.ALL &&
                        now.visibleTitles.size < PAGE_SIZE &&
                        now.hasMore &&
                        filterChainLoads < MAX_FILTER_CHAIN_LOADS
                if (!chainAnotherPage) filterChainLoads = 0
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
        if (chainAnotherPage) {
            filterChainLoads += 1
            loadServerPage(reset = false)
        }
    }

    // ------------------------------------------------------------------
    // Shows tab (client-side filter/sort over cached pages)
    // ------------------------------------------------------------------

    /**
     * Fetches the next page of EACH Shows source and merges them
     * into the cache. Two spines, one grid (cycle 7): TVmaze's
     * catalogue plus the Archive's classic-TV collection — the
     * tab's playable source. Each fetch runs under its own
     * runCatching: one source failing must not empty the tab; the
     * tab only reports a failure when BOTH fail.
     *
     * State contract (cycle 7 fix): [BrowseUiState.titles] holds
     * the genre/sort-filtered cache with the playability filter
     * NOT applied and no windowing — the same contract the server
     * tabs keep. Previously this published the already-playability-
     * filtered slice, so the screen could not tell "the filter hid
     * everything" from "the catalogue is empty" and landed on the
     * wrong, button-less empty state that blamed the genre.
     */
    private suspend fun loadShowsPages() {
        val state = _uiState.value
        val isFirstLoad = showsCache.isEmpty()
        if (!isFirstLoad) _uiState.update { it.copy(isLoadingMore = true) }

        val tvmazeResult = if (showsHasMore) {
            runCatching { TvMazeApi.fetchShowsPage(showsPage) }
        } else {
            Result.success(emptyList())
        }
        val archiveResult = if (showsArchiveHasMore) {
            runCatching {
                ArchiveApi.fetchCollectionPage(
                    ArchiveApi.CLASSIC_TV,
                    page = showsArchivePage,
                    perPage = PAGE_SIZE
                )
            }
        } else {
            Result.success(emptyList())
        }

        if (tvmazeResult.isFailure && archiveResult.isFailure) {
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
            return
        }

        tvmazeResult.onSuccess { pageTitles ->
            if (showsHasMore) {
                showsPage += 1
                // TVmaze signals the end with an empty page.
                showsHasMore = pageTitles.isNotEmpty()
                showsCache = (showsCache + pageTitles).distinctBy { it.stableKey }
            }
        }
        archiveResult.onSuccess { pageTitles ->
            if (showsArchiveHasMore) {
                showsArchivePage += 1
                showsArchiveHasMore = pageTitles.size >= PAGE_SIZE
                showsCache = (showsCache + pageTitles).distinctBy { it.stableKey }
            }
        }

        // Exactly one source failed: the grid still fills from the
        // other, but say so honestly in the banner slot.
        val partialFailure = tvmazeResult.isFailure || archiveResult.isFailure
        publishShows(
            errorMessage = if (partialFailure) {
                "Some sources couldn't load. Check your connection."
            } else {
                null
            }
        )

        // Chain: with a playability filter on, a round can add
 * nothing the grid can show. Top up (bounded) while either
        // source still has pages — same pattern as loadServerPage.
        val now = _uiState.value
        if (now.playabilityFilter != PlayabilityFilter.ALL &&
            now.visibleTitles.size < PAGE_SIZE &&
            now.hasMore &&
            filterChainLoads < MAX_FILTER_CHAIN_LOADS
        ) {
            filterChainLoads += 1
            loadShowsPages()
        } else {
            filterChainLoads = 0
        }
    }

    /** Publishes the cache into state per the contract above. */
    private fun publishShows(errorMessage: String?) {
        val state = _uiState.value
        val filtered = applyBrowseFilter(showsCache, state.selectedGenre, state.sort)
        _uiState.update {
            it.copy(
                isLoading = false,
                isLoadingMore = false,
                titles = filtered,
                hasMore = showsHasMore || showsArchiveHasMore,
                errorMessage = errorMessage
            )
        }
    }

    companion object {
        const val PAGE_SIZE = 30

        /** Cap on filter-driven top-up page loads (Phase D). */
        const val MAX_FILTER_CHAIN_LOADS = 10

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
            val uiPrefs = context?.let {
                UiPreferencesRepository(it.applicationContext)
            }
            return BrowseViewModel(kind, keys, uiPrefs) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: $modelClass")
    }
}
