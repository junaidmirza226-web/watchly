package com.watchly.app.data.downloads

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.PlayableFile
import com.watchly.app.data.model.Title
import org.json.JSONArray
import org.json.JSONObject
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
 * DownloadsRepository against BOTH engines:
 *  - the cycle-5 Watchly engine (fake [DownloadWorkScheduler] +
 *    the real [DownloadStateStore] — the worker itself needs a
 *    real Android runtime, so its writes are simulated by writing
 *    the state store exactly as the worker does), and
 *  - LEGACY DownloadManager-era records seeded straight into
 *    prefs (a fake [DownloadEngine] behind them), proving records
 *    from before the engine swap still refresh, complete, pause
 *    and delete instead of crashing the Library.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DownloadsRepositoryTest {

    /** Legacy engine double: the pre-cycle-5 DownloadManager seam. */
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

    /** Records WorkManager scheduling calls without WorkManager. */
    private class FakeWorkScheduler : DownloadWorkScheduler {
        val started = mutableListOf<String>()
        val stopped = mutableListOf<String>()
        override fun start(record: DownloadRecord) {
            started += record.id
        }

        override fun stop(recordId: String) {
            stopped += recordId
        }
    }

    /** Scripts the §7.3 platform facts (network / battery / space). */
    private class FakeEnvironment(
        var env: DownloadEnvironment
    ) : DownloadEnvironmentProvider {
        override fun current(): DownloadEnvironment = env
    }

    private lateinit var engine: FakeDownloadEngine
    private lateinit var scheduler: FakeWorkScheduler
    private lateinit var stateStore: DownloadStateStore
    private lateinit var repo: DownloadsRepository
    private lateinit var context: Application

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
        context = ApplicationProvider.getApplicationContext()
        listOf(
            DownloadsRepository.PREFS_NAME,
            DownloadSettingsRepository.PREFS_NAME,
            DownloadStateStore.PREFS_NAME
        ).forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
        engine = FakeDownloadEngine()
        scheduler = FakeWorkScheduler()
        stateStore = DownloadStateStore(context)
        repo = newRepo()
    }

    private fun newRepo(
        environment: DownloadEnvironmentProvider =
            FakeEnvironment(goodEnv())
    ): DownloadsRepository = DownloadsRepository(
        context,
        engine,
        DownloadSettingsRepository(context),
        environment,
        scheduler,
        stateStore
    )

    private fun checkedRepo(env: DownloadEnvironment): DownloadsRepository =
        newRepo(FakeEnvironment(env))

    private fun goodEnv() = DownloadEnvironment(
        network = NetworkKind.WIFI,
        batteryPercent = 80,
        batteryCharging = false,
        freeBytes = 50_000_000_000L
    )

    // ------------------------------------------------------------------
    // Watchly engine (cycle 5)
    // ------------------------------------------------------------------

    @Test
    fun `start download schedules work and records it as queued`() {
        val record = repo.startDownload(film, file)

        assertEquals(listOf(record.id), scheduler.started)
        assertEquals(DownloadStatus.QUEUED, record.status)
        assertEquals(DownloadRecord.ENGINE_WATCHLY, record.engineKind)
        assertEquals(-1L, record.downloadId)
        assertEquals(film.stableKey, record.titleKey)
        assertEquals(1, repo.getDownloads().size)
    }

    @Test
    fun `starting the same file twice does not duplicate`() {
        repo.startDownload(film, file)
        repo.startDownload(film, file)

        assertEquals(1, scheduler.started.size)
        assertEquals(1, repo.getDownloads().size)
    }

    @Test
    fun `worker progress in the state store drives the record`() {
        val record = repo.startDownload(film, file)

        // The worker writes snapshots like this as bytes land.
        stateStore.saveProgress(
            record.id,
            DownloadSnapshot(
                segments = listOf(DownloadSegment(0, 1_999, 500)),
                totalBytes = 2_000,
                singleStream = false
            )
        )
        val running = repo.recordForTitle(film.stableKey)!!
        assertEquals(DownloadStatus.RUNNING, running.status)
        assertEquals(500L, running.bytesDownloaded)
        assertEquals(0.25f, running.progressFraction!!, 0.001f)
    }

    @Test
    fun `worker completion lands the record with its local path`() {
        val record = repo.startDownload(film, file)
        val finalFile = File(repo.downloadsDir(), "film.mp4")
        finalFile.parentFile?.mkdirs()
        finalFile.writeBytes(ByteArray(2_000))
        stateStore.saveProgress(
            record.id,
            DownloadSnapshot(
                segments = listOf(DownloadSegment(0, 1_999, 2_000)),
                totalBytes = 2_000,
                singleStream = false
            )
        )
        stateStore.markDone(record.id)

        val done = repo.recordForTitle(film.stableKey)!!
        assertEquals(DownloadStatus.COMPLETED, done.status)
        assertEquals(finalFile.absolutePath, done.localPath)
        assertEquals(2_000L, repo.totalDownloadedBytes())
    }

    @Test
    fun `pause state persists across repository re-creation`() {
        // The brief's guarantee: pause must survive the repository
        // (and the process) being recreated — the state lives in
        // prefs + the state store, never in memory.
        val record = repo.startDownload(film, file)
        stateStore.saveProgress(
            record.id,
            DownloadSnapshot(
                segments = listOf(DownloadSegment(0, 1_999, 700)),
                totalBytes = 2_000,
                singleStream = false
            )
        )
        val paused = repo.pauseDownload(repo.recordForTitle(film.stableKey)!!)
        assertEquals(DownloadStatus.PAUSED, paused.status)
        assertTrue(paused.userPaused)
        assertEquals(700L, paused.bytesDownloaded)
        assertEquals(listOf(record.id), scheduler.stopped)

        // A brand-new repository over the same storage (the "app
        // restarted" case) still shows the pause and its bytes.
        val recreated = newRepo()
        val afterRestart = recreated.recordForTitle(film.stableKey)!!
        assertEquals(DownloadStatus.PAUSED, afterRestart.status)
        assertTrue(afterRestart.userPaused)
        assertEquals(700L, afterRestart.bytesDownloaded)
        // …and the resume point (segments) survived untouched.
        assertEquals(
            700L,
            DownloadStateStore(context).state(record.id)?.bytesDownloaded
        )
    }

    @Test
    fun `resume re-enqueues work and keeps the saved segments`() {
        val record = repo.startDownload(film, file)
        stateStore.saveProgress(
            record.id,
            DownloadSnapshot(
                segments = listOf(DownloadSegment(0, 1_999, 700)),
                totalBytes = 2_000,
                singleStream = false
            )
        )
        val paused = repo.pauseDownload(repo.recordForTitle(film.stableKey)!!)

        val resumed = repo.resumeDownload(paused)

        assertEquals(DownloadStatus.QUEUED, resumed.status)
        assertTrue(!resumed.userPaused)
        // Work was scheduled a second time (start + resume)…
        assertEquals(2, scheduler.started.size)
        // …and the worker will find the same 700 bytes waiting.
        assertEquals(
            700L,
            stateStore.state(record.id)?.bytesDownloaded
        )
        assertEquals(false, stateStore.state(record.id)?.userPaused)
    }

    @Test
    fun `failed watchly download keeps its segments so retry resumes`() {
        val record = repo.startDownload(film, file)
        stateStore.saveProgress(
            record.id,
            DownloadSnapshot(
                segments = listOf(DownloadSegment(0, 1_999, 900)),
                totalBytes = 2_000,
                singleStream = false
            )
        )
        stateStore.markFailed(record.id, "The connection dropped. Tap Resume to carry on.")

        val failed = repo.recordForTitle(film.stableKey)!!
        assertEquals(DownloadStatus.FAILED, failed.status)
        assertTrue(failed.failureReason!!.contains("connection dropped"))

        // Retry through startDownload: NOT a fresh attempt — the
        // segment state must survive so the worker resumes at 900.
        val retried = repo.startDownload(film, file)
        assertEquals(DownloadStatus.QUEUED, retried.status)
        assertEquals(900L, stateStore.state(record.id)?.bytesDownloaded)
        assertEquals(2, scheduler.started.size)
    }

    @Test
    fun `delete cancels work and removes state files and record`() {
        val record = repo.startDownload(film, file)
        val part = File(repo.downloadsDir(), "film.mp4.part")
        part.parentFile?.mkdirs()
        part.writeBytes(ByteArray(10))
        stateStore.saveProgress(
            record.id,
            DownloadSnapshot(
                segments = listOf(DownloadSegment(0, 1_999, 10)),
                totalBytes = 2_000,
                singleStream = false
            )
        )

        repo.delete(record)

        assertEquals(listOf(record.id), scheduler.stopped)
        assertTrue(!part.exists())
        assertNull(stateStore.state(record.id))
        assertTrue(repo.getDownloads().isEmpty())
    }

    // ------------------------------------------------------------------
    // §7.3 checked starts (unchanged rules, new engine underneath)
    // ------------------------------------------------------------------

    @Test
    fun `checked start is refused on mobile data while wifi only is on`() {
        val outcome = checkedRepo(
            goodEnv().copy(network = NetworkKind.METERED)
        ).startDownloadChecked(film, file)

        assertTrue(outcome is StartDownloadOutcome.Refused)
        assertTrue(
            (outcome as StartDownloadOutcome.Refused).message.contains("Wi-Fi only")
        )
        assertTrue(scheduler.started.isEmpty())
    }

    @Test
    fun `checked start proceeds on wifi and warns near the storage cap`() {
        DownloadSettingsRepository(context).setStorageCapBytes(2_100)

        val outcome = checkedRepo(goodEnv()).startDownloadChecked(film, file)

        assertTrue(outcome is StartDownloadOutcome.Started)
        assertNotNull((outcome as StartDownloadOutcome.Started).warning)
        assertEquals(1, scheduler.started.size)
    }

    @Test
    fun `checked start is refused when the battery is low`() {
        val outcome = checkedRepo(
            goodEnv().copy(batteryPercent = 10, batteryCharging = false)
        ).startDownloadChecked(film, file)

        assertTrue(outcome is StartDownloadOutcome.Refused)
        assertTrue(scheduler.started.isEmpty())
    }

    @Test
    fun `start download records the picked quality label`() {
        val record = repo.startDownload(film, file)

        assertEquals("MPEG4", record.qualityLabel)
        assertEquals("MPEG4", repo.recordForTitle(film.stableKey)!!.qualityLabel)
    }

    // ------------------------------------------------------------------
    // Legacy DownloadManager-era records (pre-cycle-5)
    // ------------------------------------------------------------------

    /** Writes a record exactly as the pre-cycle-5 app stored it. */
    private fun seedLegacyRecord(downloadId: Long, status: DownloadStatus) {
        val obj = JSONObject()
            .put("id", "${film.stableKey}|film.mp4")
            .put("titleKey", film.stableKey)
            .put("titleName", film.name)
            .put("poster", JSONObject.NULL)
            .put("type", "MOVIE")
            .put("fileName", "film.mp4")
            .put("url", file.url)
            .put("size", 2_000L)
            .put("quality", "MPEG4")
            .put("downloadId", downloadId)
            // No "engine" field — that absence IS the legacy marker.
            .put("status", status.name)
            .put("bytes", 0L)
            .put("total", -1L)
            .put("path", JSONObject.NULL)
            .put("reason", JSONObject.NULL)
            .put("createdAt", 1234L)
            .put("userPaused", false)
        context.getSharedPreferences(DownloadsRepository.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(DownloadsRepository.KEY_DOWNLOADS, JSONArray().put(obj).toString())
            .commit()
    }

    @Test
    fun `legacy record refreshes through the old engine and completes`() {
        seedLegacyRecord(downloadId = 55L, status = DownloadStatus.RUNNING)
        engine.states[55L] = EngineDownloadState(DownloadRules.CODE_RUNNING, 500, 2_000, 0)

        val running = repo.recordForTitle(film.stableKey)!!
        assertEquals(DownloadRecord.ENGINE_DM, running.engineKind)
        assertEquals(DownloadStatus.RUNNING, running.status)
        assertEquals(0.25f, running.progressFraction!!, 0.001f)

        File(engine.localPathFor("film.mp4")).writeBytes(ByteArray(2_000))
        engine.states[55L] =
            EngineDownloadState(DownloadRules.CODE_SUCCESSFUL, 2_000, 2_000, 0)
        val done = repo.recordForTitle(film.stableKey)!!
        assertEquals(DownloadStatus.COMPLETED, done.status)
        assertEquals(engine.localPathFor("film.mp4"), done.localPath)
        assertEquals(2_000L, repo.totalDownloadedBytes())
    }

    @Test
    fun `legacy failed download carries a plain reason`() {
        seedLegacyRecord(downloadId = 56L, status = DownloadStatus.RUNNING)
        engine.states[56L] = EngineDownloadState(DownloadRules.CODE_FAILED, 0, -1, 1004)

        val failed = repo.recordForTitle(film.stableKey)!!
        assertEquals(DownloadStatus.FAILED, failed.status)
        assertTrue(failed.failureReason!!.contains("space", ignoreCase = true))
    }

    @Test
    fun `legacy pause and resume keep the old restart semantics`() {
        seedLegacyRecord(downloadId = 57L, status = DownloadStatus.RUNNING)
        engine.states[57L] = EngineDownloadState(DownloadRules.CODE_RUNNING, 500, 2_000, 0)
        val running = repo.recordForTitle(film.stableKey)!!

        val paused = repo.pauseDownload(running)
        assertEquals(DownloadStatus.PAUSED, paused.status)
        assertTrue(paused.userPaused)
        assertTrue(engine.removedIds.contains(57L))
        // Refresh must not misread the removed entry as a failure.
        assertEquals(
            DownloadStatus.PAUSED,
            repo.getDownloads().single().status
        )

        val resumed = repo.resumeDownload(paused)
        assertEquals(DownloadStatus.QUEUED, resumed.status)
        assertEquals(1, engine.enqueueCount)
    }

    @Test
    fun `legacy engine losing a download marks it failed honestly`() {
        seedLegacyRecord(downloadId = 58L, status = DownloadStatus.RUNNING)
        // No state in the fake engine: the system lost the entry.

        val after = repo.recordForTitle(film.stableKey)!!
        assertEquals(DownloadStatus.FAILED, after.status)
        assertTrue(after.failureReason!!.contains("removed", ignoreCase = true))
    }
}
