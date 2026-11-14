/*
 * Watchly — browse-tab sorting & filtering.
 *
 * Pure Kotlin (no Android dependency) so the JVM unit tests can cover it.
 * Used by the Shows tab, which filters/sorts TVmaze pages client-side
 * (TVmaze offers no server-side genre filter or sort). The Movies and
 * Anime tabs filter/sort server-side instead.
 */
package com.watchly.app.data.browse

import com.watchly.app.data.model.Title

/** Sort orders offered on the browse tabs. */
enum class BrowseSort {
    POPULAR,
    TOP_RATED,
    NEWEST,
    A_Z
}

/**
 * This list with one genre applied (cycle 5): [genre] null, blank
 * or "All" means no filtering. Matching is case-insensitive and a
 * title passes when ANY of its genres matches — the same rule the
 * Browse tabs and (now) Search share.
 */
fun List<Title>.filteredByGenre(genre: String?): List<Title> {
    if (genre.isNullOrBlank() || genre == "All") return this
    return filter { title ->
        title.genres.any { it.equals(genre, ignoreCase = true) }
    }
}

/**
 * Applies one genre filter and one sort to an already-loaded list.
 * [genre] is null (or "All") for no filtering. Genre matching is
 * case-insensitive and matches any of the title's genres.
 * Sort rules: POPULAR = highest rating first (unrated last);
 * TOP_RATED = same as POPULAR (rating is the only popularity signal
 * TVmaze gives); NEWEST = newest year first; A_Z = name A→Z.
 */
fun applyBrowseFilter(
    titles: List<Title>,
    genre: String?,
    sort: BrowseSort
): List<Title> {
    val filtered = titles.filteredByGenre(genre)
    return when (sort) {
        BrowseSort.POPULAR, BrowseSort.TOP_RATED ->
            filtered.sortedWith(
                compareByDescending<Title> { it.rating != null }
                    .thenByDescending { it.rating ?: 0.0 }
                    .thenBy { it.name }
            )
        BrowseSort.NEWEST ->
            filtered.sortedWith(
                compareByDescending<Title> { it.year != null }
                    .thenByDescending { it.year ?: "" }
                    .thenBy { it.name }
            )
        BrowseSort.A_Z ->
            filtered.sortedBy { it.name.lowercase() }
    }
}
