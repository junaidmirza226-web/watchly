package com.watchly.app.data.settings

import com.watchly.app.data.remote.config.RemoteProviderConfig

/**
 * Test-only reset of the embedded master keys.
 *
 * Production ships built-in keys so the app works out of the box, but
 * unit tests that assert "no key saved yet" behaviour must opt out
 * explicitly so the embedded defaults never leak into their
 * expectations. Call this from each test class's @Before.
 */
object TestKeys {
    fun clearEmbeddedDefaults() {
        RemoteProviderConfig.tmdbApiKey = null
        ProviderKeysRepository.DEFAULT_KEYS.clear()
    }
}
