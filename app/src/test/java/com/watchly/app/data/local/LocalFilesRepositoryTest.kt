package com.watchly.app.data.local

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Cycle 5 (Phase D): the local-files store — picked files and
 * folder finds persist across repository re-creation, duplicates
 * never land twice, and removal drops only the entry (single or
 * whole folder), never anything else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocalFilesRepositoryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(
            LocalFilesRepository.PREFS_NAME, Context.MODE_PRIVATE
        ).edit().clear().commit()
    }

    private fun repo() = LocalFilesRepository(context)

    @Test
    fun `picked files persist across repository re-creation with derived titles`() {
        repo().addFiles(
            listOf(
                "content://media/1" to "The.Matrix.1999.mkv",
                "content://media/2" to "home_video.mp4"
            ),
            nowMs = 1_000L
        )

        val titles = repo().titles()
        assertEquals(2, titles.size)
        assertEquals("The Matrix 1999", titles[0].name)
        assertEquals("content://media/1", titles[0].localUri)
        assertEquals("home video", titles[1].name)
    }

    @Test
    fun `adding the same file twice stores it once`() {
        val files = listOf("content://media/1" to "a.mp4")
        repo().addFiles(files, nowMs = 1L)
        repo().addFiles(files, nowMs = 2L)
        assertEquals(1, repo().entries().size)
    }

    @Test
    fun `folder files carry their tree and removeTree drops only those`() {
        val r = repo()
        r.addFiles(listOf("content://media/9" to "loose.mp4"), nowMs = 1L)
        r.addFolderFiles(
            treeUri = "content://tree/movies",
            files = listOf(
                "content://tree/movies/1" to "one.mp4",
                "content://tree/movies/2" to "two.mp4"
            ),
            nowMs = 2L
        )
        assertEquals(3, r.entries().size)
        assertEquals(setOf("content://tree/movies"), r.treeUris())

        r.removeTree("content://tree/movies")
        val remaining = r.entries()
        assertEquals(1, remaining.size)
        assertEquals("loose.mp4", remaining[0].displayName)
    }

    @Test
    fun `removing one entry leaves the others`() {
        val r = repo()
        r.addFiles(
            listOf(
                "content://media/1" to "a.mp4",
                "content://media/2" to "b.mp4"
            ),
            nowMs = 1L
        )
        r.remove("content://media/1")
        val remaining = r.entries()
        assertEquals(1, remaining.size)
        assertEquals("b.mp4", remaining[0].displayName)
        assertTrue(repo().titles().single().localUri == "content://media/2")
    }
}
