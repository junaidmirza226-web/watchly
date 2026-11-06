package com.watchly.app.data.browse

import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Title
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pure-JVM tests for the browse-tab sort/filter helper (no Android
 * dependency, so these run on the plain JUnit 5 platform).
 */
class BrowseFiltersTest {

    private val titles = listOf(
        Title(name = "Zebra Show", mediaType = MediaType.TV, rating = 9.2,
            year = "2021", genres = listOf("Drama", "Crime")),
        Title(name = "Apple Show", mediaType = MediaType.TV, rating = 7.5,
            year = "2024", genres = listOf("Comedy")),
        Title(name = "Mango Show", mediaType = MediaType.TV, rating = null,
            year = "2019", genres = listOf("Drama")),
        Title(name = "Banana Show", mediaType = MediaType.TV, rating = 8.8,
            year = "2024", genres = listOf("Drama", "Comedy")),
    )

    @Test
    fun `popular sorts by rating descending, unrated last`() {
        val result = applyBrowseFilter(titles, null, BrowseSort.POPULAR)
        assertEquals(
            listOf("Zebra Show", "Banana Show", "Apple Show", "Mango Show"),
            result.map { it.name }
        )
    }

    @Test
    fun `top rated matches popular when only ratings exist`() {
        val result = applyBrowseFilter(titles, null, BrowseSort.TOP_RATED)
        assertEquals(
            listOf("Zebra Show", "Banana Show", "Apple Show", "Mango Show"),
            result.map { it.name }
        )
    }

    @Test
    fun `newest sorts by year descending`() {
        val result = applyBrowseFilter(titles, null, BrowseSort.NEWEST)
        assertEquals(
            listOf("Apple Show", "Banana Show", "Zebra Show", "Mango Show"),
            result.map { it.name }
        )
    }

    @Test
    fun `a-z sorts by name ignoring case`() {
        val result = applyBrowseFilter(titles, null, BrowseSort.A_Z)
        assertEquals(
            listOf("Apple Show", "Banana Show", "Mango Show", "Zebra Show"),
            result.map { it.name }
        )
    }

    @Test
    fun `genre filter keeps only matching titles, case-insensitive`() {
        val result = applyBrowseFilter(titles, "drama", BrowseSort.A_Z)
        assertEquals(
            listOf("Banana Show", "Mango Show", "Zebra Show"),
            result.map { it.name }
        )
    }

    @Test
    fun `all genre returns everything`() {
        val result = applyBrowseFilter(titles, "All", BrowseSort.A_Z)
        assertEquals(4, result.size)
    }

    @Test
    fun `unknown genre returns an empty list`() {
        val result = applyBrowseFilter(titles, "Western", BrowseSort.POPULAR)
        assertEquals(emptyList<Title>(), result)
    }
}
