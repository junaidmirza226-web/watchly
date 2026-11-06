/*
 * Watchly — the player engine behind the UI (spec §5.1).
 *
 * The ViewModel only ever talks to [PlayerEngine]: a thin wrapper
 * around one Media3 ExoPlayer. That split is what makes the player
 * testable on this VM — the Robolectric tests drive a fake engine
 * (no real ExoPlayer, no surface), while the phone uses
 * [ExoPlayerEngine]. The engine owns the ExoPlayer instance; the
 * Compose screen renders it through a PlayerView bound to
 * [PlayerEngine.player].
 *
 * Media3 plays MP4, MKV (where the device supports it), HLS and DASH.
 * An unsupported file surfaces as [EngineState.error], never a crash,
 * and the ViewModel's failover then tries the next source (spec §13.2).
 */
package com.watchly.app.data.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the UI needs to know about the engine, polled twice a second. */
data class EngineState(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val isBuffering: Boolean = false,
    /** Non-null when the current source failed; cleared on a new source. */
    val error: String? = null
)

/** Minimal player contract the ViewModel drives. */
interface PlayerEngine {
    /** The Media3 player for the PlayerView, or null for fakes. */
    val player: Player?
    val state: StateFlow<EngineState>
    fun setSource(url: String)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setSpeed(speed: Float)
    fun release()
}

/** Real engine: one ExoPlayer, created on the caller's scope. */
class ExoPlayerEngine(
    context: Context,
    private val scope: CoroutineScope
) : PlayerEngine {

    private val exoPlayer: ExoPlayer = ExoPlayer.Builder(context).build()

    private val _state = MutableStateFlow(EngineState())
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    override val player: Player get() = exoPlayer

    private var pollJob: Job? = null

    init {
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                publish()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                publish()
                if (isPlaying) startPolling() else stopPolling()
            }

            override fun onPlayerError(error: PlaybackException) {
                _state.value = snapshot(error = plainError(error))
            }
        })
    }

    override fun setSource(url: String) {
        _state.value = EngineState()
        exoPlayer.setMediaItem(MediaItem.fromUri(url))
        exoPlayer.prepare()
    }

    override fun play() {
        exoPlayer.play()
    }

    override fun pause() {
        exoPlayer.pause()
        publish()
    }

    override fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs.coerceAtLeast(0L))
        publish()
    }

    override fun setSpeed(speed: Float) {
        exoPlayer.setPlaybackSpeed(speed)
    }

    override fun release() {
        stopPolling()
        exoPlayer.release()
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                publish()
                delay(POLL_MS)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun publish() {
        _state.value = snapshot()
    }

    private fun snapshot(error: String? = null): EngineState = EngineState(
        isPlaying = exoPlayer.isPlaying,
        positionMs = exoPlayer.currentPosition.coerceAtLeast(0L),
        durationMs = exoPlayer.duration.takeIf { it > 0 } ?: 0L,
        bufferedMs = exoPlayer.bufferedPosition.coerceAtLeast(0L),
        isBuffering = exoPlayer.playbackState == Player.STATE_BUFFERING,
        error = error
    )

    private fun plainError(error: PlaybackException): String =
        when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "Couldn't reach this source. Check your connection."
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                "This file couldn't be found on the source."
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ->
                "This file uses a format this phone can't play."
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
                "This file's format isn't supported."
            else -> "This source couldn't be played."
        }

    companion object {
        const val POLL_MS: Long = 500L
    }
}
