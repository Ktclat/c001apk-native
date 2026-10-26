// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.data.*
import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** Loopback and synthetic typed proofs only. No real CAPTCHA, account mutation or external SDK. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NoticeReadVerificationRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var store: AccountStore
    private lateinit var repository: SocialRepository
    private var writes = 0
    private var monotonic = 0L
    private val origin = "https://api.coolapk.com/".toHttpUrl()
    private val proof = "NEC:abcdefgh:synthetic-notice-proof"
    private val kinds = listOf(NoticeCategory.MENTIONS, NoticeCategory.REPLIES, NoticeCategory.LIKES, NoticeCategory.FOLLOWERS, NoticeCategory.SYSTEM)
    private fun challenge(field: String = "") = """{"messageStatus":"err_request_captcha_v2","messageExtra":{"captchaId":"abcdefghijklmnop","captchaField":"$field"}}"""
    private val rows = """{"data":[{"id":8,"entityType":"notification","fromuid":44,"fromusername":"Synthetic","note":"<a href='/feed/900'>notice</a>"}]}"""
    @Before fun setup() {
        server = MockWebServer().apply { start() }
        store = AccountStore(object : CredentialPersistence {
            override fun read(): ByteArray? = null
            override fun write(bytes: ByteArray) { writes++ }
            override fun reset() = Unit
        }, "synthetic-notice-device")
        repository = SocialRepository(store) { snapshot ->
            val source = store.requestSessionFor(snapshot)
            CoolapkHttpTransport(endpoints = ApiEndpoints(server.url("/"), server.url("/")),
                protocol = ClientProtocol("synthetic-notice-device", { 1770000000 }), monotonicMillis = { monotonic },
                requestSession = { mapped(source) })
        }
    }
    private fun mapped(source: RequestSession): RequestSession {
        fun official(url: HttpUrl) = url.newBuilder().scheme("https").host("api.coolapk.com").port(443).build()
        return source.copy(cookieFor = { source.cookieFor(official(it)) },
            acceptCookies = { url, headers -> source.acceptCookies(official(url), headers) },
            acceptChallengeCookies = { url, headers -> source.acceptChallengeCookies?.invoke(official(url), headers)?.let(::mapped) })
    }
    @After fun close() { server.shutdown() }
    private fun signIn(id: String = "123") {
        val cookies = listOf("uid=$id", "token=synthetic-$id", "SESSID=original").map { Cookie.parse(origin, "$it; Domain=coolapk.com; Path=/; Secure")!! }
        check(store.saveAuthenticated(id, "Synthetic", "synthetic-notice-device", cookies, store.snapshot()))
    }
    private suspend fun pending(category: NoticeCategory = NoticeCategory.MENTIONS, cursor: PageCursor = PageCursor(), response: MockResponse = MockResponse().setBody(challenge())): PendingNoticeRead {
        server.enqueue(response)
        val failure = repository.notices(category, cursor, store.state.value.generation) as ApiResult.Failure
        assertNull(failure.personalRead); assertNull(failure.countsRead); assertNull(failure.pageRead)
        return requireNotNull(failure.noticeRead)
    }
    @Test fun fiveNoticeKindsKeepExactRejectedGetCursorAndOneExplicitRetry() = runBlocking {
        signIn()
        for (category in kinds) {
            val cursor = PageCursor(2, "11", "10")
            val cap = pending(category, cursor, MockResponse().setResponseCode(403).setBody(challenge()))
            val count = server.requestCount
            assertTrue(cap.acceptHumanProof(proof)); assertEquals(count, server.requestCount)
            server.enqueue(MockResponse().setBody(rows))
            val page = (cap.retryOnce() as ApiResult.Success).value
            assertEquals("8", page.items.single().id); assertTrue(canDeleteNotification(page.items.single()))
            assertTrue(cap.retryOnce() is ApiResult.Failure)
            val original = server.takeRequest(); val retry = server.takeRequest()
            assertEquals("GET", retry.method); assertEquals(original.path, retry.path)
            assertEquals("/${category.path}", retry.requestUrl!!.encodedPath)
            assertEquals("2", retry.requestUrl!!.queryParameter("page"))
            assertEquals("11", retry.requestUrl!!.queryParameter("firstItem")); assertEquals("10", retry.requestUrl!!.queryParameter("lastItem"))
            assertEquals(0L, retry.bodySize); assertEquals(count + 1, server.requestCount)
        }
    }
    @Test fun acceptedNoticeMemoryNeverPersistsOrReachesMessagesChatAndWrites() = runBlocking {
        signIn(); val baseline = writes
        val cap = pending(response = MockResponse().setBody(challenge()).addHeader("Set-Cookie", "SESSID=notice-accepted; Path=/")
            .addHeader("Set-Cookie", "uid=999; Path=/").addHeader("Set-Cookie", "token=foreign; Path=/"))
        assertTrue(cap.acceptHumanProof(proof)); server.enqueue(MockResponse().setBody(rows)); assertTrue(cap.retryOnce() is ApiResult.Success)
        server.takeRequest(); val retry = server.takeRequest()
        assertEquals("uid=123; token=synthetic-123; SESSID=notice-accepted; validate=NEC%3Aabcdefgh%3Asynthetic-notice-proof", retry.getHeader("Cookie"))
        server.enqueue(MockResponse().setBody("{\"data\":[]}")); repository.notices(NoticeCategory.REPLIES)
        assertEquals(retry.getHeader("Cookie"), server.takeRequest().getHeader("Cookie"))
        for (load in listOf<suspend () -> Any>({ repository.notices(NoticeCategory.MESSAGES) }, { repository.chat("synthetic-conversation") }, { repository.like("42", false, true) })) {
            server.enqueue(MockResponse().setBody("{\"data\":[]}")); load()
            val request = server.takeRequest(); assertFalse(request.getHeader("Cookie").orEmpty().contains("validate=")); assertFalse(request.getHeader("Cookie").orEmpty().contains("notice-accepted"))
        }
        assertEquals(baseline, writes); assertFalse(store.snapshot().cookieHeader(origin).contains("validate=")); assertFalse(store.snapshot().cookieHeader(origin).contains("notice-accepted"))
    }
    @Test fun fieldProofIsOneQueryValueNeverReusableCookieAndCannotOverrideControls() = runBlocking {
        signIn()
        val cap = pending(response = MockResponse().setBody(challenge("_captcha_token")))
        assertTrue(cap.acceptHumanProof(proof)); server.enqueue(MockResponse().setBody(rows)); assertTrue(cap.retryOnce() is ApiResult.Success)
        server.takeRequest(); val retry = server.takeRequest()
        assertEquals(proof, retry.requestUrl!!.queryParameter("_captcha_token")); assertNull(retry.getHeader("_captcha_token"))
        assertFalse(retry.getHeader("Cookie").orEmpty().contains("validate="))
        server.enqueue(MockResponse().setBody("{\"data\":[]}")); repository.notices(NoticeCategory.MENTIONS)
        val next = server.takeRequest(); assertNull(next.requestUrl!!.queryParameter("_captcha_token")); assertFalse(next.getHeader("Cookie").orEmpty().contains("validate="))
        for (field in listOf("_uid", "_page", "_first_item", "_lastItem", "_ukey", "_X_App_Device", "_Cookie", "_method")) {
            server.enqueue(MockResponse().setBody(challenge(field)))
            assertNull((repository.notices(NoticeCategory.MENTIONS) as ApiResult.Failure).noticeRead); server.takeRequest()
        }
    }
    @Test fun graphicRequiresValidationAndThenASeparateRead() = runBlocking {
        signIn(); val baseline = writes
        val cap = pending(response = MockResponse().setBody("""{"messageStatus":"err_request_captcha"}""").addHeader("Set-Cookie", "SESSID=graphic-notice; Path=/"))
        assertFalse(cap.acceptHumanProof(proof)); assertTrue(cap.retryOnce() is ApiResult.Failure)
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(byteArrayOf(1, 2, 3))))
        assertTrue(cap.image() is ApiResult.Success)
        server.enqueue(MockResponse().setBody("{\"data\":\"验证通过\"}")); assertTrue(cap.verifyGraphic("1234"))
        assertEquals(3, server.requestCount)
        server.enqueue(MockResponse().setBody(rows)); assertTrue(cap.retryOnce() is ApiResult.Success)
        val paths = List(4) { server.takeRequest() }
        assertEquals("/v6/notification/atMeList?page=1", paths.first().path); assertEquals(paths.first().path, paths.last().path)
        assertEquals("/v6/account/captchaImage", paths[1].requestUrl!!.encodedPath)
        assertEquals("/v6/account/requestValidate", paths[2].requestUrl!!.encodedPath)
        assertEquals("POST", paths[2].method); assertEquals("GET", paths.last().method); assertEquals(baseline, writes)
    }
    @Test fun bareForbiddenHtmlRiskRedirectAndRateLimitNeverCreateCapability() = runBlocking {
        signIn()
        val responses = listOf(
            MockResponse().setResponseCode(403).setBody("Forbidden"),
            MockResponse().setResponseCode(403).setBody("<html>blocked</html>"),
            MockResponse().setResponseCode(403).addHeader("Content-Type", "application/json").addHeader("Content-Type", "text/html").setBody(challenge()),
            MockResponse().setBody("{\"messageStatus\":\"err_risk_control\",\"message\":\"risk\"}"),
            MockResponse().setResponseCode(302).setHeader("Location", "https://untrusted.invalid/").setBody(challenge()),
            MockResponse().setResponseCode(429).setBody(challenge()),
            MockResponse().setResponseCode(403).setBody("{\"data\":[]}"),
            MockResponse().setBody("not-json"),
            MockResponse().setBody("x".repeat(4 * 1024 * 1024 + 1)),
        )
        for (response in responses) { server.enqueue(response); assertNull((repository.notices(NoticeCategory.SYSTEM) as ApiResult.Failure).noticeRead) }
        assertEquals(responses.size, server.requestCount)
    }
    @Test fun cancelledExpiredWrongProofAndChangedOwnersCannotDispatch() = runBlocking {
        signIn()
        val cancelled = pending(); cancelled.cancel(); assertFalse(cancelled.acceptHumanProof(proof)); assertTrue(cancelled.retryOnce() is ApiResult.Failure)
        val wrong = pending(); assertFalse(wrong.acceptHumanProof("NEC:zzzzzzzz:synthetic-wrong-owner")); assertTrue(wrong.retryOnce() is ApiResult.Failure); wrong.cancel()
        val expired = pending(); assertTrue(expired.acceptHumanProof(proof)); monotonic = 300_001; assertTrue(expired.retryOnce() is ApiResult.Failure)
        val changed = pending(); assertTrue(changed.acceptHumanProof(proof)); signIn("456"); assertTrue(changed.retryOnce() is ApiResult.Failure)
        signIn(); val revised = pending(); assertTrue(revised.acceptHumanProof(proof))
        assertTrue(store.acceptCookies(store.snapshot(), origin, listOf("SESSID=unrelated; Domain=coolapk.com; Path=/; Secure")))
        assertTrue(revised.retryOnce() is ApiResult.Failure); assertEquals(5, server.requestCount)
    }
    @Test fun guestAndStaleGenerationNeverDispatchWhileMessageChallengeStaysOutsideNoticePath() = runBlocking {
        assertEquals(FailureKind.AUTHENTICATION, (repository.notices(NoticeCategory.MENTIONS) as ApiResult.Failure).kind)
        signIn(); val generation = store.state.value.generation; store.useGuest(); store.switchTo("123")
        try { repository.notices(NoticeCategory.MENTIONS, expectedGeneration = generation); fail("Old generation dispatched") } catch (_: CancellationException) { }
        assertEquals(0, server.requestCount)
        for (load in listOf<suspend () -> ApiResult<Page<SocialRow>>>({ repository.notices(NoticeCategory.MESSAGES) }, { repository.chat("synthetic") })) {
            server.enqueue(MockResponse().setBody(challenge())); assertNull((load() as ApiResult.Failure).noticeRead)
        }
        assertEquals(2, server.requestCount)
    }
    @Test fun secondChallengeMalformedDecodeAndNetworkFailureNeverLoopOrRetainProof() = runBlocking {
        signIn()
        for (response in listOf(MockResponse().setBody(challenge()), MockResponse().setBody("{\"data\":{}}"), MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))) {
            val cap = pending(); assertTrue(cap.acceptHumanProof(proof)); server.enqueue(response)
            val failure = cap.retryOnce() as ApiResult.Failure; assertNull(failure.noticeRead)
            assertTrue(cap.retryOnce() is ApiResult.Failure); server.takeRequest(); server.takeRequest()
            server.enqueue(MockResponse().setBody("{\"data\":[]}")); repository.notices(NoticeCategory.MENTIONS)
            assertFalse(server.takeRequest().getHeader("Cookie").orEmpty().contains("validate="))
        }
        assertEquals(9, server.requestCount)
    }
    @Test fun verifiedResponseCannotPublishAfterSameUidGenerationSwitch() = runBlocking {
        signIn(); val cap = pending(); server.takeRequest(); assertTrue(cap.acceptHumanProof(proof))
        server.enqueue(MockResponse().setBody(rows).setBodyDelay(250, TimeUnit.MILLISECONDS))
        val retry = async(Dispatchers.Default) { cap.retryOnce() }
        assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) })
        store.useGuest(); store.switchTo("123")
        try { retry.await(); fail("Old owner response published") } catch (_: CancellationException) { }
        assertEquals(2, server.requestCount)
    }
    @Test fun departedReadOwnerCannotDispatchOrPublishAcceptedMemory() = runBlocking {
        signIn()
        try { repository.notices(NoticeCategory.MENTIONS, attempt = NotificationReadAttempt().also { it.cancel() }); fail("Departed owner dispatched") }
        catch (_: CancellationException) { }
        assertEquals(0, server.requestCount)
        val alive = NotificationReadAttempt()
        server.enqueue(MockResponse().setBody(challenge()))
        val cap = requireNotNull((repository.notices(NoticeCategory.MENTIONS, attempt = alive) as ApiResult.Failure).noticeRead)
        server.takeRequest(); assertTrue(cap.acceptHumanProof(proof))
        server.enqueue(MockResponse().setBody(rows).setBodyDelay(250, TimeUnit.MILLISECONDS))
        val retry = async(Dispatchers.Default) { cap.retryOnce() }
        assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) }); alive.cancel()
        try { retry.await(); fail("Departed owner result published") } catch (_: CancellationException) { }
        server.enqueue(MockResponse().setBody("{\"data\":[]}")); repository.notices(NoticeCategory.MENTIONS)
        assertFalse(server.takeRequest().getHeader("Cookie").orEmpty().contains("validate=")); assertEquals(3, server.requestCount)
    }

    @Test fun cancelledLateSuccessCannotOverwriteNewerSameOwnerAcceptedMemory() = lateRead(rows)
    @Test fun cancelledLateChallengeCannotEraseNewerSameOwnerAcceptedMemory() = lateRead(challenge())
    private fun lateRead(lateBody: String) = runBlocking {
        signIn(); val baseline = writes
        val releaseOld = CountDownLatch(1); val oldStarted = CountDownLatch(1)
        val oldAlive = NotificationReadAttempt(); val challenges = AtomicInteger()
        val oldProof = "NEC:abcdefgh:synthetic-older-proof"
        val newProof = "NEC:abcdefgh:synthetic-newer-proof"
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val cookie = request.getHeader("Cookie").orEmpty()
                return when {
                    cookie.contains("synthetic-older-proof") -> {
                        oldStarted.countDown(); check(releaseOld.await(10, TimeUnit.SECONDS))
                        MockResponse().setBody(lateBody).addHeader("Set-Cookie", "SESSID=late-old-response; Path=/")
                    }
                    cookie.contains("synthetic-newer-proof") -> MockResponse().setBody(rows)
                    else -> MockResponse().setBody(challenge()).addHeader("Set-Cookie", "SESSID=challenge-${challenges.incrementAndGet()}; Path=/")
                }
            }
        }
        try {
            val older = requireNotNull((repository.notices(NoticeCategory.MENTIONS, attempt = oldAlive) as ApiResult.Failure).noticeRead)
            assertTrue(older.acceptHumanProof(oldProof)); server.takeRequest()
            val late = async(Dispatchers.Default) { older.retryOnce() }
            assertTrue(withContext(Dispatchers.IO) { oldStarted.await(3, TimeUnit.SECONDS) }); server.takeRequest()
            // Revoke the old route without cancelling its coroutine: the source ignores cancellation
            // and deliberately returns after a newer read has already established accepted memory.
            oldAlive.cancel()
            val newer = requireNotNull((repository.notices(NoticeCategory.LIKES) as ApiResult.Failure).noticeRead)
            assertTrue(newer.acceptHumanProof(newProof)); server.takeRequest()
            assertTrue(newer.retryOnce() is ApiResult.Success); val accepted = server.takeRequest()
            assertTrue(accepted.getHeader("Cookie").orEmpty().contains("SESSID=challenge-2"))
            releaseOld.countDown()
            try { late.await(); fail("Cancelled older result published") } catch (_: CancellationException) { }
            assertTrue(repository.notices(NoticeCategory.FOLLOWERS) is ApiResult.Success)
            val retained = server.takeRequest().getHeader("Cookie").orEmpty()
            assertTrue(retained.contains("synthetic-newer-proof")); assertTrue(retained.contains("SESSID=challenge-2"))
            assertFalse(retained.contains("synthetic-older-proof")); assertFalse(retained.contains("late-old-response"))
            assertEquals(2, challenges.get()); assertEquals(5, server.requestCount); assertEquals(baseline, writes)
        } finally { releaseOld.countDown() }
    }

    @Test fun cancelledOriginalLateChallengeCannotRevokeNewerAcceptedMemory() = lateOriginalChallenge(true)
    @Test fun staleOriginalChallengeVersionCannotRevokeNewerAcceptedMemory() = lateOriginalChallenge(false)
    private fun lateOriginalChallenge(cancelOld: Boolean) = runBlocking {
        signIn(); val releaseOld = CountDownLatch(1); val oldStarted = CountDownLatch(1); val oldAlive = NotificationReadAttempt()
        val newProof = "NEC:abcdefgh:synthetic-current-proof"
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.requestUrl!!.encodedPath == "/v6/notification/atMeList" -> {
                    oldStarted.countDown(); check(releaseOld.await(10, TimeUnit.SECONDS))
                    MockResponse().setBody(challenge()).addHeader("Set-Cookie", "SESSID=late-original; Path=/")
                }
                request.getHeader("Cookie").orEmpty().contains("synthetic-current-proof") -> MockResponse().setBody(rows)
                else -> MockResponse().setBody(challenge()).addHeader("Set-Cookie", "SESSID=current-accepted; Path=/")
            }
        }
        try {
            val late = async(Dispatchers.Default) { repository.notices(NoticeCategory.MENTIONS, attempt = oldAlive) }
            assertTrue(withContext(Dispatchers.IO) { oldStarted.await(3, TimeUnit.SECONDS) }); server.takeRequest()
            if (cancelOld) oldAlive.cancel()
            val newer = requireNotNull((repository.notices(NoticeCategory.LIKES) as ApiResult.Failure).noticeRead)
            assertTrue(newer.acceptHumanProof(newProof)); server.takeRequest()
            assertTrue(newer.retryOnce() is ApiResult.Success); server.takeRequest()
            releaseOld.countDown()
            if (cancelOld) {
                try { late.await(); fail("Cancelled original result published") } catch (_: CancellationException) { }
            } else assertNull((late.await() as ApiResult.Failure).noticeRead)
            assertTrue(repository.notices(NoticeCategory.FOLLOWERS) is ApiResult.Success)
            val retained = server.takeRequest().getHeader("Cookie").orEmpty()
            assertTrue(retained.contains("synthetic-current-proof")); assertTrue(retained.contains("SESSID=current-accepted"))
            assertFalse(retained.contains("late-original")); assertEquals(4, server.requestCount)
        } finally { releaseOld.countDown() }
    }

}
