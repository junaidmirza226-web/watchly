/*
 * Watchly — audio track options for the player (cycle 9, spec §5.6).
 *
 * The engine reports the audio tracks of the file actually loaded;
 * the UI shows them only when there is a real choice (more than
 * one). Labels follow the spec's shape — "English — AAC 5.1" —
 * built from the track's own format data, never invented: an
 * unknown language is "Unknown", an unknown codec is simply left
 * out of the label.
 *
 * Ids are "audioGroupIndex:trackIndex" within the current media —
 * stable for the engine's track-selection calls, meaningless across
 * files (which is why the remembered choice is the language, the
 * one property that carries from one file of a title to the next).
 */
package com.watchly.app.data.playback

import java.util.Locale

/** One selectable audio track in the currently loaded media. */
data class AudioTrackOption(
    val id: String,
    val label: String,
    /** ISO language code as the file reports it, may be null. */
    val language: String?,
    val isSelected: Boolean
)

/** Pure label/id helpers — JVM-testable, no Android types. */
object AudioTrackLabels {

    /** Engine id for one track: "groupIndex:trackIndex". */
    fun trackId(groupIndex: Int, trackIndex: Int): String = "$groupIndex:$trackIndex"

    /** Parses an engine id back into (groupIndex, trackIndex). */
    fun parseTrackId(id: String): Pair<Int, Int>? {
        val parts = id.split(":")
        if (parts.size != 2) return null
        val group = parts[0].toIntOrNull() ?: return null
        val track = parts[1].toIntOrNull() ?: return null
        return group to track
    }

    /** "English — AAC 5.1" style menu label for one track format. */
    fun labelFor(language: String?, mimeType: String?, channelCount: Int): String {
        // Language stands alone; codec + layout form one detail
        // phrase ("AAC 5.1"), exactly the spec's §5.6 shape.
        val detail = listOfNotNull(codecName(mimeType), channelLabel(channelCount))
            .joinToString(" ")
        val languagePart = languageName(language)
        return if (detail.isEmpty()) languagePart else "$languagePart — $detail"
    }

    /** Human language name; "Unknown" for missing/undetermined. */
    fun languageName(language: String?): String {
        val code = language?.trim()?.lowercase().orEmpty()
        if (code.isEmpty() || code == "und") return "Unknown"
        val name = runCatching {
            Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH)
        }.getOrNull()
        return if (name.isNullOrBlank() || name.equals(code, ignoreCase = true)) {
            code.uppercase()
        } else {
            name
        }
    }

    /** Friendly codec name for an audio MIME type, null when unknown. */
    fun codecName(mimeType: String?): String? = when (mimeType?.lowercase()) {
        "audio/mp4a-latm", "audio/mp4a", "audio/aac" -> "AAC"
        "audio/mpeg", "audio/mp3" -> "MP3"
        "audio/ac3" -> "Dolby Digital"
        "audio/eac3" -> "Dolby Digital Plus"
        "audio/dts" -> "DTS"
        "audio/true-hd", "audio/mlp" -> "TrueHD"
        "audio/flac" -> "FLAC"
        "audio/vorbis" -> "Vorbis"
        "audio/opus" -> "Opus"
        "audio/raw", "audio/l16" -> "PCM"
        else -> null
    }

    /** Speaker layout label; null when the count says nothing useful. */
    fun channelLabel(channelCount: Int): String? = when (channelCount) {
        1 -> "Mono"
        2 -> "Stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> if (channelCount > 2) "$channelCount ch" else null
    }
}
