/*
 * Watchly — film industries for browse rows (user request 2026-11-06).
 *
 * An "industry" here is a TMDB original-language filter: TMDB's
 * community keeps every one of these catalogues updated daily, so
 * Watchly shows live rows per industry without hosting any database
 * of its own. [languageCode] is the ISO 639-1 code TMDB expects in
 * `with_original_language`.
 */
package com.watchly.app.data.browse

import com.watchly.app.data.model.Title

/** One industry row definition. */
data class Industry(
    val label: String,
    /** ISO 639-1 original-language code for TMDB discover. */
    val languageCode: String
)

/** Industries shown on Home (with a TMDB key) and the Movies tab. */
val INDUSTRIES: List<Industry> = listOf(
    Industry("Bollywood", "hi"),
    Industry("Tollywood", "te"),
    Industry("Kollywood", "ta"),
    Industry("Mollywood", "ml"),
    Industry("Punjabi Cinema", "pa"),
    Industry("Bengali Cinema", "bn"),
    Industry("Hollywood", "en"),
    Industry("Korean Cinema", "ko")
)

/** A loaded industry row: the definition plus its current titles. */
data class IndustryRow(
    val industry: Industry,
    val titles: List<Title>
)
