/*
 * Watchly — browse tabs state holder (Movies / Shows / Anime) with
 * multi-dimensional filtering (multi-industry, multi-genre ANY/ALL,
 * release era/year, minimum rating, sort, and availability).
 */
package com.watchly.app.ui.browse

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.watchly.app.data.browse.BrowseRatingFilter
import com.watchly.app.data.browse.BrowseSort
import com.watchly.app.data.browse.BrowseYearFilter
import com.watchly.app.data.browse.GenreMatchMode
import com.watchly.app.data.browse.INDUSTRIES
import com.watchly.app.data.browse.Industry
import com.watchly.app.data.browse.applyAdvancedBrowseFilter
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.PlayabilityFilter
import com.watchly.app.data.model.Title
import com.watchly.app.data.model.filteredBy
import com.watchly.app.data.remote.AniListApi
import com.watchly.app.data.remote.ArchiveApi
import com.watchly.app.data.remote.CinemetaApi
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

/**
 * Optional cross-tab preset handed over when the user taps "See All"
 * on an industry or curated row from Home.
 */
data class BrowsePreset(
    val kind: BrowseKind,
    val industry: Industry? = null,
    val sort: BrowseSort? = null,
    val genre: String? = null
)

object BrowsePresetStore {
    @Volatile
    private var pending: BrowsePreset? = null

    fun offer(preset: BrowsePreset) {
        pending = preset
    }

    fun consumeFor(kind: BrowseKind): BrowsePreset? {
        val current = pending
        if (current != null && current.kind == kind) {
            pending = null
            return current
        }
        return null
    }
}

data class BrowseUiState(
    val kind: BrowseKind,
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val titles: List<Title> = emptyList(),
    val genres: List<String> = emptyList(),
    /** Primary selected genre (kept in sync with [selectedGenres] for legacy callers/tests). */
    val selectedGenre: String? = null,
    /** Multi-select genres! */
    val selectedGenres: Set<String> = emptySet(),
    val genreMatchMode: GenreMatchMode = GenreMatchMode.ANY,
    val sort: BrowseSort = BrowseSort.POPULAR,
    val availableSorts: List<BrowseSort> = BrowseSort.entries,
    val hasMore: Boolean = true,
    /** TMDB is the catalogue source (key saved). */
    val tmdbActive: Boolean = false,
    /** Movies tab only: no key saved — show the "add your key" note. */
    val showTmdbPrompt: Boolean = false,
    /** Industry chips (Bollywood, Tollywood, Mollywood, South Indian, Hollywood, etc.). */
    val industries: List<Industry> = emptyList(),
    /** Primary selected industry (kept in sync with [selectedIndustries]). */
    val selectedIndustry: Industry? = null,
    /** Multi-select regional/global industries! */
    val selectedIndustries: Set<Industry> = emptySet(),
    /** Release year / decade filter. */
    val yearFilter: BrowseYearFilter = BrowseYearFilter.ALL,
    /** Minimum score filter. */
    val ratingFilter: BrowseRatingFilter = BrowseRatingFilter.ANY,
    /** The shared playability filter, persisted. */
    val playabilityFilter: PlayabilityFilter = PlayabilityFilter.ALL,
    /** Non-null when something failed; safe to show directly to the user. */
    val errorMessage: String? = null
) {
    /** Count of active user-chosen filters (excluding default Popular sort). */
    val activeFilterCount: Int
        get() = selectedIndustries.size +
            selectedGenres.size +
            (if (yearFilter != BrowseYearFilter.ALL) 1 else 0) +
            (if (ratingFilter != BrowseRatingFilter.ANY) 1 else 0) +
            (if (playabilityFilter != PlayabilityFilter.ALL) 1 else 0)

    val hasAnyActiveFilter: Boolean
        get() = activeFilterCount > 0 || selectedGenre != null || selectedIndustry != null

    /** Concise human-readable summary of active multi-filters. */
    val activeFilterSummary: String?
        get() {
            val parts = mutableListOf<String>()
            if (selectedIndustries.isNotEmpty()) {
                parts.add(selectedIndustries.joinToString(" + ") { it.label })
            }
            if (selectedGenres.isNotEmpty()) {
                val sep = if (genreMatchMode == GenreMatchMode.ALL && selectedGenres.size > 1) " & " else " / "
                parts.add(selectedGenres.joinToString(sep))
            }
            if (yearFilter != BrowseYearFilter.ALL) {
                parts.add(yearFilter.label)
            }
            if (ratingFilter != BrowseRatingFilter.ANY) {
                parts.add(ratingFilter.label)
            }
            return parts.takeIf { it.isNotEmpty() }?.joinToString("  •  ")
        }

    /** The grid's titles: [titles] with playability + client-side refinements applied. */
    val visibleTitles: List<Title>
        get() = applyAdvancedBrowseFilter(
            titles = titles.filteredBy(playabilityFilter),
            selectedGenres = if (kind == BrowseKind.SHOWS && !tmdbActive) selectedGenres else emptySet(),
            genreMatchMode = genreMatchMode,
            selectedIndustries = emptySet(),
            yearFilter = yearFilter,
            ratingFilter = ratingFilter,
            sort = null
        )

    /** True when loading finished and the grid is empty. */
    val isEmpty: Boolean
        get() = !isLoading && visibleTitles.isEmpty()
}

