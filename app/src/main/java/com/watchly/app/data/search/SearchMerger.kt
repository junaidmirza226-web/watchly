/*
 * Watchly — unified search merging (spec §3.1, §1.1).
 *
 * Pure Kotlin, no Android dependency: merges the per-provider result
 * lists into one de-duplicated, grouped result. The same title from two
 * services shows once (canonical merge): first by stable provider key,
 * then by normalised name + year + media type, so a TVmaze show and an
 * AniList entry for the same anime collapse into one row.
 *
 * Merge rules for two records of the same title:
 *  - Keep the first record's provider ids, then fill in any id the
 *    first record lacks (so the merged row carries every known id).
 *  - Text fields: first non-blank wins (poster, overview).
 *  - Rating: the higher non-null rating wins (ratings are per-provider;
 *    the details page still labels its badge by source).
 *  - Genres: union, first-seen order, case-insensitive dedupe.
 *  - Runtime/status: first non-null wins.
 */
package com.watchly.app.data.search

import com.watchly.app.data.browse.filteredByGenre
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.PlayabilityFilter
import com.watchly.app.data.model.Title
import com.watchly.app.data.model.filteredBy

/** Search results grouped the way the UI shows them (spec §3.1). */
data class SearchGroups(
    val movies: List<Title> = emptyList(),
    val shows: List<Title> = emptyList(),
    val anime: List<Title> = emptyList()
) {
    /** True when every group is empty. */
    val isEmpty: Boolean
        get() = movies.isEmpty() && shows.isEmpty() && anime.isEmpty()

    /** Total result count across groups. */
    val totalCount: Int
        get() = movies.size + shows.size + anime.size

    /** These groups with the playability filter applied (Phase D). */
    fun filteredBy(filter: PlayabilityFilter): SearchGroups =
        SearchGroups(
            movies = movies.filteredBy(filter),
            shows = shows.filteredBy(filter),
            anime = anime.filteredBy(filter)
        )

    /** These groups with one genre applied (cycle 5). */
    fun filteredByGenre(genre: String?): SearchGroups =
        SearchGroups(
            movies = movies.filteredByGenre(genre),
            shows = shows.filteredByGenre(genre),
            anime = anime.filteredByGenre(genre)
        )

    /** Every genre label present in these results, A–Z (cycle 5). */
    val allGenres: List<String>
        get() = (movies + shows + anime)
            .flatMap { it.genres }
            .distinct()
            .sorted()
}

object SearchMerger {

    /**
     * Merges several provider result lists into grouped, de-duplicated
     * results. Provider order decides which record wins a tie: pass the
     * lists most-preferred first.
     */
    fun merge(lists: List<List<Title>>): SearchGroups {
        val merged = mergeTitles(lists.flatten())
        return SearchGroups(
            movies = merged.filter { it.mediaType == MediaType.MOVIE },
            shows = merged.filter { it.mediaType == MediaType.TV },
            anime = merged.filter { it.mediaType == MediaType.ANIME }
        )
    }

    /**
     * De-duplicates one flat list. Two records are the same title when
     * they share a [Title.stableKey], or when their normalised name,
     * year and media type all match.
     */
    fun mergeTitles(titles: List<Title>): List<Title> {
        val byKey = LinkedHashMap<String, Title>()
        val byName = LinkedHashMap<String, String>() // normalised name -> stableKey
        for (title in titles) {
            val nameKey = nameKey(title)
            val existingKey = byKey[title.stableKey]?.let { title.stableKey }
                ?: nameKey?.let { byName[it] }
            if (existingKey == null) {
                byKey[title.stableKey] = title
                nameKey?.let { byName[it] = title.stableKey }
            } else {
                byKey[existingKey] = mergePair(byKey.getValue(existingKey), title)
            }
        }
        return byKey.values.toList()
    }

    /** Normalised identity for name-based matching, or null when unusable. */
    internal fun nameKey(title: Title): String? {
        val normalised = title.name.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
        if (normalised.isBlank()) return null
        // Year disambiguates remakes; a missing year still matches on name
        // alone, which is the safer side for search results.
        val year = title.year?.takeIf { it.length >= 4 }?.substring(0, 4) ?: ""
        return "${title.mediaType.name}|$normalised|$year"
    }

    private fun mergePair(first: Title, second: Title): Title = Title(
        tmdbId = first.tmdbId ?: second.tmdbId,
        imdbId = first.imdbId ?: second.imdbId,
        tvmazeId = first.tvmazeId ?: second.tvmazeId,
        anilistId = first.anilistId ?: second.anilistId,
        archiveId = first.archiveId ?: second.archiveId,
        youtubeId = first.youtubeId ?: second.youtubeId,
        name = first.name,
        overview = first.overview?.takeIf { it.isNotBlank() }
            ?: second.overview?.takeIf { it.isNotBlank() },
        posterUrl = first.posterUrl?.takeIf { it.isNotBlank() }
            ?: second.posterUrl?.takeIf { it.isNotBlank() },
        rating = listOfNotNull(first.rating, second.rating).maxOrNull(),
        mediaType = first.mediaType,
        year = first.year ?: second.year,
        genres = mergeGenres(first.genres, second.genres),
        runtimeMinutes = first.runtimeMinutes ?: second.runtimeMinutes,
        status = first.status ?: second.status
    )

    private fun mergeGenres(first: List<String>, second: List<String>): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (genre in first + second) {
            val trimmed = genre.trim()
            if (trimmed.isNotEmpty() && seen.add(trimmed.lowercase())) out.add(trimmed)
        }
        return out
    }
}
