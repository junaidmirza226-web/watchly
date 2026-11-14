/*
 * Watchly — canonical content model.
 *
 * Dependencies: none beyond the Kotlin standard library.
 * Every provider (TMDB, TVmaze, AniList, future sources) maps its own
 * response into this single model so the UI never cares where a title
 * came from. Any provider ID may be null — no single source has them all.
 */
package com.watchly.app.data.model

/** Broad content kind. ANIME is kept separate from TV for the Anime tab/row. */
enum class MediaType {
    MOVIE,
    TV,
    ANIME
}

/**
 * One movie / show / anime entry, normalised across all providers.
 *
 * @param tmdbId    TMDB id, when known (primary key once the TMDB provider lands).
 * @param imdbId    IMDb id, e.g. "tt0944947", when known.
 * @param tvmazeId  TVmaze id, when the entry came from / maps to TVmaze.
 * @param anilistId AniList id, when the entry came from / maps to AniList.
 * @param archiveId Internet Archive identifier, when the entry is a
 *                  public-domain Archive film (e.g. "nightofthelivingdead_201910").
 * @param youtubeId YouTube video id, when the entry is a video from an
 *                  official YouTube channel (cycle 4). Playable only
 *                  through YouTube's official embedded player.
 * @param localUri  content:// URI string of a video file on the
 *                  user's own device, granted via SAF (cycle 5).
 *                  Playable in the Watchly player like any file.
 * @param name      Display title (already localised by the provider where possible).
 * @param overview  Plain-text synopsis (providers' HTML is stripped at parse time). May be null.
 * @param posterUrl Full poster image URL. May be null — UI must show a placeholder.
 * @param rating    Average score normalised to a 0.0–10.0 scale. May be null.
 * @param mediaType MOVIE, TV or ANIME.
 * @param year      Release / premiere year when known, e.g. "2024". May be null.
 * @param genres    Genre labels as supplied by the provider. Empty when unknown.
 * @param runtimeMinutes Typical episode runtime (TV) or film runtime, when the
 *                  provider supplies it. May be null.
 * @param status    Provider status string, e.g. "Running" / "Ended". May be null.
 */
data class Title(
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val tvmazeId: Int? = null,
    val anilistId: Int? = null,
    val archiveId: String? = null,
    val youtubeId: String? = null,
    val localUri: String? = null,
    val name: String,
    val overview: String? = null,
    val posterUrl: String? = null,
    val rating: Double? = null,
    val mediaType: MediaType,
    val year: String? = null,
    val genres: List<String> = emptyList(),
    val runtimeMinutes: Int? = null,
    val status: String? = null
) {
    /**
     * Stable key for LazyRow / LazyColumn items and future local caching.
     * Prefers the strongest available provider id; falls back to name+type.
     */
    val stableKey: String
        get() = when {
            tmdbId != null -> "tmdb:$tmdbId"
            anilistId != null -> "anilist:$anilistId"
            tvmazeId != null -> "tvmaze:$tvmazeId"
            imdbId != null -> "imdb:$imdbId"
            archiveId != null -> "archive:$archiveId"
            youtubeId != null -> "youtube:$youtubeId"
            localUri != null -> "local:$localUri"
            else -> "name:${mediaType.name}:${name.lowercase()}"
        }
}
