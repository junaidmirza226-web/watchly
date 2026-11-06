/*
 * Watchly — episode and cast models for the Title Details page (spec §4.1).
 *
 * Dependencies: none beyond the Kotlin standard library.
 * Episodes come from TVmaze (`/shows/:id/episodes`) in v1; cast from
 * TVmaze (`/shows/:id/cast`). Anime episode lists and TMDB cast arrive
 * with later providers — the models are provider-neutral on purpose.
 */
package com.watchly.app.data.model

/**
 * One episode of a show.
 *
 * @param id        Provider episode id (TVmaze id in v1). May be null for
 *                  hand-built entries.
 * @param season    Season number (1-based). Specials are season 0 on TVmaze.
 * @param number    Episode number within the season.
 * @param name      Episode title. May be null (TVmaze sometimes omits it).
 * @param airDate   Air date as "yyyy-MM-dd", when known.
 * @param runtimeMinutes Episode runtime, when known.
 * @param overview  Plain-text synopsis. May be null.
 * @param imageUrl  Episode still image URL. May be null.
 */
data class Episode(
    val id: Int? = null,
    val season: Int,
    val number: Int,
    val name: String? = null,
    val airDate: String? = null,
    val runtimeMinutes: Int? = null,
    val overview: String? = null,
    val imageUrl: String? = null
) {
    /** "S1 E4" style label used in the episode list. */
    val label: String
        get() = "S$season E$number"
}

/**
 * One cast row: a person and the character they play.
 *
 * @param personName    Actor name.
 * @param characterName Character name. May be null.
 * @param imageUrl      Actor photo URL. May be null.
 */
data class CastMember(
    val personName: String,
    val characterName: String? = null,
    val imageUrl: String? = null
)

/**
 * One playable / downloadable file for a title (Internet Archive in v1).
 *
 * @param url       Direct file URL.
 * @param fileName  File name as stored on the source.
 * @param format    Container/codec label, e.g. "h.264" / "MPEG4".
 * @param sizeBytes File size in bytes, when known.
 * @param qualityLabel Human quality label when derivable, e.g. "512Kb MPEG4".
 */
data class PlayableFile(
    val url: String,
    val fileName: String,
    val format: String? = null,
    val sizeBytes: Long? = null,
    val qualityLabel: String? = null
) {
    /** "596 MB" style size label, or null when the size is unknown. */
    val sizeLabel: String?
        get() = sizeBytes?.let {
            when {
                it >= 1_000_000_000 -> "%.1f GB".format(it / 1_000_000_000.0)
                it >= 1_000_000 -> "%d MB".format(it / 1_000_000)
                it >= 1_000 -> "%d KB".format(it / 1_000)
                else -> "$it B"
            }
        }
}
