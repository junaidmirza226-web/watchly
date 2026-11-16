package com.watchly.app.data.remote.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Dubbed-audio detection from provider labels/URLs: every dubbed variant
 * a source exposes must be tagged so it surfaces as its own labelled
 * source in the player.
 */
class AudioLanguageDetectTest {

    @Test
    fun specificLanguagesAreDetectedFromUrlsAndLabels() {
        assertEquals("Hindi", StreamProviderHelper.detectAudioLanguage("https://cdn.x/hindi/fight-club.m3u8"))
        assertEquals("Tamil", StreamProviderHelper.detectAudioLanguage("1080p • Tamil • MoviesAPI"))
        assertEquals("Telugu", StreamProviderHelper.detectAudioLanguage("stream-telugu-720.mp4"))
        assertEquals("Malayalam", StreamProviderHelper.detectAudioLanguage("Malayalam Dubbed HD"))
    }

    @Test
    fun dualAudioVariantsAreDetected() {
        assertEquals("Dual Audio", StreamProviderHelper.detectAudioLanguage("Dual Audio WEB-DL"))
        assertEquals("Dual Audio", StreamProviderHelper.detectAudioLanguage("multi audio 1080p"))
        assertEquals("Dual Audio", StreamProviderHelper.detectAudioLanguage("dual-hindi-english"))
    }

    @Test
    fun dualAudioMarkerWinsOverASingleLanguageName() {
        assertEquals("Dual Audio", StreamProviderHelper.detectAudioLanguage("Hindi Dual Audio"))
        assertEquals("Hindi", StreamProviderHelper.detectAudioLanguage("Hindi Dubbed"))
    }

    @Test
    fun dualMarkersNeverMatchInsideUnrelatedWords() {
        assertEquals("Hindi", StreamProviderHelper.detectAudioLanguage("gradual hindi release"))
    }

    @Test
    fun defaultAudioStreamsStayUntagged() {
        assertNull(StreamProviderHelper.detectAudioLanguage("1080p • MultiEmbed"))
        assertNull(StreamProviderHelper.detectAudioLanguage("https://cdn.x/movie/abc.m3u8"))
        assertNull(StreamProviderHelper.detectAudioLanguage(""))
    }
}
