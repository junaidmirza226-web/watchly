package com.watchly.app.data.settings

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.watchly.app.data.model.SubtitleBackground
import com.watchly.app.data.model.SubtitleStyle
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Cycle 9 (§6.2): the subtitle appearance persists — size snapped
 * to the five spec steps, background as chosen — and a corrupt
 * saved value falls back to the defaults instead of breaking.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UiPreferencesSubtitleStyleTest {

    private lateinit var context: Context
    private lateinit var prefs: UiPreferencesRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences(
            UiPreferencesRepository.PREFS_NAME, Context.MODE_PRIVATE
        ).edit().clear().commit()
        prefs = UiPreferencesRepository(context)
    }

    @Test
    fun `defaults are the middle size step and semi background`() {
        val style = prefs.subtitleStyle()
        assertEquals(SubtitleStyle.DEFAULT_TEXT_SIZE_SP, style.textSizeSp)
        assertEquals(SubtitleBackground.SEMI, style.background)
    }

    @Test
    fun `size and background persist across repository instances`() {
        prefs.setSubtitleTextSizeSp(24f)
        prefs.setSubtitleBackground(SubtitleBackground.SOLID)
        val fresh = UiPreferencesRepository(context).subtitleStyle()
        assertEquals(24f, fresh.textSizeSp)
        assertEquals(SubtitleBackground.SOLID, fresh.background)
    }

    @Test
    fun `size snaps to the nearest offered step`() {
        prefs.setSubtitleTextSizeSp(22f)
        assertEquals(20f, prefs.subtitleStyle().textSizeSp)
        prefs.setSubtitleTextSizeSp(27f)
        assertEquals(28f, prefs.subtitleStyle().textSizeSp)
    }

    @Test
    fun `corrupt saved values fall back to defaults`() {
        context.getSharedPreferences(
            UiPreferencesRepository.PREFS_NAME, Context.MODE_PRIVATE
        ).edit()
            .putFloat("subtitle_text_size_sp", 99f)
            .putString("subtitle_background", "SPARKLY")
            .commit()
        val style = prefs.subtitleStyle()
        assertEquals(SubtitleStyle.DEFAULT_TEXT_SIZE_SP, style.textSizeSp)
        assertEquals(SubtitleBackground.SEMI, style.background)
    }
}
