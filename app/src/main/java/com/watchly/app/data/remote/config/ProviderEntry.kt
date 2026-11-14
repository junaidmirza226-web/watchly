package com.watchly.app.data.remote.config

import org.json.JSONObject

enum class ProviderType {
    EMBED,
    API,
    SCRAPER;

    companion object {
        fun fromString(value: String?): ProviderType {
            return when (value?.uppercase()?.trim()) {
                "API" -> API
                "SCRAPER" -> SCRAPER
                else -> EMBED
            }
        }
    }
}

/**
 * Configuration entry for a streaming provider.
 *
 * @param name            Display/identifier name of the provider (e.g. "VidSrc.to").
 * @param baseUrl         Base URL for the provider service (HTTPS).
 * @param enabled         Whether this provider is currently active.
 * @param priority        Priority ranking (lower number = tried first).
 * @param type            Type of provider: EMBED, API, or SCRAPER.
 * @param requiresReferer Whether requests need a matching Referer header.
 * @param userAgent       Custom User-Agent string, or null to use rotating default.
 */
data class ProviderEntry(
    val name: String,
    val baseUrl: String,
    val enabled: Boolean = true,
    val priority: Int = 1,
    val type: ProviderType = ProviderType.EMBED,
    val requiresReferer: Boolean = false,
    val userAgent: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("baseUrl", baseUrl)
        put("enabled", enabled)
        put("priority", priority)
        put("type", type.name)
        put("requiresReferer", requiresReferer)
        if (userAgent != null) put("userAgent", userAgent)
    }

    companion object {
        fun fromJson(json: JSONObject): ProviderEntry {
            return ProviderEntry(
                name = json.optString("name", "Unknown"),
                baseUrl = json.optString("baseUrl", ""),
                enabled = json.optBoolean("enabled", true),
                priority = json.optInt("priority", 99),
                type = ProviderType.fromString(json.optString("type")),
                requiresReferer = json.optBoolean("requiresReferer", false),
                userAgent = json.optString("userAgent").takeIf { it.isNotBlank() }
            )
        }
    }
}
