/*
 * Watchly — Fanart.tv artwork picks for one title (cycle 4).
 *
 * Dependencies: none beyond the Kotlin standard library.
 */
package com.watchly.app.data.model

/**
 * The two Fanart pieces Watchly uses on Title Details: an HD clear
 * logo (transparent PNG) to lay over the hero, and an extra backdrop
 * for when TMDB has none. TMDB art stays primary; Fanart only fills
 * gaps (see FanartApi.bestPicks).
 */
data class FanartArtwork(
    val logoUrl: String? = null,
    val backdropUrl: String? = null
) {
    val isEmpty: Boolean get() = logoUrl == null && backdropUrl == null
}
