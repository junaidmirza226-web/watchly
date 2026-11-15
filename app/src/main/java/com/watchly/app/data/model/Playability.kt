/*
 * Watchly — "Playable" vs "Info only" rule (pure Kotlin, no Android).
 *
 * Playable sources in Watchly:
 *  - Cloud streaming catalogue titles carrying a [Title.tmdbId] or
 *    [Title.imdbId], resolved on demand across Watchly's multi-server
 *    aggregator (VidLink, VidSrc, MultiEmbed, AutoEmbed, 2Embed, etc.).
 *  - Public-domain Internet Archive films carrying an [Title.archiveId].
 *  - Official YouTube channel entries carrying a [Title.youtubeId].
 *  - On-device user videos granted via SAF carrying a [Title.localUri].
 *
 * Catalogue entries with none of those identifiers yet (e.g. unlinked
 * TVmaze / AniList stubs before canonical ID enrichment) report
 * [Playability.INFO_ONLY] until enriched on the details page.
 */
package com.watchly.app.data.model

/** Whether a title can actually play in Watchly right now. */
enum class Playability {
    /** Streamable or locally playable in Watchly. */
    PLAYABLE,

    /** Catalogue / metadata only until resolved to a streamable ID. */
    INFO_ONLY
}

/** Title-level playability, from the data the title itself carries. */
fun Title.playability(): Playability =
    if (archiveId != null ||
        youtubeId != null ||
        localUri != null ||
        tmdbId != null ||
        imdbId != null
    ) {
        Playability.PLAYABLE
    } else {
        Playability.INFO_ONLY
    }

/**
 * The user's playability filter (Phase D, user point 1): show
 * everything, only what plays in Watchly, or only catalogue titles.
 * One shared, persisted choice across Browse, Search and Home.
 */
enum class PlayabilityFilter {
    ALL,
    PLAYABLE_ONLY,
    INFO_ONLY;

    /** True when [title] passes this filter. */
    fun accepts(title: Title): Boolean = when (this) {
        ALL -> true
        PLAYABLE_ONLY -> title.playability() == Playability.PLAYABLE
        INFO_ONLY -> title.playability() == Playability.INFO_ONLY
    }
}

/** This list with [filter] applied (order preserved). */
fun List<Title>.filteredBy(filter: PlayabilityFilter): List<Title> =
    if (filter == PlayabilityFilter.ALL) this else filter { filter.accepts(it) }
