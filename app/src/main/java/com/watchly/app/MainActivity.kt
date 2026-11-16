package com.watchly.app

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.watchly.app.data.settings.TmdbKeyRepository
import com.watchly.app.ui.nav.WatchlyApp
import com.watchly.app.ui.player.PipState
import com.watchly.app.ui.player.enterWatchlyPip
import com.watchly.app.ui.theme.WatchlyTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Master keys are build-secret injected (BuildConfig) and resolved on
        // demand by TmdbKeyRepository / ProviderKeysRepository — nothing is
        // seeded into preferences here (a seeded prefs key used to outrank
        // the cloud-updated one and freeze the app onto a stale key).
        runCatching {
            com.watchly.app.data.remote.TmdbApi.fallbackKeyCandidates =
                TmdbKeyRepository.MASTER_TMDB_KEYS
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
