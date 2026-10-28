// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.feature.media

import io.github.ktclat.c001apk.nativeapp.data.ClientDeviceProfile
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.*
import org.junit.Test
import javax.net.ssl.SSLPeerUnverifiedException

class PublicMediaRequestPolicyTest {
    private val profile = ClientDeviceProfile("Google", "google", "Pixel 8", "AP3A.test", "15")
    private fun policy() = PublicMediaRequestPolicy(profile)
    private fun ua(initial: String, current: String = initial) = policy().userAgent(initial.toHttpUrl(), current.toHttpUrl())
    private fun base() = anonymousMediaClientBuilder(profile).build()
    @Test(timeout = 60_000) fun exactHttps443SameOriginOnly() {
        listOf("image.coolapk.com", "avatar.coolapk.com", "static.coolapk.com").forEach { host ->
            assertEquals(profile.userAgent(), ua("https://$host/a", "https://$host/b"))
        }
        listOf("http://image.coolapk.com/a", "https://image.coolapk.com:444/a", "https://image.coolapk.com.evil.test/a",
            "https://sub.image.coolapk.com/a", "https://outside.test/a", "https://user:pass@image.coolapk.com/a").forEach {
            assertEquals(anonymousMediaUserAgent, ua(it))
        }
        assertEquals(anonymousMediaUserAgent, ua("https://image.coolapk.com/a", "https://avatar.coolapk.com/b"))
        assertEquals(anonymousMediaUserAgent, ua("https://outside.test/a", "https://image.coolapk.com/b"))
        assertEquals(anonymousMediaUserAgent, ua("https://image.coolapk.com/a", "https://outside.test/b"))
    }
    @Test(timeout = 60_000) fun invalidSystemProfileFallsBackWithoutInventedMetadata() {
        for (model in listOf("", "bad\r\nInjected: true", "模型", "x".repeat(257))) {
            val invalid = ClientDeviceProfile.fromSystemProperties("Google", "google", model, "AP3A", "15")
            assertNull(invalid)
            val url = "https://image.coolapk.com/a".toHttpUrl()
            assertEquals(anonymousMediaUserAgent, PublicMediaRequestPolicy(invalid).userAgent(url, url))
        }
    }
    @Test(timeout = 60_000) fun realTlsHopScrubsRepeatedSecretsBeforeConnectAndPreservesMediaHeaders() {
        PublicMediaTlsFixture().use { fixture ->
            fixture.tunnel(); fixture.server.enqueue(MockResponse().setBody("local bytes"))
            val request = Request.Builder().url("https://image.coolapk.com/a")
                .addHeader("User-Agent", "caller-secret").addHeader("User-Agent", "another")
                .addHeader("Cookie", "secret").addHeader("Cookie", "other").addHeader("Cookie2", "secret")
                .header("Authorization", "secret").header("Proxy-Authorization", "secret").header("Referer", "secret")
                .header("X-App-Device", "secret").header("X-Sdk-Token", "secret").header("X-Requested-With", "secret")
                .header("Accept", "image/*").header("Range", "bytes=0-10").header("If-None-Match", "local-etag")
                .header("X-Image-Variant", "thumbnail").build()
            fixture.client(base()).newCall(request).execute().use { assertEquals(200, it.code) }
            val received = fixture.nextGet()
            PublicMediaTlsFixture.assertNoSecrets(received)
            assertEquals(listOf(profile.userAgent()), received.headers.values("User-Agent"))
            assertEquals("image/*", received.getHeader("Accept")); assertEquals("bytes=0-10", received.getHeader("Range"))
            assertEquals("local-etag", received.getHeader("If-None-Match")); assertEquals("thumbnail", received.getHeader("X-Image-Variant"))
        }
    }
    @Test(timeout = 60_000) fun sameOriginRedirectAndPooledRequestsStayAnonymous() {
        PublicMediaTlsFixture().use { f ->
            f.tunnel()
            f.server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/second").setHeader("Set-Cookie", "session=secret"))
            f.server.enqueue(MockResponse().setBody("ok")); f.server.enqueue(MockResponse().setBody("again"))
            val client = f.client(base())
            repeat(2) { client.newCall(Request.Builder().url("https://image.coolapk.com/first").build()).execute().use { r -> r.body!!.string() } }
            repeat(3) { val request = f.nextGet(); assertEquals(profile.userAgent(), request.getHeader("User-Agent")); PublicMediaTlsFixture.assertNoSecrets(request) }
        }
    }
    @Test(timeout = 60_000) fun everyCrossOriginHopDropsModelUaIncludingExternalToAllowedAndAllowedToAllowed() {
        for ((from, to) in listOf("image.coolapk.com" to "outside.test", "outside.test" to "image.coolapk.com", "image.coolapk.com" to "avatar.coolapk.com")) {
            PublicMediaTlsFixture().use { f ->
                f.tunnel(); f.server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://$to/final"))
                f.tunnel(); f.server.enqueue(MockResponse().setBody("ok"))
                f.client(base()).newCall(Request.Builder().url("https://$from/start").build()).execute().use { it.body!!.string() }
                assertEquals(if (from == "outside.test") anonymousMediaUserAgent else profile.userAgent(), f.nextGet().getHeader("User-Agent"))
                val final = f.nextGet(); assertEquals(anonymousMediaUserAgent, final.getHeader("User-Agent")); PublicMediaTlsFixture.assertNoSecrets(final)
            }
        }
    }
    @Test(timeout = 60_000) fun httpsDowngradeIsNotFollowed() {
        PublicMediaTlsFixture().use { f ->
            f.tunnel(); f.server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://outside.test/never"))
            f.client(base()).newCall(Request.Builder().url("https://image.coolapk.com/start").build()).execute().use { assertEquals(302, it.code) }
            assertEquals(2, f.server.requestCount)
        }
    }
    @Test(timeout = 60_000) fun hostnameMismatchStillFailsBeforeGet() {
        PublicMediaTlsFixture().use { f ->
            f.tunnel(); f.server.enqueue(MockResponse().setBody("must not read"))
            val result = runCatching { f.client(base()).newCall(Request.Builder().url("https://mismatch.test/a").build()).execute().use { it.body!!.string() } }
            assertTrue(result.exceptionOrNull() is SSLPeerUnverifiedException)
            assertEquals(1, f.server.requestCount)
        }
    }
}