class BrowseViewModel(
    private val kind: BrowseKind,
    private val tmdbKeys: TmdbKeyRepository? = null,
    private val uiPrefs: UiPreferencesRepository? = null
) : ViewModel() {

    /** Movies and Shows: is TMDB the active catalogue source right now? */
    private var tmdbActive: Boolean =
        (kind == BrowseKind.MOVIES || kind == BrowseKind.SHOWS) && tmdbKeys?.hasKey() == true

    private val _uiState = MutableStateFlow(buildInitialState())
    val uiState: StateFlow<BrowseUiState> = _uiState.asStateFlow()

    private fun buildInitialState(): BrowseUiState {
        val savedGenre = uiPrefs?.genreFor(genreScope)
        val initialGenres = if (!savedGenre.isNullOrBlank()) setOf(savedGenre) else emptySet()
        return BrowseUiState(
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
            availableSorts = if (kind == BrowseKind.MOVIES && !tmdbActive) {
                listOf(BrowseSort.POPULAR, BrowseSort.NEWEST, BrowseSort.A_Z)
            } else {
                BrowseSort.entries
            },
            tmdbActive = tmdbActive,
            showTmdbPrompt = kind == BrowseKind.MOVIES && !tmdbActive,
            industries = if ((kind == BrowseKind.MOVIES || kind == BrowseKind.SHOWS) && tmdbActive) {
                INDUSTRIES
            } else {
                emptyList()
            },
            playabilityFilter = uiPrefs?.playabilityFilter() ?: PlayabilityFilter.ALL,
            selectedGenre = savedGenre,
            selectedGenres = initialGenres
        ).withSourcePresentation()
    }

    private val genreScope: String
        get() = when (kind) {
            BrowseKind.MOVIES -> "movies"
            BrowseKind.SHOWS -> "shows"
            BrowseKind.ANIME -> "anime"
        }

    private var nextPage = 1
    private var filterChainLoads = 0

    private var showsCache: List<Title> = emptyList()
    private var showsPage = 0
    private var showsArchivePage = 1
    private var showsHasMore = true
    private var showsArchiveHasMore = true

    private var youtubeExtras: List<Title> = emptyList()

    init {
        applyPendingPresetIfAny(reload = false)
        load()
    }

    /** Consumes any pending "See All" preset from Home when opening this tab. */
    fun consumePresetIfPresent() {
        if (applyPendingPresetIfAny(reload = true)) return
    }

    private fun applyPendingPresetIfAny(reload: Boolean): Boolean {
        val preset = BrowsePresetStore.consumeFor(kind) ?: return false
        _uiState.update { state ->
            val nextIndustries = preset.industry?.let { setOf(it) } ?: emptySet()
            val nextGenres = preset.genre?.let { setOf(it) } ?: emptySet()
            uiPrefs?.setGenreFor(genreScope, preset.genre)
            state.copy(
                selectedIndustry = preset.industry,
                selectedIndustries = nextIndustries,
                selectedGenre = preset.genre,
                selectedGenres = nextGenres,
                sort = preset.sort ?: state.sort,
                yearFilter = BrowseYearFilter.ALL,
                ratingFilter = BrowseRatingFilter.ANY
            ).withSourcePresentation()
        }
        if (reload) load()
        return true
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
     * TMDB is active for Movies & Shows when a key exists — except when
     * Movies explicitly switches into the Archive public-domain view under
     * [PlayabilityFilter.PLAYABLE_ONLY] (preserving BrowsePlayabilityFilterTest).
     */
    private fun useTmdb(state: BrowseUiState): Boolean {
        if (!tmdbActive) return false
        if (kind == BrowseKind.MOVIES && state.playabilityFilter == PlayabilityFilter.PLAYABLE_ONLY) {
            return false
        }
        return kind == BrowseKind.MOVIES || kind == BrowseKind.SHOWS
    }

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

    fun refreshKeyState() {
        if (applyPendingPresetIfAny(reload = true)) return
        val persisted = uiPrefs?.playabilityFilter()
        if (persisted != null && persisted != _uiState.value.playabilityFilter) {
            applyPlayabilityFilter(persisted)
        }
        val activeNow = (kind == BrowseKind.MOVIES || kind == BrowseKind.SHOWS) &&
            tmdbKeys?.hasKey() == true
        if (activeNow == tmdbActive) return
        tmdbActive = activeNow
        _uiState.update {
            buildInitialState().copy(
                isLoading = it.isLoading,
                titles = it.titles,
                sort = it.sort
            )
        }
        load()
    }

    fun setPlayabilityFilter(filter: PlayabilityFilter) {
        if (_uiState.value.playabilityFilter == filter) return
        uiPrefs?.setPlayabilityFilter(filter)
        applyPlayabilityFilter(filter)
    }

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
                it.copy(
                    selectedGenre = null,
                    selectedGenres = emptySet(),
                    selectedIndustry = null,
                    selectedIndustries = emptySet()
                ).withSourcePresentation()
            }
            load()
            return
        }
        val state = _uiState.value
        if (!state.isLoading && state.visibleTitles.size < PAGE_SIZE && state.hasMore) {
            loadMore()
        }
    }

    /** Single-genre setter preserved for existing callers & unit tests. */
    fun selectGenre(genre: String?) {
        val clean = genre?.takeIf { it.isNotBlank() && !it.equals("All", ignoreCase = true) }
        val nextSet = if (clean == null) emptySet() else setOf(clean)
        if (_uiState.value.selectedGenre == clean && _uiState.value.selectedGenres == nextSet) return
        uiPrefs?.setGenreFor(genreScope, clean)
        _uiState.update { it.copy(selectedGenre = clean, selectedGenres = nextSet) }
        if (kind == BrowseKind.SHOWS && !useTmdb(_uiState.value) && showsCache.isNotEmpty()) {
            publishShows(errorMessage = _uiState.value.errorMessage)
        } else {
            load()
        }
    }

    /** Multi-select genre toggle: lets users combine Action + Thriller, Drama + Romance, etc.! */
    fun toggleGenre(genre: String) {
        val current = _uiState.value.selectedGenres
        val next = if (genre in current) current - genre else current + genre
        val primary = next.firstOrNull()
        uiPrefs?.setGenreFor(genreScope, primary)
        _uiState.update {
            it.copy(
                selectedGenre = primary,
                selectedGenres = next
            )
        }
        if (kind == BrowseKind.SHOWS && !useTmdb(_uiState.value) && showsCache.isNotEmpty()) {
            publishShows(errorMessage = _uiState.value.errorMessage)
        } else {
            load()
        }
    }

    fun setGenreMatchMode(mode: GenreMatchMode) {
        if (_uiState.value.genreMatchMode == mode) return
        _uiState.update { it.copy(genreMatchMode = mode) }
        if (_uiState.value.selectedGenres.size > 1) {
            load()
        }
    }

    /** Single-industry setter preserved for existing callers & unit tests. */
    fun selectIndustry(industry: Industry?) {
        val nextSet = if (industry == null) emptySet() else setOf(industry)
        if (_uiState.value.selectedIndustry == industry && _uiState.value.selectedIndustries == nextSet) return
        _uiState.update {
            it.copy(
                selectedIndustry = industry,
                selectedIndustries = nextSet
            )
        }
        load()
    }

    /** Multi-select industry toggle: combine Bollywood + Tollywood + Mollywood + Hollywood, etc.! */
    fun toggleIndustry(industry: Industry) {
        val current = _uiState.value.selectedIndustries
        val next = if (industry in current) current - industry else current + industry
        _uiState.update {
            it.copy(
                selectedIndustry = next.firstOrNull(),
                selectedIndustries = next
            )
        }
        load()
    }

    fun selectYearFilter(yearFilter: BrowseYearFilter) {
        if (_uiState.value.yearFilter == yearFilter) return
        _uiState.update { it.copy(yearFilter = yearFilter) }
        if (useTmdb(_uiState.value)) {
            load()
        }
    }

    fun selectRatingFilter(ratingFilter: BrowseRatingFilter) {
        if (_uiState.value.ratingFilter == ratingFilter) return
        _uiState.update { it.copy(ratingFilter = ratingFilter) }
        if (useTmdb(_uiState.value)) {
            load()
        }
    }

    /** Clears ALL filters back to clean global discovery. */
    fun clearAllFilters() {
        uiPrefs?.setGenreFor(genreScope, null)
        uiPrefs?.setPlayabilityFilter(PlayabilityFilter.ALL)
        _uiState.update {
            it.copy(
                selectedGenre = null,
                selectedGenres = emptySet(),
                genreMatchMode = GenreMatchMode.ANY,
                selectedIndustry = null,
                selectedIndustries = emptySet(),
                yearFilter = BrowseYearFilter.ALL,
                ratingFilter = BrowseRatingFilter.ANY,
                playabilityFilter = PlayabilityFilter.ALL
            ).withSourcePresentation()
        }
        load()
    }

    private fun BrowseUiState.withSourcePresentation(): BrowseUiState {
        if (kind == BrowseKind.ANIME) return this
        val tmdbSource = useTmdb(this)
        return copy(
            genres = when {
                kind == BrowseKind.MOVIES && tmdbSource -> TmdbApi.MOVIE_GENRES.values.toList()
                kind == BrowseKind.MOVIES -> ArchiveApi.MOVIE_GENRES.keys.toList()
                kind == BrowseKind.SHOWS && tmdbSource -> TmdbApi.TV_GENRES.values.toList()
                else -> SHOW_GENRES
            },
            industries = if (tmdbSource) INDUSTRIES else emptyList(),
            availableSorts = if (kind == BrowseKind.MOVIES && !tmdbSource) {
                listOf(BrowseSort.POPULAR, BrowseSort.NEWEST, BrowseSort.A_Z)
            } else {
                BrowseSort.entries
            },
            sort = if (kind == BrowseKind.MOVIES && !tmdbSource && sort == BrowseSort.TOP_RATED) {
                BrowseSort.POPULAR
            } else {
                sort
            }
        )
    }

    fun selectSort(sort: BrowseSort) {
        if (_uiState.value.sort == sort) return
        _uiState.update { it.copy(sort = sort) }
        if (kind == BrowseKind.SHOWS && !useTmdb(_uiState.value) && showsCache.isNotEmpty()) {
            publishShows(errorMessage = _uiState.value.errorMessage)
        } else {
            load()
        }
    }

    fun onErrorShown() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    // ------------------------------------------------------------------
    // Server-paged tabs (Movies / Shows with TMDB / Anime)
    // ------------------------------------------------------------------

    private fun buildTmdbLanguageQuery(state: BrowseUiState): String? {
        if (state.selectedIndustries.isEmpty()) return state.selectedIndustry?.languageCode
        val codes = state.selectedIndustries.flatMap { it.languageCodes }.distinct()
        return codes.takeIf { it.isNotEmpty() }?.joinToString("|")
    }

    private fun buildMovieGenresQuery(state: BrowseUiState): String? {
        val genres = state.selectedGenres.ifEmpty {
            listOfNotNull(state.selectedGenre).toSet()
        }
        val ids = genres.mapNotNull { TmdbApi.genreIdFor(it) }.distinct()
        if (ids.isEmpty()) return null
        val sep = if (state.genreMatchMode == GenreMatchMode.ALL) "," else "|"
        return ids.joinToString(sep)
    }

    private fun buildTvGenresQuery(state: BrowseUiState): String? {
        val genres = state.selectedGenres.ifEmpty {
            listOfNotNull(state.selectedGenre).toSet()
        }
        val ids = genres.mapNotNull { TmdbApi.tvGenreIdFor(it) }.distinct()
        if (ids.isEmpty()) return null
        val sep = if (state.genreMatchMode == GenreMatchMode.ALL) "," else "|"
        return ids.joinToString(sep)
    }

    private fun isAdvancedFilterActive(state: BrowseUiState): Boolean =
        state.selectedIndustries.size > 1 ||
            (state.selectedIndustry?.languageCode?.contains("|") == true) ||
            state.selectedGenres.size > 1 ||
            state.yearFilter != BrowseYearFilter.ALL ||
            state.ratingFilter != BrowseRatingFilter.ANY

    private suspend fun loadServerPage(reset: Boolean) {
        val state = _uiState.value
        if (!reset) _uiState.update { it.copy(isLoadingMore = true) }
        val page = if (reset) 1 else nextPage
        val tmdbSort = when (state.sort) {
            BrowseSort.POPULAR -> TmdbApi.MovieSort.POPULAR
            BrowseSort.TOP_RATED -> TmdbApi.MovieSort.TOP_RATED
            BrowseSort.NEWEST -> TmdbApi.MovieSort.NEWEST
            BrowseSort.A_Z -> TmdbApi.MovieSort.A_Z
        }

        val result = runCatching {
            when (kind) {
                BrowseKind.MOVIES -> {
                    if (useTmdb(state)) {
                        val apiKey = tmdbKeys?.getKey()
                            ?: throw IllegalStateException("No TMDB key")
                        val langQuery = buildTmdbLanguageQuery(state)
                        val tmdbResult = runCatching {
                            if (isAdvancedFilterActive(state)) {
                                TmdbApi.discoverMoviesAdvanced(
                                    apiKey = apiKey,
                                    languageQuery = langQuery,
                                    genresQuery = buildMovieGenresQuery(state),
                                    yearFilter = state.yearFilter,
                                    minRating = state.ratingFilter.minScore,
                                    sort = tmdbSort,
                                    page = page
                                )
                            } else {
                                TmdbApi.discoverMovies(
                                    apiKey = apiKey,
                                    language = langQuery,
                                    genreId = TmdbApi.genreIdFor(state.selectedGenre),
                                    sort = tmdbSort,
                                    page = page
                                )
                            }
                        }.getOrElse { err ->
                            // When no regional filter is set, Cinemeta can rescue a transient TMDB outage
                            if (langQuery == null && !isAdvancedFilterActive(state)) {
                                val fallback = runCatching {
                                    CinemetaApi.fetchTrendingMovies(skip = (page - 1) * PAGE_SIZE)
                                }.getOrNull()
                                if (!fallback.isNullOrEmpty()) fallback else throw err
                            } else {
                                throw err
                            }
                        }
                        tmdbResult
                    } else {
                        // Keyless / Archive-only presentation: go straight to ArchiveApi
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

                BrowseKind.ANIME -> {
                    val raw = AniListApi.fetchAnimePage(
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
                    if (state.selectedGenres.size > 1) {
                        applyAdvancedBrowseFilter(
                            titles = raw,
                            selectedGenres = state.selectedGenres,
                            genreMatchMode = state.genreMatchMode
                        )
                    } else {
                        raw
                    }
                }

                BrowseKind.SHOWS -> {
                    val apiKey = tmdbKeys?.getKey()
                        ?: throw IllegalStateException("No TMDB key")
                    val langQuery = buildTmdbLanguageQuery(state)
                    if (isAdvancedFilterActive(state) || langQuery != null) {
                        TmdbApi.discoverTvAdvanced(
                            apiKey = apiKey,
                            languageQuery = langQuery,
                            genresQuery = buildTvGenresQuery(state),
                            yearFilter = state.yearFilter,
                            minRating = state.ratingFilter.minScore,
                            sort = tmdbSort,
                            page = page
                        )
                    } else {
                        TmdbApi.discoverTv(
                            apiKey = apiKey,
                            genreId = TmdbApi.tvGenreIdFor(state.selectedGenre),
                            sort = tmdbSort,
                            page = page
                        )
                    }
                }
            }
        }

        var chainAnotherPage = false
        result.fold(
            onSuccess = { pageTitles ->
                nextPage = page + 1
                val expectedPageSize = if (useTmdb(state)) TMDB_PAGE_SIZE else PAGE_SIZE
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        titles = if (reset) {
                            if (kind == BrowseKind.ANIME &&
                                state.selectedGenre == null &&
                                state.selectedGenres.isEmpty()
                            ) {
                                youtubeExtras + pageTitles
                            } else {
                                pageTitles
                            }
                        } else {
                            (it.titles + pageTitles).distinctBy { t -> t.stableKey }
                        },
                        hasMore = pageTitles.size >= expectedPageSize,
                        errorMessage = null
                    )
                }
                val now = _uiState.value
                chainAnotherPage =
                    (now.playabilityFilter != PlayabilityFilter.ALL ||
                        now.yearFilter != BrowseYearFilter.ALL ||
                        now.ratingFilter != BrowseRatingFilter.ANY) &&
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
                            "Couldn't load content."
                        } else {
                            "Couldn't load more."
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
    // Shows tab without TMDB key (client-side filter/sort over cached pages)
    // ------------------------------------------------------------------

    private suspend fun loadShowsPages() {
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
                        "Couldn't load content."
                    } else {
                        "Couldn't load more."
                    }
                )
            }
            return
        }

        tvmazeResult.onSuccess { pageTitles ->
            if (showsHasMore) {
                showsPage += 1
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

        val partialFailure = tvmazeResult.isFailure || archiveResult.isFailure
        publishShows(
            errorMessage = if (partialFailure) {
                "Some sources couldn't load. Check your connection."
            } else {
                null
            }
        )

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

    private fun publishShows(errorMessage: String?) {
        val state = _uiState.value
        val activeGenres = state.selectedGenres.ifEmpty {
            listOfNotNull(state.selectedGenre).toSet()
        }
        val filtered = applyAdvancedBrowseFilter(
            titles = showsCache,
            selectedGenres = activeGenres,
            genreMatchMode = state.genreMatchMode,
            sort = state.sort
        )
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
        const val MAX_FILTER_CHAIN_LOADS = 10
        const val TMDB_PAGE_SIZE = 20

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
