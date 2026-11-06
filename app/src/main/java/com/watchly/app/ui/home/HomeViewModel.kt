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
import com.watchly.app.data.model.Title
import com.watchly.app.data.remote.AniListApi
import com.watchly.app.data.remote.ArchiveApi
import com.watchly.app.data.remote.TmdbApi
import com.watchly.app.data.remote.TvMazeApi
import com.watchly.app.data.settings.TmdbKeyRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Immutable UI state for HomeScreen. */
data class HomeUiState(
    val isLoading: Boolean = true,
    val movies: List<Title> = emptyList(),
    val shows: List<Title> = emptyList(),
    val anime: List<Title> = emptyList(),
    /** Per-industry TMDB rows. Empty unless a TMDB key is saved. */
    val industryRows: List<IndustryRow> = emptyList(),
    /** True when a TMDB key is saved on this phone. */
    val hasTmdbKey: Boolean = false,
    /** Non-null when something failed; safe to show directly to the user. */
    val errorMessage: String? = null
) {
    /** First available title, used as the hero feature. Null while empty. */
    val hero: Title?
        get() = heroCandidates.firstOrNull()

    /** Up to 5 titles with posters, for the rotating hero spotlight. */
    val heroCandidates: List<Title>
        get() = (shows + anime + movies)
            .filter { it.posterUrl != null }
            .take(5)

    /** True when loading finished and every row is empty. */
    val isEmpty: Boolean
        get() = !isLoading && movies.isEmpty() && shows.isEmpty() && anime.isEmpty()
}

class HomeViewModel(
    private val tmdbKeys: TmdbKeyRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    /**
     * Called when Home becomes visible: if the TMDB key situation
     * changed since the last load (key just added or removed on the
     * TMDB screen), reload so the industry rows appear/disappear.
     */
    fun refreshKeyState() {
        val hasKeyNow = tmdbKeys?.hasKey() == true
        if (hasKeyNow != _uiState.value.hasTmdbKey) {
            load()
        }
    }

    /** (Re)loads all home rows. Safe to call from a Retry button. */
    fun load() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            // Run all three sources in parallel; each is independently guarded.
            val moviesDeferred = async { runCatching { ArchiveApi.fetchMoviesPage(page = 1, perPage = 20) } }
            val showsDeferred = async { runCatching { TvMazeApi.fetchTrendingShows() } }
            val animeDeferred = async { runCatching { AniListApi.fetchTrendingAnime() } }

            val moviesResult = moviesDeferred.await()
            val showsResult = showsDeferred.await()
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
                    shows = showsResult.getOrDefault(emptyList()),
                    anime = animeResult.getOrDefault(emptyList()),
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

            // Industry rows need the user's TMDB key; without one they
            // stay hidden (no error, no empty shells). Each industry
            // fails independently — a failed one just doesn't appear.
            val apiKey = tmdbKeys?.getKey()
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
                TmdbKeyRepository(context.applicationContext)
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: $modelClass")
    }
}
