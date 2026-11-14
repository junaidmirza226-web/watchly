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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import com.watchly.app.data.model.PlayableMediaHeaders
import com.watchly.app.data.model.SubtitleTrack
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
    val error: String? = null,
    /**
     * Audio tracks inside the current media (cycle 9, spec §5.6),
     * in file order. Empty until the media is prepared; the UI only
     * offers a choice when the list holds more than one entry.
     */
    val audioTracks: List<AudioTrackOption> = emptyList()
)

/** Minimal player contract the ViewModel drives. */
interface PlayerEngine {
    /** The Media3 player for the PlayerView, or null for fakes. */
    val player: Player?
    val state: StateFlow<EngineState>
    fun setSource(url: String)
    fun setSource(url: String, headers: Map<String, String>) = setSource(url)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setSpeed(speed: Float)

    /**
     * Attaches [track] as the current item's subtitle (cycle 4,
     * OpenSubtitles), or removes the subtitle when null. Playback
     * position and play/pause state are preserved.
     */
    fun setSubtitle(track: SubtitleTrack?)

    /**
     * Selects one of the audio tracks reported in [EngineState]
     * (cycle 9, spec §5.6) by its [AudioTrackOption.id]; null
     * restores the player's automatic choice. Default no-op so
     * test fakes need no change.
     */
    fun selectAudioTrack(id: String?) = Unit

    /**
     * Stamps title/artist metadata onto the current item (cycle 9):
     * this is what the MediaSession, lock screen and notification
     * show as "now playing". Default no-op for test fakes.
     */
    fun setNowPlaying(title: String, subtitle: String?) = Unit

    fun release()
}

/** Real engine: one ExoPlayer, created on the caller's scope. */
class ExoPlayerEngine(
    private val context: Context,
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

            override fun onTracksChanged(tracks: Tracks) {
                // Audio track list (§5.6) arrives with the tracks.
                publish()
            }

            override fun onPlayerError(error: PlaybackException) {
                _state.value = snapshot(error = plainError(error))
            }
        })
    }

    override fun setSource(url: String) {
        setSource(url, emptyMap())
    }

    override fun setSource(url: String, headers: Map<String, String>) {
        _state.value = EngineState()
        val effectiveHeaders = if (headers.isNotEmpty()) headers else PlayableMediaHeaders.get(url)
        val mediaItem = MediaItem.fromUri(url)

        if (effectiveHeaders.isNotEmpty()) {
            val httpDataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(effectiveHeaders)
                .setAllowCrossProtocolRedirects(true)
            val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(context, httpDataSourceFactory)

            val mediaSource = try {
                if (url.contains(".m3u8", ignoreCase = true)) {
                    val hlsFactoryClass = Class.forName("androidx.media3.exoplayer.hls.HlsMediaSource\$Factory")
                    val hlsFactory = hlsFactoryClass.getConstructor(androidx.media3.datasource.DataSource.Factory::class.java).newInstance(dataSourceFactory)
                    val createMethod = hlsFactoryClass.getMethod("createMediaSource", MediaItem::class.java)
                    createMethod.invoke(hlsFactory, mediaItem) as androidx.media3.exoplayer.source.MediaSource
                } else {
                    androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(mediaItem)
                }
            } catch (_: Throwable) {
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context)
                    .setDataSourceFactory(dataSourceFactory)
                    .createMediaSource(mediaItem)
            }
            exoPlayer.setMediaSource(mediaSource)
        } else {
            exoPlayer.setMediaItem(mediaItem)
        }
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

    override fun setSubtitle(track: SubtitleTrack?) {
        val current = exoPlayer.currentMediaItem ?: return
        val wasPlaying = exoPlayer.isPlaying
        val position = exoPlayer.currentPosition.coerceAtLeast(0L)
        val builder = current.buildUpon()
        if (track == null) {
            builder.setSubtitleConfigurations(emptyList())
        } else {
            val config = MediaItem.SubtitleConfiguration.Builder(
                android.net.Uri.parse(track.url)
            )
                .setMimeType(track.mimeType)
                .setLanguage(track.language)
                .setLabel(track.label)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
            builder.setSubtitleConfigurations(listOf(config))
        }
        // Rebuild in place: same item, same position, same intent.
        exoPlayer.setMediaItem(builder.build(), position)
        exoPlayer.prepare()
        if (wasPlaying) exoPlayer.play()
    }

    override fun selectAudioTrack(id: String?) {
        val builder = exoPlayer.trackSelectionParameters.buildUpon()
        if (id == null) {
            builder.clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        } else {
            val (groupIndex, trackIndex) = AudioTrackLabels.parseTrackId(id) ?: return
            val group = audioGroups().getOrNull(groupIndex) ?: return
            if (trackIndex !in 0 until group.length) return
            builder.setOverrideForType(
                TrackSelectionOverride(group.mediaTrackGroup, trackIndex)
            )
        }
        exoPlayer.trackSelectionParameters = builder.build()
    }

    override fun setNowPlaying(title: String, subtitle: String?) {
        val current = exoPlayer.currentMediaItem ?: return
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(subtitle)
            .setDisplayTitle(title)
            .build()
        if (current.mediaMetadata == metadata) return
        val wasPlaying = exoPlayer.isPlaying
        val position = exoPlayer.currentPosition.coerceAtLeast(0L)
        exoPlayer.setMediaItem(
            current.buildUpon().setMediaMetadata(metadata).build(), position
        )
        exoPlayer.prepare()
        if (wasPlaying) exoPlayer.play()
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
        error = error,
        audioTracks = audioTrackOptions()
    )

    /** The player's audio track groups, in reported order. */
    private fun audioGroups(): List<Tracks.Group> =
        exoPlayer.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }

    /** UI-ready view of the audio tracks inside the current media. */
    private fun audioTrackOptions(): List<AudioTrackOption> =
        audioGroups().flatMapIndexed { groupIndex, group ->
            (0 until group.length).map { trackIndex ->
                val format = group.getTrackFormat(trackIndex)
                AudioTrackOption(
                    id = AudioTrackLabels.trackId(groupIndex, trackIndex),
                    label = AudioTrackLabels.labelFor(
                        format.language, format.sampleMimeType, format.channelCount
                    ),
                    language = format.language,
                    isSelected = group.isTrackSelected(trackIndex)
                )
            }
        }

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
