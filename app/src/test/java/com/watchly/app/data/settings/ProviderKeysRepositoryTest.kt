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

/**
 * The cycle-4 provider key store: per-provider save/read/clear,
 * trimmed on save, blank clears — the same contract the TMDB store
 * has, for the four new providers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProviderKeysRepositoryTest {

    private lateinit var repo: ProviderKeysRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences(
            ProviderKeysRepository.PREFS_NAME, Context.MODE_PRIVATE
        ).edit().clear().commit()
        repo = ProviderKeysRepository(context)
    }

    @Test
    fun `each provider saves and reads back independently`() {
        ApiProvider.entries.forEach { provider ->
            assertFalse(repo.hasKey(provider))
            repo.setKey(provider, "key-for-${provider.name}")
            assertTrue(repo.hasKey(provider))
            assertEquals("key-for-${provider.name}", repo.getKey(provider))
        }
        // Clearing one leaves the others untouched.
        repo.clearKey(ApiProvider.OMDB)
        assertNull(repo.getKey(ApiProvider.OMDB))
        assertEquals("key-for-TRAKT", repo.getKey(ApiProvider.TRAKT))
        assertEquals(
            "key-for-OPENSUBTITLES",
            repo.getKey(ApiProvider.OPENSUBTITLES)
        )
    }

    @Test
    fun `keys are trimmed and blank clears`() {
        repo.setKey(ApiProvider.FANART, "  abc123  ")
        assertEquals("abc123", repo.getKey(ApiProvider.FANART))
        repo.setKey(ApiProvider.FANART, "   ")
        assertNull(repo.getKey(ApiProvider.FANART))
    }
}
