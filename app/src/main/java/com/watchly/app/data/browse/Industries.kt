/*
 * Watchly — global & regional film/series industries.
 *
 * An "industry" maps to TMDB's `with_original_language` parameter
 * (single ISO 639-1 code like "hi", or pipe-delimited multi-language
 * expression like "te|ta|ml|kn" for all of South Indian cinema).
 */
package com.watchly.app.data.browse

import com.watchly.app.data.model.Title

/** One industry / cinema hub definition. */
data class Industry(
    val label: String,
    /** ISO 639-1 original-language code (or pipe-separated codes for regional hubs). */
    val languageCode: String
) {
    /** Individual ISO 639-1 language codes covered by this industry. */
    val languageCodes: Set<String>
        get() = languageCode.split("|")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

    /** Short subtitle explaining the industry in filter sheets. */
    val subtitle: String
        get() = when (languageCode) {
            "hi" -> "Hindi Blockbusters"
            "en" -> "English Global Hits"
            "te|ta|ml|kn" -> "Telugu • Tamil • Malayalam • Kannada"
            "te" -> "Telugu Cinema"
            "ta" -> "Tamil Cinema"
            "ml" -> "Malayalam Cinema"
            "kn" -> "Kannada Cinema"
            "pa" -> "Punjabi Hits"
            "ur" -> "Pakistani & Urdu"
            "bn" -> "Bengali Cinema"
            "ko" -> "K-Cinema & K-Drama"
            "ja" -> "Japanese & Anime"
            "tr" -> "Turkish Drama & Film"
            "es" -> "Spanish & Latin"
            else -> languageCode.uppercase()
        }
}

/**
 * Full regional + international industry catalogue shown across Home,
 * Movies, and Shows multi-filters.
 *
 * Note: Bollywood ("hi") precedes Hollywood ("en") preserving the
 * canonical row order asserted in HomeTmdbIndustriesTest.
 */
val INDUSTRIES: List<Industry> = listOf(
    Industry("Bollywood", "hi"),
    Industry("Hollywood", "en"),
    Industry("South Indian", "te|ta|ml|kn"),
    Industry("Tollywood", "te"),
    Industry("Kollywood", "ta"),
    Industry("Mollywood", "ml"),
    Industry("Sandalwood", "kn"),
    Industry("Punjabi Cinema", "pa"),
    Industry("Pakistani & Urdu", "ur"),
    Industry("Bengali Cinema", "bn"),
    Industry("Korean Cinema", "ko"),
    Industry("Turkish Cinema", "tr"),
    Industry("Japanese Cinema", "ja"),
    Industry("Spanish Cinema", "es")
)

/** A loaded industry row: the definition plus its current titles. */
data class IndustryRow(
    val industry: Industry,
    val titles: List<Title>
)
