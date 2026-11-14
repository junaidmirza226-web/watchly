package com.watchly.app.data.local

import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Playability
import com.watchly.app.data.model.Title
import com.watchly.app.data.model.playability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cycle 5 (Phase D): filename → title derivation, the video
 * extension rule, and the playability truth for on-device files —
 * a title carrying a local URI is PLAYABLE, full stop.
 */
class LocalFileTitlesTest {

    @Test
    fun `dotted scene style names become readable titles`() {
        assertEquals(
            "The Matrix 1999 1080p WEB DL",
            deriveTitleFromFileName("The.Matrix.1999.1080p.WEB-DL.mkv")
        )
    }

    @Test
    fun `underscores and dashes become spaces`() {
        assertEquals(
            "home video 2024 final",
            deriveTitleFromFileName("home_video-2024_final.mp4")
        )
    }

    @Test
    fun `a plain name just loses its extension`() {
        assertEquals("Holiday", deriveTitleFromFileName("Holiday.mp4"))
    }

    @Test
    fun `a name that is only an extension keeps the raw name`() {
        assertEquals(".mp4", deriveTitleFromFileName(".mp4"))
    }

    @Test
    fun `video extensions are recognised case insensitively`() {
        assertTrue(isVideoFileName("clip.MKV"))
        assertTrue(isVideoFileName("clip.webm"))
        assertFalse(isVideoFileName("notes.txt"))
        assertFalse(isVideoFileName("noextension"))
    }

    @Test
    fun `a local file title is playable and keys by its uri`() {
        val title = Title(
            localUri = "content://media/external/video/42",
            name = "Holiday",
            mediaType = MediaType.MOVIE
        )
        assertEquals(Playability.PLAYABLE, title.playability())
        assertEquals("local:content://media/external/video/42", title.stableKey)
    }
}
