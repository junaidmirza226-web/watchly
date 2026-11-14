/*
 * Watchly — share text for a title (details page Share button).
 *
 * Pure function, no Android dependencies, so it is unit-testable on
 * the JVM. Format: "Name (Year)" on the first line, then a real link
 * to that title on the provider that supplied it (first available ID
 * wins). Bug reported 2026-11-06: the old share text was only the
 * name — no link at all. A title with no provider ID at all falls
 * back to the old "— found on Watchly" line.
 */
package com.watchly.app.ui.details

import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Title

/** Public web page for this title, or null when it has no known ID. */
fun shareLinkFor(title: Title): String? = when {
    title.tmdbId != null -> {
        val kind = if (title.mediaType == MediaType.MOVIE) "movie" else "tv"
        "https://www.themoviedb.org/$kind/${title.tmdbId}"
    }
    title.imdbId != null -> "https://www.imdb.com/title/${title.imdbId}/"
    title.tvmazeId != null -> "https://www.tvmaze.com/shows/${title.tvmazeId}"
    title.anilistId != null -> "https://anilist.co/anime/${title.anilistId}"
    title.archiveId != null -> "https://archive.org/details/${title.archiveId}"
    title.youtubeId != null -> "https://www.youtube.com/watch?v=${title.youtubeId}"
    else -> null
}

/** Full share text: name + year, then the link on its own line. */
fun shareTextFor(title: Title): String {
    val heading = buildString {
        append(title.name)
        title.year?.let { append(" ($it)") }
    }
    val link = shareLinkFor(title)
    return if (link != null) {
        "$heading\n$link"
    } else {
        "$heading — found on Watchly"
    }
}
