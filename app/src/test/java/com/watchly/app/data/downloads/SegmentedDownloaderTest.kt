package com.watchly.app.data.downloads

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/**
 * Pure-JVM tests for the cycle-5 segmented download core, driven by
 * an in-memory [RangeFetcher]. These pin the brief's guarantees:
 * resume continues from the SAVED offset (never zero), segmented
 * assembly is byte-identical, and pause leaves a resumable state.
 */
class SegmentedDownloaderTest {

    /** Serves [data] with optional Range support; records requests. */
    private class FakeFetcher(
        val data: ByteArray,
        var honoursRanges: Boolean = true,
        var probeAcceptsRanges: Boolean = true,
        var knownTotal: Boolean = true
    ) : RangeFetcher {
        /** (start, end) of every open() call, in order. */
        val opens = mutableListOf<Pair<Long?, Long?>>()

        override fun probe(url: String): FetchProbe = FetchProbe(
            totalBytes = if (knownTotal) data.size.toLong() else null,
            acceptsRanges = probeAcceptsRanges
        )

        override fun open(url: String, start: Long?, endInclusive: Long?): InputStream {
            opens += start to endInclusive
            if (start != null && !honoursRanges) {
                throw RangeNotHonouredException()
            }
            val from = (start ?: 0L).toInt()
            val to = (endInclusive ?: (data.size - 1).toLong()).toInt()
            return ByteArrayInputStream(data, from, to - from + 1)
        }
    }

    private fun tempFile(): File =
        File.createTempFile("watchly-seg-test", ".part").apply { delete() }

    private fun sourceBytes(size: Int): ByteArray =
        ByteArray(size) { index -> (index * 31 + 7).toByte() }

    @Test
    fun `segmented download assembles a byte-identical file`() = runBlocking {
        // > 4 × MIN_SEGMENT_BYTES so the planner really uses 4 segments.
        val data = sourceBytes(5_000_003) // odd size: uneven segments
        val fetcher = FakeFetcher(data)
        val target = tempFile()
        val downloader = SegmentedDownloader(fetcher, connections = 4, chunkBytes = 4096)

        val outcome = downloader.download("https://x/film.mp4", target, null)

        assertTrue(outcome is DownloadOutcome.Completed, "was $outcome")
        assertEquals(data.size.toLong(), (outcome as DownloadOutcome.Completed).totalBytes)
        assertTrue(data.contentEquals(target.readBytes()), "file bytes differ from source")
        // Four ranged opens, one per segment.
        assertEquals(4, fetcher.opens.size)
        assertTrue(fetcher.opens.all { it.first != null })
    }

    @Test
    fun `resume continues from the saved segment offsets, not zero`() = runBlocking {
        val data = sourceBytes(4_000_000)
        val plan = SegmentPlanner.plan(data.size.toLong(), connections = 4)
        // Pretend an earlier attempt finished segment 0 and half of segment 1.
        val saved = plan.mapIndexed { index, segment ->
            when (index) {
                0 -> segment.copy(downloaded = segment.length)
                1 -> segment.copy(downloaded = segment.length / 2)
                else -> segment
            }
        }
        // The partial file on disk already holds those bytes, at
        // their segment offsets.
        val target = tempFile()
        java.io.RandomAccessFile(target, "rw").use { raf ->
            raf.setLength(0)
            saved.forEach { segment ->
                raf.seek(segment.start)
                raf.write(data, segment.start.toInt(), segment.downloaded.toInt())
            }
        }
        val fetcher = FakeFetcher(data)
        val downloader = SegmentedDownloader(fetcher, connections = 4, chunkBytes = 4096)

        val outcome = downloader.download("https://x/film.mp4", target, saved)

        assertTrue(outcome is DownloadOutcome.Completed, "was $outcome")
        assertTrue(data.contentEquals(target.readBytes()), "resumed file differs")
        // Segment 0 was complete: no request for it. Segment 1 resumed
        // mid-way; segments 2/3 started at their own starts.
        val starts = fetcher.opens.map { it.first }
        assertTrue(!starts.contains(saved[0].start), "completed segment re-fetched: $starts")
        assertTrue(
            starts.contains(saved[1].start + saved[1].downloaded),
            "segment 1 did not resume from its saved offset: $starts"
        )
        assertTrue(starts.contains(saved[2].start))
        assertTrue(starts.contains(saved[3].start))
    }

