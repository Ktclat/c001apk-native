// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.TimeUnit

class UserConnectionsRepositoryTest {
    private lateinit var server: MockWebServer
    @Before fun setup() { server = MockWebServer().apply { start() } }
    @After fun close() { server.shutdown() }
    private fun repo(session: (() -> RequestSession)? = null) = HttpCoolapkRepository(
        endpoints = ApiEndpoints(server.url("/"), server.url("/")), requestSession = session)
    private val contact = """{"id":"record-1","entityId":"shared-entity","entityType":"contacts","uid":"777","userInfo":{"uid":null,"username":"preferred"},"fUserInfo":{"uid":"8","username":"fallback","userAvatar":"http://avatar.coolapk.com/a.png","follow":3,"fans":4}}"""

    @Test fun exactFlutterGuestPathsAndRawRecordCursorsNeverUseOwnUidOrSocialWrites() = runBlocking {
        val repository = repo()
        for (kind in UserConnectionKind.entries) {
            server.enqueue(MockResponse().setBody("""{"data":[$contact,$contact]}"""))
            val first = (repository.userConnections("7", kind) as ApiResult.Success).value
            assertEquals(2, first.items.size); assertEquals("8", first.items.first().userId)
            assertEquals("preferred", first.items.first().userName)
            assertEquals("https://avatar.coolapk.com/a.png", first.items.first().avatarUrl)
            assertEquals(PageCursor(2, "record-1", "record-1"), first.nextCursor)
            val request = server.takeRequest()
            assertEquals("GET", request.method); assertEquals("/${kind.path}?uid=7&page=1", request.path)
            assertNull(request.getHeader("Cookie")); assertEquals(0L, request.bodySize)
            server.enqueue(MockResponse().setBody("""{"data":[${contact.replace("\"uid\":\"8\"", "\"uid\":\"9\"")}]}"""))
            val repeated = (repository.userConnections("7", kind, requireNotNull(first.nextCursor)) as ApiResult.Success).value
            assertEquals("9", repeated.items.single().userId)
            assertEquals(PageCursor(3, "record-1", "record-1"), repeated.nextCursor)
            assertEquals("/${kind.path}?uid=7&page=2&firstItem=record-1&lastItem=record-1", server.takeRequest().path)
            server.enqueue(MockResponse().setBody("""{"data":[${contact.replace("\"uid\":\"8\"", "\"uid\":\"10\"")}]}"""))
            val third = (repository.userConnections("7", kind, requireNotNull(repeated.nextCursor)) as ApiResult.Success).value
            assertEquals("10", third.items.single().userId)
            assertEquals(PageCursor(4, "record-1", "record-1"), third.nextCursor)
            assertEquals("/${kind.path}?uid=7&page=3&firstItem=record-1&lastItem=record-1", server.takeRequest().path)
        }
    }
    @Test fun connectionPageOverflowStopsButUnrelatedReadersKeepRepeatedBoundaryPolicy() = runBlocking {
        val repository = repo()
        for (kind in UserConnectionKind.entries) {
            server.enqueue(MockResponse().setBody("""{"data":[$contact]}"""))
            val last = (repository.userConnections("7", kind, PageCursor(Int.MAX_VALUE, "record-1", "record-1")) as ApiResult.Success).value
            assertEquals(1, last.items.size); assertNull(last.nextCursor)
            assertEquals(Int.MAX_VALUE.toString(), server.takeRequest().requestUrl!!.queryParameter("page"))
        }
        server.enqueue(MockResponse().setBody("""{"data":[$contact]}"""))
        assertNull((repository.userFeed("7", PageCursor(2, "record-1", "record-1")) as ApiResult.Success).value.nextCursor)
        assertEquals("/v6/user/feedList", server.takeRequest().requestUrl!!.encodedPath)
    }
    @Test fun flutterTypedEndCardSurvivesWireDecodeForRouteOwnedTermination() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[$contact,{"id":"end","entityType":"card","entityTemplate":"noMoreDataCard","title":"没有更多了"}]}"""))
        val page = (repo().userConnections("7", UserConnectionKind.FANS) as ApiResult.Success).value
        assertEquals("card", page.items.last().kind); assertEquals("noMoreDataCard", page.items.last().entityTemplate)
        assertEquals("end", page.nextCursor!!.lastItem) // The route projects the explicit end marker without changing source record IDs.
        assertEquals("/v6/user/fansList?uid=7&page=1", server.takeRequest().path)
    }
    @Test fun invalidUidsNeverDispatch() = runBlocking {
        val repository = repo()
        for (kind in UserConnectionKind.entries) for (uid in listOf("", "0", "000", "-1", "7:8", "7?uid=8", "7/8", "1".repeat(33)))
            assertEquals(FailureKind.INVALID_ARGUMENT, (repository.userConnections(uid, kind) as ApiResult.Failure).kind)
        assertEquals(0, server.requestCount)
    }
    @Test fun authenticationAndMalformedResponsesRemainExplicitFailures() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        assertTrue(repo().userConnections("7", UserConnectionKind.FANS) is ApiResult.Failure)
        server.enqueue(MockResponse().setBody("""{"data":{"uid":7}}"""))
        assertEquals(FailureKind.INVALID_RESPONSE, (repo().userConnections("7", UserConnectionKind.FANS) as ApiResult.Failure).kind)
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        val empty = (repo().userConnections("7", UserConnectionKind.FANS) as ApiResult.Success).value
        assertTrue(empty.items.isEmpty()); assertNull(empty.nextCursor); assertEquals(3, server.requestCount)
    }
    @Test fun explicitGuestVerificationRetriesExactTargetKindAndCursorOnce() = runBlocking {
        val store = AccountStore(object : CredentialPersistence {
            override fun read(): ByteArray? = null
            override fun write(bytes: ByteArray) = Unit
            override fun reset() = Unit
        }, "synthetic-connection-device")
        for (kind in UserConnectionKind.entries) {
            val repository = repo { store.captureRequestSession() }
            server.enqueue(MockResponse().setBody("""{"messageStatus":"err_request_captcha_v2","messageExtra":{"captchaId":"abcdefghijklmnop","captchaField":"_v2_post_token"}}"""))
            val failure = repository.userConnections("7", kind, PageCursor(2, "record-1", "record-9")) as ApiResult.Failure
            val pending = requireNotNull(failure.pageRead)
            assertTrue(pending.acceptHumanProof("NEC:abcdefgh:synthetic-proof"))
            server.enqueue(MockResponse().setBody("""{"data":[$contact]}"""))
            assertTrue(pending.retryOnce() is ApiResult.Success); assertTrue(pending.retryOnce() is ApiResult.Failure)
            val first = server.takeRequest(); val retried = server.takeRequest()
            assertEquals("/${kind.path}", retried.requestUrl!!.encodedPath)
            for (key in listOf("uid", "page", "firstItem", "lastItem")) assertEquals(first.requestUrl!!.queryParameter(key), retried.requestUrl!!.queryParameter(key))
            assertEquals("NEC:abcdefgh:synthetic-proof", retried.requestUrl!!.queryParameter("_v2_post_token"))
            assertEquals("GET", retried.method); assertNull(store.state.value.activeId)
        }
        assertEquals(4, server.requestCount)
    }
    @Test fun accountSwitchDuringReadCannotPublishOrAcceptCookies() = runBlocking {
        var current = true; var cookies = false
        server.enqueue(MockResponse().setBody("""{"data":[$contact]}""").setBodyDelay(300, TimeUnit.MILLISECONDS).addHeader("Set-Cookie", "SESSID=old; Path=/"))
        val repository = repo { RequestSession(cookieFor = { null }, isCurrent = { current }, acceptCookies = { _, _ -> cookies = true }) }
        val request = async { repository.userConnections("7", UserConnectionKind.FOLLOWING) }
        assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }); current = false
        try { request.await(); fail("stale connection list published") } catch (_: CancellationException) { }
        assertFalse(cookies)
    }
}
