/*
 * Watchly — subtitle models (cycle 4, OpenSubtitles).
 *
 * Dependencies: none beyond the Kotlin standard library.
 */
package com.watchly.app.data.model

/**
 * One subtitle file offered by the subtitle provider for a title
 * (or one episode), before it is downloaded.
 *
 * @param fileId Provider file id, used for the download call.
 * @param language ISO language code ("en", "ur", …).
 * @param releaseName The release the subtitle was synced for
 *   ("The.Dark.Knight.2008.1080p.BluRay.x264"), or null.
 * @param downloadCount How often this file has been downloaded —
 *   a rough popularity/quality signal, shown in the picker.
 * @param rating Provider community rating 0–10, when reported.
 * @param hearingImpaired True for SDH / hearing-impaired tracks.
 */
data class SubtitleResult(
    val fileId: Int,
    val language: String,
    val releaseName: String? = null,
    val downloadCount: Int = 0,
    val rating: Double? = null,
    val hearingImpaired: Boolean = false
) {
    /** Short human label for menus ("English • BluRay release"). */
    val displayLabel: String
        get() = releaseName?.takeIf { it.isNotBlank() } ?: "Subtitle #$fileId"
}

/**
 * A subtitle ready to attach to the player: a local file (downloaded
 * into the app's cache) or a direct URL, with its MIME type.
 */
data class SubtitleTrack(
    val url: String,
    val mimeType: String,
    val language: String,
    val label: String
)
