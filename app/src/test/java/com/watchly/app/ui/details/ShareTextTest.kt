package com.watchly.app.ui.details

import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Title
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pure-JVM tests for the details Share text (bug reported
 * 2026-11-06: share carried only the title name, no link).
 * Runs with the standalone kotlinc + JUnit console launcher
 * (tools/run-jvm-tests.sh).
 */
class ShareTextTest {

    @Test
    fun `tmdb movie shares the tmdb movie link`() {
        val title = Title(
            tmdbId = 155,
            name = "The Dark Knight",
            mediaType = MediaType.MOVIE,
            year = "2008"
        )
        assertEquals(
            "The Dark Knight (2008)\nhttps://www.themoviedb.org/movie/155",
            shareTextFor(title)
        )
    }

    @Test
    fun `tmdb tv shares the tmdb tv link`() {
        val title = Title(
            tmdbId = 1399,
            name = "Game of Thrones",
            mediaType = MediaType.TV,
            year = "2011"
        )
        assertEquals(
            "Game of Thrones (2011)\nhttps://www.themoviedb.org/tv/1399",
            shareTextFor(title)
        )
    }

    @Test
    fun `tmdb anime also uses the tmdb tv path`() {
        val title = Title(
            tmdbId = 37854,
            name = "One Piece",
            mediaType = MediaType.ANIME,
            year = "1999"
        )
        assertEquals(
            "One Piece (1999)\nhttps://www.themoviedb.org/tv/37854",
            shareTextFor(title)
        )
    }

    @Test
    fun `imdb is the fallback when there is no tmdb id`() {
        val title = Title(
            imdbId = "tt0468569",
            name = "The Dark Knight",
            mediaType = MediaType.MOVIE,
            year = "2008"
        )
        assertEquals(
            "The Dark Knight (2008)\nhttps://www.imdb.com/title/tt0468569/",
            shareTextFor(title)
        )
    }

    @Test
    fun `tvmaze anilist and archive links follow in order`() {
        assertEquals(
            "Some Show\nhttps://www.tvmaze.com/shows/82",
            shareTextFor(Title(tvmazeId = 82, name = "Some Show", mediaType = MediaType.TV))
        )
        assertEquals(
            "Some Anime\nhttps://anilist.co/anime/21",
            shareTextFor(Title(anilistId = 21, name = "Some Anime", mediaType = MediaType.ANIME))
        )
        assertEquals(
            "Old Film\nhttps://archive.org/details/night_of_the_living_dead",
            shareTextFor(
                Title(
                    archiveId = "night_of_the_living_dead",
                    name = "Old Film",
                    mediaType = MediaType.MOVIE
                )
            )
        )
    }

    @Test
    fun `no id at all falls back to the found-on-watchly line`() {
        val title = Title(
            name = "Mystery Title",
            mediaType = MediaType.MOVIE,
            year = "2020"
        )
        assertEquals("Mystery Title (2020) — found on Watchly", shareTextFor(title))
    }

    @Test
    fun `no year omits the parentheses`() {
        val title = Title(
            tmdbId = 1,
            name = "Undated",
            mediaType = MediaType.MOVIE
        )
        assertEquals("Undated\nhttps://www.themoviedb.org/movie/1", shareTextFor(title))
    }
}
