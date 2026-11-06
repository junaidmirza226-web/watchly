package com.watchly.app.data.search

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric test for the persisted recent-searches store.
 * Terms only — nothing leaves the device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SearchHistoryRepositoryTest {

    private lateinit var repo: SearchHistoryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences(
            SearchHistoryRepository.PREFS_NAME,
            android.content.Context.MODE_PRIVATE
        ).edit().clear().commit()
        repo = SearchHistoryRepository(context)
    }

    @Test
    fun `saves most recent first de-duplicated and capped`() {
        repo.save("breaking bad")
        repo.save("naruto")
        repo.save("breaking bad")

        assertEquals(listOf("breaking bad", "naruto"), repo.load())
    }

    @Test
    fun `blank terms are ignored and clear empties the list`() {
        repo.save("  ")
        assertTrue(repo.load().isEmpty())

        repo.save("dune")
        repo.clear()
        assertTrue(repo.load().isEmpty())
    }

    @Test
    fun `keeps at most eight recent searches`() {
        for (i in 1..10) repo.save("term $i")
        val loaded = repo.load()
        assertEquals(8, loaded.size)
        assertEquals("term 10", loaded.first())
        assertEquals("term 3", loaded.last())
    }
}
