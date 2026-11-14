/*
 * Watchly — Home screen state holder.
 *
 * ASSUMED DEPENDENCIES:
 *  - androidx.lifecycle:lifecycle-viewmodel-ktx (AndroidX ViewModel +
 *    viewModelScope)
 *  - org.jetbrains.kotlinx:kotlinx-coroutines-android
 * Get it in Compose with: val viewModel: HomeViewModel = viewModel()
 * (requires androidx.lifecycle:lifecycle-viewmodel-compose).
 *
 * Behaviour: loads TVmaze shows + AniList anime + Internet Archive
 * public-domain films in parallel. Each source fails independently — a
 * failure empties only its own row and surfaces a single user-facing
 * error message. This class NEVER crashes the UI: every exception is
 * caught and folded into HomeUiState.
 *
 * NOTE (v1 scope): the Movies row is public-domain films from the
 * Internet Archive. When the user has added their TMDB key, Home also
 * shows one row per film industry (Bollywood, Tollywood, Hollywood,
 * Korean…) from TMDB discover — TMDB's catalogue updates itself
 * daily, so the rows are always current. Without a key the industry
 * rows simply don't appear.
 */
package com.watchly.app.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.watchly.app.data.browse.INDUSTRIES
import com.watchly.app.data.browse.IndustryRow
import com.watchly.app.data.model.Playability
import com.watchly.app.data.model.PlayabilityFilter
import com.watchly.app.data.model.Title
import com.watchly.app.data.model.filteredBy
import com.watchly.app.data.model.playability
import com.watchly.app.data.remote.AniListApi
import com.watchly.app.data.remote.ArchiveApi
import com.watchly.app.data.remote.TmdbApi
import com.watchly.app.data.remote.TraktApi
import com.watchly.app.data.remote.TvMazeApi
import com.watchly.app.data.remote.YouTubeApi
import com.watchly.app.data.search.SearchMerger
import com.watchly.app.data.settings.ApiProvider
import com.watchly.app.data.settings.ProviderKeysRepository
import com.watchly.app.data.settings.TmdbKeyRepository
import com.watchly.app.data.settings.UiPreferencesRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One headed row of titles on Home (provider rows, cycle 4). */
data class NamedTitleRow(
    val heading: String,
    val titles: List<Title>
)

