/*
 * Watchly — canonical person model (spec §3.3, cycle 11).
 *
 * Dependencies: none beyond the Kotlin standard library.
 * A person page is a TMDB-only feature: only TMDB cast rows carry a
 * person id (see CastMember.tmdbPersonId), so this model is only
 * ever built from a TMDB /person response.
 */
package com.watchly.app.data.model

/**
 * One person (actor, director, …) as TMDB describes them.
 *
 * @param tmdbId             TMDB person id — the page's identity.
 * @param name               Display name.
 * @param biography          TMDB biography. Null when TMDB has none
 *                           (or only whitespace) — the page says so
 *                           plainly instead of faking a bio.
 * @param knownForDepartment e.g. "Acting" / "Directing". May be null.
 * @param photoUrl           Profile photo URL. May be null — the UI
 *                           shows the gold monogram fallback.
 */
data class Person(
    val tmdbId: Int,
    val name: String,
    val biography: String? = null,
    val knownForDepartment: String? = null,
    val photoUrl: String? = null
)
