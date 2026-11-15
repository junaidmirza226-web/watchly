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
import com.watchly.app.data.downloads.DownloadPolicy
import com.watchly.app.data.downloads.DownloadStatus
import com.watchly.app.data.downloads.DownloadsRepository
import com.watchly.app.data.downloads.StartDownloadOutcome
import com.watchly.app.data.library.ListsRepository
import com.watchly.app.data.library.WatchlistRepository
import com.watchly.app.data.model.CastMember
import com.watchly.app.data.model.Episode
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.OmdbRatings
import com.watchly.app.data.model.Playability
import com.watchly.app.data.model.PlayableFile
import com.watchly.app.data.model.Title
import com.watchly.app.data.model.WatchProviders
import com.watchly.app.data.model.playability
import com.watchly.app.data.model.preferredWatchRegion
import com.watchly.app.data.remote.ArchiveApi
import com.watchly.app.data.remote.ExternalIdCache
import com.watchly.app.data.remote.FanartApi
import com.watchly.app.data.remote.OmdbApi
import com.watchly.app.data.remote.OmdbCache
import com.watchly.app.data.remote.TmdbApi
import com.watchly.app.data.remote.TraktApi
import com.watchly.app.data.remote.TvMazeApi
import com.watchly.app.data.remote.YouTubeApi
import com.watchly.app.data.settings.ApiProvider
import com.watchly.app.data.settings.ProviderKeysRepository
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
    /**
     * True when a TMDB key is saved (cycle 14): distinguishes the
     * no-key case — where where-to-watch, the trailer and More
     * Like This are all off because TMDB was never asked — from a
     * keyed fetch that simply found nothing. The screen uses it
     * to show one honest guidance line instead of a silently
     * missing section.
     */
    val hasTmdbKey: Boolean = false,
    /** Playable/downloadable files (Internet Archive in v1). */
    val playableFiles: List<PlayableFile> = emptyList(),
    /**
     * Cycle-4 Phase D: the Archive files check COMPLETED (whatever
     * it found). Only a completed check may downgrade a title to
     * "Info only" — an unfinished or failed check must not, because
     * "we couldn't look" is not "there is nothing to play".
     */
    val filesChecked: Boolean = false,
    /**
     * Cycle-4 Phase D: the files check FAILED (usually the network).
     * The page shows a "couldn't check — Retry" state instead of
     * pretending the title has no files.
     */
    val filesCheckFailed: Boolean = false,
    val isInWatchlist: Boolean = false,
    val isWatched: Boolean = false,
    /**
     * Cycle 10: the YouTube key of TMDB's official trailer for
     * this title. Null — and no Trailer button — when TMDB lists
     * no suitable YouTube video (or there's no TMDB id / key).
     */
    val trailerYoutubeId: String? = null,
    /**
     * Cycle 10: "More like this" — TMDB's recommendations for this
     * title. Empty when TMDB has none (or no id / key); the
     * section simply doesn't render.
     */
    val recommendations: List<Title> = emptyList(),
    /**
     * Cycle 10: the user's own 1–10 rating for this title (§4.2),
     * from RatingsRepository. Null = not rated. Never blended with
     * provider ratings — it's shown as his, labelled "Your rating".
     */
    val personalRating: Int? = null,
    /** Live download state for this title, null when never downloaded. */
    val downloadStatus: DownloadStatus? = null,
    /**
     * Non-null while the download quality picker is open (spec
     * §7.1): the files the user can choose between, best first.
     * Null whenever no choice is pending.
     */
    val qualityChoices: List<PlayableFile>? = null,
    /**
     * Non-null while the "Add to List…" picker is open (spec
     * §9.2): every user list with whether this title is in it.
     * Null whenever no picker is open.
     */
    val listChoices: List<ListMembership>? = null,
    /** 0..1 download progress while active; null when indeterminate. */
    val downloadProgress: Float? = null,
    /** Episode keys ("S1E2") marked watched, for the selected title. */
    val watchedEpisodes: Set<String> = emptySet(),
    /** Cycle 4: OMDb ratings bundle (needs the user's OMDb key). */
    val omdbRatings: OmdbRatings? = null,
    /** Cycle 4: Trakt community rating 0–10 (needs the Client ID). */
    val traktRating: Double? = null,
    /** Cycle 4: Fanart HD clear logo for the hero overlay. */
    val fanartLogoUrl: String? = null,
    /** Cycle 4: Fanart backdrop, used only when TMDB has no image. */
    val fanartBackdropUrl: String? = null,
    /**
     * Cycle 5: autoplay continuation for an official-YouTube series
     * episode — the channel feed's later episodes, computed after
     * load. Empty for everything else.
     */
    val upNextQueue: List<com.watchly.app.data.playback.UpNextItem> = emptyList(),
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

    /** True when the title has something playable right now (including cloud streams). */
    val hasPlayableSource: Boolean
        get() = playableFiles.isNotEmpty() || title.tmdbId != null || title.imdbId != null || title.youtubeId != null || title.localUri != null || title.name.isNotBlank()

    /**
     * Badge truth for the details page — ONE source of truth, the
     * same rule the cards use (Title.playability) until the files
     * check gives a definitive answer:
     *  - files present → PLAYABLE;
     *  - files check completed and found none → INFO_ONLY (an
     *    Archive item can genuinely hold no playable video);
     *  - check still running or FAILED → the title-level rule, so
     *    the badge never contradicts the card the user tapped.
     * (Phase D bug fix: a slow-network failure used to be read as
     * "no files", flipping a ▶ Playable card to "Info only" here.)
     */
    val playability: Playability
        get() = when {
            hasPlayableSource -> Playability.PLAYABLE
            filesChecked && title.archiveId != null -> Playability.INFO_ONLY
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

/** One row of the "Add to List…" picker: a list + membership. */
data class ListMembership(
    val listId: String,
    val listName: String,
    val itemCount: Int,
    val containsTitle: Boolean
)

/**
 * Phase D merge: [extra]'s fields fill ONLY the gaps in [base] —
 * used when TMDB data enriches a title owned by another provider,
 * whose own ids (archiveId, tvmazeId, anilistId) and name must
 * survive untouched. The name and media type are never taken from
 * [extra]: the user tapped [base]'s card.
 */
internal fun mergeFillMissing(base: Title, extra: Title): Title = base.copy(
    tmdbId = base.tmdbId ?: extra.tmdbId,
    imdbId = base.imdbId ?: extra.imdbId,
    posterUrl = base.posterUrl ?: extra.posterUrl,
    overview = base.overview ?: extra.overview,
    year = base.year ?: extra.year,
    rating = base.rating ?: extra.rating,
    runtimeMinutes = base.runtimeMinutes ?: extra.runtimeMinutes,
    genres = if (base.genres.isEmpty()) extra.genres else base.genres,
    status = base.status ?: extra.status
)

/**
 * Phase D: picks the TMDB search result that is [title] — same
 * media type, same name (case/punctuation-insensitive), and the
 * same year whenever both years are known. A wrong poster is worse
 * than no poster, so anything looser returns null.
 */
internal fun pickNameYearMatch(results: List<Title>, title: Title): Title? {
    fun normalize(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9]"), "")
    val wanted = normalize(title.name)
    val candidates = results.filter {
        it.mediaType == title.mediaType && normalize(it.name) == wanted
    }
    return candidates.firstOrNull { it.year != null && it.year == title.year }
        ?: candidates.firstOrNull { title.year == null }
}

class TitleDetailsViewModel(
    private val title: Title,
    private val library: WatchlistRepository,
    private val tmdbKeys: TmdbKeyRepository? = null,
    private val downloads: DownloadsRepository? = null,
    private val userLists: ListsRepository? = null,
    /** Cycle 4: the user's OMDb / Trakt / Fanart keys. */
    private val providerKeys: ProviderKeysRepository? = null,
    /** Cycle 4: OMDb response cache (protects the 1,000/day limit). */
    private val omdbCache: OmdbCache? = null,
    /** Phase D: IMDb → TMDB resolution cache (never expires). */
    private val externalIdCache: ExternalIdCache? = null,
    /** Cycle 10: the user's own ratings (§4.2). */
    private val ratings: com.watchly.app.data.library.RatingsRepository? = null,
    /** Cycle 10: UI preferences — the download path reads Data Saver. */
    private val uiPrefs: com.watchly.app.data.settings.UiPreferencesRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        TitleDetailsUiState(
            title = title,
            isInWatchlist = library.isInWatchlist(title),
            isWatched = library.isWatched(title),
            personalRating = ratings?.ratingFor(title.stableKey),
            hasTmdbKey = tmdbKeys?.hasKey() == true
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
            // Phase D: resolve an EFFECTIVE TMDB id for titles that
            // arrived without one (TVmaze/AniList/Archive), so their
            // details page can show watch providers and a TMDB
            // poster too. Only with the user's TMDB key; failures
            // leave the title exactly as it was.
            val apiKey = tmdbKeys?.getKey()
            var effectiveTmdbId = title.tmdbId
            var searchMatch: Title? = null
            if (apiKey != null && effectiveTmdbId == null) {
                // (a) IMDb id → TMDB /find, cached forever.
                val imdbId = title.imdbId
                if (imdbId != null) {
                    effectiveTmdbId = externalIdCache?.tmdbIdFor(imdbId)
                        ?: runCatching {
                            TmdbApi.findByImdbId(apiKey, imdbId, title.mediaType)
                        }.getOrNull()?.also { found ->
                            externalIdCache?.putTmdbId(imdbId, found)
                        }
                }
                // (b) Name + year search (Archive titles have no
                // IMDb id here): the match's poster fills the page,
                // and its id unlocks providers/details below.
                if (effectiveTmdbId == null) {
                    searchMatch = runCatching {
                        TmdbApi.searchMulti(apiKey, title.name)
                    }.getOrNull()?.let { results ->
                        pickNameYearMatch(results, title)
                    }
                    effectiveTmdbId = searchMatch?.tmdbId
                }
            }

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
            // TMDB enrichment, only with the user's key. The
            // DETAILS endpoint used here is movie-only, so it runs
            // for movies; TV/anime titles get watch providers
            // (type-aware) through the same id instead of a
            // guaranteed-404 details call. CAST is type-aware
            // since cycle 11: a TMDB-sourced show's cast comes
            // from /tv/:id/credits, carrying the TMDB person ids
            // the person pages (§3.3) open from. (A TVmaze cast,
            // when the title has one, still wins — it is the
            // source's own cast; its rows carry no TMDB ids and
            // stay inert on the page.)
            val isMovie = title.mediaType == MediaType.MOVIE
            val tmdbDetails = if (effectiveTmdbId != null && apiKey != null && isMovie) {
                async { runCatching { TmdbApi.fetchMovieDetails(apiKey, effectiveTmdbId) } }
            } else {
                null
            }
            val tmdbCast = if (effectiveTmdbId != null && apiKey != null) {
                async {
                    runCatching {
                        if (isMovie) {
                            TmdbApi.fetchMovieCast(apiKey, effectiveTmdbId)
                        } else {
                            TmdbApi.fetchTvCast(apiKey, effectiveTmdbId)
                        }
                    }
                }
            } else {
                null
            }
            // Where-to-watch, same key conditions. Region = the
            // device's country (US when unusable); TMDB itself
            // falls back to US data and the result says so.
            val watchProviders = if (effectiveTmdbId != null && apiKey != null) {
                async {
                    runCatching {
                        TmdbApi.fetchWatchProviders(
                            apiKey,
                            title.mediaType,
                            effectiveTmdbId,
                            preferredWatchRegion(Locale.getDefault().country)
                        )
                    }
                }
            } else {
                null
            }
            // Cycle 10 details depth: the official trailer and
            // "More like this". Both endpoints are type-aware
            // (movie AND tv), and both are extras — a failure or an
            // empty answer leaves the page exactly as it was, with
            // no button and no section. Neither joins the failures
            // banner: absence here is a normal answer, not an error.
            val trailer = if (effectiveTmdbId != null && apiKey != null) {
                async {
                    runCatching {
                        TmdbApi.fetchTrailerKey(apiKey, title.mediaType, effectiveTmdbId)
                    }
                }
            } else {
                null
            }
            val recommendations = if (effectiveTmdbId != null && apiKey != null) {
                async {
                    runCatching {
                        TmdbApi.fetchRecommendations(
                            apiKey, title.mediaType, effectiveTmdbId
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
            // Phase D: the name+year match fills the gaps TMDB's
            // search knows (poster above all) — never overwriting
            // what the title already has.
            searchMatch?.let { enriched = mergeFillMissing(enriched, it) }
            tmdbDetails?.await()?.onSuccess { details ->
                // A NATIVE TMDB title is replaced by its richer
                // parse (it has nothing else to lose). A RESOLVED
                // title (Archive/TVmaze/AniList) must keep its own
                // ids — the TMDB parse only fills missing fields,
                // or the Archive id (and with it playability) and
                // the TVmaze id (episodes) would be lost.
                enriched = if (title.tmdbId != null) {
                    details
                } else {
                    mergeFillMissing(enriched, details)
                }
            }?.onFailure { failures.add("details") }
            val episodeList = episodes?.await()?.getOrElse {
                failures.add("episodes"); emptyList()
            }.orEmpty()
            var castList = cast?.await()?.getOrElse {
                failures.add("cast"); emptyList()
            }.orEmpty()
            if (castList.isEmpty()) {
                tmdbCast?.await()?.onSuccess { castList = it }
            }
            // The files check gets its own outcome flags (Phase D):
            // a failure is NOT "no files" and must not join the
            // generic failures banner — the page shows a dedicated
            // couldn't-check state with a working Retry instead.
            var filesCheckedNow = false
            var filesFailedNow = false
            val playable = when (val filesResult = files?.await()) {
                null -> emptyList()
                else -> filesResult.fold(
                    onSuccess = { found ->
                        filesCheckedNow = true
                        found
                    },
                    onFailure = {
                        filesFailedNow = true
                        emptyList()
                    }
                )
            }
            val watch = watchProviders?.await()?.getOrElse {
                failures.add("providers"); null
            }
            val trailerKey = trailer?.await()?.getOrNull()
            val moreLikeThis = recommendations?.await()
                ?.getOrNull().orEmpty()

            // Current download state for this title (if any) so the
            // Download button can label itself honestly.
            val downloadRecord = downloads?.recordForTitle(enriched.stableKey)

            _uiState.update {
                it.copy(
                    title = enriched,
                    isLoading = false,
                    hasTmdbKey = apiKey != null,
                    episodesBySeason = episodeList.groupBy { ep -> ep.season },
                    selectedSeason = episodeList.map { ep -> ep.season }
                        .distinct().minOrNull(),
                    cast = castList,
                    watchProviders = watch,
                    trailerYoutubeId = trailerKey,
                    recommendations = moreLikeThis,
                    // The enriched title can carry a different
                    // stable key (a resolved TMDB id outranks the
                    // source id) — re-read his rating under the key
                    // the page now uses, like the watchlist state.
                    personalRating = ratings?.ratingFor(enriched.stableKey)
                        ?: it.personalRating,
                    playableFiles = playable,
                    filesChecked = filesCheckedNow,
                    filesCheckFailed = filesFailedNow,
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

            // Cycle-4 enrichment runs last and independently: the
            // page above is already complete without it.
            enrichWithProviders(enriched)

            // Cycle 5: for an official-YouTube episode, find its
            // channel feed and queue the series' later episodes for
            // autoplay. Independent and fail-soft like enrichment.
            if (enriched.youtubeId != null) {
                loadYouTubeSeriesQueue(enriched.youtubeId)
            }
        }
    }

    /**
     * Cycle-4 provider enrichment: OMDb ratings, the Trakt
     * community rating and Fanart artwork. Each needs the user's
     * own key for that provider plus the right id on the title
     * (IMDb id for OMDb/Trakt, TMDB id for Fanart); anything missing
     * or failing simply leaves the page as it was — no errors, no
     * placeholders.
     */
    /**
     * Cycle 5: builds the autoplay queue for a YouTube series
     * episode. Scans the known official channels' feeds for the one
     * carrying this video (bounded: the curated channel list), then
     * queues the episodes published after it. Any failure simply
     * leaves the queue empty — no autoplay, never a wrong queue.
     */
    private suspend fun loadYouTubeSeriesQueue(youtubeId: String) {
        val queue = runCatching {
            var found: List<com.watchly.app.data.playback.UpNextItem> = emptyList()
            for (channel in YouTubeApi.CHANNELS) {
                val videos = runCatching {
                    YouTubeApi.fetchChannelVideos(channel, limit = 50)
                }.getOrDefault(emptyList())
                if (videos.any { it.youtubeId == youtubeId }) {
                    found = com.watchly.app.data.playback.AutoplayResolver
                        .youtubeSeriesQueue(videos, youtubeId)
                    break
                }
            }
            found
        }.getOrDefault(emptyList())
        if (queue.isNotEmpty()) {
            _uiState.update { it.copy(upNextQueue = queue) }
        }
    }

    private suspend fun enrichWithProviders(enriched: Title) {
        val keys = providerKeys ?: return
        val imdbId = enriched.imdbId

        val omdbKey = runCatching { keys.getKey(ApiProvider.OMDB) }.getOrNull()
        if (imdbId != null && omdbKey != null) {
            runCatching { OmdbApi.getRatings(omdbKey, imdbId, omdbCache) }
                .getOrNull()?.let { ratings ->
                    _uiState.update { it.copy(omdbRatings = ratings) }
                }
        }

        val traktKey = runCatching { keys.getKey(ApiProvider.TRAKT) }.getOrNull()
        if (imdbId != null && traktKey != null) {
            runCatching {
                TraktApi.fetchRating(traktKey, imdbId, enriched.mediaType)
            }.getOrNull()?.let { rating ->
                _uiState.update { it.copy(traktRating = rating) }
            }
        }

        val fanartKey = runCatching { keys.getKey(ApiProvider.FANART) }.getOrNull()
        val tmdbId = enriched.tmdbId
        if (tmdbId != null && fanartKey != null) {
            runCatching {
                FanartApi.fetchArtwork(fanartKey, tmdbId, enriched.mediaType)
            }.getOrNull()?.let { artwork ->
                _uiState.update {
                    it.copy(
                        fanartLogoUrl = artwork.logoUrl,
                        fanartBackdropUrl = artwork.backdropUrl
                    )
                }
            }
        }
    }

    /** Picks which season's episodes the list shows. */
    fun selectSeason(season: Int) {
        _uiState.update { it.copy(selectedSeason = season) }
    }

    // ------------------------------------------------------------------
    // Personal rating (cycle 10, §4.2)
    // ------------------------------------------------------------------

    /**
     * Live slider movement: the shown value follows the thumb,
     * but nothing is written yet — a drag across the scale must
     * not spam the store (or the backup) with every step.
     */
    fun onPersonalRatingPreview(rating: Int) {
        val clamped = rating.coerceIn(0, 10)
        _uiState.update {
            it.copy(personalRating = clamped.takeIf { value -> value > 0 })
        }
    }

    /** The slider settled: persist the shown value (0 clears). */
    fun onPersonalRatingCommitted() {
        val current = _uiState.value
        ratings?.setRating(current.title.stableKey, current.personalRating)
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
     * Re-runs ONLY the Archive files check after it failed (Phase D):
     * the couldn't-check state's Retry button. Success fills the
     * file list (Play lights up); another failure keeps the
     * couldn't-check state — never a fake "no files".
     */
    fun retryFilesCheck() {
        val archiveId = _uiState.value.title.archiveId ?: return
        _uiState.update { it.copy(filesCheckFailed = false) }
        viewModelScope.launch {
            runCatching { ArchiveApi.fetchPlayableFiles(archiveId) }.fold(
                onSuccess = { found ->
                    _uiState.update {
                        it.copy(
                            playableFiles = found,
                            filesChecked = true,
                            filesCheckFailed = false
                        )
                    }
                },
                onFailure = {
                    _uiState.update { it.copy(filesCheckFailed = true) }
                }
            )
        }
    }

    /**
     * Play is tapped with no playable source. The screen routes to the
     * player when a source exists; this is only reached for catalogue
     * titles with nothing to play, so the honest answer is a plain
     * note, not a dead button. (Phase D: the note no longer promises
     * a Jellyfin/Plex connection — that feature is not built; the
     * title is simply catalogue-only for now.)
     */
    fun onPlayRequested() {
        _uiState.update {
            it.copy(
                infoMessage = "“${it.title.name}” is catalogue-only for now — " +
                    "there's no video file Watchly can play for it yet. " +
                    "Public-domain titles and YouTube channel titles play right here."
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
            // YouTube titles play in the official player; there is
            // no file Watchly may save, and the note says so plainly.
            state.title.youtubeId != null && !state.hasPlayableSource ->
                _uiState.update {
                    it.copy(
                        infoMessage = "“${it.title.name}” plays in YouTube's " +
                            "official player, so it can't be downloaded. " +
                            "Public-domain films and your own library files " +
                            "can be saved for offline watching."
                    )
                }
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
                // Cycle 10 (§14.1): with Data Saver on, the picker
                // is skipped and the efficient file starts at once —
                // the note in the confirmation says that happened,
                // so the missing picker never looks like a bug.
                val dataSaverDefault = if (uiPrefs?.dataSaverEnabled() == true) {
                    DownloadPolicy.efficientDefault(state.playableFiles)
                } else {
                    null
                }
                if (dataSaverDefault != null) {
                    startChosenDownload(
                        dataSaverDefault,
                        note = "Data Saver is on, so Watchly picked " +
                            "the smaller file to save data."
                    )
                } else {
                    // Spec §7.1: when the source offers more than one
                    // quality, the user picks BEFORE anything starts.
                    // A single file needs no choice and starts at once.
                    val options = DownloadPolicy.qualityOptions(state.playableFiles)
                    if (options.size > 1) {
                        _uiState.update { it.copy(qualityChoices = options) }
                    } else {
                        startChosenDownload(options.first())
                    }
                }
            }
        }
    }

    /** The user picked a quality in the picker: start that file. */
    fun onQualityChosen(file: PlayableFile) {
        _uiState.update { it.copy(qualityChoices = null) }
        startChosenDownload(file)
    }

    /** The user closed the quality picker without picking. */
    fun onQualityPickerDismissed() {
        _uiState.update { it.copy(qualityChoices = null) }
    }

    /**
     * Starts [file] through the §7.3-checked repository path: the
     * start can be refused (Wi-Fi only, low battery, space, cap)
     * with a plain reason, or allowed with a warning — either way
     * the outcome lands in the info message, never a dead button.
     */
    private fun startChosenDownload(file: PlayableFile, note: String? = null) {
        val state = _uiState.value
        val repo = downloads ?: return
        runCatching { repo.startDownloadChecked(state.title, file) }.fold(
            onSuccess = { outcome ->
                when (outcome) {
                    is StartDownloadOutcome.Started -> _uiState.update {
                        it.copy(
                            downloadStatus = outcome.record.status,
                            downloadProgress = outcome.record.progressFraction,
                            infoMessage = "Download started — “${it.title.name}” " +
                                "will be in My Library → Downloads." +
                                (outcome.warning?.let { warning -> " $warning" }
                                    ?: "") +
                                (note?.let { text -> " $text" } ?: "")
                        )
                    }
                    is StartDownloadOutcome.Refused -> _uiState.update {
                        it.copy(infoMessage = outcome.message)
                    }
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

    /** Clears the info note once shown/dismissed. */
    fun onInfoShown() {
        _uiState.update { it.copy(infoMessage = null) }
    }

    // ------------------------------------------------------------------
    // Lists (spec §9.2): "Add to List…"
    // ------------------------------------------------------------------

    /**
     * Opens the list picker with the title's real membership in
     * every list. Without a lists store (never in production) the
     * tap explains itself instead of opening an empty dialog.
     */
    fun onAddToListRequested() {
        val repo = userLists
        if (repo == null) {
            _uiState.update {
                it.copy(infoMessage = "Lists aren't available here.")
            }
            return
        }
        _uiState.update { it.copy(listChoices = memberships(repo)) }
    }

    /** Toggles this title in/out of the tapped list, in place. */
    fun onListMembershipToggled(listId: String) {
        val repo = userLists ?: return
        val current = _uiState.value.title
        if (repo.containsTitle(listId, current)) {
            repo.removeTitle(listId, current)
        } else {
            repo.addTitle(listId, current)
        }
        _uiState.update { it.copy(listChoices = memberships(repo)) }
    }

    /** Creates a list from the picker and puts this title in it. */
    fun onCreateListAndAdd(name: String) {
        val repo = userLists ?: return
        if (name.isBlank()) return
        val created = repo.createList(name)
        repo.addTitle(created.id, _uiState.value.title)
        _uiState.update { it.copy(listChoices = memberships(repo)) }
    }

    /** Closes the list picker without changing anything. */
    fun onAddToListDismissed() {
        _uiState.update { it.copy(listChoices = null) }
    }

    private fun memberships(repo: ListsRepository): List<ListMembership> {
        val current = _uiState.value.title
        return repo.getLists().map { list ->
            ListMembership(
                listId = list.id,
                listName = list.name,
                itemCount = list.size,
                containsTitle = list.titles.any { it.stableKey == current.stableKey }
            )
        }
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
                downloads = DownloadsRepository(app),
                userLists = ListsRepository(app),
                providerKeys = ProviderKeysRepository(app),
                omdbCache = com.watchly.app.data.remote.SharedPreferencesOmdbCache(app),
                externalIdCache =
                    com.watchly.app.data.remote.SharedPreferencesExternalIdCache(app),
                ratings = com.watchly.app.data.library.RatingsRepository(app),
                uiPrefs = com.watchly.app.data.settings.UiPreferencesRepository(app)
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: $modelClass")
    }
}
