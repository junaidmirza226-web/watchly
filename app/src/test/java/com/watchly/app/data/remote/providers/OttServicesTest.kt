package com.watchly.app.data.remote.providers

import com.watchly.app.data.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sources must show the services people look for — Netflix, Prime, Hotstar… */
class OttServicesTest {

    @Test
    fun allRequestedConsumerServicesAreSurfaced() {
        val names = OttServices.ALL.map { it.displayName }
        listOf(
            "Netflix", "Amazon Prime Video", "Disney+ Hotstar", "Disney+",
            "Hulu", "Max", "Apple TV+", "Peacock", "Paramount+",
            "Zee5", "SonyLIV", "JioCinema", "Crunchyroll"
        ).forEach { wanted ->
            assertTrue("missing $wanted", names.contains(wanted))
        }
    }

    @Test
    fun embedUrlsCarryTheServiceSlugAndTheRightMediaShape() {
        val netflix = OttServices.ALL.first { it.slug == "netflix" }
        val movie = OttServices.embedUrl(netflix, 155, "tt0468569", MediaType.MOVIE, null, null)!!
        assertTrue(movie.startsWith("https://"))
        assertTrue(movie.contains("/embed/movie/155"))
        assertTrue(movie.endsWith("?s=netflix"))

        val prime = OttServices.ALL.first { it.slug == "prime" }
        val tv = OttServices.embedUrl(prime, 1399, null, MediaType.TV, 2, 5)!!
        assertTrue(tv.contains("/embed/tv/1399/2/5"))
        assertTrue(tv.endsWith("?s=prime"))

        val hotstar = OttServices.ALL.first { it.slug == "hotstar" }
        val imdbOnly = OttServices.embedUrl(hotstar, null, "tt0468569", MediaType.MOVIE, null, null)!!
        assertTrue(imdbOnly.contains("/embed/movie/tt0468569"))

        assertNull(OttServices.embedUrl(netflix, null, null, MediaType.MOVIE, null, null))
    }

    @Test
    fun buildSourcesLabelsEveryServiceForTheSourcesList() {
        val sources = OttServices.buildSources(155, "tt0468569", MediaType.MOVIE)
        assertEquals(OttServices.ALL.size, sources.size)
        assertNotNull(sources.firstOrNull { it.providerName == "Netflix" })
        assertNotNull(sources.firstOrNull { it.providerName == "Amazon Prime Video" })
        assertNotNull(sources.firstOrNull { it.providerName == "Disney+ Hotstar" })
        assertTrue(sources.all { it.url.startsWith("https://") })
    }
}
