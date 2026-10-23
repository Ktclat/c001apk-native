// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.feature.media

import okhttp3.Dns
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

class ArticleImagePolicyTest {
    @Test fun exactAnonymousHttpsUrlOnly() {
        assertNotNull(ArticleImagePolicy.safeUrl("https://image.coolapk.com/a.jpg"))
        assertNotNull(ArticleImagePolicy.safeUrl("https://image.coolapk.com:443/a.jpg?q=1"))
        listOf("http://image.coolapk.com/a", "https://image.coolapk.com:444/a", "https://image.coolapk.com.evil/a",
            "https://user@image.coolapk.com/a", "https://@image.coolapk.com/a", "https://image.coolapk.com/a#",
            "https://127.0.0.1/a", " https://image.coolapk.com/a", "https://image.coolapk.com/\\a").forEach {
            assertNull(it, ArticleImagePolicy.safeUrl(it))
        }
        val prefix = "https://image.coolapk.com/"
        assertNotNull(ArticleImagePolicy.safeUrl(prefix + "a".repeat(4096 - prefix.length)))
        assertNull(ArticleImagePolicy.safeUrl(prefix + "a".repeat(4097 - prefix.length)))
        assertNull(ArticleImagePolicy.safeUrl(prefix + "中".repeat(1000))) // Normalization must remain bounded.
    }

