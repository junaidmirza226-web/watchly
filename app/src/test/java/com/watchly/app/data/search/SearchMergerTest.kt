package com.watchly.app.data.search

import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Title
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure-JVM tests for the unified search merge (spec §3.1, §1.1).
 * No Android classes involved — runs with the standalone kotlinc +
 * JUnit console launcher (tools/run-jvm-tests.sh).
 */
class SearchMergerTest {

    private fun show(
        name: String,
        year: String? = "2008",
        tvmazeId: Int? = null,
        imdbId: String? = null,
        rating: Double? = null,
        posterUrl: String? = null
    ) = Title(
        tvmazeId = tvmazeId,
        imdbId = imdbId,
        name = name,
        rating = rating,
        posterUrl = posterUrl,
        mediaType = MediaType.TV,
        year = year
    )

    @Test
    fun `same tvmaze id from two lists shows once with both ids kept`() {
        val fromSearch = show("Breaking Bad", tvmazeId = 169, rating = 9.2)
        val fromOther = show("Breaking Bad", tvmazeId = 169, imdbId = "tt0903747")

        val merged = SearchMerger.mergeTitles(listOf(fromSearch, fromOther))

        assertEquals(1, merged.size)
        assertEquals(169, merged[0].tvmazeId)
        assertEquals("tt0903747", merged[0].imdbId)
        assertEquals(9.2, merged[0].rating)
    }

    @Test
    fun `same name and year without a shared id still merges`() {
        val tvmaze = show("Breaking Bad", tvmazeId = 169, posterUrl = null)
        val other = show(
            "Breaking Bad",
            imdbId = "tt0903747",
            posterUrl = "https://x/poster.jpg"
        )

        val merged = SearchMerger.mergeTitles(listOf(tvmaze, other))

        assertEquals(1, merged.size)
        assertEquals(169, merged[0].tvmazeId)
        assertEquals("tt0903747", merged[0].imdbId)
        // First non-blank poster wins.
        assertEquals("https://x/poster.jpg", merged[0].posterUrl)
    }

    @Test
    fun `name matching ignores case and punctuation`() {
        val first = show("Breaking Bad!", tvmazeId = 169)
        val second = show("breaking bad", imdbId = "tt0903747")

        assertEquals(1, SearchMerger.mergeTitles(listOf(first, second)).size)
    }

    @Test
    fun `same name but different year stays two titles`() {
        val old = show("Dune", year = "1984", tvmazeId = 1)
        val remake = show("Dune", year = "2021", tvmazeId = 2)

        assertEquals(2, SearchMerger.mergeTitles(listOf(old, remake)).size)
    }

    @Test
    fun `same name in different media types stays separate`() {
        val showTitle = show("Naruto", tvmazeId = 5)
        val anime = Title(
            anilistId = 20,
            name = "Naruto",
            mediaType = MediaType.ANIME,
            year = "2002"
        )

        val merged = SearchMerger.mergeTitles(listOf(showTitle, anime))
        assertEquals(2, merged.size)
    }

    @Test
    fun `merge keeps the higher rating and unions genres`() {
        val first = show("Show", tvmazeId = 1, rating = 7.5).copy(genres = listOf("Drama"))
        val second = show("Show", imdbId = "tt1", rating = 8.4).copy(genres = listOf("drama", "Crime"))

        val merged = SearchMerger.mergeTitles(listOf(first, second))
        assertEquals(1, merged.size)
        assertEquals(8.4, merged[0].rating)
        assertEquals(listOf("Drama", "Crime"), merged[0].genres)
    }

    @Test
    fun `merge groups movies shows and anime separately`() {
        val groups = SearchMerger.merge(
            listOf(
                listOf(
                    Title(name = "Film", mediaType = MediaType.MOVIE, year = "1968"),
                    show("Show", tvmazeId = 1)
                ),
                listOf(
                    Title(name = "Anime", mediaType = MediaType.ANIME, anilistId = 2)
                )
            )
        )

        assertEquals(listOf("Film"), groups.movies.map { it.name })
        assertEquals(listOf("Show"), groups.shows.map { it.name })
        assertEquals(listOf("Anime"), groups.anime.map { it.name })
        assertEquals(3, groups.totalCount)
        assertTrue(!groups.isEmpty)
    }

    @Test
    fun `empty input gives empty groups`() {
        val groups = SearchMerger.merge(emptyList())
        assertTrue(groups.isEmpty)
        assertEquals(0, groups.totalCount)
        assertNull(SearchMerger.nameKey(Title(name = "   ", mediaType = MediaType.MOVIE)))
    }

    @Test
    fun `genre filter keeps only matching titles in every group`() {
        // Cycle 5: Search genre chips filter the merged groups.
        val groups = SearchGroups(
            movies = listOf(
                Title(
                    name = "Scary Film", mediaType = MediaType.MOVIE,
                    genres = listOf("Horror", "Thriller")
                ),
                Title(
                    name = "Funny Film", mediaType = MediaType.MOVIE,
                    genres = listOf("Comedy")
                )
            ),
            shows = listOf(
                Title(
                    name = "Scary Show", mediaType = MediaType.TV,
                    genres = listOf("horror") // case must not matter
                )
            )
        )

        val horror = groups.filteredByGenre("Horror")
        assertEquals(listOf("Scary Film"), horror.movies.map { it.name })
        assertEquals(listOf("Scary Show"), horror.shows.map { it.name })
        assertTrue(horror.anime.isEmpty())

        // "All"/null pass everything through untouched.
        assertEquals(3, groups.filteredByGenre(null).totalCount)
        assertEquals(3, groups.filteredByGenre("All").totalCount)
        // The chip list is the union of genres present, A–Z.
        assertEquals(
            listOf("Comedy", "Horror", "Thriller", "horror"),
            groups.allGenres
        )
    }
}
