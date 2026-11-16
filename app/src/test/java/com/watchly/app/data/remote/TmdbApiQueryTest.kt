package com.watchly.app.data.remote

import com.watchly.app.data.browse.BrowseYearFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The real-device bug this suite pins down: pipe lists like "te|ta|ml|kn"
 * were sent as `with_original_language` and TMDB answered HTTP 422, blanking
 * every industry row and every multi-filter browse. Multi-language queries
 * now fan out per language and URLs only ever carry one code.
 */
class TmdbApiQueryTest {

    @Test
    fun splitLanguageQueryTurnsPipeAndCommaListsIntoDistinctCodes() {
        assertEquals(emptyList<String>(), TmdbApi.splitLanguageQuery(null))
        assertEquals(listOf("hi"), TmdbApi.splitLanguageQuery("hi"))
        assertEquals(listOf("te", "ta", "ml", "kn"), TmdbApi.splitLanguageQuery("te|ta|ml|kn"))
        assertEquals(listOf("hi", "te", "ta"), TmdbApi.splitLanguageQuery(" hi|TE , ta "))
        assertEquals(listOf("hi"), TmdbApi.splitLanguageQuery("hi|hi"))
    }

    @Test
    fun discoverUrlsNeverEmbedMultiLanguagePipes() {
        val url = TmdbApi.movieDiscoverUrl("key", "hi", null, TmdbApi.MovieSort.POPULAR, 1)
        assertTrue(url.contains("with_original_language=hi"))
        assertFalse(url.contains("%7C"))
        assertFalse(url.contains("|"))

        val all = TmdbApi.movieDiscoverUrl("key", null, null, TmdbApi.MovieSort.POPULAR, 1)
        assertFalse(all.contains("with_original_language"))
    }

    @Test
    fun advancedDiscoverUrlsCarrySingleLanguageGenresAndYearBounds() {
        val url = TmdbApi.movieDiscoverAdvancedUrl(
            "key", "te", "80,16", BrowseYearFilter.DECADE_2020S,
            7.5, TmdbApi.MovieSort.TOP_RATED, 2
        )
        assertTrue(url.contains("with_original_language=te"))
        assertFalse(url.contains("%7C"))
        // `,` = OR (ANY mode) — URL-encoded comma.
        assertTrue(url.contains("with_genres=80%2C16"))
        assertTrue(url.contains("page=2"))
        assertTrue(url.contains("primary_release_date.gte="))
    }

    @Test
    fun tvDiscoverUrlsUseTvSortAndPipeGenreAndSemantics() {
        val url = TmdbApi.tvDiscoverAdvancedUrl(
            "key", "ko", "18%7C10759", BrowseYearFilter.ALL,
            null, TmdbApi.MovieSort.NEWEST, 1
        )
        assertTrue(url.contains("sort_by=first_air_date.desc"))
        assertTrue(url.contains("with_original_language=ko"))
    }

    @Test
    fun describeTmdbFailureDistinguishesKeyRejectionFromUnreachableNetworks() {
        assertTrue(
            TmdbApi.describeTmdbFailure(UnknownHostException("api.themoviedb.org"))
                .contains("Couldn't reach TMDB")
        )
        assertTrue(
            TmdbApi.describeTmdbFailure(SocketTimeoutException("timeout"))
                .contains("Couldn't reach TMDB")
        )
        assertTrue(
            TmdbApi.describeTmdbFailure(IllegalStateException("TMDB HTTP 401: Unauthorized"))
                .contains("rejected")
        )
        assertTrue(
            TmdbApi.describeTmdbFailure(IllegalStateException("TMDB HTTP 429: slow down"))
                .contains("rate-limiting")
        )
        assertTrue(
            TmdbApi.describeTmdbFailure(IllegalStateException("TMDB HTTP 503: down"))
                .contains("servers")
        )
        assertTrue(
            TmdbApi.describeTmdbFailure(IllegalStateException("No TMDB key"))
                .contains("No TMDB key")
        )
    }

    @Test
    fun keyRotationHelpersRewriteAndReadTheApiKeyParameter() {
        val url = "https://api.themoviedb.org/3/discover/movie?api_key=old&include_adult=false"
        val swapped = TmdbApi.withApiKey(url, "new-key")
        assertTrue(swapped.contains("api_key=new-key"))
        assertFalse(swapped.contains("api_key=old"))
        assertEquals("new-key", TmdbApi.apiKeyFrom(swapped))
        assertEquals("old", TmdbApi.apiKeyFrom(url))
    }
}
