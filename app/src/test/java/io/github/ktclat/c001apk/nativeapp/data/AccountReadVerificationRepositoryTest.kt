// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.TimeUnit

/** Explicit loopback, synthetic identities and typed proof callbacks; no real CAPTCHA solving. */
class AccountReadVerificationRepositoryTest {
    private lateinit var api: MockWebServer
    private lateinit var store: AccountStore
    private var writes = 0
    private val origin = "https://api.coolapk.com/".toHttpUrl()
    private val proof = "NEC:abcdefgh:synthetic-account-read-proof"
    private fun challenge(field: String = "") = """{"messageStatus":"err_request_captcha_v2","messageExtra":{"captchaId":"abcdefghijklmnop","captchaField":"$field"}}"""
    @Before fun setup() {
        api = MockWebServer().apply { start() }
        store = AccountStore(object : CredentialPersistence {
            override fun read(): ByteArray? = null
            override fun write(bytes: ByteArray) { writes++ }
            override fun reset() = Unit
        }, "synthetic-account-read-device")
    }
    @After fun close() { api.shutdown() }
    private fun signIn(id: String = "123") {
        val cookies = listOf("uid=$id", "token=synthetic-$id", "SESSID=raw-session").map { Cookie.parse(origin, "$it; Domain=coolapk.com; Path=/; Secure")!! }
        check(store.saveAuthenticated(id, "Synthetic", "synthetic-account-read-device", cookies, store.snapshot()))
    }
    private fun capture(): RequestSession {
        val source = store.captureRequestSession()
        return source.copy(cookieFor = { url -> source.cookieFor(url.newBuilder().scheme("https").host("api.coolapk.com").port(443).build()) })
    }
    private fun repo() = HttpCoolapkRepository(endpoints = ApiEndpoints(api.url("/"), api.url("/"), api.url("/")),
        protocol = ClientProtocol("synthetic-account-read-device", { 1770000000 }), requestSession = ::capture)
    private fun guard(): () -> Boolean {
        val generation = store.state.value.generation
        return { store.state.value.activeId == "123" && store.state.value.generation == generation }
    }
    @Test fun personalReadKeepsExactAccountQueryCursorAndReusesAcceptedReadSession() = runBlocking {
        signIn(); val baseline = writes; val repository = repo(); val personal = repository.personalLists("123", guard())
        api.enqueue(MockResponse().setResponseCode(403).setBody(challenge())
            .addHeader("Set-Cookie", "SESSID=read-accepted; Path=/").addHeader("Set-Cookie", "uid=999; Path=/").addHeader("Set-Cookie", "token=foreign; Path=/"))
        val failure = personal.load(PersonalListRequest(PersonalListKind.FEED), "123", PageCursor(2, "11", "10")) as ApiResult.Failure
        val pending = requireNotNull(failure.personalRead); assertNull(failure.pageRead); assertNull(failure.countsRead)
        assertTrue(pending.acceptHumanProof(proof)); assertEquals(1, api.requestCount)
        api.enqueue(MockResponse().setBody("""{"data":[{"id":8,"entityType":"feed","message":"verified personal"}]}"""))
        val success = pending.retryOnce() as ApiResult.Success
        assertEquals("8", success.value.items.single().content.id); assertTrue(pending.retryOnce() is ApiResult.Failure)
        val original = api.takeRequest(); val retry = api.takeRequest()
        assertEquals("GET", retry.method); assertEquals(original.path, retry.path)
        assertEquals("123", retry.requestUrl!!.queryParameter("uid")); assertEquals("2", retry.requestUrl!!.queryParameter("page"))
        assertEquals("11", retry.requestUrl!!.queryParameter("firstItem")); assertEquals("10", retry.requestUrl!!.queryParameter("lastItem"))
        assertEquals("uid=123; token=synthetic-123; SESSID=read-accepted; validate=NEC%3Aabcdefgh%3Asynthetic-account-read-proof", retry.getHeader("Cookie"))
        api.enqueue(MockResponse().setBody("{\"data\":[]}")); personal.load(PersonalListRequest(PersonalListKind.FEED), "123", success.value.nextCursor!!)
        val next = api.takeRequest(); assertEquals("3", next.requestUrl!!.queryParameter("page")); assertEquals(retry.getHeader("Cookie"), next.getHeader("Cookie"))
        assertEquals(baseline, writes); assertFalse(store.snapshot().cookieHeader(origin).contains("read-accepted")); assertFalse(store.snapshot().cookieHeader(origin).contains("validate="))
    }
    @Test fun countsReadHasOneExplicitEmptyQueryGetAndNeverReloadsProfileOrAcknowledges() = runBlocking {
        signIn(); val baseline = writes; val repository = repo(); val counts = repository.notificationCounts("123", guard())
        api.enqueue(MockResponse().setBody(challenge()).addHeader("Set-Cookie", "SESSID=counts-accepted; Path=/"))
        val failure = counts.load() as ApiResult.Failure; val pending = requireNotNull(failure.countsRead)
        assertNull(failure.personalRead); assertTrue(pending.acceptHumanProof(proof)); assertEquals(1, api.requestCount)
        api.enqueue(MockResponse().setBody("""{"data":{"atme":0,"message":2,"notification":3}}"""))
        val result = pending.retryOnce() as ApiResult.Success
        assertEquals("123", result.value.uid); assertEquals(5L, result.value.messagesAndNotifications)
        assertTrue(pending.retryOnce() is ApiResult.Failure)
        api.enqueue(MockResponse().setBody("{\"data\":{\"atme\":1}}")); assertTrue(counts.load() is ApiResult.Success)
        repeat(3) { index -> val request = api.takeRequest(); assertEquals("GET", request.method)
            assertEquals("/v6/notification/checkCount", request.path); assertEquals(0L, request.bodySize)
            assertEquals(index > 0, request.getHeader("Cookie").orEmpty().contains("validate=")) }
        assertEquals(baseline, writes)
    }
    @Test fun personalFactoryRejectsWrongUidAndGuestWithoutDispatch() = runBlocking {
        val repository = repo()
        assertEquals(FailureKind.AUTHENTICATION, (repository.personalLists("123") { true }.load(PersonalListRequest(PersonalListKind.FOLLOW), "123", PageCursor()) as ApiResult.Failure).kind)
        signIn()
        assertEquals(FailureKind.AUTHENTICATION, (repository.personalLists("123", guard()).load(PersonalListRequest(PersonalListKind.FOLLOW), "456", PageCursor()) as ApiResult.Failure).kind)
        assertEquals(FailureKind.AUTHENTICATION, (repository.personalLists(null) { true }.load(PersonalListRequest(PersonalListKind.FOLLOW), "123", PageCursor()) as ApiResult.Failure).kind)
        assertEquals(0, api.requestCount)
    }
    @Test fun cancelledCountsAndChangedAccountOrRawCookieRevisionCannotDispatch() = runBlocking {
        signIn(); val repository = repo()
        suspend fun pending(): PendingCountsRead {
            api.enqueue(MockResponse().setBody(challenge()))
            return requireNotNull((repository.notificationCounts("123", guard()).load() as ApiResult.Failure).countsRead)
        }
        val cancelled = pending(); cancelled.cancel(); assertFalse(cancelled.acceptHumanProof(proof)); assertTrue(cancelled.retryOnce() is ApiResult.Failure)
        val changed = pending(); assertTrue(changed.acceptHumanProof(proof)); signIn("456"); assertTrue(changed.retryOnce() is ApiResult.Failure)
        signIn(); val revised = pending(); assertTrue(revised.acceptHumanProof(proof))
        assertTrue(store.acceptCookies(store.snapshot(), origin, listOf("SESSID=unrelated; Domain=coolapk.com; Path=/; Secure")))
        assertTrue(revised.retryOnce() is ApiResult.Failure); assertEquals(3, api.requestCount)
    }
    @Test fun secondChallengeAndMalformedVerifiedCountNeverCreateLoopOrRetainedProof() = runBlocking {
        signIn(); val repository = repo(); val counts = repository.notificationCounts("123", guard())
        for (body in listOf(challenge(), "{\"data\":[]}")) {
            api.enqueue(MockResponse().setBody(challenge())); val pending = requireNotNull((counts.load() as ApiResult.Failure).countsRead)
            assertTrue(pending.acceptHumanProof(proof)); api.enqueue(MockResponse().setBody(body))
            val rejected = pending.retryOnce() as ApiResult.Failure; assertNull(rejected.countsRead)
            api.takeRequest(); api.takeRequest()
            api.enqueue(MockResponse().setBody("{\"data\":{\"atme\":0}}")); counts.load()
            assertFalse(api.takeRequest().getHeader("Cookie").orEmpty().contains("validate="))
        }
        assertEquals(6, api.requestCount)
    }
    @Test fun unsupportedChallengeFieldAndBareForbiddenHaveNoCountsEntry() = runBlocking {
        signIn(); val counts = repo().notificationCounts("123", guard())
        for (response in listOf(MockResponse().setBody(challenge("_uid")), MockResponse().setResponseCode(403).setBody("<html>blocked</html>"))) {
            api.enqueue(response); assertNull((counts.load() as ApiResult.Failure).countsRead)
        }
        assertEquals(2, api.requestCount)
    }
    @Test fun rawRevisionChangeBetweenHelperChecksAndTransportDispatchSendsNothing() = runBlocking {
        var calls = 0; var revisionCurrent = true
        val repository = HttpCoolapkRepository(endpoints = ApiEndpoints(api.url("/"), api.url("/")), requestSession = {
            RequestSession(cookieFor = { null }, isCurrent = { if (++calls == 3) revisionCurrent = false; true },
                acceptCookies = { _, _ -> }, deviceCode = "synthetic-race-device", isSessionCurrent = { revisionCurrent })
        })
        api.enqueue(MockResponse().setBody("{\"data\":{\"atme\":0}}"))
        try { repository.notificationCounts("123") { true }.load(); fail("Stale account request dispatched") }
        catch (_: CancellationException) { }
        assertEquals(0, api.requestCount); assertTrue(calls >= 3)
    }
    @Test fun verifiedCountCannotPublishAfterAccountSwitchDuringResponse() = runBlocking {
        signIn(); val counts = repo().notificationCounts("123", guard())
        api.enqueue(MockResponse().setBody(challenge())); val pending = requireNotNull((counts.load() as ApiResult.Failure).countsRead)
        api.takeRequest(); assertTrue(pending.acceptHumanProof(proof))
        api.enqueue(MockResponse().setBody("{\"data\":{\"atme\":7}}").setBodyDelay(200, TimeUnit.MILLISECONDS))
        val deferred = async(Dispatchers.Default) { pending.retryOnce() }
        assertNotNull(withContext(Dispatchers.IO) { api.takeRequest(3, TimeUnit.SECONDS) }); signIn("456")
        try { deferred.await(); fail("Old account result published") } catch (_: CancellationException) { }
        assertEquals(2, api.requestCount)
    }
}
