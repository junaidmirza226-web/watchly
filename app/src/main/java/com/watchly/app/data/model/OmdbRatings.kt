/*
 * Watchly — OMDb ratings bundle for one title (cycle 4).
 *
 * Dependencies: none beyond the Kotlin standard library.
 * OMDb is a field provider: it never supplies a catalogue, only
 * these facts for a title Watchly already knows by IMDb id.
 */
package com.watchly.app.data.model

/**
 * The ratings one OMDb lookup returns for a title.
 *
 * @param imdbRating IMDb score 0–10 (e.g. 9.0), when rated.
 * @param imdbVotes Vote count as OMDb formats it ("2,900,000").
 * @param rottenTomatoesPercent Rotten Tomatoes score 0–100.
 * @param metacriticScore Metacritic score 0–100.
 * @param rated MPAA age rating ("PG-13", "TV-MA"), when listed.
 */
data class OmdbRatings(
    val imdbRating: Double? = null,
    val imdbVotes: String? = null,
    val rottenTomatoesPercent: Int? = null,
    val metacriticScore: Int? = null,
    val rated: String? = null
) {
    /** True when the lookup carried at least one displayable fact. */
    val hasAnyRating: Boolean
        get() = imdbRating != null || rottenTomatoesPercent != null ||
            metacriticScore != null || rated != null
}
