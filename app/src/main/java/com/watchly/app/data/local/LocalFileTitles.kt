/*
 * Watchly — filename → display title for on-device videos
 * (cycle 5, pure Kotlin so it is JVM-testable).
 *
 * Phone video files are usually named like
 * "The.Matrix.1999.1080p.WEB-DL.mkv" or "home_video 2024.mp4".
 * The derived title strips the extension, turns separators
 * (. _ -) into spaces and collapses whitespace:
 * "The Matrix 1999 1080p WEB DL". Honest and predictable — no
 * metadata service is consulted for the user's own files.
 */
package com.watchly.app.data.local

/** Video file extensions Watchly treats as playable in a folder scan. */
val VIDEO_EXTENSIONS: Set<String> = setOf(
    "mp4", "mkv", "webm", "avi", "mov", "m4v", "3gp", "ts", "mpg", "mpeg"
)

/** True when [displayName] ends in a known video extension. */
fun isVideoFileName(displayName: String): Boolean {
    val ext = displayName.substringAfterLast('.', "").lowercase()
    return ext in VIDEO_EXTENSIONS
}

/**
 * Derives the display title from a file's display name. Falls back
 * to the raw name when stripping would leave nothing (a file named
 * just ".mp4" keeps its name rather than becoming blank).
 */
fun deriveTitleFromFileName(displayName: String): String {
    val withoutExtension = displayName.substringBeforeLast('.', displayName)
    val spaced = withoutExtension
        .replace(Regex("[._\\-]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    return spaced.ifBlank { displayName }
}
