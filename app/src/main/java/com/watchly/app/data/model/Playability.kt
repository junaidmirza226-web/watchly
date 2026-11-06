/*
 * Watchly — "Playable" vs "Info only" rule (pure Kotlin, no Android).
 *
 * The honest truth in v1: the only titles with a real video file are
 * the public-domain films from the Internet Archive — a title carries
 * an [Title.archiveId] exactly when it came from that catalogue, and
 * the details page then fetches its actual playable files. TMDB,
 * TVmaze and AniList titles are catalogue/metadata only: no video
 * file exists for them in Watchly yet, so they are "Info only".
 *
 * Downloads only ever start from an Archive playable file, so a
 * downloaded title always has an archiveId too; local files (§12.2)
 * do not exist yet. When they land, this rule gains a "downloaded /
 * local file present" branch — until then, archiveId is the whole
 * truth, and nothing here invents playability a title doesn't have.
 *
 * The details page refines this with the real file list once it has
 * loaded (see TitleDetailsUiState.playability): an Archive film whose
 * files fetch came back empty is honestly "Info only".
 */
package com.watchly.app.data.model

/** Whether a title can actually play in Watchly right now. */
enum class Playability {
    /** A real video file exists (Archive film in v1). */
    PLAYABLE,

    /** Catalogue / metadata only — no video file to play yet. */
    INFO_ONLY
}

/** Title-level playability, from the data the title itself carries. */
fun Title.playability(): Playability =
    if (archiveId != null) Playability.PLAYABLE else Playability.INFO_ONLY
