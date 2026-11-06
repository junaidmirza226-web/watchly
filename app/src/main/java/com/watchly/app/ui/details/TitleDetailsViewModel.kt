/*
 * Watchly — Title Details state holder (spec §4.1).
 *
 * One rich page per title: backdrop/poster, year, runtime/seasons,
 * genres, overview, per-provider ratings badges, cast, episode list
 * for shows, and the source list (Internet Archive files in v1).
 * Watchlist / watched toggles are stored locally and work offline.
 *
 * Loading is per-provider and degrades gracefully: a failed episode
 * fetch still shows the header, cast and sources. TMDB titles (when
 * the user has saved their key) are enriched with TMDB details and
 * cast, plus where-to-watch: the official services that stream, rent
 * or sell the title in the device's region. Play navigates to the Media3 player when a playable source
 * exists (wired by the screen). Download starts a real offline
 * download for titles with a playable file (public-domain Archive
 * films in v1); for catalogue-only titles it says plainly why there
 * is nothing to download instead of pretending to work.
 */
package com.watchly.app.ui.details

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.watchly.app.data.downloads.DownloadStatus
import com.watchly.app.data.downloads.DownloadsRepository
import com.watchly.app.data.library.WatchlistRepository
import com.watchly.app.data.model.CastMember
import com.watchly.app.data.model.Episode
import com.watchly.app.data.model.Playability
import com.watchly.app.data.model.PlayableFile
import com.watchly.app.data.model.Title
import com.watchly.app.data.model.WatchProviders
import com.watchly.app.data.model.playability
import com.watchly.app.data.model.preferredWatchRegion
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
import java.util.Locale

data class TitleDetailsUiState(
    val title: Title,
    val isLoading: Boolean = true,
    /** Season number -> episodes, in air order. Empty for movies/anime. */
    val episodesBySeason: Map<Int, List<Episode>> = emptyMap(),
    val selectedSeason: Int? = null,
    val cast: List<CastMember> = emptyList(),
    /**
     * Official services offering this title (TMDB where-to-watch).
     * Null when the section doesn't apply — no TMDB id, no saved key,
     * or the fetch failed. Non-null but empty is a real answer: TMDB
     * lists nothing for the region, and the page says exactly that.
     */
    val watchProviders: WatchProviders? = null,
    /** Playable/downloadable files (Internet Archive in v1). */
    val playableFiles: List<PlayableFile> = emptyList(),
    val isInWatchlist: Boolean = false,
    val isWatched: Boolean = false,
    /** Live download state for this title, null when never downloaded. */
    val downloadStatus: DownloadStatus? = null,
    /** 0..1 download progress while active; null when indeterminate. */
    val downloadProgress: Float? = null,
    /** Episode keys ("S1E2") marked watched, for the selected title. */
    val watchedEpisodes: Set<String> = emptySet(),
    /** Non-null when something failed; safe to show directly. */
    val errorMessage: String? = null,
    /** Plain-language note shown after tapping Play / Download. */
    val infoMessage: String? = null
) {
    /** Seasons present, sorted ascending (specials = season 0 last). */
    val seasons: List<Int>
        get() = episodesBySeason.keys.sortedWith(
            compareBy<Int> { it == 0 }.thenBy { it }
        )

    /** Episodes of the selected season (or the first season by default). */
    val selectedEpisodes: List<Episode>
        get() {
            val season = selectedSeason ?: seasons.firstOrNull() ?: return emptyList()
            return episodesBySeason[season].orEmpty()
        }

    /** True when the title has something playable right now. */
    val hasPlayableSource: Boolean
        get() = playableFiles.isNotEmpty()

    /**
     * Badge truth for the details page: the real file list once the
     * load has finished, the title-level rule while loading. An Archive
     * film whose files fetch came back empty has no file to play, so it
     * is honestly "Info only" even though it came from the Archive.
     */
    val playability: Playability
        get() = when {
            hasPlayableSource -> Playability.PLAYABLE
            !isLoading && title.archiveId != null -> Playability.INFO_ONLY
            else -> title.playability()
        }

    /** Label for the Download button, reflecting the live state. */
    val downloadButtonLabel: String
        get() = when (downloadStatus) {
            DownloadStatus.COMPLETED -> "Downloaded ✓"
            DownloadStatus.RUNNING, DownloadStatus.QUEUED,
            DownloadStatus.PAUSED -> "Downloading…"
            DownloadStatus.FAILED -> "Retry Download"
            null -> "Download"
        }

    /**
     * Which provider supplied the rating shown, for an honest badge
     * label ("TVmaze 9.2", "AniList 8.7"). Archive films carry no rating.
     */
    val ratingSourceLabel: String?
        get() = when {
            title.tvmazeId != null -> "TVmaze"
            title.anilistId != null -> "AniList"
            title.tmdbId != null -> "TMDB"
            else -> null
        }
}

