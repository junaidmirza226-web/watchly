package com.watchly.app

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.watchly.app.ui.nav.WatchlyApp
import com.watchly.app.ui.player.PipState
import com.watchly.app.ui.player.enterWatchlyPip
import com.watchly.app.ui.theme.WatchlyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