    @Test fun resolverValidatesTheExactAddressesReturnedToConnection() {
        val public = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))
        val addresses = listOf(public)
        var calls = 0
        val dns = ArticlePublicDns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                assertEquals("image.coolapk.com", hostname)
                calls++
                return addresses
            }
        })
        assertSame(addresses, dns.lookup("image.coolapk.com"))
        assertEquals(1, calls)
        rejected(ArticleImageFailure.BLOCKED_URL) { dns.lookup("elsewhere.example") }
        assertEquals(1, calls)
        rejected(ArticleImageFailure.BLOCKED_ADDRESS) {
            ArticlePublicDns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    listOf(public, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
            }).lookup("image.coolapk.com")
        }
    }

    @Test fun privateReservedMappedAndTransitionAddressesAreRejected() {
        listOf("0.0.0.0", "10.0.0.1", "127.0.0.1", "169.254.1.2", "172.31.255.1", "192.168.1.1",
            "100.64.0.1", "192.0.2.1", "198.18.0.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "255.255.255.255",
            "::", "::1", "fe80::1", "fc00::1", "ff02::1", "::ffff:127.0.0.1", "2001:db8::1", "2002:7f00:1::", "3fff::1").forEach {
            assertFalse(it, ArticleImagePolicy.isPublicAddress(InetAddress.getByName(it))) // Numeric literals only, no DNS.
        }
        assertTrue(ArticleImagePolicy.isPublicAddress(InetAddress.getByName("2606:4700:4700::1111")))
        assertTrue(ArticleImagePolicy.isPublicAddress(InetAddress.getByName("8.8.8.8")))
    }

    @Test fun currentSystemProxyIsConsultedAndNeverBypassed() {
        var current: ProxySelector? = selector(listOf(Proxy.NO_PROXY))
        val guard = ArticleProxySelector { current }
        val uri = URI("https://image.coolapk.com/a.jpg")
        assertEquals(listOf(Proxy.NO_PROXY), guard.select(uri))
        current = selector(listOf(Proxy.NO_PROXY, Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example", 8080))))
        rejected(ArticleImageFailure.UNSUPPORTED_PROXY) { guard.select(uri) }
        current = selector(emptyList())
        rejected(ArticleImageFailure.UNSUPPORTED_PROXY) { guard.select(uri) }
        current = object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> = throw IllegalStateException("selector unavailable")
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
        }
        rejected(ArticleImageFailure.UNSUPPORTED_PROXY) { guard.select(uri) }
        current = object : ProxySelector() {
            override fun select(uri: URI): List<Proxy>? = null // Java platform contract may return null.
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
        }
        rejected(ArticleImageFailure.UNSUPPORTED_PROXY) { guard.select(uri) }
        rejected(ArticleImageFailure.UNSUPPORTED_PROXY) {
            ArticleProxySelector { throw IllegalStateException("current selector unavailable") }.select(uri)
        }
        current = null
        assertEquals(listOf(Proxy.NO_PROXY), guard.select(uri))
    }

    @Test fun magicMimeAndAnimationMustAgree() {
        val jpeg = byteArrayOf(-1, -40, -1, -39)
        assertEquals("image/jpeg", ArticleImagePolicy.validateFormat(jpeg, "image/jpeg; charset=binary"))
        assertEquals("image/png", ArticleImagePolicy.validateFormat(png(), "image/png"))
        assertEquals("image/webp", ArticleImagePolicy.validateFormat(webp("VP8 " to byteArrayOf(1, 2, 3, 4)), "image/webp"))
        listOf(null, "image/png", "application/octet-stream").forEach { mime ->
            rejected(ArticleImageFailure.UNSUPPORTED_FORMAT) { ArticleImagePolicy.validateFormat(jpeg, mime) }
        }
        listOf("GIF89a".toByteArray(), "....ftypavif".toByteArray()).forEach {
            rejected(ArticleImageFailure.UNSUPPORTED_FORMAT) { ArticleImagePolicy.validateFormat(it, "image/jpeg") }
        }
        listOf("acTL", "fcTL", "fdAT").forEach {
            rejected(ArticleImageFailure.UNSUPPORTED_FORMAT) { ArticleImagePolicy.validateFormat(png(it), "image/png") }
        }
        val animated = byteArrayOf(2) + ByteArray(9)
        listOf(webp("VP8X" to animated, "VP8 " to byteArrayOf(1, 2)),
            webp("ANIM" to ByteArray(6), "VP8 " to byteArrayOf(1, 2)),
            webp("ANMF" to ByteArray(16), "VP8 " to byteArrayOf(1, 2))).forEach {
            rejected(ArticleImageFailure.UNSUPPORTED_FORMAT) { ArticleImagePolicy.validateFormat(it, "image/webp") }
        }
        rejected(ArticleImageFailure.UNSUPPORTED_FORMAT) { ArticleImagePolicy.validateFormat(png().copyOf(20), "image/png") }
        rejected(ArticleImageFailure.UNSUPPORTED_FORMAT) { ArticleImagePolicy.validateFormat(webp("VP8 " to byteArrayOf(1)) + 0, "image/webp") }
    }

    @Test fun sampledAllocationBoundsAreComputedWithCeilingAndLongArithmetic() {
        assertEquals(1, ArticleImagePolicy.sampleSize(2000, 2000))
        assertEquals(2, ArticleImagePolicy.sampleSize(2048, 2048)) // > 4,000,000 pixels.
        assertEquals(4, ArticleImagePolicy.sampleSize(4097, 1)) // ceil(4097 / 2) is 2049.
        assertEquals(16, ArticleImagePolicy.sampleSize(32768, 1))
        assertEquals(4, ArticleImagePolicy.sampleSize(8000, 5000))
        listOf(32769 to 1, 8001 to 5000, Int.MAX_VALUE to Int.MAX_VALUE, 0 to 1, -1 to 1).forEach { (w, h) ->
            rejected(ArticleImageFailure.SOURCE_LIMIT) { ArticleImagePolicy.sampleSize(w, h) }
        }
    }

    @Test fun geometryAndAnimationSensitivePreviewStaySourceDerived() {
        assertEquals(0.5f, articleImageHeightRatio("https://image.coolapk.com/p@800x400.jpg"), 0f)
        assertEquals(22f / 9f, articleImageHeightRatio("https://image.coolapk.com/p@1x100.jpg"), 0f)
        assertEquals(1f, articleImageHeightRatio(null), 0f)
        assertEquals("https://image.coolapk.com/p.jpg.s.jpg", articlePreviewUrl("https://image.coolapk.com/p.jpg"))
        listOf("png", "webp", "gif", "avif").forEach { ext ->
            val url = "https://image.coolapk.com/p.$ext"
            assertEquals(url, articlePreviewUrl(url))
        }
        assertNull(articlePreviewUrl("https://other.example/a.jpg"))
    }

    @Test fun previewSuffixAtUrlLimitFallsBackOnlyToAlreadySafeOriginal() {
        val prefix = "https://image.coolapk.com/"
        val suffix = ".jpg"
        val exact = prefix + "a".repeat(ArticleImagePolicy.MAX_URL - prefix.length - suffix.length) + suffix
        assertEquals(ArticleImagePolicy.MAX_URL, exact.length)
        assertEquals(exact, articlePreviewUrl(exact))
        assertNotNull(ArticleImagePolicy.safeUrl(articlePreviewUrl(exact)!!))
        assertNull(articlePreviewUrl(exact + "a"))
        val fits = prefix + "a".repeat(ArticleImagePolicy.MAX_URL - prefix.length - suffix.length - 6) + suffix
        assertEquals(fits + ".s.jpg", articlePreviewUrl(fits))
    }

    @Test fun containerScanObservesCancellationBetweenChunks() {
        var checks = 0
        val cancelled = kotlinx.coroutines.CancellationException("fixture cancellation")
        val failure = assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            ArticleImagePolicy.validateFormat(png(), "image/png") {
                checks++
                if (checks == 3) throw cancelled
            }
        }
        assertSame(cancelled, failure)
        assertEquals(3, checks)
    }

    private fun rejected(reason: ArticleImageFailure, action: () -> Any?) {
        val thrown = assertThrows(ArticleImageException::class.java) { action() }
        assertEquals(reason, thrown.reason)
    }
    private fun selector(result: List<Proxy>) = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> = result
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
    }
    private fun png(animationTag: String? = null): ByteArray {
        fun chunk(tag: String, data: ByteArray): ByteArray = integer(data.size, false) + tag.toByteArray() + data + ByteArray(4)
        return byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) + chunk("IHDR", ByteArray(13)) +
            (animationTag?.let { chunk(it, ByteArray(8)) } ?: ByteArray(0)) + chunk("IEND", ByteArray(0))
    }
    private fun webp(vararg chunks: Pair<String, ByteArray>): ByteArray {
        val data = chunks.fold(ByteArray(0)) { result, (tag, bytes) ->
            result + tag.toByteArray() + integer(bytes.size, true) + bytes + ByteArray(bytes.size % 2)
        }
        return "RIFF".toByteArray() + integer(data.size + 4, true) + "WEBP".toByteArray() + data
    }
    private fun integer(value: Int, little: Boolean): ByteArray = ByteArray(4) { i -> (value ushr (8 * if (little) i else 3 - i)).toByte() }
}
