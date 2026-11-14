package com.watchly.app.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Data Saver image policy (cycle 10, §14.1): only provider URL
 * schemes with a guaranteed smaller variant are rewritten, and
 * everything else passes through byte-identical — an invented
 * URL would 404, which is worse than a big poster.
 */
class DataSaverImagePolicyTest {

    @Test
    fun `tmdb w500 poster drops to w342`() {
        assertEquals(
            "https://image.tmdb.org/t/p/w342/abc.jpg",
            DataSaverImagePolicy.smallerUrl(
                "https://image.tmdb.org/t/p/w500/abc.jpg"
            )
        )
    }

    @Test
    fun `tmdb w780 backdrop drops to w342`() {
        assertEquals(
            "https://image.tmdb.org/t/p/w342/xyz.jpg",
            DataSaverImagePolicy.smallerUrl(
                "https://image.tmdb.org/t/p/w780/xyz.jpg"
            )
        )
    }

    @Test
    fun `tmdb original drops to w500`() {
        assertEquals(
            "https://image.tmdb.org/t/p/w500/big.jpg",
            DataSaverImagePolicy.smallerUrl(
                "https://image.tmdb.org/t/p/original/big.jpg"
            )
        )
    }

    @Test
    fun `tmdb sizes already small pass through`() {
        // w185 cast photos and w92 provider logos are already the
        // small variant — rewriting them would only blur.
        assertEquals(
            "https://image.tmdb.org/t/p/w185/face.jpg",
            DataSaverImagePolicy.smallerUrl(
                "https://image.tmdb.org/t/p/w185/face.jpg"
            )
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w92/logo.png",
            DataSaverImagePolicy.smallerUrl(
                "https://image.tmdb.org/t/p/w92/logo.png"
            )
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w342/already.jpg",
            DataSaverImagePolicy.smallerUrl(
                "https://image.tmdb.org/t/p/w342/already.jpg"
            )
        )
    }

    @Test
    fun `anilist large cover drops to medium`() {
        assertEquals(
            "https://s4.anilist.co/file/anilistcdn/media/anime/cover/medium/bx1-a.jpg",
            DataSaverImagePolicy.smallerUrl(
                "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx1-a.jpg"
            )
        )
    }

    @Test
    fun `providers without a derivable variant pass through`() {
        // TVmaze hands out unrelated original/medium URLs — no
        // rewrite is derivable, so the URL stays exactly as-is.
        val tvmaze = "https://static.tvmaze.com/uploads/images/original_untouched/1/2.jpg"
        assertEquals(tvmaze, DataSaverImagePolicy.smallerUrl(tvmaze))
        val fanart = "https://assets.fanart.tv/fanart/movies/123/moviebackground/x.jpg"
        assertEquals(fanart, DataSaverImagePolicy.smallerUrl(fanart))
        val archive = "https://archive.org/services/img/somefilm"
        assertEquals(archive, DataSaverImagePolicy.smallerUrl(archive))
    }

    @Test
    fun `non url strings pass through`() {
        assertEquals("", DataSaverImagePolicy.smallerUrl(""))
        assertEquals("null", DataSaverImagePolicy.smallerUrl("null"))
    }
}
