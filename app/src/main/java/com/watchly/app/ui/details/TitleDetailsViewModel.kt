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
    val isSeasonLoading: Boolean = false,
    /** Instant cloud embed servers available for 1-tap selection. */
    val cloudServers: List<PlayableFile> = emptyList(),
    val selectedServerUrl: String? = null,
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

    /** True when the title has direct playable files / YouTube / local media. */
    val hasPlayableSource: Boolean
        get() = playableFiles.isNotEmpty() || title.youtubeId != null || title.localUri != null

    /** True when Watchly's multi-server cloud network can stream this title. */
    val hasCloudStreamSource: Boolean
        get() = cloudServers.isNotEmpty() || title.tmdbId != null || title.imdbId != null

    /** True when either direct files, cloud servers, or YouTube can play in-app. */
    val canStreamInApp: Boolean
        get() = hasPlayableSource || hasCloudStreamSource

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
    backdropUrl = base.backdropUrl ?: extra.backdropUrl,
    originalLanguage = base.originalLanguage ?: extra.originalLanguage,
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
                // (b) Name + year search for real provider-sourced titles
                // (Archive / TVmaze / AniList) lacking an IMDb id. Bare
                // synthetic fixtures with no provider ids stay offline.
                val hasExternalProviderId = title.archiveId != null ||
                    title.tvmazeId != null ||
                    title.anilistId != null
                if (effectiveTmdbId == null && hasExternalProviderId) {
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
            val isMovie = title.mediaType == MediaType.MOVIE
            val tmdbDetails = if (effectiveTmdbId != null && apiKey != null && isMovie) {
                async { runCatching { TmdbApi.fetchMovieDetails(apiKey, effectiveTmdbId) } }
            } else {
                null
            }
            val tmdbTvBundle = if (effectiveTmdbId != null && apiKey != null && !isMovie) {
                async { runCatching { TmdbApi.fetchTvDetails(apiKey, effectiveTmdbId) } }
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
                enriched = if (title.tmdbId != null) {
                    details
                } else {
                    mergeFillMissing(enriched, details)
                }
            }?.onFailure { failures.add("details") }

            var tmdbSeasonNumbers: List<Int> = emptyList()
            tmdbTvBundle?.await()?.onSuccess { bundle ->
                enriched = if (title.tmdbId != null && title.tvmazeId == null && title.anilistId == null) {
                    bundle.title
                } else {
                    mergeFillMissing(enriched, bundle.title)
                }
                tmdbSeasonNumbers = bundle.seasonNumbers
            }

            val tvMazeEpisodeList = episodes?.await()?.getOrElse {
                failures.add("episodes"); emptyList()
            }.orEmpty()

            // When TVmaze has no episodes (TMDB TV shows & bridged Anime),
            // load Season 1 episodes from TMDB and register all season tabs!
            var groupedEpisodes: Map<Int, List<Episode>> = tvMazeEpisodeList.groupBy { ep -> ep.season }
            if (groupedEpisodes.isEmpty() &&
                tmdbSeasonNumbers.isNotEmpty() &&
                effectiveTmdbId != null &&
                apiKey != null
            ) {
                val firstSeason = tmdbSeasonNumbers.first()
                val firstSeasonEpisodes = runCatching {
                    TmdbApi.fetchTvSeasonEpisodes(apiKey, effectiveTmdbId, firstSeason)
                }.getOrDefault(emptyList())
                val mutableSeasons = LinkedHashMap<Int, List<Episode>>()
                for (s in tmdbSeasonNumbers) {
                    mutableSeasons[s] = if (s == firstSeason) firstSeasonEpisodes else emptyList()
                }
                if (firstSeasonEpisodes.isNotEmpty() || tmdbSeasonNumbers.isNotEmpty()) {
                    groupedEpisodes = mutableSeasons
                }
            }
            val episodeList = groupedEpisodes.values.flatten()

            var castList = cast?.await()?.getOrElse {
                failures.add("cast"); emptyList()
            }.orEmpty()
            if (castList.isEmpty()) {
                tmdbCast?.await()?.onSuccess { castList = it }
            }

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

            // Build instant multi-server cloud stream options when TMDB / IMDb id is present
            val instantServers = if (enriched.archiveId == null &&
                (enriched.tmdbId != null || enriched.imdbId != null)
            ) {
                com.watchly.app.data.remote.providers.StreamAggregator
                    .getInstantEmbedServers(enriched)
                    .map { s ->
                        PlayableFile(
                            url = s.url,
                            fileName = "${enriched.name} (${s.providerName})",
                            format = "EMBED",
                            qualityLabel = s.providerName,
                            headers = s.headers
                        )
                    }
            } else {
                emptyList()
            }

            val downloadRecord = downloads?.recordForTitle(enriched.stableKey)

            _uiState.update {
                it.copy(
                    title = enriched,
                    isLoading = false,
                    hasTmdbKey = apiKey != null,
                    episodesBySeason = groupedEpisodes,
                    selectedSeason = groupedEpisodes.keys
                        .sortedWith(compareBy<Int> { s -> s == 0 }.thenBy { s -> s })
                        .firstOrNull(),
                    cloudServers = instantServers,
                    selectedServerUrl = it.selectedServerUrl ?: instantServers.firstOrNull()?.url,
                    cast = castList,
                    watchProviders = watch,
                    trailerYoutubeId = trailerKey,
                    recommendations = moreLikeThis,
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

    /** Picks which season's episodes the list shows (lazy-loads TMDB seasons on demand). */
    fun selectSeason(season: Int) {
        _uiState.update { it.copy(selectedSeason = season) }
        val state = _uiState.value
        val existing = state.episodesBySeason[season]
        val tmdbId = state.title.tmdbId
        val apiKey = tmdbKeys?.getKey()
        if (existing.isNullOrEmpty() && tmdbId != null && apiKey != null && state.title.tvmazeId == null) {
            _uiState.update { it.copy(isSeasonLoading = true) }
            viewModelScope.launch {
                val loaded = runCatching {
                    TmdbApi.fetchTvSeasonEpisodes(apiKey, tmdbId, season)
                }.getOrDefault(emptyList())
                _uiState.update { cur ->
                    val nextMap = LinkedHashMap(cur.episodesBySeason)
                    nextMap[season] = loaded
                    val newlyWatched = loaded
                        .filter { ep -> library.isEpisodeWatched(cur.title, ep.season, ep.number) }
                        .map { ep -> "S${ep.season}E${ep.number}" }
                    cur.copy(
                        isSeasonLoading = false,
                        episodesBySeason = nextMap,
                        watchedEpisodes = cur.watchedEpisodes + newlyWatched
                    )
                }
            }
        }
    }

    /** Picks the preferred cloud streaming server. */
    fun selectServer(serverUrl: String) {
        _uiState.update { it.copy(selectedServerUrl = serverUrl) }
    }

    /** Builds the UpNext continuation queue for episodes following [current]. */
    fun episodeQueueAfter(current: Episode): List<com.watchly.app.data.playback.UpNextItem> {
        val state = _uiState.value
        val seasonEpisodes = state.episodesBySeason[current.season].orEmpty()
        return seasonEpisodes
            .filter { it.number > current.number }
            .map { ep ->
                com.watchly.app.data.playback.UpNextItem(
                    title = state.title,
                    episodeLabel = "S${ep.season}E${ep.number} - ${ep.name ?: "Episode ${ep.number}"}"
                )
            }
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
                infoMessage = "“${it.title.name}” is catalogue-only. No video file available."
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
            repo == null || state.playableFiles.isEmpty() -> _uiState.update {
                it.copy(
                    infoMessage = if (state.hasCloudStreamSource) {
                        "Tap ▶ Play and hit “Native Player” once detected to stream directly, " +
                            "or save public-domain & local files offline."
                    } else {
                        "“${state.title.name}” is in the catalogue only — there is no file to save for offline watching."
                    }
                )
            }
            state.downloadStatus == DownloadStatus.COMPLETED -> _uiState.update {
                it.copy(
                    infoMessage = "Already downloaded — find it in " +
                        "My Library → Downloads."
                )
            }
            state.downloadStatus == DownloadStatus.RUNNING ||
                state.downloadStatus == DownloadStatus.QUEUED ||
                state.downloadStatus == DownloadStatus.PAUSED -> _uiState.update {
                it.copy(
                    infoMessage = "Downloading — progress is in " +
                        "My Library → Downloads."
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
                        infoMessage = "Couldn't start the download."
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
