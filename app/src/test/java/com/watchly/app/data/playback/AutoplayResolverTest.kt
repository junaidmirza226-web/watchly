package com.watchly.app.data.playback

import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Title
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure-JVM tests for autoplay-next resolution (cycle 5): the next
 * item must be playable, info-only items are skipped, and the
 * queue ends cleanly.
 */
class AutoplayResolverTest {

    private fun playable(name: String) = Title(
        archiveId = "arch-$name", name = name, mediaType = MediaType.MOVIE
    )

    private fun infoOnly(name: String) = Title(
        tvmazeId = name.hashCode(), name = name, mediaType = MediaType.MOVIE
    )

    private fun episode(title: Title, label: String) =
        UpNextItem(title, episodeLabel = label)

    @Test
    fun `next playable is the immediate playable item`() {
        val queue = listOf(
            episode(playable("Ep2"), "S1 E2"),
            episode(playable("Ep3"), "S1 E3")
        )
        val next = AutoplayResolver.nextPlayable(queue)
        assertEquals(0, next?.queueIndex)
        assertEquals("S1 E2", next?.item?.episodeLabel)
    }

    @Test
    fun `info only items are skipped`() {
        val queue = listOf(
            UpNextItem(infoOnly("Catalogue Film")),
            episode(infoOnly("Catalogue Episode"), "S1 E2"),
            episode(playable("Real Episode"), "S1 E3")
        )
        val next = AutoplayResolver.nextPlayable(queue)
        assertEquals(2, next?.queueIndex)
        assertEquals("Real Episode", next?.item?.title?.name)
        // And the queue after it is empty (list end respected).
        assertTrue(
            AutoplayResolver.remainingAfter(queue, next!!).isEmpty()
        )
    }

    @Test
    fun `stops at the end of the queue`() {
        assertNull(AutoplayResolver.nextPlayable(emptyList()))
        assertNull(
            AutoplayResolver.nextPlayable(
                listOf(UpNextItem(infoOnly("Only Catalogue")))
            )
        )
        val queue = listOf(UpNextItem(playable("A")), UpNextItem(playable("B")))
        assertNull(AutoplayResolver.nextPlayable(queue, fromIndex = 2))
        assertEquals(
            "B",
            AutoplayResolver.nextPlayable(queue, fromIndex = 1)?.item?.title?.name
        )
    }

    @Test
    fun `custom playability rule is honoured`() {
        // The player refines the badge rule with a real file check:
        // a badged-playable title whose files fail to load is not a
        // valid autoplay target.
        val queue = listOf(
            UpNextItem(playable("Broken")),
            UpNextItem(playable("Works"))
        )
        val next = AutoplayResolver.nextPlayable(queue) { it.name != "Broken" }
        assertEquals("Works", next?.item?.title?.name)
    }

    @Test
    fun `youtube series queue walks towards newer episodes`() {
        // Feed order is newest first: E3, E2, E1.
        val e3 = Title(youtubeId = "v3", name = "Episode 3", mediaType = MediaType.ANIME)
        val e2 = Title(youtubeId = "v2", name = "Episode 2", mediaType = MediaType.ANIME)
        val e1 = Title(youtubeId = "v1", name = "Episode 1", mediaType = MediaType.ANIME)
        val feed = listOf(e3, e2, e1)

        // After E1 comes E2, then E3.
        assertEquals(
            listOf("Episode 2", "Episode 3"),
            AutoplayResolver.youtubeSeriesQueue(feed, "v1").map { it.title.name }
        )
        // After E2 comes E3 only.
        assertEquals(
            listOf("Episode 3"),
            AutoplayResolver.youtubeSeriesQueue(feed, "v2").map { it.title.name }
        )
        // E3 is the newest: nothing follows.
        assertTrue(AutoplayResolver.youtubeSeriesQueue(feed, "v3").isEmpty())
        // Unknown video: no queue is invented.
        assertTrue(AutoplayResolver.youtubeSeriesQueue(feed, "nope").isEmpty())
        assertTrue(AutoplayResolver.youtubeSeriesQueue(feed, null).isEmpty())
    }
}
