/*
 * Watchly — the source-death predicates the embed player fails over on.
 * "Playback never fails" starts with correctly telling a dead source
 * apart from navigation noise.
 */
package com.watchly.app.ui.player

import android.webkit.WebViewClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebLoadGuardTest {

    private val active = "https://vidsrc.example/embed/tt123"

    @Test
    fun mainFrameFailuresForTheActiveUrlAreFatal() {
        assertTrue(
            isFatalLoadError(WebViewClient.ERROR_HOST_LOOKUP, true, active, active)
        )
        assertTrue(
            isFatalLoadError(WebViewClient.ERROR_CONNECT, true, active, active)
        )
        assertTrue(
            isFatalLoadError(WebViewClient.ERROR_TIMEOUT, true, active, active)
        )
    }

    @Test
    fun cancelledLoadsAndSubresourcesNeverFailOver() {
        // Cancelled/redirect-race loads surface as ERROR_UNKNOWN.
        assertFalse(isFatalLoadError(WebViewClient.ERROR_UNKNOWN, true, active, active))
        // intent:// and friends are blocked stunts, not dead sources.
        assertFalse(
            isFatalLoadError(WebViewClient.ERROR_UNSUPPORTED_SCHEME, true, active, active)
        )
        // A dead .js/.css subresource says nothing about the player.
        assertFalse(
            isFatalLoadError(WebViewClient.ERROR_HOST_LOOKUP, false, "$active/a.js", active)
        )
    }

    @Test
    fun failuresForAnAbandonedUrlAreIgnored() {
        // The previous load, cancelled by a server switch.
        assertFalse(
            isFatalLoadError(
                errorCode = WebViewClient.ERROR_CONNECT,
                isForMainFrame = true,
                failingUrl = "https://old.example/embed",
                activeUrl = active
            )
        )
    }

    @Test
    fun httpErrorsFailOverOnlyForDeadMainFrames() {
        assertTrue(isFatalHttpError(404, true))
        assertTrue(isFatalHttpError(503, true))
        assertFalse(isFatalHttpError(200, true))
        assertFalse(isFatalHttpError(404, false))
    }
}