    @Test
    fun `pause stops mid-download and the saved state resumes byte-exact`() = runBlocking {
        val data = sourceBytes(200_000)
        val fetcher = FakeFetcher(data)
        val target = tempFile()
        val downloader = SegmentedDownloader(
            fetcher, connections = 2, chunkBytes = 2048, persistEveryBytes = 2048
        )
        var chunks = 0
        var lastSnapshot: DownloadSnapshot? = null

        val paused = downloader.download(
            url = "https://x/film.mp4",
            targetFile = target,
            savedSegments = null,
            shouldStop = { ++chunks > 3 },
            onState = { lastSnapshot = it }
        )

        assertTrue(paused is DownloadOutcome.Paused, "was $paused")
        val snapshot = (paused as DownloadOutcome.Paused).snapshot
        assertTrue(snapshot.bytesDownloaded in 1 until data.size.toLong())
        assertEquals(snapshot.bytesDownloaded, lastSnapshot?.bytesDownloaded)

        // A fresh downloader (the "app restarted" case) resumes from
        // exactly that state and lands a byte-identical file.
        val fetcher2 = FakeFetcher(data)
        val outcome = SegmentedDownloader(fetcher2, connections = 2, chunkBytes = 2048)
            .download("https://x/film.mp4", target, snapshot.segments)
        assertTrue(outcome is DownloadOutcome.Completed, "was $outcome")
        assertTrue(data.contentEquals(target.readBytes()))
    }

    @Test
    fun `server ignoring ranges falls back to a byte-identical single stream`() = runBlocking {
        val data = sourceBytes(50_000)
        // Probe claims ranges (some servers lie); real requests get 200s.
        val fetcher = FakeFetcher(data, honoursRanges = false, probeAcceptsRanges = true)
        val target = tempFile()

        val outcome = SegmentedDownloader(fetcher, connections = 4, chunkBytes = 4096)
            .download("https://x/film.mp4", target, null)

        assertTrue(outcome is DownloadOutcome.Completed, "was $outcome")
        assertTrue(data.contentEquals(target.readBytes()))
    }

    @Test
    fun `no range support and unknown size still downloads single stream`() = runBlocking {
        val data = sourceBytes(30_000)
        val fetcher = FakeFetcher(
            data, honoursRanges = false, probeAcceptsRanges = false, knownTotal = false
        )
        val target = tempFile()

        val outcome = SegmentedDownloader(fetcher, chunkBytes = 4096)
            .download("https://x/film.mp4", target, null)

        assertTrue(outcome is DownloadOutcome.Completed, "was $outcome")
        assertEquals(data.size.toLong(), (outcome as DownloadOutcome.Completed).totalBytes)
        assertTrue(data.contentEquals(target.readBytes()))
    }

    @Test
    fun `saved state for a changed file is rejected and starts fresh`() = runBlocking {
        val oldData = sourceBytes(40_000)
        val saved = SegmentPlanner.plan(oldData.size.toLong(), 4)
            .map { it.copy(downloaded = it.length / 2) }
        val newData = ByteArray(60_000) { 0x5A }
        val fetcher = FakeFetcher(newData)
        val target = tempFile()

        val outcome = SegmentedDownloader(fetcher, connections = 4, chunkBytes = 4096)
            .download("https://x/film.mp4", target, saved)

        assertTrue(outcome is DownloadOutcome.Completed, "was $outcome")
        assertTrue(newData.contentEquals(target.readBytes()))
        // Every request started at a fresh segment boundary — none
        // used the stale half-way offsets.
        val freshStarts = SegmentPlanner.plan(newData.size.toLong(), 4).map { it.start }.toSet()
        assertTrue(fetcher.opens.all { it.first in freshStarts })
    }

    @Test
    fun `http error surfaces as a plain failure with no partial success`() = runBlocking {
        val fetcher = object : RangeFetcher {
            override fun probe(url: String): FetchProbe = throw FetchHttpException(503)
            override fun open(url: String, start: Long?, endInclusive: Long?): InputStream =
                throw FetchHttpException(503)
        }
        val outcome = SegmentedDownloader(fetcher)
            .download("https://x/film.mp4", tempFile(), null)
        assertTrue(outcome is DownloadOutcome.Failed, "was $outcome")
        assertTrue(
            (outcome as DownloadOutcome.Failed).reason.contains("503")
        )
    }

    @Test
    fun `planner tiles the file exactly and respects the minimum segment size`() {
        val plan = SegmentPlanner.plan(10_000_001, connections = 4)
        assertEquals(4, plan.size)
        assertEquals(0L, plan.first().start)
        assertEquals(10_000_000L, plan.last().endInclusive)
        for (i in 1 until plan.size) {
            assertEquals(plan[i - 1].endInclusive + 1, plan[i].start)
        }
        // Tiny file: one segment, however many connections offered.
        assertEquals(1, SegmentPlanner.plan(1_000, connections = 4).size)
        assertTrue(SegmentPlanner.isResumable(plan, 10_000_001))
        assertTrue(!SegmentPlanner.isResumable(plan, 9_999))
    }
}
