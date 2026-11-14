/*
 * Watchly — Home screen state holder.
 *
 * Modern MovieBox-grade home state:
 * - Cinemeta API for keyless global trending blockbusters & top rated movies/shows
 * - TMDB API integration when user key is provided
 * - TVmaze and AniList for shows and anime
 * - Resilient silent fallbacks and zero dead rows
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
import com.watchly.app.data.remote.CinemetaApi
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

/** One headed row of titles on Home. */
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
    val youtubeRows: List<NamedTitleRow> = emptyList(),
    val archiveRows: List<NamedTitleRow> = emptyList(),
    val traktRow: NamedTitleRow? = null,
    val hasTmdbKey: Boolean = false,
    val playabilityFilter: PlayabilityFilter = PlayabilityFilter.ALL,
    val failedSections: Set<String> = emptySet(),
    val errorMessage: String? = null
) {
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

    val hasAnyContent: Boolean
        get() = movies.isNotEmpty() || shows.isNotEmpty() || anime.isNotEmpty() ||
            youtubeRows.isNotEmpty() || archiveRows.isNotEmpty() ||
            industryRows.isNotEmpty() || traktRow != null

    val hero: Title?
        get() = heroCandidates.firstOrNull()

    /**
     * Modern blockbuster titles with posters for the rotating hero banner.
     */
    val heroCandidates: List<Title>
        get() = (visibleMovies + visibleShows + visibleAnime)
            .filter { it.posterUrl != null }
            .take(6)

    val isEmpty: Boolean
        get() = !isLoading && visibleMovies.isEmpty() && visibleShows.isEmpty() &&
            visibleAnime.isEmpty() && visibleYoutubeRows.isEmpty() &&
            visibleArchiveRows.isEmpty() && visibleIndustryRows.isEmpty() &&
            visibleTraktRow == null
}

class HomeViewModel(
    private val tmdbKeys: TmdbKeyRepository? = null,
    private val providerKeys: ProviderKeysRepository? = null,
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

    fun setPlayabilityFilter(filter: PlayabilityFilter) {
        if (_uiState.value.playabilityFilter == filter) return
        uiPrefs?.setPlayabilityFilter(filter)
        _uiState.update { it.copy(playabilityFilter = filter) }
    }

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

    fun load() {
        _uiState.update {
            it.copy(isLoading = true, errorMessage = null, failedSections = emptySet())
        }
        viewModelScope.launch {
            val apiKey = tmdbKeys?.getKey()
            val hasKey = apiKey != null
            _uiState.update { it.copy(hasTmdbKey = hasKey) }

            // 1. Trending Movies: Cinemeta modern blockbusters + TMDB discover
            val moviesDeferred = async {
                runCatching {
                    val tmdb = if (apiKey != null) {
                        runCatching { TmdbApi.fetchTrendingMovies(apiKey) }.getOrNull()
                    } else null
                    if (!tmdb.isNullOrEmpty()) tmdb else {
                        val cinemeta = runCatching { CinemetaApi.fetchTrendingMovies() }.getOrNull()
                        if (!cinemeta.isNullOrEmpty()) cinemeta else ArchiveApi.fetchMoviesPage(page = 1, perPage = 20)
                    }
                }
            }

            // 2. Top Rated Movies: Cinemeta top rated
            val topRatedMoviesDeferred = async {
                runCatching {
                    val tmdb = if (apiKey != null) {
                        runCatching { TmdbApi.fetchTopRatedMovies(apiKey) }.getOrNull()
                    } else null
                    if (!tmdb.isNullOrEmpty()) tmdb else CinemetaApi.fetchTopRatedMovies()
                }
            }

            // 3. Trending TV Shows: Cinemeta + TVmaze
            val showsDeferred = async {
                runCatching {
                    val tmdb = if (apiKey != null) {
                        runCatching { TmdbApi.fetchTrendingTv(apiKey) }.getOrNull()
                    } else null
                    if (!tmdb.isNullOrEmpty()) tmdb else {
                        val cinemeta = runCatching { CinemetaApi.fetchTrendingShows() }.getOrNull()
                        if (!cinemeta.isNullOrEmpty()) cinemeta else TvMazeApi.fetchTrendingShows()
                    }
                }
            }

            // 4. Top Rated TV Shows
            val topRatedShowsDeferred = async {
                runCatching {
                    val tmdb = if (apiKey != null) {
                        runCatching { TmdbApi.fetchTopRatedTv(apiKey) }.getOrNull()
                    } else null
                    if (!tmdb.isNullOrEmpty()) tmdb else CinemetaApi.fetchTopRatedShows()
                }
            }

            // 5. Trending Anime
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
                    errorMessage = null
                )
            }

            // YouTube Channels (curated)
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

            // Trakt Trending
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

            // Industry rows (if TMDB key present)
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

    fun onErrorShown() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    companion object {
        const val INDUSTRY_ROW_SIZE = 15
        const val TRAKT_ROW_SIZE = 20
    }
}

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
