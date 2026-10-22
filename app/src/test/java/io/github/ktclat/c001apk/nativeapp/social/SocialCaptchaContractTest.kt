// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.data.*
import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.*
import org.junit.Assert.*

/** Real account boundary + HTTP transport, but only synthetic credentials and loopback fixtures. */
class SocialCaptchaContractTest {
    private lateinit var server: MockWebServer
    private lateinit var accounts: AccountStore
    private lateinit var repository: SocialRepository
    private val official = "https://api.coolapk.com/v6/feed/like".toHttpUrl()
    private fun loopbackScope(real: RequestSession): RequestSession = real.copy(
        cookieFor = { real.cookieFor(official) },
        acceptCookies = { _, headers -> real.acceptCookies(official, headers) },
        acceptChallengeCookies = { _, headers -> real.acceptChallengeCookies?.invoke(official, headers)?.let { loopbackScope(it) } },
    )
    @Before fun setup() {
        server = MockWebServer(); server.start()
        accounts = AccountStore(object : CredentialPersistence {
            private var value: ByteArray? = null
            override fun read() = value?.copyOf()
            override fun write(bytes: ByteArray) { value = bytes.copyOf() }
            override fun reset() { value = null }
        })
        accounts.saveAuthenticated("1", "synthetic", "synthetic-device", listOf(Cookie.Builder()
            .name("token").value("synthetic-only").domain("coolapk.com").path("/").secure()
            .expiresAt(System.currentTimeMillis() + 3_600_000).build()), accounts.snapshot())
        repository = SocialRepository(accounts) { captured -> CoolapkHttpTransport(
            endpoints = ApiEndpoints(server.url("/"), server.url("/")),
            requestSession = { loopbackScope(accounts.requestSessionFor(captured)) }) }
    }
    @After fun teardown() { server.shutdown() }
    @Test fun exactNeteaseRejectionCanAdoptItsOwnSessionCookieButDoesNotAutoReplay() = runBlocking {
        server.enqueue(MockResponse().addHeader("Set-Cookie", "SESSID=challenge-session; Domain=coolapk.com; Path=/; Secure")
            .setBody("""{"messageStatus":"err_request_captcha_v2","messageExtra":{"captchaId":"414e5c9b866a03db03f860a1a9101672","field":"_v2_post_token"}}"""))
        val rejected = repository.like("42", reply = false, targetLiked = true) as WriteOutcome.Rejected
        val pending = requireNotNull(rejected.continuation)
        assertTrue(pending.acceptHumanProof("NEC:414e5c9b:synthetic-human-proof"))
        assertEquals(1, server.requestCount)
        server.enqueue(MockResponse().setBody("""{"data":{"likenum":1}}"""))
        assertTrue(pending.retryOnce() is WriteOutcome.Confirmed)
        server.takeRequest(); val replay = server.takeRequest()
        assertTrue(replay.getHeader("Cookie").orEmpty().contains("SESSID=challenge-session"))
        assertTrue(replay.body.readUtf8().contains("_v2_post_token="))
        assertTrue(pending.retryOnce() is WriteOutcome.Rejected); assertEquals(2, server.requestCount)
    }
    @Test fun graphicImageAndValidationCanAdvanceOnlyTheirExactResponseSession() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"messageStatus":"err_request_captcha"}"""))
        val pending = requireNotNull((repository.like("42", reply = false, targetLiked = true) as WriteOutcome.Rejected).continuation)
        server.enqueue(MockResponse().addHeader("Content-Type", "image/png")
            .addHeader("Set-Cookie", "SESSID=image-session; Domain=coolapk.com; Path=/; Secure").setBody("synthetic-image-bytes"))
        assertTrue(pending.image() is ApiResult.Success)
        server.enqueue(MockResponse().addHeader("Set-Cookie", "SESSID=validated-session; Domain=coolapk.com; Path=/; Secure")
            .setBody("""{"data":"验证通过"}"""))
        assertTrue(pending.verifyGraphic("1234")); assertEquals(3, server.requestCount)
        server.enqueue(MockResponse().setBody("""{"data":{"likenum":1}}"""))
        assertTrue(pending.retryOnce() is WriteOutcome.Confirmed)
        server.takeRequest(); server.takeRequest()
        val validation = server.takeRequest(); assertEquals("/v6/account/requestValidate", validation.path)
        assertTrue(validation.body.readUtf8().contains("name=\"code\""))
        val replay = server.takeRequest()
        assertTrue(replay.getHeader("Cookie").orEmpty().contains("SESSID=validated-session"))
        assertFalse(replay.getHeader("Cookie").orEmpty().contains("NEC:")); assertEquals(4, server.requestCount)
    }
    @Test fun unrelatedSessionRotationCannotBorrowVerifiedProof() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"messageStatus":"err_request_captcha_v2","messageExtra":{"captchaId":"414e5c9b866a03db03f860a1a9101672","field":"_v2_post_token"}}"""))
        val pending = requireNotNull((repository.like("42", reply = false, targetLiked = true) as WriteOutcome.Rejected).continuation)
        assertTrue(pending.acceptHumanProof("NEC:414e5c9b:synthetic-human-proof"))
        val snapshot = accounts.snapshot()
        accounts.requestSessionFor(snapshot).acceptCookies(official, listOf("SESSID=unrelated-session; Domain=coolapk.com; Path=/; Secure"))
        assertTrue(pending.retryOnce() is WriteOutcome.Rejected)
        assertEquals(1, server.requestCount)
    }

    @Test fun replyChallengeKeepsUnlikeIntentAndCapturedGeneration() = runBlocking {
        val generation = accounts.state.value.generation
        server.enqueue(MockResponse().setBody("""{"messageStatus":"err_request_captcha_v2","messageExtra":{"captchaId":"414e5c9b866a03db03f860a1a9101672","field":"_v2_post_token"}}"""))
        val pending = requireNotNull((repository.like("42", reply = true, targetLiked = false,
            expectedGeneration = generation) as WriteOutcome.Rejected).continuation)
        assertTrue(pending.acceptHumanProof("NEC:414e5c9b:synthetic-human-proof"))
        assertEquals(1, server.requestCount)
        server.enqueue(MockResponse().setBody("""{"data":{"likenum":0}}"""))
        assertTrue(pending.retryOnce() is WriteOutcome.Confirmed)
        val original = server.takeRequest(); val replay = server.takeRequest()
        assertEquals("POST", original.method); assertEquals("POST", replay.method)
        assertEquals("/v6/feed/unLikeReply?id=42&detail=0", original.path)
        assertEquals(original.path, replay.path)
        assertTrue(replay.body.readUtf8().contains("_v2_post_token="))
        assertEquals(generation, accounts.state.value.generation)
        assertTrue(pending.retryOnce() is WriteOutcome.Rejected); assertEquals(2, server.requestCount)
    }
    @Test fun replyChallengeCannotReplayAfterSameAccountGenerationChanges() = runBlocking {
        val generation = accounts.state.value.generation
        server.enqueue(MockResponse().setBody("""{"messageStatus":"err_request_captcha_v2","messageExtra":{"captchaId":"414e5c9b866a03db03f860a1a9101672","field":"_v2_post_token"}}"""))
        val pending = requireNotNull((repository.like("42", reply = true, targetLiked = true,
            expectedGeneration = generation) as WriteOutcome.Rejected).continuation)
        assertTrue(pending.acceptHumanProof("NEC:414e5c9b:synthetic-human-proof"))
        accounts.useGuest(); accounts.switchTo("1")
        assertTrue(pending.retryOnce() is WriteOutcome.Rejected)
        assertEquals(1, server.requestCount)
    }
}
