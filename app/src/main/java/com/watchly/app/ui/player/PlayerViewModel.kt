/*
 * Watchly — player state holder (spec §5.1 core, §5.4 resume,
 * §5.5 speed, §13.2 failover).
 *
 * Behaviour:
 *  - Starts the best (first) source, auto-resumes from the saved
 *    position when it is between 5% and 95% of the duration.
 *  - Saves the position every few seconds while playing, on pause,
 *    and when leaving. Past 95% the title counts as watched and the
 *    resume point is cleared.
 *  - On a source error it tries the next source with the same resume
 *    position, and only shows an error screen when every source has
 *    failed. The user can also pick a source manually from the error
 *    screen.
 *  - Speed is remembered across sessions (PlaybackRepository).
 *
 * The engine is injected (real ExoPlayer on the phone, a fake in
 * tests), so all of this is verifiable without a device.
 */
package com.watchly.app.ui.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.watchly.app.data.library.WatchlistRepository
import com.watchly.app.data.model.PlayableFile
import com.watchly.app.data.model.SubtitleBackground
import com.watchly.app.data.model.SubtitleResult
import com.watchly.app.data.model.SubtitleStyle
import com.watchly.app.data.model.SubtitleTrack
import com.watchly.app.data.model.Title
import com.watchly.app.data.remote.OpenSubtitlesApi
import com.watchly.app.data.remote.SubtitleDownloadOutcome
import com.watchly.app.data.remote.SubtitleSearchOutcome
import com.watchly.app.data.settings.ApiProvider
import com.watchly.app.data.settings.ProviderKeysRepository
import com.watchly.app.data.playback.AudioTrackOption
import com.watchly.app.data.playback.AutoplayResolver
import com.watchly.app.data.playback.EngineState
import com.watchly.app.data.playback.ExoPlayerEngine
import com.watchly.app.data.playback.PlayableSourceResolver
import com.watchly.app.data.playback.PlaybackProgress
import com.watchly.app.data.playback.PlaybackRepository
import com.watchly.app.data.playback.PlaybackRules
import com.watchly.app.data.playback.PlayerEngine
import com.watchly.app.data.playback.PlayerMediaSessionController
import com.watchly.app.data.playback.ResolvedPlayable
import com.watchly.app.data.playback.SubtitleShifter
import com.watchly.app.data.playback.UpNextItem
import com.watchly.app.data.settings.UiPreferencesRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlayerUiState(
    val title: Title,
    val episodeLabel: String?,
    /** All sources for this title, best first (Archive: largest first). */
    val files: List<PlayableFile>,
    val fileIndex: Int = 0,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val isBuffering: Boolean = false,
    val speed: Float = 1.0f,
    /** True while a long-press holds temporary 2x speed (§5.2). */
    val isSpeedHeld: Boolean = false,
    /** True when playback resumed from a saved position this session. */
    val resumedFromMs: Long? = null,
    /** Shown while failing over: "This source failed — trying next…". */
    val failoverNote: String? = null,
    /** Shown when every source failed; Retry / pick a source / Back. */
    val errorMessage: String? = null,
    /** Sources already tried and failed this session. */
    val failedIndexes: Set<Int> = emptySet(),
    /**
     * Set for official-YouTube titles (cycle 4): the screen renders
     * YouTube's official embedded player for this video id and the
     * Media3 engine never starts.
     */
    val youtubeVideoId: String? = null,
    val webEmbedUrl: String? = null,
    /** Subtitle picker is open (cycle 4, OpenSubtitles). */
    val subtitlesOpen: Boolean = false,
    /** A subtitle search/download is in flight. */
    val subtitlesLoading: Boolean = false,
    /** Subtitle search results for this title/episode. */
    val subtitleResults: List<SubtitleResult> = emptyList(),
    /** Plain-language subtitle note (no key, none found, quota…). */
    val subtitleMessage: String? = null,
    /** Label of the attached subtitle track, null when off. */
    val activeSubtitleLabel: String? = null,
    /**
     * Autoplay (cycle 5): the next item being offered because the
     * current one finished — shown with a countdown; null when no
     * offer is up.
     */
    val upNext: UpNextItem? = null,
    /** Seconds left before [upNext] starts; 0 when not counting. */
    val upNextSecondsLeft: Int = 0,
    /**
     * Audio tracks inside the current media (cycle 9, §5.6). The
     * picker only appears when this holds a real choice (> 1).
     */
    val audioTracks: List<AudioTrackOption> = emptyList(),
    /** Queue items still to come after this one (autoplay, cycle 9). */
    val upNextQueueSize: Int = 0,
    /** Subtitle sync offset in ms (cycle 9, §6.3), per title/episode. */
    val subtitleOffsetMs: Long = 0L,
    /** Subtitle appearance (cycle 9, §6.2): size + background. */
    val subtitleStyle: SubtitleStyle = SubtitleStyle()
) {
    val currentFile: PlayableFile? get() = files.getOrNull(fileIndex)
    val hasMultipleSources: Boolean get() = files.size > 1

    /** True when a Next action exists (an offer is up, or the queue has more). */
    val hasNext: Boolean get() = upNext != null || upNextQueueSize > 0
    val positionLabel: String get() = PlaybackRules.formatTime(positionMs)
    val durationLabel: String get() = PlaybackRules.formatTime(durationMs)
    val progressFraction: Float
        get() = if (durationMs > 0) {
            (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}

class PlayerViewModel(
    private val request: PlaybackRequest,
    private val playback: PlaybackRepository,
    private val library: WatchlistRepository,
    private val engineFactory: (Context) -> PlayerEngine,
    private val appContext: Context,
    /** Cycle 4: the user's provider keys (OpenSubtitles subtitles). */
    private val providerKeys: ProviderKeysRepository? = null,
    /** Cycle 4: where downloaded subtitle files are cached. */
    private val subtitleDir: java.io.File? = null,
    /** Cycle 5: the autoplay-next settings toggle, read at finish. */
    private val autoplayEnabled: () -> Boolean = { true },
    /**
     * Cycle 5: verifies a queued item really plays before it is
     * offered. Defaults to "nothing else plays" so plain player
     * tests never touch the network.
     */
    private val sourceResolver: suspend (Title) -> ResolvedPlayable =
        { ResolvedPlayable.NOT_PLAYABLE },
    /** Cycle 9: subtitle style store (§6.2); null in plain tests. */
    private val uiPrefs: UiPreferencesRepository? = null,
    /**
     * Cycle 9: builds the MediaSession controller (§5.3) around a
     * real player. Defaults to none so tests with fake engines
     * (player == null) never touch the session layer.
     */
    private val sessionFactory: (Player) -> PlayerMediaSessionController? = { null }
) : ViewModel() {

    /**
     * Persistence scope for the cycle-9 per-item choices (quality,
     * audio language, subtitle offset): the title's stable key plus
     * the episode label, so episodes remember separately.
     */
    private val scopedKey: String = request.title.stableKey +
        (request.episodeLabel?.let { "|$it" } ?: "")

    /** The MediaSession + notification owner; null without a real player. */
    private var mediaSession: PlayerMediaSessionController? = null

    /** The attached subtitle (original file, never the shifted copy). */
    private var activeSubtitle: SubtitleTrack? = null

    /** True once the saved audio language was applied for this item. */
    private var audioChoiceApplied = false

    private val _uiState = MutableStateFlow(
        PlayerUiState(
            title = request.title,
            episodeLabel = request.episodeLabel,
            files = request.files,
            speed = playback.getSpeed(),
            youtubeVideoId = request.youtubeVideoId,
            upNextQueueSize = request.upNext.size,
            subtitleOffsetMs = playback.getSubtitleOffsetMs(scopedKey),
            subtitleStyle = uiPrefs?.subtitleStyle() ?: SubtitleStyle()
        )
    )
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    /** The engine the PlayerView renders (null until started). */
    var engine: PlayerEngine? = null
        private set

    private var resumed = false
    /** Last wall-clock save, for the 5-second throttle (no ticker:
     * an infinite delay loop on the Main dispatcher livelocks
     * kotlinx-coroutines-test's runTest, which advances until idle). */
    private var lastSavedAtMs: Long = 0L

    // ---- Autoplay (cycle 5) ----
    /** Queue items still to consider after the current one. */
    private var remainingQueue: List<UpNextItem> = request.upNext
    /** The offered item + its verified sources, while counting down. */
    private var pendingNext: Pair<UpNextItem, ResolvedPlayable>? = null
    private var countdownJob: Job? = null
    /** Guards the finish path: it must run once per item, not per poll. */
    private var finishHandled = false

    init {
        start()
    }

    private fun start() {
        // Official-YouTube titles render the embedded player in the
        // screen; there is no Media3 source to start.
        if (request.youtubeVideoId != null) return
        if (request.files.isEmpty()) {
            _uiState.update { it.copy(isBuffering = true, errorMessage = null) }
            viewModelScope.launch {
                val seasonEpisodeMatch = request.episodeLabel?.let {
                    Regex("""[sS](d+)s*[:eE](d+)""").find(it)
                }
                val season = seasonEpisodeMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                val episode = seasonEpisodeMatch?.groupValues?.getOrNull(2)?.toIntOrNull()

                val resolved = PlayableSourceResolver.resolve(
                    request.title,
                    com.watchly.app.data.downloads.DownloadsRepository(appContext),
                    season = season,
                    episode = episode
                )
                if (resolved.files.isNotEmpty()) {
                    val directFile = resolved.files.firstOrNull { it.format != "EMBED" }
                    if (directFile != null) {
                        _uiState.update { it.copy(files = resolved.files, isBuffering = false) }
                        startWithFiles(resolved.files)
                    } else if (resolved.webEmbedUrl != null) {
                        _uiState.update { it.copy(webEmbedUrl = resolved.webEmbedUrl, isBuffering = false) }
                    } else {
                        _uiState.update { it.copy(files = resolved.files, isBuffering = false) }
                        startWithFiles(resolved.files)
                    }
                } else if (resolved.webEmbedUrl != null) {
                    _uiState.update { it.copy(webEmbedUrl = resolved.webEmbedUrl, isBuffering = false) }
                } else if (resolved.youtubeVideoId != null) {
                    _uiState.update { it.copy(youtubeVideoId = resolved.youtubeVideoId, isBuffering = false) }
                } else {
                    _uiState.update {
                        it.copy(
                            isBuffering = false,
                            errorMessage = "Couldn't find an active stream for “${request.title.name}”."
                        )
                    }
                }
            }
            return
        }
        startWithFiles(request.files)
    }

    private fun startWithFiles(files: List<PlayableFile>) {
        val engine = engineFactory(appContext)
        this.engine = engine
        engine.setSpeed(_uiState.value.speed)
        // Cycle 9 (§5.3): system media controls for a real player.
        engine.player?.let { player ->
            mediaSession = sessionFactory(player)?.also { controller ->
                controller.onTogglePlayPause = { togglePlayPause() }
                controller.onNext = { skipToNext() }
            }
        }
        viewModelScope.launch {
            engine.state.collect { engineState ->
                onEngineState(engineState)
            }
        }
        // Cycle 9 (§5.7): start from the remembered Quality pick
        // when this title still offers that exact file.
        val initialIndex = initialFileIndex(files)
        if (initialIndex != 0) {
            _uiState.update { it.copy(fileIndex = initialIndex) }
        }
        val effectiveHeaders = files[initialIndex].headers
        engine.setSource(files[initialIndex].url, effectiveHeaders)
        engine.setNowPlaying(request.title.name, request.episodeLabel)
        engine.play()
    }

    fun downloadCurrentMedia() {
        val file = _uiState.value.currentFile ?: return
        if (file.format == "EMBED") return
        viewModelScope.launch {
            val repo = com.watchly.app.data.downloads.DownloadsRepository(appContext)
            val title = request.title
            repo.startDownload(
                title = title,
                file = file,
                // note
            )
        }
    }

    /** Index of the remembered Quality file, or 0 (best first). */
    private fun initialFileIndex(files: List<PlayableFile>): Int {
        val savedUrl = playback.getQualityUrl(scopedKey) ?: return 0
        val index = files.indexOfFirst { it.url == savedUrl }
        return if (index >= 0) index else 0
    }

    private fun onEngineState(engineState: EngineState) {
        val previous = _uiState.value
        // First duration report: apply the saved resume position once.
        if (!resumed && engineState.durationMs > 0) {
            resumed = true
            val saved = playback.getProgress(previous.title.stableKey)
            val resumeAt = saved?.let {
                PlaybackRules.resumePosition(it.positionMs, engineState.durationMs)
            } ?: 0L
            if (resumeAt > 0L) {
                engine?.seekTo(resumeAt)
                _uiState.update { it.copy(resumedFromMs = resumeAt) }
            }
        }
        // A source error: fail over to the next untried source.
        if (engineState.error != null) {
            failOver()
            return
        }
        // Finished: mark watched, clear the resume point, and —
        // once per item — consider the autoplay offer.
        val isFinishedNow = engineState.durationMs > 0 &&
            PlaybackRules.isFinished(engineState.positionMs, engineState.durationMs)
        if (!isFinishedNow) finishHandled = false
        if (isFinishedNow && previous.errorMessage == null && !finishHandled) {
            finishHandled = true
            onFinished()
        }
        _uiState.update {
            it.copy(
                isPlaying = engineState.isPlaying,
                positionMs = engineState.positionMs,
                durationMs = engineState.durationMs,
                bufferedMs = engineState.bufferedMs,
                isBuffering = engineState.isBuffering,
                audioTracks = engineState.audioTracks
            )
        }
        applySavedAudioChoice(engineState.audioTracks)
        pushSessionState()
        // Save every few seconds while playing (throttled here, on the
        // engine's own 500 ms updates — no background ticker).
        if (engineState.isPlaying &&
            System.currentTimeMillis() - lastSavedAtMs >= SAVE_INTERVAL_MS
        ) {
            saveProgress()
        }
    }

    /** Tries the next untried source; errors only when none is left. */
    private fun failOver() {
        val state = _uiState.value
        val failed = state.failedIndexes + state.fileIndex
        val next = state.files.indices.firstOrNull { it !in failed }
        if (next == null) {
            _uiState.update {
                it.copy(
                    failedIndexes = failed,
                    failoverNote = null,
                    errorMessage = "Every source for “${state.title.name}” failed. " +
                        "Try another source."
                )
            }
            return
        }
        _uiState.update {
            it.copy(
                failedIndexes = failed,
                fileIndex = next,
                failoverNote = "This source failed — trying the next one…"
            )
        }
        audioChoiceApplied = false
        engine?.setSource(state.files[next].url)
        engine?.setNowPlaying(state.title.name, state.episodeLabel)
        engine?.setSpeed(_uiState.value.speed)
        // Resume at the same position on the new source, if known.
        val resumeAt = state.positionMs
        if (resumeAt > 0L) engine?.seekTo(resumeAt)
        engine?.play()
    }

    /** Plays a source the user picked manually (error screen). */
    fun playSource(index: Int) {
        val state = _uiState.value
        val file = state.files.getOrNull(index) ?: return
        _uiState.update {
            it.copy(
                fileIndex = index,
                errorMessage = null,
                failoverNote = null,
                failedIndexes = it.failedIndexes - index
            )
        }
        audioChoiceApplied = false
        engine?.setSource(file.url)
        engine?.setNowPlaying(state.title.name, state.episodeLabel)
        engine?.setSpeed(_uiState.value.speed)
        engine?.play()
    }

    /** Retries the current source after a total failure. */
    fun retry() {
        val state = _uiState.value
        if (state.files.isEmpty()) {
            start()
            return
        }
        _uiState.update {
            it.copy(
                errorMessage = null,
                failoverNote = null,
                failedIndexes = it.failedIndexes - it.fileIndex
            )
        }
        audioChoiceApplied = false
        engine?.setSource(state.files[state.fileIndex].url)
        engine?.setNowPlaying(state.title.name, state.episodeLabel)
        engine?.setSpeed(_uiState.value.speed)
        engine?.play()
    }

    fun togglePlayPause() {
        // Taking manual control dismisses an autoplay offer.
        if (_uiState.value.upNext != null) cancelUpNext()
        if (_uiState.value.isPlaying) {
            engine?.pause()
            saveProgress()
        } else {
            engine?.play()
        }
    }

    fun seekBy(deltaMs: Long) {
        if (_uiState.value.upNext != null) cancelUpNext()
        val state = _uiState.value
        engine?.seekTo(PlaybackRules.clampSeek(state.positionMs, deltaMs, state.durationMs))
    }

    fun seekTo(positionMs: Long) {
        if (_uiState.value.upNext != null) cancelUpNext()
        val state = _uiState.value
        engine?.seekTo(positionMs.coerceIn(0L, state.durationMs))
    }

    fun setSpeed(speed: Float) {
        val snapped = PlaybackRules.nearestSpeed(speed)
        playback.saveSpeed(snapped)
        _uiState.update { it.copy(speed = snapped) }
        engine?.setSpeed(snapped)
    }

    /**
     * Long-press speed (§5.2): temporary speed while held — it is
     * never persisted and never changes the saved speed.
     */
    fun holdSpeed(speed: Float) {
        if (_uiState.value.isSpeedHeld) return
        _uiState.update { it.copy(isSpeedHeld = true) }
        engine?.setSpeed(PlaybackRules.nearestSpeed(speed))
    }

    /** Releases a held speed back to the saved speed. No-op otherwise. */
    fun restoreSpeed() {
        if (!_uiState.value.isSpeedHeld) return
        _uiState.update { it.copy(isSpeedHeld = false) }
        engine?.setSpeed(_uiState.value.speed)
    }

    fun dismissFailoverNote() {
        _uiState.update { it.copy(failoverNote = null) }
    }

    // ---------------------------------------------------------------
    // Cycle 9 player pack: quality (§5.7), audio (§5.6), subtitle
    // style (§6.2) + sync (§6.3), MediaSession state (§5.3)
    // ---------------------------------------------------------------

    /**
     * The in-player Quality menu (§5.7): switches to another real
     * file this title offers, keeping the exact position, and
     * remembers the pick for this title/episode. Picking the file
     * already playing is a no-op (unless an error is showing —
     * then it behaves as a retry of that file).
     */
    fun switchQuality(index: Int) {
        val state = _uiState.value
        val file = state.files.getOrNull(index) ?: return
        if (index == state.fileIndex && state.errorMessage == null) return
        val position = state.positionMs
        _uiState.update {
            it.copy(
                fileIndex = index,
                errorMessage = null,
                failoverNote = null,
                failedIndexes = it.failedIndexes - index
            )
        }
        playback.saveQualityUrl(scopedKey, file.url)
        audioChoiceApplied = false
        engine?.setSource(file.url)
        engine?.setNowPlaying(state.title.name, state.episodeLabel)
        engine?.setSpeed(_uiState.value.speed)
        if (position > 0L) engine?.seekTo(position)
        engine?.play()
    }

    /**
     * The in-player Audio menu (§5.6): selects one track by its
     * engine id (null = the player's automatic choice) and
     * remembers the LANGUAGE for this title/episode — the property
     * that survives a different file of the same title.
     */
    fun selectAudioTrack(id: String?) {
        val state = _uiState.value
        engine?.selectAudioTrack(id)
        val chosenLanguage = state.audioTracks.firstOrNull { it.id == id }?.language
        if (chosenLanguage != null) {
            playback.saveAudioLanguage(scopedKey, chosenLanguage)
        } else if (id == null) {
            playback.clearAudioLanguage(scopedKey)
        }
        _uiState.update {
            it.copy(
                audioTracks = it.audioTracks.map { track ->
                    track.copy(isSelected = track.id == id)
                }
            )
        }
    }

    /**
     * Once per loaded item: if the user picked an audio language
     * for this title/episode before, and the file offers it, select
     * it. Runs off the engine's own track reports, so it lands the
     * moment the tracks are known.
     */
    private fun applySavedAudioChoice(tracks: List<AudioTrackOption>) {
        if (audioChoiceApplied || tracks.size < 2) return
        audioChoiceApplied = true
        val language = playback.getAudioLanguage(scopedKey) ?: return
        val match = tracks.firstOrNull {
            it.language == language && !it.isSelected
        } ?: return
        engine?.selectAudioTrack(match.id)
        _uiState.update { state ->
            state.copy(
                audioTracks = state.audioTracks.map {
                    it.copy(isSelected = it.id == match.id)
                }
            )
        }
    }

    /** Subtitle text size (§6.2): persisted, applied live by the screen. */
    fun setSubtitleTextSize(sizeSp: Float) {
        uiPrefs?.setSubtitleTextSizeSp(sizeSp)
        _uiState.update {
            it.copy(
                subtitleStyle = uiPrefs?.subtitleStyle()
                    ?: it.subtitleStyle.copy(textSizeSp = sizeSp)
            )
        }
    }

    /** Subtitle background (§6.2): persisted, applied live by the screen. */
    fun setSubtitleBackground(background: SubtitleBackground) {
        uiPrefs?.setSubtitleBackground(background)
        _uiState.update {
            it.copy(
                subtitleStyle = uiPrefs?.subtitleStyle()
                    ?: it.subtitleStyle.copy(background = background)
            )
        }
    }

    /**
     * Subtitle sync (§6.3): clamps to the ±5s range, persists per
     * title/episode, and re-attaches the current subtitle shifted
     * by the new offset (the file rewrite lives in
     * [SubtitleShifter]; the original file is never modified).
     */
    fun setSubtitleOffsetMs(offsetMs: Long) {
        val clamped = SubtitleShifter.clampOffset(offsetMs)
        playback.saveSubtitleOffsetMs(scopedKey, clamped)
        _uiState.update { it.copy(subtitleOffsetMs = clamped) }
        attachActiveSubtitle()
    }

    /** Attaches the active subtitle, shifted by the saved offset. */
    private fun attachActiveSubtitle() {
        val track = activeSubtitle ?: return
        val offset = _uiState.value.subtitleOffsetMs
        if (offset == 0L) {
            engine?.setSubtitle(track)
            return
        }
        val shifted = shiftedSubtitleFile(track, offset)
        if (shifted == null) {
            // Not a local file we can rewrite (or the write failed):
            // attach the original rather than losing the subtitle.
            engine?.setSubtitle(track)
        } else {
            engine?.setSubtitle(track.copy(url = shifted.toURI().toString()))
        }
    }

    /**
     * Writes a timestamp-shifted copy of the subtitle's local file
     * and returns it; null when the track is not a readable local
     * file. The name carries the offset so each offset value gets
     * its own copy and stale shifts never mix.
     */
    private fun shiftedSubtitleFile(
        track: SubtitleTrack,
        offsetMs: Long
    ): java.io.File? {
        val dir = subtitleDir ?: return null
        val source = runCatching {
            java.io.File(java.net.URI(track.url))
        }.getOrNull() ?: return null
        if (!source.isFile) return null
        val content = runCatching { source.readText() }.getOrNull() ?: return null
        val isVtt = track.mimeType == "text/vtt" ||
            source.name.endsWith(".vtt", ignoreCase = true)
        val shiftedText = if (isVtt) {
            SubtitleShifter.shiftVtt(content, offsetMs)
        } else {
            SubtitleShifter.shiftSrt(content, offsetMs)
        }
        val sign = if (offsetMs < 0) "m${-offsetMs}" else "p$offsetMs"
        val out = java.io.File(
            dir, "${source.nameWithoutExtension}_sync$sign.${source.extension}"
        )
        return runCatching {
            out.writeText(shiftedText)
            out
        }.getOrNull()
    }

    /** Mirrors the visible state into the MediaSession notification. */
    private fun pushSessionState() {
        val state = _uiState.value
        mediaSession?.update(
            title = state.title.name,
            subtitle = state.episodeLabel,
            isPlaying = state.isPlaying,
            hasNext = state.hasNext
        )
    }

    private fun saveProgress() {
        val state = _uiState.value
        if (state.durationMs <= 0) return
        lastSavedAtMs = System.currentTimeMillis()
        if (PlaybackRules.isFinished(state.positionMs, state.durationMs)) {
            finishTitle()
            return
        }
        playback.saveProgress(
            PlaybackProgress(
                key = state.title.stableKey,
                titleName = state.title.name,
                posterUrl = state.title.posterUrl,
                mediaType = state.title.mediaType,
                episodeLabel = state.episodeLabel,
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                updatedAtMs = System.currentTimeMillis()
            )
        )
    }

    private fun finishTitle() {
        val state = _uiState.value
        playback.clearProgress(state.title.stableKey)
        library.setWatched(state.title, true)
        engine?.pause()
        _uiState.update { it.copy(isPlaying = false) }
    }

    // ---------------------------------------------------------------
    // Autoplay next (cycle 5)
    // ---------------------------------------------------------------

    /**
     * The current item finished. Marks it watched (as before) and,
     * when autoplay is on and the queue holds a next item that
     * REALLY plays (verified through the source resolver — info-only
     * items and items whose files fail to load are skipped), offers
     * it with a visible countdown.
     */
    private fun onFinished() {
        finishTitle()
        if (!autoplayEnabled()) return
        advanceToNext(startImmediately = false)
    }

    /**
     * "Next" from the notification / MediaSession (cycle 9, §5.3):
     * when an autoplay offer is already counting down, start it;
     * otherwise skip straight to the next queue item that really
     * plays — no countdown, the tap was deliberate.
     */
    fun skipToNext() {
        if (_uiState.value.upNext != null) {
            playUpNextNow()
            return
        }
        advanceToNext(startImmediately = true)
    }

    /**
     * Finds the next queue item that REALLY plays (verified through
     * the source resolver — info-only items and items whose files
     * fail to load are skipped) and either offers it with the
     * countdown or starts it at once.
     */
    private fun advanceToNext(startImmediately: Boolean) {
        if (remainingQueue.isEmpty()) return
        viewModelScope.launch {
            var fromIndex = 0
            while (fromIndex < remainingQueue.size) {
                val candidate = AutoplayResolver.nextPlayable(
                    remainingQueue, fromIndex
                ) ?: return@launch
                val resolved = runCatching {
                    sourceResolver(candidate.item.title)
                }.getOrNull()
                if (resolved != null && resolved.isPlayable) {
                    remainingQueue = AutoplayResolver.remainingAfter(
                        remainingQueue, candidate
                    )
                    _uiState.update {
                        it.copy(upNextQueueSize = remainingQueue.size)
                    }
                    if (startImmediately) {
                        pendingNext = candidate.item to resolved
                        startUpNext()
                    } else {
                        offerUpNext(candidate.item, resolved)
                    }
                    return@launch
                }
                // Badged playable but nothing actually loads: skip it.
                fromIndex = candidate.queueIndex + 1
            }
        }
    }

    /** Shows the offer and starts the (bounded) countdown. */
    private fun offerUpNext(item: UpNextItem, resolved: ResolvedPlayable) {
        pendingNext = item to resolved
        _uiState.update {
            it.copy(upNext = item, upNextSecondsLeft = COUNTDOWN_SECONDS)
        }
        pushSessionState()
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            var left = COUNTDOWN_SECONDS
            while (left > 0) {
                delay(1_000L)
                left -= 1
                _uiState.update { it.copy(upNextSecondsLeft = left) }
            }
            startUpNext()
        }
    }

    /** Cancels the offer (user tapped Cancel, or took control). */
    fun cancelUpNext() {
        countdownJob?.cancel()
        countdownJob = null
        pendingNext = null
        _uiState.update { it.copy(upNext = null, upNextSecondsLeft = 0) }
        pushSessionState()
    }

    /** Skips the countdown and starts the offered item now. */
    fun playUpNextNow() {
        if (_uiState.value.upNext == null) return
        countdownJob?.cancel()
        countdownJob = null
        startUpNext()
    }

    /** Loads the offered item into the player (or the YouTube embed). */
    private fun startUpNext() {
        val (item, resolved) = pendingNext ?: return
        pendingNext = null
        finishHandled = false
        resumed = false
        // The new item starts clean (cycle 9): no subtitle carried
        // over, and its own saved audio choice may apply.
        activeSubtitle = null
        audioChoiceApplied = false
        val youtubeId = resolved.youtubeVideoId
        _uiState.update {
            it.copy(
                title = item.title,
                episodeLabel = item.episodeLabel,
                files = resolved.files,
                fileIndex = 0,
                isPlaying = false,
                positionMs = 0L,
                durationMs = 0L,
                bufferedMs = 0L,
                isBuffering = false,
                resumedFromMs = null,
                failoverNote = null,
                errorMessage = null,
                failedIndexes = emptySet(),
                youtubeVideoId = youtubeId,
                subtitlesOpen = false,
                subtitlesLoading = false,
                subtitleResults = emptyList(),
                subtitleMessage = null,
                activeSubtitleLabel = null,
                upNext = null,
                upNextSecondsLeft = 0
            )
        }
        if (youtubeId == null && resolved.files.isNotEmpty()) {
            val activeEngine = engine ?: engineFactory(appContext).also { created ->
                engine = created
                viewModelScope.launch {
                    created.state.collect { engineState -> onEngineState(engineState) }
                }
                // Same session wiring as start() (cycle 9): an
                // engine born here (YouTube item before this one)
                // still gets system media controls.
                created.player?.let { player ->
                    mediaSession = sessionFactory(player)?.also { controller ->
                        controller.onTogglePlayPause = { togglePlayPause() }
                        controller.onNext = { skipToNext() }
                    }
                }
            }
            activeEngine.setSpeed(_uiState.value.speed)
            activeEngine.setSource(resolved.files.first().url)
            activeEngine.setNowPlaying(item.title.name, item.episodeLabel)
            activeEngine.play()
        }
        pushSessionState()
    }

    /**
     * Saves the position once and releases the engine. Called by
     * [onCleared]; also public so tests can release the fake engine
     * without a lifecycle (ViewModel.onCleared is protected).
     */
    fun close() {
        countdownJob?.cancel()
        saveProgress()
        mediaSession?.release()
        mediaSession = null
        engine?.release()
    }

    // ---------------------------------------------------------------
    // Subtitles (cycle 4, OpenSubtitles)
    // ---------------------------------------------------------------

    /**
     * Opens the subtitle picker and loads candidates for this title
     * (and episode). Every state the picker can be in — no key, no
     * IMDb id, searching, none found, quota spent, provider down —
     * is a plain-language state here, never a crash or a spinner
     * that never ends.
     */
    fun openSubtitles() {
        _uiState.update { it.copy(subtitlesOpen = true) }
        val key = providerKeys?.getKey(ApiProvider.OPENSUBTITLES)
        if (key == null) {
            _uiState.update {
                it.copy(
                    subtitleResults = emptyList(),
                    subtitleMessage = "Subtitles come from OpenSubtitles. Add " +
                        "your free OpenSubtitles key in My Library → API Keys, " +
                        "then reopen this menu."
                )
            }
            return
        }
        val plan = OpenSubtitlesApi.planSearch(request.title.imdbId, request.episodeLabel)
        if (plan == null) {
            _uiState.update {
                it.copy(
                    subtitleResults = emptyList(),
                    subtitleMessage = "This title has no IMDb id, so " +
                        "OpenSubtitles can't be searched for it."
                )
            }
            return
        }
        _uiState.update {
            it.copy(subtitlesLoading = true, subtitleMessage = null, subtitleResults = emptyList())
        }
        viewModelScope.launch {
            val outcome = runCatching { OpenSubtitlesApi.search(key, plan) }
                .getOrElse { SubtitleSearchOutcome.Failed }
            _uiState.update { state ->
                when (outcome) {
                    is SubtitleSearchOutcome.Found -> state.copy(
                        subtitlesLoading = false,
                        subtitleResults = outcome.results,
                        subtitleMessage = if (outcome.results.isEmpty()) {
                            "No subtitles found for this title on OpenSubtitles."
                        } else {
                            null
                        }
                    )
                    SubtitleSearchOutcome.QuotaReached -> state.copy(
                        subtitlesLoading = false,
                        subtitleMessage = QUOTA_MESSAGE
                    )
                    SubtitleSearchOutcome.Failed -> state.copy(
                        subtitlesLoading = false,
                        subtitleMessage = "Couldn't reach OpenSubtitles."
                    )
                }
            }
        }
    }

    fun closeSubtitles() {
        _uiState.update { it.copy(subtitlesOpen = false) }
    }

    /**
     * Downloads the picked subtitle (spending one unit of the
     * account's daily quota), caches it on-device and attaches it
     * to the running player without losing the position.
     */
    fun pickSubtitle(result: SubtitleResult) {
        val key = providerKeys?.getKey(ApiProvider.OPENSUBTITLES) ?: return
        val dir = subtitleDir
        if (dir == null) {
            _uiState.update {
                it.copy(subtitleMessage = "Subtitles aren't available in this build.")
            }
            return
        }
        _uiState.update { it.copy(subtitlesLoading = true, subtitleMessage = null) }
        viewModelScope.launch {
            val outcome = runCatching {
                OpenSubtitlesApi.downloadLink(key, result.fileId)
            }.getOrElse { SubtitleDownloadOutcome.Failed }
            when (outcome) {
                is SubtitleDownloadOutcome.Ready -> {
                    val bytes = runCatching {
                        OpenSubtitlesApi.downloadFileBytes(outcome.link)
                    }.getOrNull()
                    val file = bytes?.let { writeSubtitleFile(dir, outcome.fileName, it) }
                    if (file == null) {
                        _uiState.update {
                            it.copy(
                                subtitlesLoading = false,
                                subtitleMessage = "That subtitle couldn't be " +
                                    "downloaded. Try another one."
                            )
                        }
                    } else {
                        // Cycle 9: remember the ORIGINAL track so a
                        // sync offset (§6.3) can re-attach it shifted;
                        // the attach itself applies any saved offset.
                        activeSubtitle = SubtitleTrack(
                            url = file.toURI().toString(),
                            mimeType = mimeTypeFor(outcome.fileName),
                            language = result.language,
                            label = result.displayLabel
                        )
                        attachActiveSubtitle()
                        _uiState.update {
                            it.copy(
                                subtitlesLoading = false,
                                subtitlesOpen = false,
                                activeSubtitleLabel = result.displayLabel,
                                subtitleMessage = null
                            )
                        }
                    }
                }
                SubtitleDownloadOutcome.QuotaReached -> _uiState.update {
                    it.copy(subtitlesLoading = false, subtitleMessage = QUOTA_MESSAGE)
                }
                SubtitleDownloadOutcome.Failed -> _uiState.update {
                    it.copy(
                        subtitlesLoading = false,
                        subtitleMessage = "That subtitle couldn't be downloaded. " +
                            "Try another one."
                    )
                }
            }
        }
    }

    /** Turns subtitles off (the cached file stays for next time). */
    fun clearSubtitles() {
        activeSubtitle = null
        engine?.setSubtitle(null)
        _uiState.update { it.copy(activeSubtitleLabel = null) }
    }

    /** Saves a downloaded subtitle into the cache dir; null on failure. */
    private fun writeSubtitleFile(
        dir: java.io.File,
        fileName: String,
        bytes: ByteArray
    ): java.io.File? = runCatching {
        dir.mkdirs()
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "subtitle.srt" }
        java.io.File(dir, safeName).also { it.writeBytes(bytes) }
    }.getOrNull()

    /** Media3 subtitle MIME type from the file extension. */
    private fun mimeTypeFor(fileName: String): String =
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "vtt" -> "text/vtt"
            "ass", "ssa" -> "text/x-ssa"
            "ttml", "xml" -> "application/ttml+xml"
            else -> "application/x-subrip" // .srt and anything unknown
        }

    override fun onCleared() {
        close()
        super.onCleared()
    }

    companion object {
        const val SAVE_INTERVAL_MS: Long = 5_000L

        /** Autoplay offer countdown, in seconds (cycle 5). */
        const val COUNTDOWN_SECONDS: Int = 10

        /** Shown when OpenSubtitles' daily free allowance is spent. */
        const val QUOTA_MESSAGE: String =
            "Daily subtitle limit used up. Resets tomorrow."
    }
}

/** Factory so the nav destination can create the ViewModel per request. */
class PlayerViewModelFactory(
    private val request: PlaybackRequest,
    private val context: Context
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PlayerViewModel::class.java)) {
            val appContext = context.applicationContext
            return PlayerViewModel(
                request = request,
                playback = PlaybackRepository(appContext),
                library = WatchlistRepository(appContext),
                engineFactory = { ctx -> ExoPlayerEngine(ctx, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)) },
                appContext = appContext,
                providerKeys = ProviderKeysRepository(appContext),
                subtitleDir = java.io.File(appContext.cacheDir, "subtitles"),
                uiPrefs = UiPreferencesRepository(appContext),
                sessionFactory = { player ->
                    PlayerMediaSessionController(appContext, player)
                },
                autoplayEnabled = {
                    com.watchly.app.data.settings.UiPreferencesRepository(appContext)
                        .autoplayNext()
                },
                sourceResolver = { title ->
                    PlayableSourceResolver.resolve(
                        title,
                        com.watchly.app.data.downloads.DownloadsRepository(appContext)
                    )
                }
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: $modelClass")
    }
}
