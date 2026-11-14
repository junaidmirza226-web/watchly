package com.watchly.app.data.settings

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Round-trip tests for the on-device TMDB key store. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TmdbKeyRepositoryTest {

    private lateinit var repo: TmdbKeyRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences(TmdbKeyRepository.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        repo = TmdbKeyRepository(context)
    }

    @Test
    fun `no key by default`() {
        assertFalse(repo.hasKey())
        assertNull(repo.getKey())
    }

    @Test
    fun `set get and clear round trip`() {
        repo.setKey("abc123")
        assertTrue(repo.hasKey())
        assertEquals("abc123", repo.getKey())

        repo.clearKey()
        assertFalse(repo.hasKey())
        assertNull(repo.getKey())
    }

    @Test
    fun `keys are trimmed and blank clears`() {
        repo.setKey("  spaced-key  ")
        assertEquals("spaced-key", repo.getKey())

        repo.setKey("   ")
        assertFalse(repo.hasKey())
    }
}
