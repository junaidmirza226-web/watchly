package com.watchly.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for the "Playable" vs "Info only" rule: only titles
 * that carry an Archive id have a real video file in v1.
 */
class PlayabilityTest {

    @Test
    fun `archive film is playable`() {
        val film = Title(
            archiveId = "nightofthelivingdead_201910",
            name = "Night of the Living Dead",
            mediaType = MediaType.MOVIE,
            year = "1968"
        )
        assertEquals(Playability.PLAYABLE, film.playability())
    }

    @Test
    fun `tmdb-only title is info only`() {
        val film = Title(
            tmdbId = 155,
            name = "The Dark Knight",
            mediaType = MediaType.MOVIE,
            year = "2008"
        )
        assertEquals(Playability.INFO_ONLY, film.playability())
    }

    @Test
    fun `tvmaze show is info only`() {
        val show = Title(
            tvmazeId = 169,
            name = "Breaking Bad",
            mediaType = MediaType.TV,
            year = "2008"
        )
        assertEquals(Playability.INFO_ONLY, show.playability())
    }

    @Test
    fun `anilist anime is info only`() {
        val anime = Title(
            anilistId = 21,
            name = "One Piece",
            mediaType = MediaType.ANIME,
            year = "1999"
        )
        assertEquals(Playability.INFO_ONLY, anime.playability())
    }

    @Test
    fun `bare title with no ids is info only`() {
        val title = Title(name = "Unknown", mediaType = MediaType.MOVIE)
        assertEquals(Playability.INFO_ONLY, title.playability())
    }

    @Test
    fun `tmdb title merged with its archive record stays playable`() {
        // The search merge fills in the archiveId a TMDB record lacks,
        // so the merged row keeps the playable file.
        val merged = Title(
            tmdbId = 12345,
            archiveId = "somefilm_2020",
            name = "Some Film",
            mediaType = MediaType.MOVIE,
            year = "2020"
        )
        assertEquals(Playability.PLAYABLE, merged.playability())
    }
}
