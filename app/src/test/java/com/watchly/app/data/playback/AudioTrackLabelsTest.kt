package com.watchly.app.data.playback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Cycle 9 (§5.6): audio menu labels are built from the track's own
 * format data — "English — AAC 5.1" — never invented detail.
 */
class AudioTrackLabelsTest {

    @Test
    fun `full label combines language codec and channels`() {
        assertEquals(
            "English — AAC 5.1",
            AudioTrackLabels.labelFor("en", "audio/mp4a-latm", 6)
        )
        assertEquals(
            "Urdu — Dolby Digital Stereo",
            AudioTrackLabels.labelFor("ur", "audio/ac3", 2)
        )
    }

    @Test
    fun `unknown pieces fall back honestly`() {
        assertEquals("Unknown", AudioTrackLabels.labelFor(null, null, -1))
        assertEquals("Unknown", AudioTrackLabels.labelFor("und", "audio/x-unknown", 0))
        // Known language, unknown codec: codec is simply absent.
        assertEquals(
            "English — Stereo",
            AudioTrackLabels.labelFor("en", "audio/x-unknown", 2)
        )
    }

    @Test
    fun `channel labels`() {
        assertEquals("Mono", AudioTrackLabels.channelLabel(1))
        assertEquals("Stereo", AudioTrackLabels.channelLabel(2))
        assertEquals("5.1", AudioTrackLabels.channelLabel(6))
        assertEquals("7.1", AudioTrackLabels.channelLabel(8))
        assertNull(AudioTrackLabels.channelLabel(-1))
        assertNull(AudioTrackLabels.channelLabel(0))
    }

    @Test
    fun `track ids round trip and reject garbage`() {
        assertEquals("1:0", AudioTrackLabels.trackId(1, 0))
        assertEquals(1 to 0, AudioTrackLabels.parseTrackId("1:0"))
        assertEquals(2 to 13, AudioTrackLabels.parseTrackId("2:13"))
        assertNull(AudioTrackLabels.parseTrackId("x"))
        assertNull(AudioTrackLabels.parseTrackId("1"))
        assertNull(AudioTrackLabels.parseTrackId("a:b"))
        assertNull(AudioTrackLabels.parseTrackId(""))
    }
}
