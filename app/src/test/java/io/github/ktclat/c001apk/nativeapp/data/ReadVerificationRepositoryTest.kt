// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*

/** Synthetic identities and explicit loopback only. No provider CAPTCHA is solved by these tests. */
class ReadVerificationRepositoryTest {
    private lateinit var api: MockWebServer
    private lateinit var web: MockWebServer
    private lateinit var store: AccountStore
    private var writes = 0
    private fun challenge(field: String = "") = """{"messageStatus":"err_request_captcha_v2","messageExtra":{"captchaId":"abcdefghijklmnop","captchaField":"$field"}}"""
    private val proof = "NEC:abcdefgh:synthetic-proof"
    @Before fun setup() {
        api = MockWebServer().apply { start() }; web = MockWebServer().apply { start() }
        store = AccountStore(object : CredentialPersistence {
            override fun read(): ByteArray? = null
            override fun write(bytes: ByteArray) { writes++ }
            override fun reset() = Unit
        }, "synthetic-read-device")
    }
    @After fun close() { api.shutdown(); web.shutdown() }
    private fun signIn() {
        val origin = "https://api.coolapk.com/".toHttpUrl()
        val cookies = listOf("uid=123", "token=synthetic-account", "SESSID=original-account").map { Cookie.parse(origin, "$it; Domain=coolapk.com; Path=/; Secure")!! }
        assertTrue(store.saveAuthenticated("123", "Synthetic", "synthetic-read-device", cookies, store.snapshot()))
    }
    private fun capture(): RequestSession {
        val captured = store.captureRequestSession()
        return captured.copy(cookieFor = { url -> captured.cookieFor(url.newBuilder().scheme("https").host("api.coolapk.com").port(443).build()) })
    }
    private fun repo() = HttpCoolapkRepository(endpoints = ApiEndpoints(api.url("/"), api.url("/"), web.url("/")),
        protocol = ClientProtocol("synthetic-read-device", { 1770000000 }), requestSession = ::capture)
    @Test fun guestHome403HasActionAndOneExplicitCookieProofRetryWithoutAdoptingAuth() = runBlocking {
        val baseline = writes
        api.enqueue(MockResponse().setResponseCode(403).setBody(challenge()).addHeader("Set-Cookie", "SESSID=challenge-session; Path=/")
            .addHeader("Set-Cookie", "uid=999; Path=/").addHeader("Set-Cookie", "token=other-account; Path=/").addHeader("Set-Cookie", "validate=server-injected; Path=/"))
        val repository = repo()
        val result = repository.home() as ApiResult.Failure
        assertEquals(403, result.httpStatus); assertEquals(FailureKind.CAPTCHA, result.kind)
        val pending = requireNotNull(result.pageRead)
        assertEquals(1, api.requestCount); assertTrue(pending.acceptHumanProof(proof)); assertEquals(1, api.requestCount)
        api.enqueue(MockResponse().setBody("""{"data":[{"id":10,"entityType":"feed","message":"verified read"}]}"""))
        assertTrue(pending.retryOnce() is ApiResult.Success); assertTrue(pending.retryOnce() is ApiResult.Failure)
        assertNull(api.takeRequest().getHeader("Cookie"))
        val retry = api.takeRequest(); assertEquals("GET", retry.method)
        assertEquals("/v6/main/indexV8", retry.requestUrl!!.encodedPath); assertEquals("1", retry.requestUrl!!.queryParameter("page"))
        assertEquals("SESSID=challenge-session; validate=NEC%3Aabcdefgh%3Asynthetic-proof", retry.getHeader("Cookie"))
        assertEquals(baseline, writes); assertNull(store.state.value.activeId)
        api.enqueue(MockResponse().setBody("{\"data\":[]}")); repository.home(PageCursor(2, "10", "10"))
        val next = api.takeRequest()
        assertEquals("2", next.requestUrl!!.queryParameter("page"))
        assertEquals("10", next.requestUrl!!.queryParameter("firstItem")); assertEquals("10", next.requestUrl!!.queryParameter("lastItem"))
        assertEquals(retry.getHeader("Cookie"), next.getHeader("Cookie"))
        assertEquals(baseline, writes); assertNull(store.state.value.activeId); assertEquals(0, web.requestCount)
    }
    @Test fun accountHomeFieldProofKeepsCapturedIdentityAndDoesNotPersistChallengeCookies() = runBlocking {
        signIn(); val baseline = writes
        api.enqueue(MockResponse().setBody(challenge("_v2_post_token")).addHeader("Set-Cookie", "SESSID=transient; Path=/")
            .addHeader("Set-Cookie", "uid=999; Path=/").addHeader("Set-Cookie", "token=wrong; Path=/"))
        val repository = repo()
        val pending = requireNotNull((repository.home() as ApiResult.Failure).pageRead)
        assertTrue(pending.acceptHumanProof(proof)); api.enqueue(MockResponse().setBody("{\"data\":[]}"))
        assertTrue(pending.retryOnce() is ApiResult.Success)
        api.takeRequest(); val retry = api.takeRequest()
        assertEquals(proof, retry.requestUrl!!.queryParameter("_v2_post_token"))
        assertEquals("uid=123; token=synthetic-account; SESSID=transient", retry.getHeader("Cookie"))
        assertEquals(baseline, writes); assertTrue(store.snapshot().cookieHeader("https://api.coolapk.com/".toHttpUrl()).contains("SESSID=original-account"))
        api.enqueue(MockResponse().setBody("{\"data\":[]}")); repository.home()
        val next = api.takeRequest(); assertEquals("uid=123; token=synthetic-account; SESSID=transient", next.getHeader("Cookie"))
        assertNull(next.requestUrl!!.queryParameter("_v2_post_token")); assertFalse(next.getHeader("Cookie")!!.contains("validate=")); assertEquals(baseline, writes)
    }
    @Test fun guestDetail403RetainsChallengeWhenPublicShareCannotReadAndRetriesOnlyDetail() = runBlocking {
        api.enqueue(MockResponse().setResponseCode(403).setBody(challenge("_v2_post_token")))
        web.enqueue(MockResponse().setResponseCode(404))
        val failure = repo().feedDetail("42") as ApiResult.Failure
        val pending = requireNotNull(failure.detailRead)
        assertTrue(pending.acceptHumanProof(proof)); api.enqueue(MockResponse().setBody("""{"data":{"id":42,"entityType":"feed","message":"full verified body"}}"""))
        val success = pending.retryOnce() as ApiResult.Success
        assertEquals("42", success.value.id); assertEquals(1, web.requestCount); assertEquals(2, api.requestCount)
        assertNull(web.takeRequest().getHeader("Cookie"))
    }
    @Test fun unsupportedFailuresNeverCreateVerificationOrFollowArbitraryUrl() = runBlocking {
        listOf(
            MockResponse().setResponseCode(403).setBody("<html>verify https://evil.invalid/challenge</html>"),
            MockResponse().setResponseCode(403).setBody("{\"data\":[]}"),
            MockResponse().setBody("""{"status":-10,"message":"网络环境异常","url":"https://evil.invalid/"}"""),
            MockResponse().setBody("""{"code":9,"message":"captcha required","url":"https://evil.invalid/"}"""),
            MockResponse().setBody(challenge("_uid")),
            MockResponse().setResponseCode(302).setHeader("Location", "https://evil.invalid/challenge").setBody(challenge()),
            MockResponse().setResponseCode(429).setBody(challenge()),
            MockResponse().setResponseCode(500).setBody(challenge()),
        ).forEach { response -> api.enqueue(response); assertNull((repo().home() as ApiResult.Failure).pageRead) }
        assertEquals(8, api.requestCount); assertEquals(0, web.requestCount)
    }
    @Test fun cancelSwitchAndCookieRevisionInvalidateWithoutDispatch() = runBlocking {
        api.enqueue(MockResponse().setBody(challenge())); val cancelled = requireNotNull((repo().home() as ApiResult.Failure).pageRead)
        cancelled.cancel(); assertFalse(cancelled.acceptHumanProof(proof)); assertTrue(cancelled.retryOnce() is ApiResult.Failure)
        api.enqueue(MockResponse().setBody(challenge())); val switched = requireNotNull((repo().home() as ApiResult.Failure).pageRead)
        assertTrue(switched.acceptHumanProof(proof)); signIn(); assertTrue(switched.retryOnce() is ApiResult.Failure)
        api.enqueue(MockResponse().setBody(challenge())); val revised = requireNotNull((repo().home() as ApiResult.Failure).pageRead)
        assertTrue(revised.acceptHumanProof(proof))
        assertTrue(store.acceptCookies(store.snapshot(), "https://api.coolapk.com/".toHttpUrl(), listOf("SESSID=unrelated; Path=/; Secure")))
        assertTrue(revised.retryOnce() is ApiResult.Failure); assertEquals(3, api.requestCount)
    }
    @Test fun secondChallengeDoesNotCreateAnAutomaticLoop() = runBlocking {
        api.enqueue(MockResponse().setBody(challenge())); val pending = requireNotNull((repo().home() as ApiResult.Failure).pageRead)
        assertTrue(pending.acceptHumanProof(proof)); api.enqueue(MockResponse().setBody(challenge()))
        val failed = pending.retryOnce() as ApiResult.Failure
        assertNull(failed.pageRead); assertEquals(FailureKind.CAPTCHA, failed.kind); assertEquals(2, api.requestCount)
    }
    @Test fun acceptedGuestReadSessionIsRevokedByLaterGenericChallengeAndAccountSwitch() = runBlocking {
        val repository = repo()
        suspend fun verify() {
            api.enqueue(MockResponse().setBody(challenge()).addHeader("Set-Cookie", "SESSID=accepted-read; Path=/"))
            val pending = requireNotNull((repository.home() as ApiResult.Failure).pageRead)
            assertTrue(pending.acceptHumanProof(proof)); api.enqueue(MockResponse().setBody("{\"data\":[]}"))
            assertTrue(pending.retryOnce() is ApiResult.Success)
            api.takeRequest(); api.takeRequest()
        }
        verify()
        api.enqueue(MockResponse().setBody(challenge("_uid")))
        assertEquals(FailureKind.CAPTCHA, (repository.appDetail("synthetic.package") as ApiResult.Failure).kind)
        assertTrue(api.takeRequest().getHeader("Cookie")!!.contains("validate="))
        api.enqueue(MockResponse().setBody("{\"data\":[]}")); repository.home()
        assertNull(api.takeRequest().getHeader("Cookie"))
        verify(); signIn()
        api.enqueue(MockResponse().setBody("{\"data\":[]}")); repository.home()
        val switched = api.takeRequest().getHeader("Cookie").orEmpty()
        assertTrue(switched.contains("uid=123")); assertFalse(switched.contains("validate=")); assertFalse(switched.contains("accepted-read"))
    }
    @Test fun guestGraphicReadRequiresTypedValidationThenExplicitReadAndRetainsOnlySessionCookie() = runBlocking {
        val repository = repo(); val baseline = writes
        api.enqueue(MockResponse().setResponseCode(403).setBody("{\"messageStatus\":\"err_request_captcha\"}")
            .addHeader("Set-Cookie", "SESSID=graphic-challenge; Path=/").addHeader("Set-Cookie", "uid=999; Path=/"))
        val pending = requireNotNull((repository.home() as ApiResult.Failure).pageRead)
        assertTrue(pending.challenge is ApiChallenge.Graphic)
        api.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("synthetic-image-fixture"))
        assertTrue(pending.image() is ApiResult.Success)
        assertEquals(2, api.requestCount); assertFalse(pending.verifyGraphic("not-valid")); assertEquals(2, api.requestCount)
        api.enqueue(MockResponse().setBody("{\"data\":\"验证通过\"}").addHeader("Set-Cookie", "SESSID=graphic-accepted; Path=/"))
        assertTrue(pending.verifyGraphic("Ab12")); assertEquals(3, api.requestCount)
        api.enqueue(MockResponse().setBody("{\"data\":[]}")); assertTrue(pending.retryOnce() is ApiResult.Success)
        assertTrue(pending.retryOnce() is ApiResult.Failure); assertEquals(4, api.requestCount)
        val initial = api.takeRequest(); val image = api.takeRequest(); val validation = api.takeRequest(); val retry = api.takeRequest()
        assertNull(initial.getHeader("Cookie")); assertEquals("/v6/account/captchaImage", image.requestUrl!!.encodedPath)
        assertEquals("SESSID=graphic-challenge", image.getHeader("Cookie")); assertEquals("SESSID=graphic-challenge", validation.getHeader("Cookie"))
        assertEquals("POST", validation.method); assertEquals("/v6/account/requestValidate", validation.requestUrl!!.encodedPath)
        assertTrue(validation.body.readUtf8().contains("Ab12")); assertEquals("GET", retry.method)
        assertEquals(initial.path, retry.path); assertEquals("SESSID=graphic-accepted", retry.getHeader("Cookie"))
        api.enqueue(MockResponse().setBody("{\"data\":[]}")); repository.home(PageCursor(2))
        assertEquals("SESSID=graphic-accepted", api.takeRequest().getHeader("Cookie"))
        assertEquals(baseline, writes); assertNull(store.state.value.activeId); assertEquals(0, web.requestCount)
    }
    @Test fun guestReadContinuationCannotBeUsedToMutate() = runBlocking {
        api.enqueue(MockResponse().setBody(challenge()))
        val transport = CoolapkHttpTransport(endpoints = ApiEndpoints(api.url("/"), api.url("/")), protocol = ClientProtocol("synthetic-read-device"))
            .forCapturedReadSession(capture())
        val rejected = transport.get("v6/main/indexV8", mapOf("page" to "1")) as ApiResult.Failure
        val replay = transport.withCaptchaProof(requireNotNull(rejected.challenge), proof)
        assertTrue(replay.post("v6/feed/like", mapOf("id" to "42")) is ApiResult.Failure)
        assertEquals(1, api.requestCount)
    }
}
