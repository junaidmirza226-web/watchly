package com.watchly.app

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.watchly.app.data.settings.ApiProvider
import com.watchly.app.data.settings.ProviderKeysRepository
import com.watchly.app.data.settings.TmdbKeyRepository
import com.watchly.app.ui.nav.WatchlyApp
import com.watchly.app.ui.player.PipState
import com.watchly.app.ui.player.enterWatchlyPip
import com.watchly.app.ui.theme.WatchlyTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Seed master TMDB, OMDb, Trakt, and OpenSubtitles keys on first launch
        // so all blockbusters, Bollywood/Tollywood, ratings, and subtitles work out-of-the-box.
        runCatching {
            val tmdbRepo = TmdbKeyRepository(applicationContext)
            val currentKey = tmdbRepo.getKey()
            if (currentKey.isNullOrBlank() || currentKey.length < 10) {
                tmdbRepo.setKey(TmdbKeyRepository.DEFAULT_TMDB_API_KEY)
            }

            val providerRepo = ProviderKeysRepository(applicationContext)
            if (!providerRepo.hasKey(ApiProvider.OMDB)) {
                providerRepo.setKey(ApiProvider.OMDB, "3fe6e3fc")
            }
            if (!providerRepo.hasKey(ApiProvider.TRAKT)) {
                providerRepo.setKey(ApiProvider.TRAKT, "2M3JfayN76tNAq_ppJw6ZXROTDmhsq9THc0pnuqfB6o")
            }
            if (!providerRepo.hasKey(ApiProvider.OPENSUBTITLES)) {
                providerRepo.setKey(ApiProvider.OPENSUBTITLES, "nU9U21UXcHfxmOSCNUHZnFNb80zq0aa9")
            }
            if (!providerRepo.hasKey(ApiProvider.FANART)) {
                providerRepo.setKey(ApiProvider.FANART, "42ab51ecdff1d6a6100d90fb4032cc9d")
            }
        }

        enableEdgeToEdge()

        // Check for updates automatically in the background
        lifecycleScope.launch {
            runCatching {
                com.watchly.app.data.remote.AppUpdateChecker.checkForUpdates(applicationContext)
            }
        }

        setContent {
            WatchlyTheme {
                WatchlyApp()
            }
        }
    }

    /**
     * Cycle 9 (§5.3): pressing Home while a video plays shrinks it
     * into Picture-in-Picture instead of leaving playback behind.
     * The decision lives in [PipState] — no player on screen (or a
     * paused one) means Home behaves exactly as before.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (PipState.shouldAutoEnter()) {
            enterWatchlyPip(PipState.isPlaying())
        }
    }

    /** Publishes the PiP flag the player uses to hide its controls. */
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PipState.setInPictureInPicture(isInPictureInPictureMode)
    }
}
