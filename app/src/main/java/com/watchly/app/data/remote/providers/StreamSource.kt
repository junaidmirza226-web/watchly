package com.watchly.app.data.remote.providers

/**
 * A resolved playable stream source from a streaming provider.
 *
 * @param providerName The name of the provider providing this stream (e.g. "VidSrc.to").
 * @param url          Direct media stream URL (.m3u8, .mp4, etc.).
 * @param quality      Quality label (e.g. "1080p", "720p", "480p", "Auto").
 * @param isHls        Whether this source is an HTTP Live Streaming (.m3u8) playlist.
 * @param headers      Required HTTP headers for playback authentication (e.g. Referer, User-Agent).
 * @param subtitles    Associated subtitle tracks discovered with the stream.
 */
data class StreamSource(
    val providerName: String,
    val url: String,
    val quality: String,
    val isHls: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<StreamSubtitle> = emptyList(),
    /**
     * Audio language of this stream when it is a specific dub ("Hindi",
     * "Tamil", "Dual Audio"…), from the provider's own labels/URLs.
     * Null for the default/original-audio stream. Dubbed variants are
     * surfaced as separate, clearly-labelled sources in the player.
     */
    val language: String? = null
)

/**
 * A subtitle track associated with a stream source.
 */
data class StreamSubtitle(
    val language: String,
    val url: String,
    val label: String
)
