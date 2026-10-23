// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.diagnostics

import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.data.*
import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okio.buffer
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException

/** Application interceptors never call proceed: synthetic responses, no DNS/socket/account service. */
class HomeRequestDiagnosticsTest {
    private fun store() = AccountStore(object : CredentialPersistence {
        override fun read(): ByteArray? = null
        override fun write(bytes: ByteArray) = Unit
        override fun reset() = Unit
    }, guestDeviceId = "SYNTHETIC_DEVICE_SECRET")
    private fun journal(store: AccountStore) = HomeRequestDiagnostics(store) { store.state.value.generation }
    private fun protocol() = ClientProtocol(deviceCode = "SYNTHETIC_DEVICE_SECRET", clockSeconds = { 1_700_000_000 }, sdkInt = 35)
    private fun response(request: Request, code: Int, type: String?, body: String) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("SYNTHETIC_RESPONSE_MESSAGE_SECRET")
        .apply { type?.let { header("Content-Type", it) }; header("Set-Cookie", "SESSID=SYNTHETIC_COOKIE_SECRET; Path=/; Secure") }
        .body(body.toResponseBody()).build()
    private fun withClient(interceptor: Interceptor, block: (OkHttpClient) -> Unit) {
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        try { block(client) } finally { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
    }
    @Test fun htmlAndJson403AreRedactedAndDoNotAcceptCookiesOrReadErrorBody() {
        for (type in listOf("text/html; charset=utf-8", "application/json")) {
            val store = store(); val journal = journal(store); var requests = 0; var bodyReads = 0
            withClient(Interceptor { chain ->
                requests++
                response(chain.request(), 403, type, "SYNTHETIC_BODY_SECRET").newBuilder().body(object : ResponseBody() {
                    override fun contentType(): MediaType? = null
                    override fun contentLength() = 99L
                    override fun source(): okio.BufferedSource = object : okio.ForwardingSource(okio.Buffer().writeUtf8("SYNTHETIC_BODY_SECRET")) {
                        override fun read(sink: okio.Buffer, byteCount: Long): Long { bodyReads++; return super.read(sink, byteCount) }
                    }.buffer()
                }).build()
            }) { client ->
                val result = runBlocking { HttpCoolapkRepository(client, protocol = protocol(), requestSession = { store.captureRequestSession() }, homeDiagnostics = journal).home(PageCursor()) }
                assertEquals(FailureKind.HTTP, (result as ApiResult.Failure).kind)
                assertEquals(1, requests); assertEquals(0, bodyReads); assertTrue(store.snapshot().cookies.isEmpty())
                val event = journal.snapshot().single()
                assertEquals(403, event.httpStatus); assertEquals(HomeRequestHost.API2, event.host)
                assertEquals(if (type.startsWith("text")) HomeContentType.HTML else HomeContentType.JSON, event.contentType)
                assertEquals(HomeRequestMode.GUEST, event.mode); assertFalse(event.hasSessid)
                val text = sampleDiagnostics().copy(homeRequests = journal.snapshot()).copyText()
                for (secret in listOf("SYNTHETIC", "https://", "installTime=", "firstLaunch=", "Set-Cookie", "User-Agent", "X-App-Token", "ca_")) assertFalse(secret, text.contains(secret))
                assertTrue(text.contains("identityProvenance=unknown")); assertTrue(text.contains("profile=fallback"))
            }
        }
    }
    @Test fun timeoutIsExistingNetworkFailureWithUnavailableStatusAndNoExceptionText() {
        val store = store(); val journal = journal(store); var calls = 0
        withClient(Interceptor { calls++; throw SocketTimeoutException("SYNTHETIC_TIMEOUT_SECRET") }) { client ->
            val result = runBlocking { HttpCoolapkRepository(client, protocol = protocol(), requestSession = { store.captureRequestSession() }, homeDiagnostics = journal).home(PageCursor()) }
            assertEquals(FailureKind.NETWORK, (result as ApiResult.Failure).kind); assertEquals(1, calls)
            val event = journal.snapshot().single(); assertNull(event.httpStatus); assertEquals(HomeContentType.UNAVAILABLE, event.contentType)
            assertFalse(event.text().contains("SYNTHETIC_TIMEOUT_SECRET"))
        }
    }
    @Test fun enabledAndDisabledDiagnosticsSendIdenticalRequestsAndAcceptSuccessCookieNormally() {
        val captured = mutableListOf<List<String>>()
        for (enabled in listOf(false, true)) {
            val store = store(); val journal = journal(store)
            withClient(Interceptor { chain ->
                val request = chain.request()
                captured.add(listOf(request.method, request.url.toString(), request.headers.toString()))
                response(request, 200, "application/json", """{"data":[]}""")
            }) { client ->
                val repo = HttpCoolapkRepository(client, protocol = protocol(), installTimeMillis = 1_700_000_000_000,
                    requestSession = { store.captureRequestSession() }, homeDiagnostics = journal.takeIf { enabled })
                runBlocking { repeat(2) { assertTrue(repo.home(PageCursor()) is ApiResult.Success) } }
                assertEquals("SYNTHETIC_COOKIE_SECRET", store.snapshot().cookies.single().value)
                if (enabled) { assertEquals(2, journal.snapshot().size); assertFalse(journal.snapshot()[0].hasSessid); assertTrue(journal.snapshot()[1].hasSessid) }
                else assertTrue(journal.snapshot().isEmpty())
            }
        }
        assertEquals(captured[0], captured[2]); assertEquals(captured[1], captured[3])
    }
    @Test fun switchDuringResponsePreservesCancellationDropsEventAndClearsPriorEvents() {
        val store = store(); val journal = journal(store); var switch = false
        withClient(Interceptor { chain -> if (switch) store.useGuest(); response(chain.request(), 403, "text/html", "ignored") }) { client ->
            val repo = HttpCoolapkRepository(client, protocol = protocol(), requestSession = { store.captureRequestSession() }, homeDiagnostics = journal)
            runBlocking { repo.home(PageCursor()) }; assertEquals(1, journal.snapshot().size)
            switch = true
            try { runBlocking { repo.home(PageCursor()) }; fail("Expected original cancellation") } catch (_: CancellationException) { }
            assertTrue(journal.snapshot().isEmpty()); assertTrue(store.snapshot().cookies.isEmpty())
            journal.export { assertTrue(it.isEmpty()) }
        }
    }
    @Test fun closedRoutesOnlyAndUnknownCustomSessionIsNotAuthenticated() {
        val lock = Any(); val journal = HomeRequestDiagnostics(lock) { 0 }
        val custom = RequestSession(cookieFor = { null }, isCurrent = { true }, acceptCookies = { _, _ -> }, diagnosticGeneration = 0)
        withClient(Interceptor { chain -> response(chain.request(), 200, "application/json", """{"data":[]}""") }) { client ->
            val repo = HttpCoolapkRepository(client, protocol = protocol(), requestSession = { custom }, homeDiagnostics = journal)
            runBlocking {
                repo.home(PageCursor()); repo.page("/page?url=V9_HOME_TAB_RANKING", "热榜", PageCursor())
                repo.page("V11_VERTICAL_TOPIC", "话题", PageCursor()); repo.page("/page?url=V11_FIND_COOLPIC", "酷图", PageCursor())
                repo.productCategories(); repo.page("SYNTHETIC_PRIVATE_ROUTE", "private", PageCursor()); repo.following(FollowType.ALL, PageCursor())
            }
            assertEquals(HomeRequestTab.entries.toSet(), journal.snapshot().map { it.tab }.toSet())
            assertEquals(5, journal.snapshot().size); assertTrue(journal.snapshot().all { it.mode == HomeRequestMode.UNKNOWN })
        }
    }
    @Test fun boundedRetentionClampsFieldsAndRejectsUnknownOrStaleGeneration() {
        var generation = 0L; val journal = HomeRequestDiagnostics(Any()) { generation }
        val event = HomeRequestEvent(HomeRequestTab.DYNAMIC, HomeRequestEndpoint.INDEX_V8, HomeRequestHost.API2,
            HomeRequestMode.GUEST, false, HomeProtocolInfo(2604201, 35, true), 999, HomeContentType.OTHER, FailureKind.HTTP, Long.MAX_VALUE)
        repeat(20) { journal.append(0, event) }; assertEquals(12, journal.snapshot().size)
        assertNull(journal.snapshot().first().httpStatus); assertEquals(120_000L, journal.snapshot().first().elapsedMillis)
        generation++
        journal.append(0, event); journal.append(null, event); assertTrue(journal.snapshot().isEmpty())
        assertEquals(HomeContentType.OTHER, homeContentType("application/SYNTHETIC_SECRET"))
        assertEquals(HomeContentType.UNAVAILABLE, homeContentType(null))
    }
    @Test fun observerFailureDoesNotChangeSuccessfulRequest() {
        val store = store(); val broken = HomeRequestDiagnostics(Any()) { throw IllegalStateException("synthetic observer failure") }
        withClient(Interceptor { chain -> response(chain.request(), 200, null, """{"data":[]}""") }) { client ->
            assertTrue(runBlocking { HttpCoolapkRepository(client, protocol = protocol(), requestSession = { store.captureRequestSession() }, homeDiagnostics = broken).home(PageCursor()) } is ApiResult.Success)
        }
    }
    @Test fun authenticatedSnapshotAndValidProfileExportOnlySafeNumericProtocolFields() {
        val store = store(); val journal = journal(store)
        store.saveAuthenticated("987654321", "SYNTHETIC_ACCOUNT_SECRET", "SYNTHETIC_BOUND_DEVICE_SECRET", listOf(
            Cookie.Builder().name("token").value("SYNTHETIC_AUTH_SECRET").domain("coolapk.com").path("/").secure()
                .expiresAt(System.currentTimeMillis() + 3_600_000).build(),
            Cookie.Builder().name("SESSID").value("SYNTHETIC_SESSION_SECRET").domain("coolapk.com").path("/").secure()
                .expiresAt(System.currentTimeMillis() + 3_600_000).build()), store.snapshot())
        val protocol = ClientProtocol(deviceCode = "SYNTHETIC_GLOBAL_DEVICE_SECRET", clockSeconds = { 1_700_000_000 },
            appCode = 2604201, sdkInt = 35, deviceProfile = ClientDeviceProfile("SYNTHETIC_MAKER", "SYNTHETIC_BRAND", "SYNTHETIC_MODEL", "SYNTHETIC_BUILD", "15"))
        withClient(Interceptor { chain -> response(chain.request(), 403, "application/json", "SYNTHETIC_BODY_SECRET") }) { client ->
            runBlocking { HttpCoolapkRepository(client, protocol = protocol, requestSession = { store.captureRequestSession() }, homeDiagnostics = journal).home(PageCursor()) }
            val event = journal.snapshot().single()
            assertEquals(HomeRequestMode.AUTHENTICATED, event.mode); assertTrue(event.hasSessid)
            assertEquals(HomeProtocolInfo(2604201, 35, true), event.protocol)
            val text = event.text()
            assertTrue(text.contains("app=16.2.0; code=2604201; api=16; sdk=35; profile=valid; identityProvenance=unknown"))
            assertFalse(text.contains("SYNTHETIC")); assertFalse(text.contains("987654321"))
        }
    }

}
