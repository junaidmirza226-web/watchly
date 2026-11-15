/*
 * Watchly — browse-tab sorting & multi-dimensional filtering engine.
 *
 * Pure Kotlin (no Android dependency) so JVM unit tests cover every
 * combination: multi-industry, multi-genre (ANY / ALL), release era/year,
 * minimum rating, playability, and sort order.
 */
package com.watchly.app.data.browse

import com.watchly.app.data.model.Title

/** Sort orders offered on the browse tabs. */
enum class BrowseSort(val displayLabel: String) {
    POPULAR("Popular"),
    TOP_RATED("Top Rated"),
    NEWEST("Newest"),
    A_Z("A–Z")
}

/** Multi-genre combination logic when 2+ genres are selected. */
enum class GenreMatchMode(val label: String) {
    /** Title matches ANY selected genre (OR / pipe `|`). */
    ANY("Any selected"),
    /** Title must match ALL selected genres (AND / comma `,`). */
    ALL("Match all")
}

/** Release year / era filter presets. */
enum class BrowseYearFilter(
    val label: String,
    val minYear: Int? = null,
    val maxYear: Int? = null
) {
    ALL("All Years", null, null),
    YEAR_2026("2026", 2026, 2026),
    YEAR_2025("2025", 2025, 2025),
    YEAR_2024("2024", 2024, 2024),
    DECADE_2020S("2020s", 2020, 2029),
    DECADE_2010S("2010s", 2010, 2019),
    DECADE_2000S("2000s", 2000, 2009),
    CLASSICS("Classics (<2000)", null, 1999);

    val startDateIso: String?
        get() = minYear?.let { "%04d-01-01".format(it) }

    val endDateIso: String?
        get() = maxYear?.let { "%04d-12-31".format(it) }

    fun matches(yearText: String?): Boolean {
        if (this == ALL) return true
        val year = yearText?.trim()?.take(4)?.toIntOrNull() ?: return false
        if (minYear != null && year < minYear) return false
        if (maxYear != null && year > maxYear) return false
        return true
    }
}

/** Minimum score filter presets. */
enum class BrowseRatingFilter(
    val label: String,
    val minScore: Double?
) {
    ANY("Any Rating", null),
    GOOD_6("6.0+ ★", 6.0),
    GREAT_7("7.0+ ★", 7.0),
    ACCLAIMED_75("7.5+ ★", 7.5),
    MASTERPIECE_8("8.0+ ★", 8.0);

    fun matches(rating: Double?): Boolean {
        val floor = minScore ?: return true
        return rating != null && rating >= floor
    }
}

/**
 * This list with one genre applied (cycle 5): [genre] null, blank
 * or "All" means no filtering. Matching is case-insensitive and a
 * title passes when ANY of its genres matches — the same rule the
 * Browse tabs and Search share.
 */
fun List<Title>.filteredByGenre(genre: String?): List<Title> {
    if (genre.isNullOrBlank() || genre == "All") return this
    return filter { title ->
        title.genres.any { it.equals(genre, ignoreCase = true) }
    }
}

/**
 * Multi-genre filter supporting both [GenreMatchMode.ANY] and [GenreMatchMode.ALL].
 */
fun List<Title>.filteredByGenres(
    genres: Set<String>,
    mode: GenreMatchMode = GenreMatchMode.ANY
): List<Title> {
    val clean = genres.map { it.trim() }.filter { it.isNotEmpty() && !it.equals("All", ignoreCase = true) }
    if (clean.isEmpty()) return this
    return filter { title ->
        when (mode) {
            GenreMatchMode.ANY -> clean.any { wanted ->
                title.genres.any { genreMatchesLoosely(it, wanted) }
            }
            GenreMatchMode.ALL -> clean.all { wanted ->
                title.genres.any { genreMatchesLoosely(it, wanted) }
            }
        }
    }
}

private fun genreMatchesLoosely(actual: String, wanted: String): Boolean {
    if (actual.equals(wanted, ignoreCase = true)) return true
    // Handle TV vs Movie naming differences (e.g. "Sci-Fi & Fantasy" vs "Science Fiction" / "Science-Fiction")
    val a = actual.lowercase().replace("-", " ")
    val w = wanted.lowercase().replace("-", " ")
    if (a.contains(w) || w.contains(a)) return true
    if ((a.contains("sci") || a.contains("science fiction")) &&
        (w.contains("sci") || w.contains("science fiction"))
    ) {
        return true
    }
    return false
}

/**
 * Applies multi-dimensional client-side filters (genres, industries/languages when
 * present on the title, release year/era, minimum rating) and sort order.
 */
fun applyAdvancedBrowseFilter(
    titles: List<Title>,
    selectedGenres: Set<String> = emptySet(),
    genreMatchMode: GenreMatchMode = GenreMatchMode.ANY,
    selectedIndustries: Set<Industry> = emptySet(),
    yearFilter: BrowseYearFilter = BrowseYearFilter.ALL,
    ratingFilter: BrowseRatingFilter = BrowseRatingFilter.ANY,
    sort: BrowseSort? = null
): List<Title> {
    val allowedLangCodes = selectedIndustries.flatMap { it.languageCodes }.toSet()
    val filtered = titles
        .filteredByGenres(selectedGenres, genreMatchMode)
        .filter { title ->
            if (allowedLangCodes.isNotEmpty() && title.originalLanguage != null) {
                title.originalLanguage.lowercase() in allowedLangCodes
            } else {
                true
            }
        }
        .filter { yearFilter.matches(it.year) }
        .filter { ratingFilter.matches(it.rating) }

    return if (sort == null) {
        filtered
    } else {
        sortBrowseTitles(filtered, sort)
    }
}

/** Sorts a list of titles according to [BrowseSort]. */
fun sortBrowseTitles(titles: List<Title>, sort: BrowseSort): List<Title> =
    when (sort) {
        BrowseSort.POPULAR, BrowseSort.TOP_RATED ->
            titles.sortedWith(
                compareByDescending<Title> { it.rating != null }
                    .thenByDescending { it.rating ?: 0.0 }
                    .thenBy { it.name }
            )
        BrowseSort.NEWEST ->
            titles.sortedWith(
                compareByDescending<Title> { it.year != null }
                    .thenByDescending { it.year ?: "" }
                    .thenBy { it.name }
            )
        BrowseSort.A_Z ->
            titles.sortedBy { it.name.lowercase() }
    }

/**
 * Applies one genre filter and one sort to an already-loaded list.
 * Preserved intact for existing callers and unit tests.
 */
fun applyBrowseFilter(
    titles: List<Title>,
    genre: String?,
    sort: BrowseSort
): List<Title> {
    val filtered = titles.filteredByGenre(genre)
    return sortBrowseTitles(filtered, sort)
}
