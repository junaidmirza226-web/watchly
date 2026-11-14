/*
 * Watchly — in-memory holder for what the player should play.
 *
 * Same pattern as TitleDetailsStore: the player route carries no
 * arguments, the selected title + its playable files are handed over
 * here instead of being serialised into the back stack. Cleared when
 * the player is left, so a stale request can never resurface.
 */
package com.watchly.app.ui.player

import com.watchly.app.data.model.PlayableFile
import com.watchly.app.data.model.Title

/** Everything the player needs to start: title, sources, episode label. */
data class PlaybackRequest(
    val title: Title,
    val files: List<PlayableFile>,
    /** e.g. "S1 E4" for an episode, null for a movie. */
    val episodeLabel: String? = null,
    /**
     * Set instead of [files] for official-YouTube titles (cycle 4):
     * the video plays in YouTube's official embedded player, never
     * in Media3. Only ever set when [files] is empty — a title with
     * a real file always prefers the native player.
     */
    val youtubeVideoId: String? = null,
    /**
     * Autoplay continuation (cycle 5): items that may follow this
     * one — the rest of a List being played, or the next episodes
     * of a YouTube channel series. The player offers the next
     * PLAYABLE one with a countdown when this item finishes.
     */
    val upNext: List<com.watchly.app.data.playback.UpNextItem> = emptyList()
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
