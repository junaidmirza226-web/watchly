/*
 * Watchly — in-memory holder for what the player should play.
 */
package com.watchly.app.ui.player

import com.watchly.app.data.model.PlayableFile
import com.watchly.app.data.model.Title

/** Everything the player needs to start: title, sources, episode label, preferred server. */
data class PlaybackRequest(
    val title: Title,
    val files: List<PlayableFile>,
    /** e.g. "S1E4 - Title" for an episode, null for a movie. */
    val episodeLabel: String? = null,
    /**
     * Set instead of [files] for official-YouTube titles (cycle 4):
     * the video plays in YouTube's official embedded player, never
     * in Media3.
     */
    val youtubeVideoId: String? = null,
    /**
     * Autoplay / Next Episode continuation queue.
     */
    val upNext: List<com.watchly.app.data.playback.UpNextItem> = emptyList(),
    /** Optional initial server URL chosen from Title Details server picker. */
    val preferredEmbedUrl: String? = null
)

object PlaybackSessionStore {

    private var request: PlaybackRequest? = null

    fun select(request: PlaybackRequest) {
        this.request = request
    }

    fun take(): PlaybackRequest? = request

    fun clear() {
        request = null
    }
}
