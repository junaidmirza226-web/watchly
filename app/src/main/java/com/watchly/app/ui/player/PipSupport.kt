/*
 * Watchly — Picture-in-Picture support (cycle 9, spec §5.3).
 *
 * Watchly is a single-activity app, so PiP shrinks the whole
 * activity; [PipState] is the small bridge between the pieces:
 *  - PlayerScreen registers itself while it is on screen (and
 *    whether playback is running),
 *  - MainActivity enters PiP on the Home press when that makes
 *    sense, and publishes the in-PiP flag the screen uses to hide
 *    every control (a PiP window shows the picture, nothing else),
 *  - the PiP window's own play/pause button broadcasts to
 *    [PipActionReceiver], which forwards to the live player.
 *
 * Closing the PiP window (its X) finishes the activity — the
 * platform behaviour for activity PiP — which releases the player
 * through the normal ViewModel teardown.
 */
package com.watchly.app.ui.player

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Shared PiP state between MainActivity, the player and the PiP button. */
object PipState {

    /** True while the Media3 player screen is composed. */
    @Volatile
    var playerVisible: Boolean = false

    /** Live "is anything playing" probe, registered by the screen. */
    @Volatile
    var isPlaying: () -> Boolean = { false }

    /** Play/pause toggle for the PiP window's own button. */
    @Volatile
    var togglePlayPause: (() -> Unit)? = null

    private val _inPictureInPicture = MutableStateFlow(false)

    /** True while the activity is shrunk into the PiP window. */
    val inPictureInPicture: StateFlow<Boolean> = _inPictureInPicture.asStateFlow()

    fun setInPictureInPicture(value: Boolean) {
        _inPictureInPicture.value = value
    }

    /** Auto-PiP on Home (§5.3): only for a playing player. */
    fun shouldAutoEnter(): Boolean = playerVisible && isPlaying()
}

/** The PiP window's play/pause button lands here. */
class PipActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE_PLAY_PAUSE) {
            PipState.togglePlayPause?.invoke()
        }
    }

    companion object {
        const val ACTION_TOGGLE_PLAY_PAUSE = "com.watchly.app.pip.TOGGLE_PLAY_PAUSE"
    }
}

/** Pure PiP decisions — kept Android-light so tests can pin them. */
object PipDecisions {

    // Android rejects PiP aspect ratios outside ≈[0.41841, 2.39048].
    private val MAX_RATIO = Rational(239, 100)
    private val MIN_RATIO = Rational(100, 239)

    /**
     * The PiP aspect ratio for a video of [width]×[height], clamped
     * to the range Android accepts; 16:9 when the size is unknown
     * (or not a real size at all).
     */
    fun aspectRatioFor(width: Int, height: Int): Rational {
        if (width <= 0 || height <= 0) return Rational(16, 9)
        val ratio = width.toDouble() / height.toDouble()
        return when {
            ratio > MAX_RATIO.toDouble() -> MAX_RATIO
            ratio < MIN_RATIO.toDouble() -> MIN_RATIO
            else -> Rational(width, height)
        }
    }
}

/**
 * PiP params for the current playback: 16:9 window (no reliable
 * video size at this layer), one play/pause action whose icon
 * matches [isPlaying].
 */
fun buildPipParams(context: Context, isPlaying: Boolean): PictureInPictureParams {
    val toggleIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, PipActionReceiver::class.java)
            .setAction(PipActionReceiver.ACTION_TOGGLE_PLAY_PAUSE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val action = RemoteAction(
        Icon.createWithResource(
            context,
            if (isPlaying) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        ),
        if (isPlaying) "Pause" else "Play",
        if (isPlaying) "Pause playback" else "Play playback",
        toggleIntent
    )
    val builder = PictureInPictureParams.Builder()
        .setAspectRatio(PipDecisions.aspectRatioFor(16, 9))
        .setActions(listOf(action))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // Entry is manual (PiP button / Home hook); seamless resize
        // keeps the picture smooth while the window settles.
        builder.setAutoEnterEnabled(false)
        builder.setSeamlessResizeEnabled(true)
    }
    return builder.build()
}

/** Enters PiP with fresh params; false when the system refuses. */
fun Activity.enterWatchlyPip(isPlaying: Boolean): Boolean =
    runCatching {
        enterPictureInPictureMode(buildPipParams(this, isPlaying))
    }.getOrDefault(false)
