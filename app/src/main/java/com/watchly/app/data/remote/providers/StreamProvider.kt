package com.watchly.app.data.remote.providers

import com.watchly.app.data.model.MediaType

/**
 * Standard contract for a multi-source video streaming provider.
 */
interface StreamProvider {
    /** Unique display name of the provider (matches remote config). */
    val name: String

    /** Default priority ranking (lower number = tried first). */
    val priority: Int

    /**
     * Resolves playable media stream URLs for a movie or TV episode.
     *
     * @param tmdbId    TMDB id of the title, when available.
     * @param imdbId    IMDb id (e.g. "tt0944947"), when available.
     * @param title     Title name.
     * @param year      Release year, when available.
     * @param mediaType MOVIE, TV, or ANIME.
     * @param season    Season number (1-based), required for TV.
     * @param episode   Episode number within season (1-based), required for TV.
     * @return List of playable [StreamSource] entries, or empty list on failure. Never throws.
     */
    suspend fun getStreams(
        tmdbId: Int?,
        imdbId: String?,
        title: String,
        year: String?,
        mediaType: MediaType,
        season: Int? = null,
        episode: Int? = null
    ): List<StreamSource>
}
