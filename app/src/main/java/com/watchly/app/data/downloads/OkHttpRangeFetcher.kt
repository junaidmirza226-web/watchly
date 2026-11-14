/*
 * Watchly — OkHttp implementation of [RangeFetcher] (cycle 5).
 *
 * The segmented engine's network layer. Probing prefers a HEAD
 * request; servers that refuse HEAD get a one-byte ranged GET
 * instead (the Content-Range header of a 206 carries the total).
 */
package com.watchly.app.data.downloads

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

class OkHttpRangeFetcher(
    private val client: OkHttpClient = defaultClient()
) : RangeFetcher {

    override fun probe(url: String): FetchProbe {
        val head = Request.Builder().url(url).head().build()
        try {
            client.newCall(head).execute().use { response ->
                if (response.isSuccessful) {
                    return FetchProbe(
                        totalBytes = response.header("Content-Length")
                            ?.toLongOrNull()?.takeIf { it >= 0 },
                        acceptsRanges = response.header("Accept-Ranges")
                            ?.contains("bytes", ignoreCase = true) == true
                    )
                }
                if (response.code != 405 && response.code != 501) {
                    throw FetchHttpException(response.code)
                }
                // HEAD refused — fall through to the ranged-GET probe.
            }
        } catch (e: FetchHttpException) {
            throw e
        } catch (e: IOException) {
            // A failed HEAD says nothing about the file; the ranged
            // GET probe below is the more reliable witness anyway.
        }
        val get = Request.Builder().url(url)
            .header("Range", "bytes=0-0")
            .get()
            .build()
        client.newCall(get).execute().use { response ->
            return when (response.code) {
                206 -> FetchProbe(
                    totalBytes = parseContentRangeTotal(
                        response.header("Content-Range")
                    ),
                    acceptsRanges = true
                )
                200 -> FetchProbe(
                    totalBytes = response.header("Content-Length")
                        ?.toLongOrNull()?.takeIf { it >= 0 },
                    acceptsRanges = false
                )
                else -> throw FetchHttpException(response.code)
            }
        }
    }

    override fun open(url: String, start: Long?, endInclusive: Long?): InputStream {
        val builder = Request.Builder().url(url).get()
        if (start != null) {
            val range = if (endInclusive != null) {
                "bytes=$start-$endInclusive"
            } else {
                "bytes=$start-"
            }
            builder.header("Range", range)
        }
        val response = client.newCall(builder.build()).execute()
        if (start != null && response.code == 200) {
            response.close()
            throw RangeNotHonouredException()
        }
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw FetchHttpException(code)
        }
        val body = response.body ?: run {
            response.close()
            throw IOException("The source sent an empty response.")
        }
        // The caller owns (and closes) the stream; closing it closes
        // the response body with it.
        return body.byteStream()
    }

    companion object {
        /** "bytes 0-0/12345" → 12345; null when absent or "*". */
        internal fun parseContentRangeTotal(header: String?): Long? {
            if (header == null) return null
            val slash = header.lastIndexOf('/')
            if (slash < 0) return null
            return header.substring(slash + 1).trim().toLongOrNull()
        }

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
    }
}
