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
import com.watchly.app.data.library.WatchlistRepository
import com.watchly.app.data.model.PlayableFile
import com.watchly.app.data.model.Title
import com.watchly.app.data.playback.EngineState
import com.watchly.app.data.playback.ExoPlayerEngine
import com.watchly.app.data.playback.PlaybackProgress
import com.watchly.app.data.playback.PlaybackRepository
import com.watchly.app.data.playback.PlaybackRules
import com.watchly.app.data.playback.PlayerEngine
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
    val failedIndexes: Set<Int> = emptySet()
) {
    val currentFile: PlayableFile? get() = files.getOrNull(fileIndex)
    val hasMultipleSources: Boolean get() = files.size > 1
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
    private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        PlayerUiState(
            title = request.title,
            episodeLabel = request.episodeLabel,
            files = request.files,
            speed = playback.getSpeed()
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

    init {
        start()
    }

    private fun start() {
        if (request.files.isEmpty()) {
            _uiState.update {
                it.copy(errorMessage = "Nothing to play for “${request.title.name}” yet.")
            }
            return
        }
        val engine = engineFactory(appContext)
        this.engine = engine
        engine.setSpeed(_uiState.value.speed)
        viewModelScope.launch {
            engine.state.collect { engineState ->
                onEngineState(engineState)
            }
        }
        engine.setSource(_uiState.value.files.first().url)
        engine.play()
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
        // Finished: mark watched, clear the resume point.
        if (engineState.durationMs > 0 &&
            PlaybackRules.isFinished(engineState.positionMs, engineState.durationMs) &&
            previous.errorMessage == null
        ) {
            finishTitle()
        }
        _uiState.update {
            it.copy(
                isPlaying = engineState.isPlaying,
                positionMs = engineState.positionMs,
                durationMs = engineState.durationMs,
                bufferedMs = engineState.bufferedMs,
                isBuffering = engineState.isBuffering
            )
        }
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
                        "Try another source, or check your connection."
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
        engine?.setSource(state.files[next].url)
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
        engine?.setSource(file.url)
        engine?.setSpeed(_uiState.value.speed)
        engine?.play()
    }

    /** Retries the current source after a total failure. */
    fun retry() {
        val state = _uiState.value
        _uiState.update {
            it.copy(
                errorMessage = null,
                failoverNote = null,
                failedIndexes = it.failedIndexes - it.fileIndex
            )
        }
        engine?.setSource(state.files[state.fileIndex].url)
        engine?.setSpeed(_uiState.value.speed)
        engine?.play()
    }

    fun togglePlayPause() {
        if (_uiState.value.isPlaying) {
            engine?.pause()
            saveProgress()
        } else {
            engine?.play()
        }
    }

    fun seekBy(deltaMs: Long) {
        val state = _uiState.value
        engine?.seekTo(PlaybackRules.clampSeek(state.positionMs, deltaMs, state.durationMs))
    }

    fun seekTo(positionMs: Long) {
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

    /**
     * Saves the position once and releases the engine. Called by
     * [onCleared]; also public so tests can release the fake engine
     * without a lifecycle (ViewModel.onCleared is protected).
     */
    fun close() {
        saveProgress()
        engine?.release()
    }

    override fun onCleared() {
        close()
        super.onCleared()
    }

    companion object {
        const val SAVE_INTERVAL_MS: Long = 5_000L
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
                appContext = appContext
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: $modelClass")
    }
}
