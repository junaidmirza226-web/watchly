package com.watchly.app.data.metadata

/**
 * Reference implementation exercised by MetadataMergerTest.
 * Copy to: app/src/main/java/com/watchly/app/data/metadata/
 * Skip this file if the app already has an equivalent class — adapt the test instead.
 */

/** Normalised metadata for one title, merged from one or more providers. */
data class TitleMetadata(
    val title: String? = null,
    val overview: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val releaseYear: Int? = null,
    val voteAverage: Double? = null,
    val genres: List<String> = emptyList(),
    val runtimeMinutes: Int? = null,
)

/**
 * Merges metadata from two providers for the same title.
 *
 * Rules (all covered by tests):
 *  - Scalar text: the primary value wins if it is non-null and non-blank,
 *    otherwise the fallback value is used.
 *  - voteAverage / runtimeMinutes: the primary value wins only if it is
 *    present and > 0 — providers often return 0 / 0.0 for "unknown",
 *    which must not overwrite a real fallback value.
 *  - Genres: union, primary first, duplicates removed case-insensitively
 *    (the primary's casing is kept).
 */
object MetadataMerger {

    fun merge(primary: TitleMetadata, fallback: TitleMetadata): TitleMetadata = TitleMetadata(
        title = primary.title.nonBlankOr(fallback.title),
        overview = primary.overview.nonBlankOr(fallback.overview),
        posterPath = primary.posterPath.nonBlankOr(fallback.posterPath),
        backdropPath = primary.backdropPath.nonBlankOr(fallback.backdropPath),
        releaseYear = primary.releaseYear ?: fallback.releaseYear,
        voteAverage = primary.voteAverage.positiveOr(fallback.voteAverage),
        genres = mergeGenres(primary.genres, fallback.genres),
        runtimeMinutes = primary.runtimeMinutes.positiveOr(fallback.runtimeMinutes),
    )

    private fun String?.nonBlankOr(other: String?): String? =
        this?.takeIf { it.isNotBlank() } ?: other

    private fun Double?.positiveOr(other: Double?): Double? =
        this?.takeIf { it > 0.0 } ?: other

    private fun Int?.positiveOr(other: Int?): Int? =
        this?.takeIf { it > 0 } ?: other

    private fun mergeGenres(primary: List<String>, fallback: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<String>()
        for (genre in primary + fallback) {
            val trimmed = genre.trim()
            if (trimmed.isNotEmpty() && seen.add(trimmed.lowercase())) {
                result += trimmed
            }
        }
        return result
    }
}
