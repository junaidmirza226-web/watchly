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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.watchly.app.data.model.SubtitleBackground
import com.watchly.app.data.model.SubtitleStyle
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
    var audioMenuOpen by remember { mutableStateOf(false) }
    var qualityMenuOpen by remember { mutableStateOf(false) }
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

    // Official-YouTube titles: YouTube's own embedded player fills
    // the screen (Media3 never sees these — see YouTubeEmbed.kt).
    val youtubeVideoId = state.youtubeVideoId
    if (youtubeVideoId != null) {
        YouTubePlayerLayout(
            videoId = youtubeVideoId,
            titleName = state.title.name,
            episodeLabel = state.episodeLabel,
            onBack = onBack,
            modifier = modifier
        )
        return
    }

    val webEmbedUrl = state.webEmbedUrl
    if (webEmbedUrl != null) {
        WebStreamPlayerLayout(
            streamUrl = webEmbedUrl,
            titleName = state.title.name,
            episodeLabel = state.episodeLabel,
            onBack = onBack,
            modifier = modifier,
            servers = state.embedServers,
            onSelectServer = viewModel::selectWebServer,
            hasNextEpisode = state.upNextQueueSize > 0,
            onNextEpisode = viewModel::skipToNextWebEpisode,
            onPromoteDirectStream = viewModel::promoteSniffedDirectStream,
            onSourceDead = viewModel::embedSourceFailed,
            statusNote = state.failoverNote,
            errorMessage = state.errorMessage,
            onRetry = viewModel::retryEmbeds,
            reloadTick = state.embedReloadTick
        )
        return
    }

    // Picture-in-Picture (cycle 9, §5.3): register this player with
    // the PiP bridge while it is on screen — MainActivity's Home
    // hook reads it, the PiP window's own button calls back into
    // the ViewModel, and inPip hides every control below (a PiP
    // window shows the picture only).
    val activity = LocalContext.current as? Activity
    val inPip by PipState.inPictureInPicture.collectAsState()
    DisposableEffect(Unit) {
        PipState.playerVisible = true
        PipState.isPlaying = { viewModel.uiState.value.isPlaying }
        PipState.togglePlayPause = { viewModel.togglePlayPause() }
        onDispose {
            PipState.playerVisible = false
            PipState.isPlaying = { false }
            PipState.togglePlayPause = null
        }
    }
    // Keep the PiP window's play/pause icon honest while shrunk.
    LaunchedEffect(inPip, state.isPlaying) {
        if (inPip) {
            activity?.setPictureInPictureParams(
                buildPipParams(context, state.isPlaying)
            )
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
                // Subtitle appearance (§6.2) is re-applied whenever
                // the saved style changes — live, mid-playback.
                update = { playerView ->
                    applySubtitleStyle(playerView, state.subtitleStyle)
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

        // In PiP none of the chrome exists — the window is just
        // the picture (cycle 9). Everything interactive lives
        // inside this gate.
        if (!inPip) {
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
                },
                onSubtitlesClick = viewModel::openSubtitles,
                onAudioClick = { audioMenuOpen = true },
                onQualityClick = { qualityMenuOpen = true },
                onDownloadClick = viewModel::downloadCurrentMedia,
                onPipClick = {
                    activity?.enterWatchlyPip(state.isPlaying)
                    Unit
                }
            )
        }

        if (audioMenuOpen) {
            AudioTrackDialog(
                tracks = state.audioTracks,
                onPick = { id ->
                    viewModel.selectAudioTrack(id)
                    audioMenuOpen = false
                },
                onDismiss = { audioMenuOpen = false }
            )
        }

        if (qualityMenuOpen) {
            QualityDialog(
                files = state.files,
                currentIndex = state.fileIndex,
                onPick = { index ->
                    viewModel.switchQuality(index)
                    qualityMenuOpen = false
                },
                onDismiss = { qualityMenuOpen = false }
            )
        }

        if (state.subtitlesOpen) {
            SubtitlePickerDialog(
                state = state,
                onPick = viewModel::pickSubtitle,
                onClear = viewModel::clearSubtitles,
                onDismiss = viewModel::closeSubtitles,
                onTextSizePick = viewModel::setSubtitleTextSize,
                onBackgroundPick = viewModel::setSubtitleBackground,
                onOffsetChange = viewModel::setSubtitleOffsetMs
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

        // Autoplay offer (cycle 5): the next item, its countdown,
        // Play Now and Cancel — always visible while it counts,
        // independent of the auto-hiding controls.
        state.upNext?.let { upNext ->
            UpNextCard(
                item = upNext,
                secondsLeft = state.upNextSecondsLeft,
                onPlayNow = viewModel::playUpNextNow,
                onCancel = viewModel::cancelUpNext,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            )
        }
        } // !inPip — end of the interactive chrome
    }
}

