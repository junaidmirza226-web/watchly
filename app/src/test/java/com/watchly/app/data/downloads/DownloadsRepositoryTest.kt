package com.watchly.app.data.downloads

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.PlayableFile
import com.watchly.app.data.model.Title
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * DownloadsRepository with a fake engine: enqueue/query/remove are
 * scripted in memory, records live in real SharedPreferences.
 * Completed-download checks touch the real filesystem (Robolectric
 * runs on this JVM), so the fake engine points at a temp directory
 * and the test creates the "downloaded" file there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DownloadsRepositoryTest {

    private class FakeDownloadEngine : DownloadEngine {
        var nextId = 100L
        val states = mutableMapOf<Long, EngineDownloadState>()
        val removedIds = mutableListOf<Long>()
        var enqueueCount = 0
        val dir: File = File(
            System.getProperty("java.io.tmpdir"),
            "watchly-dl-test-${System.nanoTime()}"
        ).apply { mkdirs() }

        override fun enqueue(url: String, fileName: String, notificationTitle: String): Long {
            enqueueCount++
            val id = nextId++
            states[id] = EngineDownloadState(DownloadRules.CODE_PENDING, 0, -1, 0)
            return id
        }

        override fun query(downloadId: Long): EngineDownloadState? = states[downloadId]

        override fun remove(downloadId: Long) {
            removedIds.add(downloadId)
            states.remove(downloadId)
        }

        override fun localPathFor(fileName: String): String =
            File(dir, fileName).absolutePath
    }

    private lateinit var engine: FakeDownloadEngine
    private lateinit var repo: DownloadsRepository

    private val film = Title(
        archiveId = "nightofthelivingdead_201910",
        name = "Night of the Living Dead",
        mediaType = MediaType.MOVIE,
        year = "1968"
    )
    private val file = PlayableFile(
        url = "https://archive.org/download/x/film.mp4",
        fileName = "film.mp4",
        format = "MPEG4",
        sizeBytes = 2_000L
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences(DownloadsRepository.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        engine = FakeDownloadEngine()
        repo = DownloadsRepository(context, engine)
    }

    @Test
    fun `start download enqueues once and records it as queued`() {
        val record = repo.startDownload(film, file)

        assertEquals(1, engine.enqueueCount)
        assertEquals(DownloadStatus.QUEUED, record.status)
        assertEquals(film.stableKey, record.titleKey)
        assertEquals("film.mp4", record.fileName)
        assertEquals(1, repo.getDownloads().size)
    }

    @Test
    fun `starting the same file twice does not duplicate`() {
        repo.startDownload(film, file)
        val again = repo.startDownload(film, file)

        assertEquals(1, engine.enqueueCount)
        assertEquals(1, repo.getDownloads().size)
        assertNotNull(again)
    }

    @Test
    fun `refresh maps engine progress then completion with local path`() {
        val record = repo.startDownload(film, file)

        engine.states[record.downloadId] =
            EngineDownloadState(DownloadRules.CODE_RUNNING, 500, 2_000, 0)
        val running = repo.recordForTitle(film.stableKey)!!
        assertEquals(DownloadStatus.RUNNING, running.status)
        assertEquals(0.25f, running.progressFraction!!, 0.001f)

        // Simulate the finished file landing on disk, then completion.
        File(engine.localPathFor("film.mp4")).writeBytes(ByteArray(2_000))
        engine.states[record.downloadId] =
            EngineDownloadState(DownloadRules.CODE_SUCCESSFUL, 2_000, 2_000, 0)
        val done = repo.recordForTitle(film.stableKey)!!
        assertEquals(DownloadStatus.COMPLETED, done.status)
        assertEquals(engine.localPathFor("film.mp4"), done.localPath)
        assertEquals(2_000L, repo.totalDownloadedBytes())
    }

    @Test
    fun `failed download carries a plain reason and can be retried`() {
        val record = repo.startDownload(film, file)
        engine.states[record.downloadId] =
            EngineDownloadState(DownloadRules.CODE_FAILED, 0, -1, 1004)

        val failed = repo.recordForTitle(film.stableKey)!!
        assertEquals(DownloadStatus.FAILED, failed.status)
        assertTrue(failed.failureReason!!.contains("space", ignoreCase = true))

        // Retry starts a fresh engine download for the same file.
        val retried = repo.startDownload(film, file)
        assertEquals(2, engine.enqueueCount)
        assertEquals(DownloadStatus.QUEUED, retried.status)
        assertEquals(1, repo.getDownloads().size)
    }

    @Test
    fun `engine losing a download marks it failed honestly`() {
        val record = repo.startDownload(film, file)
        engine.states.remove(record.downloadId)

        val after = repo.recordForTitle(film.stableKey)!!
        assertEquals(DownloadStatus.FAILED, after.status)
        assertTrue(after.failureReason!!.contains("removed", ignoreCase = true))
    }

    @Test
    fun `delete removes engine entry record and file`() {
        val record = repo.startDownload(film, file)
        val local = File(engine.localPathFor("film.mp4"))
        local.writeBytes(ByteArray(10))
        engine.states[record.downloadId] =
            EngineDownloadState(DownloadRules.CODE_SUCCESSFUL, 2_000, 2_000, 0)
        val done = repo.recordForTitle(film.stableKey)!!
        assertEquals(DownloadStatus.COMPLETED, done.status)

        repo.delete(done)

        assertTrue(engine.removedIds.contains(done.downloadId))
        assertTrue(repo.getDownloads().isEmpty())
        assertNull(repo.recordForTitle(film.stableKey))
    }
}