/** Immutable UI state for HomeScreen. */
data class HomeUiState(
    val isLoading: Boolean = true,
    val movies: List<Title> = emptyList(),
    val topRatedMovies: List<Title> = emptyList(),
    val shows: List<Title> = emptyList(),
    val topRatedShows: List<Title> = emptyList(),
    val anime: List<Title> = emptyList(),
    /** Per-industry TMDB rows. Empty unless a TMDB key is saved. */
    val industryRows: List<IndustryRow> = emptyList(),
    /**
     * Official-YouTube-channel rows (cycle 4): one per curated
     * channel that loaded. Every title is playable in the official
     * embedded player. Empty when every feed failed.
     */
    val youtubeRows: List<NamedTitleRow> = emptyList(),
    /**
     * Wider Internet Archive rows (cycle 4): Classic TV, Animation
     * & Cartoons, Prelinger — public-domain, playable. Empty when
     * every collection failed.
     */
    val archiveRows: List<NamedTitleRow> = emptyList(),
    /** "Trending on Trakt" row (cycle 4). Null unless a Trakt Client ID is saved and trending loaded. */
    val traktRow: NamedTitleRow? = null,
    /** True when a TMDB key is saved on this phone. */
    val hasTmdbKey: Boolean = false,
    /** The shared playability filter (Phase D), persisted. */
    val playabilityFilter: PlayabilityFilter = PlayabilityFilter.ALL,
    /**
     * Backbone sections whose load FAILED (Phase D): "Movies",
     * "TV shows", "Anime". A failed row renders a retry-able
     * couldn't-load state instead of a dead-end "nothing available".
     */
    val failedSections: Set<String> = emptySet(),
    /** Non-null when something failed; safe to show directly to the user. */
    val errorMessage: String? = null
) {
    // The lists above are the UNFILTERED loads; the screen renders
    // these filtered views so the playability filter applies at
    // read time with no reload (Phase D).
    val visibleMovies: List<Title> get() = movies.filteredBy(playabilityFilter)
    val visibleShows: List<Title> get() = shows.filteredBy(playabilityFilter)
    val visibleAnime: List<Title> get() = anime.filteredBy(playabilityFilter)
    val visibleIndustryRows: List<IndustryRow>
        get() = industryRows.map { it.copy(titles = it.titles.filteredBy(playabilityFilter)) }
            .filter { it.titles.isNotEmpty() }
    val visibleYoutubeRows: List<NamedTitleRow>
        get() = youtubeRows.map { it.copy(titles = it.titles.filteredBy(playabilityFilter)) }
            .filter { it.titles.isNotEmpty() }
    val visibleArchiveRows: List<NamedTitleRow>
        get() = archiveRows.map { it.copy(titles = it.titles.filteredBy(playabilityFilter)) }
            .filter { it.titles.isNotEmpty() }
    val visibleTraktRow: NamedTitleRow?
        get() = traktRow?.copy(titles = traktRow.titles.filteredBy(playabilityFilter))
            ?.takeIf { it.titles.isNotEmpty() }

    /** True when any row holds anything at all (ignoring the filter). */
    val hasAnyContent: Boolean
        get() = movies.isNotEmpty() || shows.isNotEmpty() || anime.isNotEmpty() ||
            youtubeRows.isNotEmpty() || archiveRows.isNotEmpty() ||
            industryRows.isNotEmpty() || traktRow != null

    /** First available title, used as the hero feature. Null while empty. */
    val hero: Title?
        get() = heroCandidates.firstOrNull()

    /**
     * Up to 5 titles with posters, for the rotating hero spotlight.
     * Playable titles come FIRST (Phase D: the hero used to lead
     * with info-only shows/anime because they were concatenated
     * before the playable Archive films) — a stable partition, so
     * each group keeps its own order.
     */
    val heroCandidates: List<Title>
        get() = (
            visibleMovies +
                visibleYoutubeRows.flatMap { it.titles } +
                visibleArchiveRows.flatMap { it.titles } +
                visibleShows +
                visibleAnime
            )
            .sortedBy {
                if (it.playability() == Playability.PLAYABLE) 0 else 1
            }
            .filter { it.posterUrl != null }
            .take(5)

    /** True when loading finished and every (visible) row is empty. */
    val isEmpty: Boolean
        get() = !isLoading && visibleMovies.isEmpty() && visibleShows.isEmpty() &&
            visibleAnime.isEmpty() && visibleYoutubeRows.isEmpty() &&
            visibleArchiveRows.isEmpty() && visibleIndustryRows.isEmpty() &&
            visibleTraktRow == null
}