/**
 * The autoplay "Up next" card (cycle 5): what plays next, the
 * countdown, and the two ways out — start it now, or cancel and
 * stay on the finished item.
 */
@Composable
private fun UpNextCard(
    item: com.watchly.app.data.playback.UpNextItem,
    secondsLeft: Int,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            // 0.92 (was 0.85): the card floats over arbitrary video
            // frames, and the extra opacity buys contrast headroom
            // for its gold/white text on bright scenes (Test Lab
            // low contrast, cycle 6).
            .background(Color.Black.copy(alpha = 0.92f))
            .padding(16.dp)
            .semantics {
                contentDescription =
                    "Up next: ${item.title.name}" +
                        (item.episodeLabel?.let { ", $it" } ?: "") +
                        ", playing in ${secondsLeft} seconds"
            }
    ) {
        Text(
            text = "Up next",
            style = MaterialTheme.typography.labelMedium,
            color = WatchlyGold
        )
        Text(
            text = item.title.name,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        item.episodeLabel?.let { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = WatchlyGoldLight
            )
        }
        Text(
            text = "Playing in ${secondsLeft}s",
            style = MaterialTheme.typography.bodyMedium,
            color = WatchlyGoldLight
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Button(
                onClick = onPlayNow,
                colors = ButtonDefaults.buttonColors(
                    containerColor = WatchlyGold,
                    contentColor = Color(0xFF1A1405)
                )
            ) {
                Text("Play now")
            }
            OutlinedButton(onClick = onCancel) {
                Text("Cancel", color = Color.White)
            }
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
    onSpeedPick: (Float) -> Unit,
    onSubtitlesClick: () -> Unit = {},
    onAudioClick: () -> Unit = {},
    onQualityClick: () -> Unit = {},
    onDownloadClick: () -> Unit = {},
    onPipClick: () -> Unit = {}
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
            // Download button (MovieBox feature).
            IconButton(onClick = onDownloadClick) {
                Icon(
                    imageVector = Icons.Filled.Download,
                    contentDescription = "Download",
                    tint = WatchlyGold
                )
            }
            // Picture-in-Picture (cycle 9 §5.3).
            IconButton(onClick = onPipClick) {
                Icon(
                    imageVector = Icons.Filled.PictureInPictureAlt,
                    contentDescription = "Picture in picture",
                    tint = Color.White
                )
            }
            // Audio tracks (cycle 9 §5.6): only a real choice is
            // offered — the button exists solely when the file
            // actually carries more than one audio track.
            if (state.audioTracks.size > 1) {
                IconButton(onClick = onAudioClick) {
                    Icon(
                        imageVector = Icons.Filled.Audiotrack,
                        contentDescription = "Audio track",
                        tint = Color.White
                    )
                }
            }
            // Quality (cycle 9 §5.7): the real files this title's
            // source offers; one file = an honest one-row menu.
            if (state.files.isNotEmpty()) {
                IconButton(onClick = onQualityClick) {
                    Icon(
                        imageVector = Icons.Filled.Hd,
                        contentDescription = "Quality",
                        tint = Color.White
                    )
                }
            }
            // Subtitles (cycle 4): OpenSubtitles picker. Gold when a
            // track is attached, white when off.
            IconButton(onClick = onSubtitlesClick) {
                Icon(
                    imageVector = Icons.Filled.Subtitles,
                    contentDescription = "Subtitles",
                    tint = if (state.activeSubtitleLabel != null) {
                        WatchlyGold
                    } else {
                        Color.White
                    }
                )
            }
            // Speed menu.
            Box {
                OutlinedButton(
                    onClick = onSpeedClick,
                    // 48dp minimum touch target (cycle-4 a11y pass).
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
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
                ),
                // Named for screen readers (cycle-4 accessibility pass).
                modifier = Modifier.semantics {
                    contentDescription = "Seek bar"
                }
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

@OptIn(UnstableApi::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun applySubtitleStyle(playerView: PlayerView, style: SubtitleStyle) {
    val subtitleView = playerView.subtitleView ?: return
    subtitleView.setApplyEmbeddedStyles(false)
    subtitleView.setApplyEmbeddedFontSizes(false)
    subtitleView.setFixedTextSize(
        android.util.TypedValue.COMPLEX_UNIT_SP, style.textSizeSp
    )
    val backgroundColor = when (style.background) {
        SubtitleBackground.NONE -> android.graphics.Color.TRANSPARENT
        SubtitleBackground.SEMI -> 0x80000000.toInt()
        SubtitleBackground.SOLID -> android.graphics.Color.BLACK
    }
    subtitleView.setStyle(
        CaptionStyleCompat(
            android.graphics.Color.WHITE,
            backgroundColor,
            android.graphics.Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            android.graphics.Color.BLACK,
            null
        )
    )
}

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
