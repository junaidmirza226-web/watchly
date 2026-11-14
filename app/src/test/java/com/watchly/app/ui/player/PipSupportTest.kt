package com.watchly.app.ui.player

import android.app.Application
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Cycle 9 (§5.3): PiP decisions — the aspect-ratio clamp Android
 * demands, the auto-enter truth table, and the params/entry path
 * on a real (shadow) activity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PipSupportTest {

    @After
    fun tearDown() {
        // PipState is a process-wide bridge; never leak it.
        PipState.playerVisible = false
        PipState.isPlaying = { false }
        PipState.togglePlayPause = null
        PipState.setInPictureInPicture(false)
    }

    @Test
    fun `aspect ratio passes normal video through reduced`() {
        assertEquals(Rational(16, 9), PipDecisions.aspectRatioFor(1920, 1080))
        assertEquals(Rational(4, 3), PipDecisions.aspectRatioFor(1440, 1080))
    }

    @Test
    fun `aspect ratio clamps outside the range Android accepts`() {
        // Ultra-wide (3.56) clamps to the 2.39 ceiling.
        assertEquals(Rational(239, 100), PipDecisions.aspectRatioFor(3840, 1080))
        // Tall phone video (0.25) clamps to the 0.418 floor.
        assertEquals(Rational(100, 239), PipDecisions.aspectRatioFor(480, 1920))
    }

    @Test
    fun `unknown size falls back to 16-9`() {
        assertEquals(Rational(16, 9), PipDecisions.aspectRatioFor(0, 0))
        assertEquals(Rational(16, 9), PipDecisions.aspectRatioFor(-1, 100))
    }

    @Test
    fun `auto enter needs the player visible and playing`() {
        PipState.playerVisible = false
        PipState.isPlaying = { true }
        assertFalse(PipState.shouldAutoEnter())

        PipState.playerVisible = true
        PipState.isPlaying = { false }
        assertFalse(PipState.shouldAutoEnter())

        PipState.isPlaying = { true }
        assertTrue(PipState.shouldAutoEnter())
    }

    @Test
    fun `params carry one play or pause action and the 16-9 ratio`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val playing = buildPipParams(context, isPlaying = true)
        assertEquals(Rational(16, 9), playing.aspectRatio)
        assertEquals(1, playing.actions.size)
        assertEquals("Pause", playing.actions[0].title.toString())

        val paused = buildPipParams(context, isPlaying = false)
        assertEquals("Play", paused.actions[0].title.toString())
    }

    @Test
    fun `activity enters pip through the helper`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java)
            .create().get()
        assertTrue(activity.enterWatchlyPip(isPlaying = true))
        assertTrue(activity.isInPictureInPictureMode)
    }
}
