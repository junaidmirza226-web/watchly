/*
 * Watchly — segmented download core (cycle 5, spec §7 engine).
 *
 * Watchly's own download engine core: pure Kotlin + java.io +
 * coroutines, no Android types, so the whole transfer logic is
 * unit-testable on a plain JVM with a fake [RangeFetcher].
 *
 * How it works:
 *  - The file is split into segments (default 4 connections). Each
 *    segment is fetched with an HTTP Range request and written into
 *    the target file at its own offset, so segments land side by
 *    side in one pre-sized file — there is no separate assembly
 *    step, and the finished file is byte-identical to the source.
 *  - Progress is reported as segment state ([DownloadSnapshot]) and
 *    persisted by the caller. A paused or killed download resumes
 *    from the SAVED segment offsets — byte-level resume, never a
 *    silent restart from zero.
 *  - Servers that ignore Range requests (answer 200 to a ranged
 *    GET) are detected and fall back to a single-stream download;
 *    if that stream is later resumed, the resume tries a Range from
 *    the saved offset first and restarts only when the server
 *    refuses again.
 */
package com.watchly.app.data.downloads

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

/** One byte-range of the target file and how much of it is on disk. */
data class DownloadSegment(
    val start: Long,
    val endInclusive: Long,
    val downloaded: Long = 0
) {
    val length: Long get() = endInclusive - start + 1
    val remaining: Long get() = length - downloaded
    val isComplete: Boolean get() = downloaded >= length
    val nextOffset: Long get() = start + downloaded
}

/** Point-in-time progress of a whole download, for persistence/UI. */
data class DownloadSnapshot(
    val segments: List<DownloadSegment>,
    /** Total file size when known; -1 when the source won't say. */
    val totalBytes: Long,
    val singleStream: Boolean
) {
    val bytesDownloaded: Long get() = segments.sumOf { it.downloaded }
    val isComplete: Boolean get() = segments.all { it.isComplete }
}

/** Splits a file into download segments. */
object SegmentPlanner {

    /** Smallest segment worth its own connection. */
    const val MIN_SEGMENT_BYTES: Long = 512L * 1024L

    /**
     * The plan for a fresh download of [totalBytes]: up to
     * [connections] near-equal segments; small files get fewer
     * (a segment is never smaller than [MIN_SEGMENT_BYTES]).
     */
    fun plan(
        totalBytes: Long,
        connections: Int,
        minSegmentBytes: Long = MIN_SEGMENT_BYTES
    ): List<DownloadSegment> {
        require(totalBytes > 0) { "totalBytes must be positive" }
        val bySize = (totalBytes / minSegmentBytes).coerceAtLeast(1L)
        val count = minOf(connections.toLong(), bySize, totalBytes)
            .toInt().coerceAtLeast(1)
        if (count == 1) return listOf(DownloadSegment(0, totalBytes - 1))
        val base = totalBytes / count
        val remainder = totalBytes % count
        val segments = ArrayList<DownloadSegment>(count)
        var start = 0L
        for (i in 0 until count) {
            val length = base + if (i < remainder) 1 else 0
            segments += DownloadSegment(start, start + length - 1)
            start += length
        }
        return segments
    }

    /**
     * True when [saved] segments exactly tile a file of
     * [probedTotal] bytes — only then may a resume trust them. A
     * changed file on the server (different total) invalidates the
     * saved state and the download starts fresh.
     */
    fun isResumable(saved: List<DownloadSegment>, probedTotal: Long): Boolean {
        if (saved.isEmpty() || probedTotal <= 0) return false
        var expectedStart = 0L
        for (segment in saved) {
            if (segment.start != expectedStart) return false
            if (segment.endInclusive < segment.start) return false
            if (segment.downloaded < 0 || segment.downloaded > segment.length) {
                return false
            }
            expectedStart = segment.endInclusive + 1
        }
        return expectedStart == probedTotal
    }
}

/** What a probe of the source URL learned. */
data class FetchProbe(
    /** Total size in bytes, or null when the source won't say. */
    val totalBytes: Long?,
    /** True when the source honours Range requests. */
    val acceptsRanges: Boolean
)

/** The source answered an HTTP error instead of file bytes. */
class FetchHttpException(val code: Int) : IOException("HTTP $code")

/** A ranged request was answered with a full 200 body. */
class RangeNotHonouredException : IOException("Server ignored the Range request")

/**
 * The byte source behind an interface: production is OkHttp
 * ([OkHttpRangeFetcher]); tests serve bytes from memory.
 */
interface RangeFetcher {
    /** Learns the total size + Range support without downloading. */
    fun probe(url: String): FetchProbe

    /**
     * Opens the byte stream for [start]..[endInclusive] (an HTTP
     * Range request), or for the whole file when [start] is null.
     * Throws [RangeNotHonouredException] when a ranged request is
     * answered with a full-body 200, [FetchHttpException] on HTTP
     * errors, [IOException] on connection failures.
     */
    fun open(url: String, start: Long?, endInclusive: Long?): InputStream
}

