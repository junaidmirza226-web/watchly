package com.watchly.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** Saved keys render masked; only the last 4 characters ever show. */
class KeyMaskTest {

    @Test
    fun keysMaskToDotsPlusLastFour() {
        assertEquals("—", maskApiKey(null))
        assertEquals("—", maskApiKey("   "))
        assertEquals("••••", maskApiKey("abcd"))
        assertEquals("••••••••a0e0", maskApiKey("3b6bdb61d7ea586e9b15d15aad74a0e0"))
        assertEquals("••••••••1234", maskApiKey("abcdefgh1234"))
    }
}
