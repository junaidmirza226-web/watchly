/*
 * Watchly — the user's subtitle appearance (cycle 9, spec §6.2).
 *
 * Two choices, applied live to the player's subtitle rendering and
 * persisted per profile: the text size (five steps, §6.2) and the
 * background behind the text. Everything else (white text, outline
 * edge) is fixed — chosen to stay readable on any frame.
 */
package com.watchly.app.data.model

/** Background painted behind subtitle text. */
enum class SubtitleBackground {
    /** No background — text and outline only. */
    NONE,

    /** Half-transparent black (the default). */
    SEMI,

    /** Solid black bar behind the text. */
    SOLID
}

/** The persisted subtitle style: text size + background. */
data class SubtitleStyle(
    val textSizeSp: Float = DEFAULT_TEXT_SIZE_SP,
    val background: SubtitleBackground = SubtitleBackground.SEMI
) {
    companion object {
        /** The five size steps (§6.2), smallest first. */
        val TEXT_SIZE_STEPS_SP: List<Float> = listOf(14f, 17f, 20f, 24f, 28f)

        const val DEFAULT_TEXT_SIZE_SP: Float = 17f

        /** Short chip labels for [TEXT_SIZE_STEPS_SP], same order. */
        val TEXT_SIZE_LABELS: List<String> = listOf("XS", "S", "M", "L", "XL")
    }
}