class HomeViewModel(
    private val tmdbKeys: TmdbKeyRepository? = null,
    /** Cycle 4: Trakt Client ID source for the trending row. */
    private val providerKeys: ProviderKeysRepository? = null,
    /** Phase D: the persisted playability filter. */
    private val uiPrefs: UiPreferencesRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        HomeUiState(
            playabilityFilter = uiPrefs?.playabilityFilter()
                ?: PlayabilityFilter.ALL
        )
    )
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    /** Picks a playability filter (Phase D); persisted + shared. */
    fun setPlayabilityFilter(filter: PlayabilityFilter) {
        if (_uiState.value.playabilityFilter == filter) return
        uiPrefs?.setPlayabilityFilter(filter)
        _uiState.update { it.copy(playabilityFilter = filter) }
    }

    /**
     * Called when Home becomes visible: if the TMDB key situation
     * changed since the last load (key just added or removed on the
     * TMDB screen), reload so the industry rows appear/disappear.
     * Also picks up a playability filter changed elsewhere (it is
     * read-time, so no reload is needed for it).
     */
    fun refreshKeyState() {
        val persisted = uiPrefs?.playabilityFilter()
        if (persisted != null && persisted != _uiState.value.playabilityFilter) {
            _uiState.update { it.copy(playabilityFilter = persisted) }
        }
        val hasKeyNow = tmdbKeys?.hasKey() == true
        val hasTraktNow = runCatching {
            providerKeys?.hasKey(ApiProvider.TRAKT) == true
        }.getOrDefault(false)
        val traktRowShown = _uiState.value.traktRow != null
        if (hasKeyNow != _uiState.value.hasTmdbKey ||
            hasTraktNow != traktRowShown
        ) {
            load()
        }
    }

    /** (Re)loads all home rows. Safe to call from a Retry button. */
    fun load() {
        _uiState.update {
            it.copy(isLoading = true, errorMessage = null, failedSections = emptySet())
        }
        viewModelScope.launch {
            val apiKey = tmdbKeys?.getKey()
            val hasKey = apiKey != null
            _uiState.update { it.copy(hasTmdbKey = hasKey) }

            // Fetch modern TMDB trending + top rated sources in parallel when key is available,
            // with resilient fallback to Archive / TVmaze when keyless or if TMDB call fails.
            val moviesDeferred = async {
                runCatching {
                    val tmdb = if (apiKey != null) {
                        runCatching { TmdbApi.fetchTrendingMovies(apiKey) }.getOrNull()
                    } else null
                    if (!tmdb.isNullOrEmpty()) tmdb else ArchiveApi.fetchMoviesPage(page = 1, perPage = 20)
                }
            }
            val topRatedMoviesDeferred = async {
                runCatching {
                    if (apiKey != null) {
                        runCatching { TmdbApi.fetchTopRatedMovies(apiKey) }.getOrDefault(emptyList())
                    } else {
                        emptyList()
                    }
                }
            }
            val showsDeferred = async {
                runCatching {
                    val tmdb = if (apiKey != null) {
                        runCatching { TmdbApi.fetchTrendingTv(apiKey) }.getOrNull()
                    } else null
                    if (!tmdb.isNullOrEmpty()) tmdb else TvMazeApi.fetchTrendingShows()
                }
            }
            val topRatedShowsDeferred = async {
                runCatching {
                    if (apiKey != null) {
                        runCatching { TmdbApi.fetchTopRatedTv(apiKey) }.getOrDefault(emptyList())
                    } else {
                        emptyList()
                    }
                }
            }
            val animeDeferred = async { runCatching { AniListApi.fetchTrendingAnime() } }

            val moviesResult = moviesDeferred.await()
            val topRatedMoviesResult = topRatedMoviesDeferred.await()
            val showsResult = showsDeferred.await()
            val topRatedShowsResult = topRatedShowsDeferred.await()
            val animeResult = animeDeferred.await()

            val failures = listOfNotNull(
                moviesResult.exceptionOrNull()?.let { "Movies" },
                showsResult.exceptionOrNull()?.let { "TV shows" },
                animeResult.exceptionOrNull()?.let { "Anime" }
            )

            _uiState.update {
                it.copy(
                    isLoading = false,
                    movies = moviesResult.getOrDefault(emptyList()),
                    topRatedMovies = topRatedMoviesResult.getOrDefault(emptyList()),
                    shows = showsResult.getOrDefault(emptyList()),
                    topRatedShows = topRatedShowsResult.getOrDefault(emptyList()),
                    anime = animeResult.getOrDefault(emptyList()),
                    failedSections = failures.toSet(),
                    errorMessage = when {
                        failures.size == 3 ->
                            "Couldn't load content. Check your connection and try again."
                        failures.size == 1 ->
                            "Couldn't load ${failures.first()}. Showing what we could fetch."
                        failures.size == 2 ->
                            "Couldn't load ${failures.joinToString(" and ")}. Showing what we could fetch."
                        else -> null
                    }
                )
            }

            // Cycle-4 rows: official YouTube channels + the wider
            // Archive collections. Fully fail-soft and independent of
            // the three main rows: a dead feed or collection just
            // doesn't appear, and none of it touches the error banner
            // (that stays reserved for the catalogue backbone).
            val channels = runCatching { YouTubeApi.CHANNELS }
                .getOrDefault(emptyList())
            val youtubeRows = channels.map { channel ->
                async {
                    runCatching { YouTubeApi.fetchChannelVideos(channel) }
                        .getOrDefault(emptyList())
                }
            }.mapIndexed { index, deferred ->
                NamedTitleRow(
                    heading = "${channels[index].displayName} on YouTube",
                    titles = deferred.await()
                )
            }.filter { it.titles.isNotEmpty() }
            _uiState.update { it.copy(youtubeRows = youtubeRows) }

            val extraCollections = listOf(
                ArchiveApi.CLASSIC_TV,
                ArchiveApi.ANIMATION_AND_CARTOONS,
                ArchiveApi.PRELINGER
            )
            val archiveRows = extraCollections.map { collection ->
                async {
                    runCatching {
                        ArchiveApi.fetchCollectionPage(
                            collection = collection,
                            page = 1,
                            perPage = 20
                        )
                    }.getOrDefault(emptyList())
                }
            }.mapIndexed { index, deferred ->
                NamedTitleRow(
                    heading = extraCollections[index].displayName,
                    titles = deferred.await()
                )
            }.filter { it.titles.isNotEmpty() }
            _uiState.update { it.copy(archiveRows = archiveRows) }

            // Trending on Trakt (cycle 4): needs the user's Trakt
            // Client ID; without one the row stays hidden. Trending
            // items carry IMDb/TMDB ids, so they run through the
            // canonical merge (deduped, richest record wins) exactly
            // like search results.
            val traktClientId = runCatching {
                providerKeys?.getKey(ApiProvider.TRAKT)
            }.getOrNull()
            val traktRow = if (traktClientId == null) {
                null
            } else {
                runCatching {
                    val trending = TraktApi.fetchTrending(traktClientId)
                    SearchMerger.mergeTitles(trending).take(TRAKT_ROW_SIZE)
                }.getOrDefault(emptyList()).takeIf { it.isNotEmpty() }?.let { merged ->
                    NamedTitleRow(heading = "Trending on Trakt", titles = merged)
                }
            }
            _uiState.update { it.copy(traktRow = traktRow) }

            // Industry rows need the user's TMDB key; without one they
            // stay hidden (no error, no empty shells). Each industry
            // fails independently — a failed one just doesn't appear.
            _uiState.update { it.copy(hasTmdbKey = apiKey != null) }
            if (apiKey != null) {
                val rows = INDUSTRIES.map { industry ->
                    async {
                        runCatching {
                            TmdbApi.discoverMovies(
                                apiKey = apiKey,
                                language = industry.languageCode,
                                page = 1
                            ).take(INDUSTRY_ROW_SIZE)
                        }.getOrDefault(emptyList())
                    }
                }.mapIndexed { index, deferred ->
                    IndustryRow(INDUSTRIES[index], deferred.await())
                }.filter { it.titles.isNotEmpty() }
                _uiState.update { it.copy(industryRows = rows) }
            } else {
                _uiState.update { it.copy(industryRows = emptyList()) }
            }
        }
    }

    /** Clears the transient partial-failure banner once the user dismisses it. */
    fun onErrorShown() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    companion object {
        /** Titles per industry row on Home. */
        const val INDUSTRY_ROW_SIZE = 15

        /** Titles in the Trending on Trakt row. */
        const val TRAKT_ROW_SIZE = 20
    }
}

/** Factory so the nav destination can create the ViewModel with a Context. */
class HomeViewModelFactory(
    private val context: Context
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            return HomeViewModel(
                TmdbKeyRepository(context.applicationContext),
                ProviderKeysRepository(context.applicationContext),
                UiPreferencesRepository(context.applicationContext)
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: $modelClass")
    }
}
