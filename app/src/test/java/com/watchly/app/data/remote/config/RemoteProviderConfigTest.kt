package com.watchly.app.data.remote.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteProviderConfigTest {

    @Test
    fun `default providers are loaded and valid`() {
        val providers = RemoteProviderConfig.DEFAULT_PROVIDERS
        assertTrue(providers.isNotEmpty())
        assertTrue(providers.size >= 15)

        for (provider in providers) {
            assertTrue(provider.baseUrl.startsWith("https://"))
            assertTrue(provider.name.isNotBlank())
            assertTrue(provider.priority > 0)
        }
    }

    @Test
    fun `parse valid json array into provider entries`() {
        val json = """
            [
              {
                "name": "CustomStream",
                "baseUrl": "https://customstream.test",
                "enabled": true,
                "priority": 1,
                "type": "EMBED",
                "requiresReferer": true
              },
              {
                "name": "DisabledStream",
                "baseUrl": "https://disabled.test",
                "enabled": false,
                "priority": 5,
                "type": "API",
                "requiresReferer": false
              }
            ]
        """.trimIndent()

        val parsed = RemoteProviderConfig.parseJson(json)
        assertEquals(2, parsed.size)

        val first = parsed[0]
        assertEquals("CustomStream", first.name)
        assertEquals("https://customstream.test", first.baseUrl)
        assertTrue(first.enabled)
        assertEquals(1, first.priority)
        assertEquals(ProviderType.EMBED, first.type)
        assertTrue(first.requiresReferer)

        val second = parsed[1]
        assertEquals("DisabledStream", second.name)
        assertFalse(second.enabled)
        assertEquals(ProviderType.API, second.type)
    }

    @Test
    fun `insecure http urls are rejected during parsing`() {
        val json = """
            [
              {
                "name": "InsecureStream",
                "baseUrl": "http://insecure.test",
                "enabled": true,
                "priority": 1
              }
            ]
        """.trimIndent()

        val parsed = RemoteProviderConfig.parseJson(json)
        assertTrue(parsed.isEmpty())
    }
}
