/*
 * Watchly — autoplay-next queue resolution (cycle 5, spec §5).
 *
 * Pure Kotlin: given the items queued after the one playing, find
 * the next that can actually play. Info-only (catalogue) items are
 * SKIPPED — autoplay must never land on a title with nothing to
 * play — and the queue simply ends when nothing playable remains.
 *
 * Producers: a user List played from List Detail (the queue is the
 * list from the tapped title on), and an official-YouTube channel
 * series (the queue is the channel feed around the episode).
 */
package com.watchly.app.data.playback

import com.watchly.app.data.model.Playability
import com.watchly.app.data.model.Title
import com.watchly.app.data.model.playability

/** One queued continuation: a title plus its episode label, if any. */
data class UpNextItem(
    val title: Title,
    /** e.g. "S1 E4" for an episode, null for a movie / standalone. */
    val episodeLabel: String? = null
)

/** A resolved next item: its position in the queue + the item. */
data class NextUp(
    val queueIndex: Int,
    val item: UpNextItem
)

object AutoplayResolver {

    /** Default playability truth: the title-level badge rule. */
    fun defaultIsPlayable(title: Title): Boolean =
        title.playability() == Playability.PLAYABLE

    /**
     * The first playable item at or after [fromIndex] in [queue],
     * or null when the rest of the queue is info-only / exhausted.
     * [isPlayable] is injectable because the player refines the
     * badge rule with a real file lookup before committing.
     */
    fun nextPlayable(
        queue: List<UpNextItem>,
        fromIndex: Int = 0,
        isPlayable: (Title) -> Boolean = ::defaultIsPlayable
    ): NextUp? {
        for (index in fromIndex until queue.size) {
            val item = queue[index]
            if (isPlayable(item.title)) return NextUp(index, item)
        }
        return null
    }

    /** The queue that remains AFTER [consumed] (the item now playing). */
    fun remainingAfter(queue: List<UpNextItem>, consumed: NextUp): List<UpNextItem> =
        queue.drop(consumed.queueIndex + 1)

    /**
     * Builds the up-next queue for an official-YouTube channel
     * series. A channel feed is NEWEST first, so the episode that
     * follows the current one (published later) sits BEFORE it in
     * the feed; the queue walks from the current entry towards the
     * top of the feed, nearest episode first. When the current
     * video is the newest upload, there is no next episode and the
     * queue is empty. Videos not found in the feed yield no queue
     * (nothing is invented).
     */
    fun youtubeSeriesQueue(
        feedNewestFirst: List<Title>,
        currentVideoId: String?
    ): List<UpNextItem> {
        if (currentVideoId == null) return emptyList()
        val index = feedNewestFirst.indexOfFirst { it.youtubeId == currentVideoId }
        if (index <= 0) return emptyList()
        return (index - 1 downTo 0).map { UpNextItem(feedNewestFirst[it]) }
    }
}
