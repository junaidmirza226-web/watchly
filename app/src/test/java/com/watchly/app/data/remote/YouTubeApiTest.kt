package com.watchly.app.data.remote

import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Playability
import com.watchly.app.data.model.playability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the YouTube channel-feed parser (cycle 4). The parser is
 * pure string work, so this runs on the plain JVM harness. The
 * fixture mirrors the real Atom shape of
 * youtube.com/feeds/videos.xml (verified live 2026-11-06).
 */
class YouTubeApiTest {

    private val museAsia = YouTubeApi.CHANNELS.first { it.displayName == "Muse Asia" }

    // Two entries: one normal, one with an entity-escaped title and
    // no media thumbnail (parser must build the standard thumb URL).
    // One broken entry (no video id) must be skipped.
    private val sampleFeed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015"
              xmlns:media="http://search.yahoo.com/mrss/"
              xmlns="http://www.w3.org/2005/Atom">
          <title>Muse Asia</title>
          <entry>
            <id>yt:video:abcDEF123_-</id>
            <yt:videoId>abcDEF123_-</yt:videoId>
            <yt:channelId>UCGbshtvS9t-8CW11W7TooQg</yt:channelId>
            <title>Date A Live III - Episode 12 [English Sub]</title>
            <published>2026-10-30T12:00:00+00:00</published>
            <media:group>
              <media:thumbnail url="https://i.ytimg.com/vi/abcDEF123_-/hqdefault.jpg" width="480" height="360"/>
            </media:group>
          </entry>
          <entry>
            <id>yt:video:xyzXYZ987_-</id>
            <yt:videoId>xyzXYZ987_-</yt:videoId>
            <title>Tom &amp; Jerry &#8212; The Movie</title>
            <published>2025-01-15T09:30:00+00:00</published>
            <media:group>
              <media:description>No thumbnail element here.</media:description>
            </media:group>
          </entry>
          <entry>
            <title>Broken entry without an id</title>
            <published>2025-01-01T00:00:00+00:00</published>
          </entry>
        </feed>
    """.trimIndent()

    @Test
    fun `parses feed entries into playable youtube titles`() {
        val titles = YouTubeApi.parseFeed(sampleFeed, museAsia)

        assertEquals(2, titles.size) // broken entry skipped

        val first = titles[0]
        assertEquals("abcDEF123_-", first.youtubeId)
        assertEquals("Date A Live III - Episode 12 [English Sub]", first.name)
        assertEquals("2026", first.year)
        assertEquals(MediaType.ANIME, first.mediaType)
        assertEquals(
            "https://i.ytimg.com/vi/abcDEF123_-/hqdefault.jpg",
            first.posterUrl
        )
        assertEquals("youtube:abcDEF123_-", first.stableKey)
        assertEquals(Playability.PLAYABLE, first.playability())
    }

    @Test
    fun `unescapes entities and derives a thumbnail when none is given`() {
        val titles = YouTubeApi.parseFeed(sampleFeed, museAsia)

        val second = titles[1]
        assertEquals("Tom & Jerry — The Movie", second.name)
        assertEquals(
            "https://i.ytimg.com/vi/xyzXYZ987_-/hqdefault.jpg",
            second.posterUrl
        )
        assertEquals("2025", second.year)
    }

    @Test
    fun `malformed or empty feeds parse to an empty list`() {
        assertTrue(YouTubeApi.parseFeed("", museAsia).isEmpty())
        assertTrue(YouTubeApi.parseFeed("not xml at all", museAsia).isEmpty())
        assertTrue(YouTubeApi.parseFeed("<feed><title>x</title></feed>", museAsia).isEmpty())
    }

    @Test
    fun `curated channels are the verified official set`() {
        // Channel ids were verified against the channels' canonical
        // URLs on 2026-11-06; a typo here would surface a stranger's
        // channel in the app, so pin them.
        val byName = YouTubeApi.CHANNELS.associateBy { it.displayName }
        assertEquals("UCGbshtvS9t-8CW11W7TooQg", byName["Muse Asia"]?.channelId)
        assertEquals("UCBOmfqgTZi7yDp4-3Lr_3lA", byName["Shemaroo Movies"]?.channelId)
        assertEquals("UCEKWXRsfUHkan-D_ljU8Asw", byName["Rajshri"]?.channelId)
        assertEquals("UCyoXW-Dse7fURq30EWl_CUA", byName["Goldmines"]?.channelId)
        assertEquals("UCYauDsl-rswjQGpA0_-Z9Pw", byName["Ultra Movie Parlour"]?.channelId)
        assertEquals(MediaType.ANIME, byName["Muse Asia"]?.mediaType)
        // Ani-One Asia: verified 2026-11-06 (cycle 6) — the @AniOneAsia
        // handle's canonical URL resolves to this id and the live feed
        // fetch returns the "Ani-One Asia" feed with fresh entries.
        assertEquals("UC0wNSTMWIL3qaorLx0jie6A", byName["Ani-One Asia"]?.channelId)
        assertEquals(MediaType.ANIME, byName["Ani-One Asia"]?.mediaType)
    }
}
