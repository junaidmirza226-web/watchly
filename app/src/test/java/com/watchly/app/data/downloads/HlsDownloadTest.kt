/*
 * Watchly — HLS download engine tests (MovieBox-parity download core).
 *
 * A fake in-memory RangeFetcher serves master → variant → segments
 * exactly like a real HLS origin, so the whole playlist-to-file path
 * is proven on the JVM: clear streams, AES-128 encrypted streams,
 * fMP4 init segments, variant selection, and honest failure on
 * unsupported encryption.
 */
package com.watchly.app.data.downloads

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class HlsDownloadTest {

    /** Serves byte fixtures from memory; unknown URLs 404. */
    private class FakeFetcher(private val files: Map<String, ByteArray>) : RangeFetcher {
        val fetched = mutableListOf<String>()

        override fun probe(url: String): FetchProbe {
            val bytes = files[url] ?: throw FetchHttpException(404)
            return FetchProbe(totalBytes = bytes.size.toLong(), acceptsRanges = true)
        }

        override fun open(url: String, start: Long?, endInclusive: Long?): InputStream {
            fetched += url
            val bytes = files[url] ?: throw FetchHttpException(404)
            return ByteArrayInputStream(bytes)
        }
    }

    @Test
    fun clearHlsDownloadsInitPlusSegmentsInOrder() {
        val baseUrl = "https://cdn.test/movie/"
        val fixtures = mapOf(
            "$baseUrl/master.m3u8" to """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=800000
                low/variant.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=2500000
                hi/variant.m3u8
            """.trimIndent().toByteArray(),
            "$baseUrl/hi/variant.m3u8" to """
                #EXTM3U
                #EXT-X-MAP:URI="init.mp4"
                #EXT-X-MEDIA-SEQUENCE:0
                #EXTINF:4.0,
                seg0.ts
                #EXTINF:4.0,
                seg1.ts
                #EXTINF:2.0,
                seg2.ts
                #EXT-X-ENDLIST
            """.trimIndent().toByteArray(),
            "$baseUrl/hi/init.mp4" to byteArrayOf(0x11, 0x22),
            "$baseUrl/hi/seg0.ts" to byteArrayOf(0x01, 0x02, 0x03),
            "$baseUrl/hi/seg1.ts" to byteArrayOf(0x04, 0x05),
            "$baseUrl/hi/seg2.ts" to byteArrayOf(0x06)
        )
        val fetcher = FakeFetcher(fixtures)
        val downloader = SegmentedDownloader(fetcher)
        val target = File.createTempFile("watchly-hls", ".mp4").apply { deleteOnExit() }

        val outcome = runBlocking {
            downloader.download("$baseUrl/master.m3u8", target, savedSegments = null)
        }

        assertTrue("expected Completed, got $outcome", outcome is DownloadOutcome.Completed)
        assertArrayEquals(
            byteArrayOf(0x11, 0x22, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06),
            target.readBytes()
        )
        // The highest-bandwidth variant must have been chosen.
        assertTrue(fetcher.fetched.contains("$baseUrl/hi/variant.m3u8"))
        assertTrue(!fetcher.fetched.contains("$baseUrl/low/variant.m3u8"))
    }

    @Test
    fun aes128SegmentsAreDecrypted() {
        val baseUrl = "https://cdn.test/enc/"
        val key = ByteArray(16) { it.toByte() }
        val plain = "hello-MovieBox-parity!".toByteArray()
        val iv = ByteArray(16) // sequence 0 → all-zero IV
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(plain)

        val fixtures = mapOf(
            "$baseUrl/master.m3u8" to """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=1000000
                variant.m3u8
            """.trimIndent().toByteArray(),
            "$baseUrl/variant.m3u8" to """
                #EXTM3U
                #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
                #EXTINF:4.0,
                seg0.bin
                #EXT-X-ENDLIST
            """.trimIndent().toByteArray(),
            "$baseUrl/key.bin" to key,
            "$baseUrl/seg0.bin" to encrypted
        )
        val downloader = SegmentedDownloader(FakeFetcher(fixtures))
        val target = File.createTempFile("watchly-hls-enc", ".ts").apply { deleteOnExit() }

        val outcome = runBlocking {
            downloader.download("$baseUrl/master.m3u8", target, savedSegments = null)
        }

        assertTrue("expected Completed, got $outcome", outcome is DownloadOutcome.Completed)
        assertArrayEquals(plain, target.readBytes())
    }

    @Test
    fun sampleAesFailsHonestly() {
        val baseUrl = "https://cdn.test/sa/"
        val fixtures = mapOf(
            "$baseUrl/variant.m3u8" to """
                #EXTM3U
                #EXT-X-KEY:METHOD=SAMPLE-AES,URI="key.bin"
                #EXTINF:4.0,
                seg0.bin
            """.trimIndent().toByteArray(),
            "$baseUrl/key.bin" to ByteArray(16),
            "$baseUrl/seg0.bin" to ByteArray(8)
        )
        val downloader = SegmentedDownloader(FakeFetcher(fixtures))
        val target = File.createTempFile("watchly-hls-sa", ".ts").apply { deleteOnExit() }

        val outcome = runBlocking {
            downloader.download("$baseUrl/variant.m3u8", target, savedSegments = null)
        }

        assertTrue("expected Failed, got $outcome", outcome is DownloadOutcome.Failed)
        val reason = (outcome as DownloadOutcome.Failed).reason
        assertTrue("reason must name the encryption: $reason", reason.contains("encryption"))
    }

    @Test
    fun mediaSequenceFillsTheDefaultIv() {
        val playlist = HlsSupport.parseMedia(
            """
                #EXTM3U
                #EXT-X-MEDIA-SEQUENCE:7
                #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
                #EXTINF:4.0,
                a.ts
                #EXTINF:4.0,
                b.ts
            """.trimIndent(),
            "https://cdn.test/x/variant.m3u8"
        )
        assertEquals(2, playlist.segments.size)
        assertEquals("https://cdn.test/x/key.bin", playlist.keyUri)
        val iv0 = HlsSupport.ivFor(playlist, 0)
        val iv1 = HlsSupport.ivFor(playlist, 1)
        assertEquals(16, iv0.size)
        assertEquals(7.toByte(), iv0[15])
        assertEquals(8.toByte(), iv1[15])
    }

    @Test
    fun masterParsesBandwidthsAndResolvesRelativeUris() {
        val variants = HlsSupport.parseMaster(
            """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=400000
                ../low.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=3000000
                high.m3u8
            """.trimIndent(),
            "https://cdn.test/t/hi/master.m3u8"
        )
        assertEquals(2, variants.size)
        assertEquals("https://cdn.test/t/hi/high.m3u8", variants[0].uri)
        assertEquals(3_000_000L, variants[0].bandwidth)
        assertEquals("https://cdn.test/t/low.m3u8", variants[1].uri)
    }

    @Test
    fun hlsUrlsAreDetected() {
        assertTrue(HlsSupport.isHlsUrl("https://a.test/master.m3u8?token=1"))
        assertTrue(!HlsSupport.isHlsUrl("https://a.test/movie.mp4"))
    }
}