/** How a [SegmentedDownloader.download] call ended. */
sealed interface DownloadOutcome {
    /** The file is complete and verified against the total size. */
    data class Completed(val totalBytes: Long) : DownloadOutcome

    /** Stopped by request; [snapshot] holds the resume state. */
    data class Paused(val snapshot: DownloadSnapshot) : DownloadOutcome

    /** Failed; [snapshot] still holds the bytes already on disk. */
    data class Failed(
        val reason: String,
        val snapshot: DownloadSnapshot
    ) : DownloadOutcome
}

/** Internal control-flow signal: the caller asked us to stop. */
private class StopRequested : CancellationException("Download stop requested")

class SegmentedDownloader(
    private val fetcher: RangeFetcher,
    private val connections: Int = DEFAULT_CONNECTIONS,
    private val chunkBytes: Int = DEFAULT_CHUNK_BYTES,
    private val persistEveryBytes: Long = DEFAULT_PERSIST_EVERY_BYTES
) {

    /**
     * Downloads [url] into [targetFile] (a partial file is fine —
     * it is written in place and only the caller renames it when
     * complete). [savedSegments] is the persisted state of an
     * earlier attempt, resumed when the source still matches.
     * [shouldStop] is polled per chunk (pause / cancellation);
     * [onState] receives snapshots to persist, throttled, and once
     * more on every exit path.
     */
    suspend fun download(
        url: String,
        targetFile: File,
        savedSegments: List<DownloadSegment>?,
        savedSingleStream: Boolean = false,
        expectedTotal: Long? = null,
        shouldStop: () -> Boolean = { false },
        onState: (DownloadSnapshot) -> Unit = {}
    ): DownloadOutcome {
        val probe = try {
            fetcher.probe(url)
        } catch (e: FetchHttpException) {
            return failedOutcome(e, emptyList(), expectedTotal ?: -1, false)
        } catch (e: IOException) {
            return failedOutcome(e, emptyList(), expectedTotal ?: -1, false)
        }
        val total = probe.totalBytes ?: expectedTotal

        // Segmented path: size known + ranges honoured.
        if (total != null && total > 0 && probe.acceptsRanges) {
            val segments = when {
                savedSegments != null &&
                    SegmentPlanner.isResumable(savedSegments, total) ->
                    savedSegments.map { it.copy() }.toMutableList()
                else -> SegmentPlanner.plan(total, connections).toMutableList()
            }
            return try {
                runSegments(url, targetFile, segments, total, shouldStop, onState)
            } catch (e: RangeNotHonouredException) {
                // The probe said ranges work but the real request
                // was answered with a full body — believe the real
                // request and restart as a single stream.
                runSingleStream(url, targetFile, null, total, shouldStop, onState)
            }
        }

        // Single-stream path (unknown size, or no Range support).
        val resumeFrom = if (savedSingleStream && savedSegments != null) {
            savedSegments.firstOrNull()?.downloaded ?: 0L
        } else {
            0L
        }
        return runSingleStream(url, targetFile, resumeFrom, total, shouldStop, onState)
    }

    // ------------------------------------------------------------------
    // Segmented transfer
    // ------------------------------------------------------------------

    private suspend fun runSegments(
        url: String,
        targetFile: File,
        segments: MutableList<DownloadSegment>,
        total: Long,
        shouldStop: () -> Boolean,
        onState: (DownloadSnapshot) -> Unit
    ): DownloadOutcome {
        // Pre-size the file so segments can write at their offsets;
        // a resumed file is already this size and keeps its bytes.
        RandomAccessFile(targetFile, "rw").use { it.setLength(total) }
        val snapshot = { DownloadSnapshot(segments.map { it }, total, false) }
        onState(snapshot())
        return try {
            coroutineScope {
                val jobs = segments.indices
                    .filter { !segments[it].isComplete }
                    .map { index ->
                        async { downloadSegment(url, targetFile, segments, index, shouldStop, onState, total) }
                    }
                jobs.forEach { it.await() }
            }
            onState(snapshot())
            val length = targetFile.length()
            if (length == total) {
                DownloadOutcome.Completed(total)
            } else {
                DownloadOutcome.Failed(
                    "The downloaded file is incomplete. Tap Resume to carry on.",
                    snapshot()
                )
            }
        } catch (e: StopRequested) {
            onState(snapshot())
            DownloadOutcome.Paused(snapshot())
        } catch (e: CancellationException) {
            onState(snapshot())
            throw e
        } catch (e: RangeNotHonouredException) {
            throw e // handled by the caller (single-stream fallback)
        } catch (e: IOException) {
            onState(snapshot())
            failedOutcome(e, segments.map { it }, total, false)
        }
    }

    private fun downloadSegment(
        url: String,
        targetFile: File,
        segments: MutableList<DownloadSegment>,
        index: Int,
        shouldStop: () -> Boolean,
        onState: (DownloadSnapshot) -> Unit,
        total: Long
    ) {
        val segment = segments[index]
        if (segment.isComplete) return
        val stream = fetcher.open(url, segment.nextOffset, segment.endInclusive)
        RandomAccessFile(targetFile, "rw").use { raf ->
            raf.seek(segment.nextOffset)
            stream.use { input ->
                val buffer = ByteArray(chunkBytes)
                var sincePersist = 0L
                while (true) {
                    if (shouldStop()) throw StopRequested()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    raf.write(buffer, 0, read)
                    val updated = segments[index].copy(
                        downloaded = segments[index].downloaded + read
                    )
                    segments[index] = updated
                    sincePersist += read
                    if (sincePersist >= persistEveryBytes) {
                        sincePersist = 0
                        onState(DownloadSnapshot(segments.map { it }, total, false))
                    }
                    if (updated.isComplete) break
                }
            }
        }
        val finished = segments[index]
        if (!finished.isComplete) {
            // The stream ended early (source closed the connection):
            // treat as a network failure — the saved state resumes.
            throw IOException("Source closed the connection early")
        }
    }

    // ------------------------------------------------------------------
    // Single-stream fallback
    // ------------------------------------------------------------------

    private fun runSingleStream(
        url: String,
        targetFile: File,
        resumeFrom: Long?,
        knownTotal: Long?,
        shouldStop: () -> Boolean,
        onState: (DownloadSnapshot) -> Unit
    ): DownloadOutcome {
        var startAt = resumeFrom ?: 0L
        var honoured = false
        var stream: InputStream? = null
        if (startAt > 0) {
            try {
                stream = fetcher.open(url, startAt, knownTotal?.minus(1))
                honoured = true
            } catch (e: RangeNotHonouredException) {
                // Server won't resume: restart the stream from zero.
                startAt = 0
                stream = null
            } catch (e: IOException) {
                val snapshot = singleSnapshot(startAt, knownTotal)
                onState(snapshot)
                return failedOutcome(e, snapshot.segments, knownTotal ?: -1, true)
            }
        }
        val segment = DownloadSegment(0, (knownTotal ?: 1L) - 1, startAt)
        val segments = mutableListOf(segment)
        val snapshot = { singleSnapshot(segments[0].downloaded, knownTotal) }
        return try {
            val input = stream ?: fetcher.open(url, null, null)
            RandomAccessFile(targetFile, "rw").use { raf ->
                if (honoured) {
                    raf.seek(startAt)
                } else {
                    raf.setLength(0)
                    raf.seek(0)
                }
                input.use {
                    val buffer = ByteArray(chunkBytes)
                    var sincePersist = 0L
                    while (true) {
                        if (shouldStop()) throw StopRequested()
                        val read = it.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        raf.write(buffer, 0, read)
                        segments[0] = segments[0].copy(
                            downloaded = segments[0].downloaded + read
                        )
                        sincePersist += read
                        if (sincePersist >= persistEveryBytes) {
                            sincePersist = 0
                            onState(snapshot())
                        }
                    }
                }
            }
            onState(snapshot())
            val downloaded = segments[0].downloaded
            when {
                knownTotal != null && downloaded < knownTotal ->
                    DownloadOutcome.Failed(
                        "The connection dropped. Tap Resume to carry on.",
                        snapshot()
                    )
                knownTotal != null && downloaded > knownTotal ->
                    DownloadOutcome.Failed(
                        "The source sent more data than expected.",
                        snapshot()
                    )
                else -> DownloadOutcome.Completed(downloaded)
            }
        } catch (e: StopRequested) {
            onState(snapshot())
            DownloadOutcome.Paused(snapshot())
        } catch (e: CancellationException) {
            onState(snapshot())
            throw e
        } catch (e: IOException) {
            onState(snapshot())
            failedOutcome(e, segments.map { it }, knownTotal ?: -1, true)
        }
    }

    private fun singleSnapshot(
        downloaded: Long,
        knownTotal: Long?
    ): DownloadSnapshot = DownloadSnapshot(
        segments = listOf(
            DownloadSegment(0, (knownTotal ?: 1L) - 1, downloaded)
        ),
        totalBytes = knownTotal ?: -1,
        singleStream = true
    )

    private fun failedOutcome(
        error: IOException,
        segments: List<DownloadSegment>,
        total: Long,
        singleStream: Boolean
    ): DownloadOutcome.Failed = DownloadOutcome.Failed(
        reason = when (error) {
            is FetchHttpException ->
                "The source is unavailable right now (HTTP ${error.code})."
            else -> "The connection dropped. Tap Resume to carry on."
        },
        snapshot = DownloadSnapshot(segments, total, singleStream)
    )

    companion object {
        /** Parallel Range connections per download. */
        const val DEFAULT_CONNECTIONS = 4
        const val DEFAULT_CHUNK_BYTES = 64 * 1024

        /** Persist progress at least this often (bytes, per segment). */
        const val DEFAULT_PERSIST_EVERY_BYTES: Long = 256L * 1024L
    }
}
