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
    val episodeLabel: String? = null
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
