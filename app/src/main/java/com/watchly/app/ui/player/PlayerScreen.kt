/*
 * Watchly — Media3 player screen (spec §5.1 core, §5.2 gestures,
 * §5.5 speed, §13.2 failover UI).
 *
 * A Media3 PlayerView renders the video (its own controls off); every
 * control the user sees is Compose in the black + gold theme:
 * back, title, play/pause, seek bar with time labels, ±10s buttons,
 * speed menu, and the failover / error states.
 *
 * Gestures (§5.2): single tap shows/hides the controls; double-tap
 * left = −10s, right = +10s, with a brief flash; long-press holds 2×
 * speed until the finger lifts; vertical swipe on the right adjusts
 * volume, on the left brightness; horizontal swipe scrubs with a
 * live time preview and seeks on release.
 */
package com.watchly.app.ui.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.PlayerView
import com.watchly.app.data.playback.PlaybackRules
import com.watchly.app.ui.theme.WatchlyGold
import com.watchly.app.ui.theme.WatchlyGoldLight

@Composable
fun PlayerScreen(
    request: PlaybackRequest,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val viewModel: PlayerViewModel = viewModel(
        factory = PlayerViewModelFactory(request, context)
    )
    val state by viewModel.uiState.collectAsState()
    val engine = viewModel.engine

    // System back button / gesture exits the player through onBack,
    // exactly like the on-screen back arrow.
    BackHandler { onBack() }

    var controlsVisible by remember { mutableStateOf(true) }
    var speedMenuOpen by remember { mutableStateOf(false) }
    var seekFlash by remember { mutableStateOf<String?>(null) }
    // One-line hint while a swipe gesture runs ("Volume 60%").
    var gestureHint by remember { mutableStateOf<String?>(null) }
    // Which side a vertical swipe started on, and its starting level.
    val gesture = remember { PlayerGestureState() }

    // Keep the screen on while watching; restore when leaving.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // Auto-hide the controls a few seconds after they appear while playing.
    LaunchedEffect(controlsVisible, state.isPlaying) {
        if (controlsVisible && state.isPlaying) {
            kotlinx.coroutines.delay(CONTROLS_HIDE_MS)
            controlsVisible = false
        }
    }
    // Clear a seek flash shortly after it shows.
    LaunchedEffect(seekFlash) {
        if (seekFlash != null) {
            kotlinx.coroutines.delay(700)
            seekFlash = null
        }
    }
    // Clear a gesture hint shortly after the gesture ends.
    LaunchedEffect(gestureHint) {
        if (gestureHint != null) {
            kotlinx.coroutines.delay(900)
            gestureHint = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { controlsVisible = !controlsVisible },
                    onDoubleTap = { offset ->
                        // Left half = back 10s, right half = forward 10s.
                        val delta = if (offset.x < size.width / 2) {
                            -PlaybackRules.SEEK_STEP_MS
                        } else {
                            PlaybackRules.SEEK_STEP_MS
                        }
                        viewModel.seekBy(delta)
                        seekFlash = if (delta < 0) "−10s" else "+10s"
                        controlsVisible = true
                    },
                    onLongPress = {
                        // Hold 2× speed until the finger lifts (§5.2).
                        viewModel.holdSpeed(2.0f)
                    },
                    onPress = {
                        // Finger up ends a held speed (no-op otherwise).
                        awaitRelease()
                        viewModel.restoreSpeed()
                    }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        viewModel.restoreSpeed()
                        gesture.start(
                            x = offset.x,
                            width = size.width.toFloat(),
                            height = size.height.toFloat(),
                            startPositionMs = viewModel.uiState.value.positionMs,
                            durationMs = viewModel.uiState.value.durationMs,
                            context = context
                        )
                    },
                    onDragCancel = {
                        gesture.reset()
                        gestureHint = null
                    },
                    onDragEnd = {
                        if (gesture.isScrub) {
                            viewModel.seekTo(gesture.scrubPreviewMs)
                        }
                        gesture.reset()
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        gesture.applyDrag(dragAmount, context)?.let { hint ->
                            gestureHint = hint
                        }
                    }
                )
            }
    ) {
        if (engine?.player != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = engine.player
                        useController = false
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (state.isBuffering && state.errorMessage == null) {
            CircularProgressIndicator(
                color = WatchlyGold,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        seekFlash?.let { flash ->
            Text(
                text = flash,
                style = MaterialTheme.typography.displaySmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            )
        }

        // Live hint while a swipe runs ("Volume 60%", "12:34").
        gestureHint?.let { hint ->
            Text(
                text = hint,
                style = MaterialTheme.typography.titleLarge,
                color = WatchlyGold,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            )
        }

        // Held 2× speed indicator (long-press, §5.2).
        if (state.isSpeedHeld) {
            Text(
                text = "2×",
                style = MaterialTheme.typography.titleLarge,
                color = Color(0xFF1A1405),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(WatchlyGold)
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }

        if (controlsVisible) {
            PlayerControls(
                state = state,
                onBack = onBack,
                onTogglePlay = viewModel::togglePlayPause,
                onSeekBy = viewModel::seekBy,
                onSeekTo = viewModel::seekTo,
                onSpeedClick = { speedMenuOpen = true },
                speedMenuOpen = speedMenuOpen,
                onSpeedDismiss = { speedMenuOpen = false },
                onSpeedPick = { speed ->
                    viewModel.setSpeed(speed)
                    speedMenuOpen = false
                }
            )
        }

        state.failoverNote?.let { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.bodyMedium,
                color = WatchlyGoldLight,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 110.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }

        state.resumedFromMs?.let { resumedAt ->
            Text(
                text = "Resumed from ${PlaybackRules.formatTime(resumedAt)}",
                style = MaterialTheme.typography.bodyMedium,
                color = WatchlyGoldLight,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }

        state.errorMessage?.let { message ->
            PlayerErrorPanel(
                message = message,
                state = state,
                onRetry = viewModel::retry,
                onPickSource = viewModel::playSource,
                onBack = onBack,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

@Composable
private fun PlayerControls(
    state: PlayerUiState,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onSpeedClick: () -> Unit,
    speedMenuOpen: Boolean,
    onSpeedDismiss: () -> Unit,
    onSpeedPick: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar: back + title.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = WatchlyGold
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.title.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                state.episodeLabel?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = WatchlyGoldLight
                    )
                }
            }
            // Speed menu.
            Box {
                OutlinedButton(onClick = onSpeedClick) {
                    Text(
                        text = "${state.speed}x",
                        color = WatchlyGold
                    )
                }
                DropdownMenu(
                    expanded = speedMenuOpen,
                    onDismissRequest = onSpeedDismiss
                ) {
                    PlaybackRules.SPEEDS.forEach { speed ->
                        DropdownMenuItem(
                            text = { Text(if (speed == 1.0f) "1x (normal)" else "${speed}x") },
                            onClick = { onSpeedPick(speed) }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Centre play / pause.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { onSeekBy(-PlaybackRules.SEEK_STEP_MS) },
                modifier = Modifier.size(56.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Replay10,
                    contentDescription = "Back 10 seconds",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }
            Spacer(Modifier.width(24.dp))
            IconButton(
                onClick = onTogglePlay,
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(36.dp))
                    .background(WatchlyGold)
            ) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                    tint = Color(0xFF1A1405),
                    modifier = Modifier.size(40.dp)
                )
            }
            Spacer(Modifier.width(24.dp))
            IconButton(
                onClick = { onSeekBy(PlaybackRules.SEEK_STEP_MS) },
                modifier = Modifier.size(56.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Forward10,
                    contentDescription = "Forward 10 seconds",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Bottom: seek bar + times.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Slider(
                value = state.progressFraction,
                onValueChange = { fraction ->
                    onSeekTo((fraction * state.durationMs).toLong())
                },
                colors = SliderDefaults.colors(
                    thumbColor = WatchlyGold,
                    activeTrackColor = WatchlyGold,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                )
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = state.positionLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White
                )
                Text(
                    text = state.durationLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun PlayerErrorPanel(
    message: String,
    state: PlayerUiState,
    onRetry: () -> Unit,
    onPickSource: (Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth(0.85f)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1A1A1A))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Couldn't play this one",
            style = MaterialTheme.typography.titleLarge,
            color = WatchlyGold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White
        )
        if (state.hasMultipleSources) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Try another source:",
                style = MaterialTheme.typography.labelLarge,
                color = WatchlyGoldLight
            )
            Spacer(Modifier.height(8.dp))
            state.files.forEachIndexed { index, file ->
                val failed = index in state.failedIndexes
                OutlinedButton(
                    onClick = { onPickSource(index) },
                    enabled = !failed,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = listOfNotNull(file.format, file.sizeLabel)
                            .joinToString(" • ")
                            .ifEmpty { file.fileName } +
                            if (failed) " (failed)" else "",
                        color = if (failed) Color.Gray else WatchlyGold
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = WatchlyGold,
                    contentColor = Color(0xFF1A1405)
                )
            ) {
                Text("Retry")
            }
            OutlinedButton(onClick = onBack) {
                Text("Back to details", color = WatchlyGold)
            }
        }
    }
}

private const val CONTROLS_HIDE_MS: Long = 4_000L

/**
 * Mutable swipe-gesture state for the player (§5.2).
 *
 * One drag is one gesture: the dominant axis on the first movement
 * decides the mode, and the mode never changes mid-drag.
 *  - Vertical, right half → volume (AudioManager music stream).
 *  - Vertical, left half → screen brightness (this window only).
 *  - Horizontal → scrub; the preview time is shown live and the
 *    seek is committed when the finger lifts.
 *
 * Kept in a plain holder (not Compose state) because it is written
 * from pointer-input callbacks; only the hint text it returns is
 * Compose state. Pure arithmetic lives in [applyDrag] so the mode
 * choice is unit-testable through [PlaybackRules.clampSeek].
 */
private class PlayerGestureState {
    private var mode: Mode? = null
    private var startX: Float = 0f
    private var width: Float = 1f
    private var height: Float = 1f
    private var startPositionMs: Long = 0L
    private var durationMs: Long = 0L
    private var totalDx: Float = 0f
    private var totalDy: Float = 0f
    private var startVolume: Int = 0
    private var maxVolume: Int = 1
    private var startBrightness: Float = 0.5f
    var scrubPreviewMs: Long = 0L
        private set

    val isScrub: Boolean get() = mode == Mode.SCRUB

    fun start(
        x: Float,
        width: Float,
        height: Float,
        startPositionMs: Long,
        durationMs: Long,
        context: Context
    ) {
        mode = null
        startX = x
        this.width = width.coerceAtLeast(1f)
        this.height = height.coerceAtLeast(1f)
        this.startPositionMs = startPositionMs
        this.durationMs = durationMs
        totalDx = 0f
        totalDy = 0f
        scrubPreviewMs = startPositionMs
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        maxVolume = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 1
        startVolume = audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        val windowBrightness = (context as? Activity)?.window?.attributes?.screenBrightness
        startBrightness = if (windowBrightness != null && windowBrightness >= 0f) {
            windowBrightness
        } else {
            0.5f
        }
    }

    /**
     * Applies one drag step. Returns the hint text to show, or null
     * when the gesture has not moved far enough to pick a mode yet.
     */
    fun applyDrag(dragAmount: androidx.compose.ui.geometry.Offset, context: Context): String? {
        totalDx += dragAmount.x
        totalDy += dragAmount.y
        if (mode == null) {
            if (kotlin.math.abs(totalDx) < DRAG_SLOP_PX &&
                kotlin.math.abs(totalDy) < DRAG_SLOP_PX
            ) {
                return null
            }
            mode = if (kotlin.math.abs(totalDy) > kotlin.math.abs(totalDx)) {
                if (startX < width / 2) Mode.BRIGHTNESS else Mode.VOLUME
            } else {
                Mode.SCRUB
            }
        }
        return when (mode) {
            Mode.VOLUME -> {
                // Full-height swipe = the whole volume range.
                val fraction = (-totalDy / height).coerceIn(-1f, 1f)
                val level = (startVolume + fraction * maxVolume)
                    .toInt().coerceIn(0, maxVolume)
                (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
                    ?.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
                "Volume ${(level * 100 / maxVolume.coerceAtLeast(1))}%"
            }
            Mode.BRIGHTNESS -> {
                val fraction = (-totalDy / height).coerceIn(-1f, 1f)
                val level = (startBrightness + fraction).coerceIn(0.05f, 1f)
                (context as? Activity)?.window?.let { window ->
                    val attrs = window.attributes
                    attrs.screenBrightness = level
                    window.attributes = attrs
                }
                "Brightness ${(level * 100).toInt()}%"
            }
            Mode.SCRUB -> {
                // Full-width swipe = the whole video.
                val deltaMs = (totalDx / width * durationMs).toLong()
                scrubPreviewMs = PlaybackRules.clampSeek(startPositionMs, deltaMs, durationMs)
                PlaybackRules.formatTime(scrubPreviewMs)
            }
            null -> null
        }
    }

    fun reset() {
        mode = null
        totalDx = 0f
        totalDy = 0f
    }

    private enum class Mode { VOLUME, BRIGHTNESS, SCRUB }

    companion object {
        const val DRAG_SLOP_PX: Float = 24f
    }
}
