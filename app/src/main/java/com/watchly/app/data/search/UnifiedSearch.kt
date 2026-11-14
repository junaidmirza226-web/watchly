/*
 * Watchly — unified search entry point (spec §3.1).
 *
 * One search box, four providers queried in parallel: TVmaze (shows),
 * AniList (anime), Internet Archive (public-domain movies), and TMDB
 * (movies + TV) once the user has saved their key in the app. The
 * Archive is asked twice — feature films and the Classic TV
 * collection (cycle 4) — but reported as one provider. Each
 * provider failing on its own degrades to an empty list for that
 * provider — the other results still show, and the caller reports
 * which providers failed. Without a key the search behaves exactly as
 * before (TVmaze + AniList + Archive); TMDB is simply not asked.
 */
package com.watchly.app.data.search

import com.watchly.app.data.model.Title
import com.watchly.app.data.remote.AniListApi
import com.watchly.app.data.remote.ArchiveApi
import com.watchly.app.data.remote.TmdbApi
import com.watchly.app.data.remote.TvMazeApi
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Result of one unified search, including per-provider health. */
data class UnifiedSearchResult(
    val groups: SearchGroups,
    /** Provider display names that failed this search (empty = all fine). */
    val failedProviders: List<String> = emptyList(),
    /**
     * True when EVERY provider call failed (cycle 13): the search
     * itself never happened, so an empty [groups] means "couldn't
     * search", not "nothing found" — the UI keeps the two apart.
     * A provider that answered with an empty list counts as
     * answered, not failed.
     */
    val allProvidersFailed: Boolean = false
)

object UnifiedSearch {

    /**
     * Searches all providers in parallel and merges the results.
     * Returns empty groups for a blank query. Never throws — provider
     * failures are reported in [UnifiedSearchResult.failedProviders].
     * [tmdbApiKey] is the user's saved TMDB key, or null when they
     * haven't added one; TMDB is only queried when a key is given.
     * TMDB goes first so its posters and overviews win the merge —
     * an Archive record for the same film still fills in the playable
     * file id, so the merged row stays playable.
     */
    suspend fun search(
        query: String,
        tmdbApiKey: String? = null
    ): UnifiedSearchResult = coroutineScope {
        val q = query.trim()
        if (q.isEmpty()) return@coroutineScope UnifiedSearchResult(SearchGroups())

        val deferred = ArrayList<Deferred<Pair<Result<List<Title>>, String>>>()
        // TMDB first so its posters and overviews win the merge — an
        // Archive record for the same film still fills in the playable
        // file id, so the merged row stays playable. Only created when
        // a key exists; without one TMDB is simply not asked.
        if (!tmdbApiKey.isNullOrBlank()) {
            deferred.add(
                async { runCatching { TmdbApi.searchMulti(tmdbApiKey, q) } to "TMDB" }
            )
        }
        deferred.add(async { runCatching { TvMazeApi.searchShows(q) } to "TVmaze" })
        deferred.add(async { runCatching { AniListApi.searchAnime(q) } to "AniList" })
        deferred.add(async { runCatching { ArchiveApi.searchMovies(q) } to "Internet Archive" })
        deferred.add(
            async {
                runCatching { ArchiveApi.searchCollection(q, ArchiveApi.CLASSIC_TV) } to
                    "Internet Archive"
            }
        )

        val lists = ArrayList<List<Title>>()
        val failed = ArrayList<String>()
        var answered = 0
        for ((outcome, name) in deferred.map { it.await() }) {
            outcome.fold(
                onSuccess = { lists.add(it); answered++ },
                onFailure = { failed.add(name) }
            )
        }
        UnifiedSearchResult(
            groups = SearchMerger.merge(lists),
            failedProviders = failed,
            // Every call failed = the search never happened (the
            // deferred list is never empty: TVmaze, AniList and the
            // Archive are always asked).
            allProvidersFailed = answered == 0
        )
    }
}