class TitleDetailsViewModel(
    private val title: Title,
    private val library: WatchlistRepository,
    private val tmdbKeys: TmdbKeyRepository? = null,
    private val downloads: DownloadsRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        TitleDetailsUiState(
            title = title,
            isInWatchlist = library.isInWatchlist(title),
            isWatched = library.isWatched(title)
        )
    )
    val uiState: StateFlow<TitleDetailsUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    /** (Re)loads episodes, cast and sources. Safe from a Retry button. */
    fun load() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val showDetails = title.tvmazeId?.let { id ->
                async { runCatching { TvMazeApi.fetchShowDetails(id) } }
            }
            val episodes = title.tvmazeId?.let { id ->
                async { runCatching { TvMazeApi.fetchShowEpisodes(id) } }
            }
            val cast = title.tvmazeId?.let { id ->
                async { runCatching { TvMazeApi.fetchShowCast(id) } }
            }
            val files = title.archiveId?.let { id ->
                async { runCatching { ArchiveApi.fetchPlayableFiles(id) } }
            }
            // TMDB enrichment for TMDB titles, only with the user's key.
            val apiKey = tmdbKeys?.getKey()
            val tmdbDetails = if (title.tmdbId != null && apiKey != null) {
                async { runCatching { TmdbApi.fetchMovieDetails(apiKey, title.tmdbId) } }
            } else {
                null
            }
            val tmdbCast = if (title.tmdbId != null && apiKey != null) {
                async { runCatching { TmdbApi.fetchMovieCast(apiKey, title.tmdbId) } }
            } else {
                null
            }
            // Where-to-watch for TMDB titles, same key conditions.
            // Region = the device's country (US when unusable); TMDB
            // itself falls back to US data and the result says so.
            val watchProviders = if (title.tmdbId != null && apiKey != null) {
                async {
                    runCatching {
                        TmdbApi.fetchWatchProviders(
                            apiKey,
                            title.mediaType,
                            title.tmdbId,
                            preferredWatchRegion(Locale.getDefault().country)
                        )
                    }
                }
            } else {
                null
            }

            val failures = ArrayList<String>()
            var enriched = title
            showDetails?.await()?.onSuccess { enriched = it }
                ?.onFailure { failures.add("details") }
            tmdbDetails?.await()?.onSuccess { enriched = it }
                ?.onFailure { failures.add("details") }
            val episodeList = episodes?.await()?.getOrElse {
                failures.add("episodes"); emptyList()
            }.orEmpty()
            var castList = cast?.await()?.getOrElse {
                failures.add("cast"); emptyList()
            }.orEmpty()
            if (castList.isEmpty()) {
                tmdbCast?.await()?.onSuccess { castList = it }
            }
            val playable = files?.await()?.getOrElse {
                failures.add("sources"); emptyList()
            }.orEmpty()
            val watch = watchProviders?.await()?.getOrElse {
                failures.add("providers"); null
            }

            // Current download state for this title (if any) so the
            // Download button can label itself honestly.
            val downloadRecord = downloads?.recordForTitle(enriched.stableKey)

            _uiState.update {
                it.copy(
                    title = enriched,
                    isLoading = false,
                    episodesBySeason = episodeList.groupBy { ep -> ep.season },
                    selectedSeason = episodeList.map { ep -> ep.season }
                        .distinct().minOrNull(),
                    cast = castList,
                    watchProviders = watch,
                    playableFiles = playable,
                    downloadStatus = downloadRecord?.status,
                    downloadProgress = downloadRecord?.progressFraction,
                    watchedEpisodes = episodeList
                        .filter { ep -> library.isEpisodeWatched(enriched, ep.season, ep.number) }
                        .map { ep -> "S${ep.season}E${ep.number}" }
                        .toSet(),
                    errorMessage = if (failures.isNotEmpty()) {
                        "Some details couldn't load (${failures.joinToString(", ")}). " +
                            "Showing what we have."
                    } else {
                        null
                    }
                )
            }
        }
    }

    /** Picks which season's episodes the list shows. */
    fun selectSeason(season: Int) {
        _uiState.update { it.copy(selectedSeason = season) }
    }

    /** Adds/removes the title from the watchlist. */
    fun toggleWatchlist() {
        val added = library.toggleWatchlist(_uiState.value.title)
        _uiState.update { it.copy(isInWatchlist = added) }
    }

    /** Marks the whole title watched / unwatched. */
    fun toggleWatched() {
        val nowWatched = !_uiState.value.isWatched
        library.setWatched(_uiState.value.title, nowWatched)
        _uiState.update { it.copy(isWatched = nowWatched) }
    }

    /** Toggles one episode's watched check. */
    fun toggleEpisodeWatched(episode: Episode) {
        val title = _uiState.value.title
        library.toggleEpisodeWatched(title, episode.season, episode.number)
        val key = "S${episode.season}E${episode.number}"
        _uiState.update {
            val watched = it.watchedEpisodes.toMutableSet()
            if (watched.contains(key)) watched.remove(key) else watched.add(key)
            it.copy(watchedEpisodes = watched)
        }
    }

    /**
     * Play is tapped with no playable source. The screen routes to the
     * player when a source exists; this is only reached for catalogue
     * titles with nothing to play, so the honest answer is a plain
     * note, not a dead button.
     */
    fun onPlayRequested() {
        _uiState.update {
            it.copy(
                infoMessage = "“${it.title.name}” isn't in your library yet, so there's nothing to play. Connect Jellyfin or Plex, or pick a public-domain title."
            )
        }
    }

    /**
     * Download is tapped. Titles with a playable file (public-domain
     * Archive films in v1) start a real download into app storage,
     * tracked in My Library → Downloads. Catalogue-only titles have
     * no file to save, and the note says exactly that — no fake
     * progress, no dead button.
     */
    fun onDownloadRequested() {
        val state = _uiState.value
        val repo = downloads
        when {
            repo == null || !state.hasPlayableSource -> _uiState.update {
                it.copy(
                    infoMessage = "Only titles that can play in Watchly can be " +
                        "downloaded — public-domain films and your own library " +
                        "files. “${it.title.name}” is in the catalogue only for " +
                        "now, so there's no file to save."
                )
            }
            state.downloadStatus == DownloadStatus.COMPLETED -> _uiState.update {
                it.copy(
                    infoMessage = "Already downloaded — find “${it.title.name}” " +
                        "in My Library → Downloads to play it offline."
                )
            }
            state.downloadStatus == DownloadStatus.RUNNING ||
                state.downloadStatus == DownloadStatus.QUEUED ||
                state.downloadStatus == DownloadStatus.PAUSED -> _uiState.update {
                it.copy(
                    infoMessage = "“${it.title.name}” is already downloading — " +
                        "progress is in My Library → Downloads."
                )
            }
            else -> {
                val started = runCatching {
                    repo.startDownload(state.title, state.playableFiles.first())
                }
                started.fold(
                    onSuccess = { record ->
                        _uiState.update {
                            it.copy(
                                downloadStatus = record.status,
                                downloadProgress = record.progressFraction,
                                infoMessage = "Download started — “${it.title.name}” " +
                                    "will be in My Library → Downloads."
                            )
                        }
                    },
                    onFailure = {
                        _uiState.update {
                            it.copy(
                                infoMessage = "Couldn't start the download. Check " +
                                    "your connection and storage space, then try again."
                            )
                        }
                    }
                )
            }
        }
    }

    /** Clears the info note once shown/dismissed. */
    fun onInfoShown() {
        _uiState.update { it.copy(infoMessage = null) }
    }
}

/** Factory so the nav destination can create the ViewModel per title. */
class TitleDetailsViewModelFactory(
    private val title: Title,
    private val context: Context
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TitleDetailsViewModel::class.java)) {
            val app = context.applicationContext
            return TitleDetailsViewModel(
                title = title,
                library = WatchlistRepository(app),
                tmdbKeys = TmdbKeyRepository(app),
                downloads = DownloadsRepository(app)
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: $modelClass")
    }
}
