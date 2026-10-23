// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Synthetic loopback only. No live accounts, persisted cookies, writes, or mark-read calls. */
class NotificationCountsScopeTest {
    private fun session(current: () -> Boolean = { true }, revision: (() -> Boolean)? = { true }, guest: Boolean = false) =
        RequestSession({ null }, current, { _, _ -> }, isSessionCurrent = revision, isGuest = guest)
    @Test fun exactReadOnlyContractCapturesOnceAndPreservesServerZero() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("""{"data":{"atme":0,"message":2,"notification":3}}"""))
            var captures = 0
            val repo = HttpCoolapkRepository(endpoints = ApiEndpoints(server.url("/"), server.url("/")), requestSession = { captures++; session() })
            val result = repo.notificationCounts("123") { true }.load() as ApiResult.Success
            assertEquals(0L, result.value.atMe); assertEquals(5L, result.value.messagesAndNotifications)
            val request = server.takeRequest(3, TimeUnit.SECONDS)!!
            assertEquals("GET", request.method); assertEquals("/v6/notification/checkCount", request.path)
            assertEquals(0L, request.bodySize); assertEquals(1, captures); assertEquals(1, server.requestCount)
        }
    }
    @Test fun noSessionGuestMissingStrictRevisionAndInvalidUidNeverSend() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val endpoints = ApiEndpoints(server.url("/"), server.url("/"))
            val suppliers: List<(() -> RequestSession)?> = listOf(null, { session(guest = true) }, { session(revision = null) })
            suppliers.forEach { supplier ->
                val result = HttpCoolapkRepository(endpoints = endpoints, requestSession = supplier).notificationCounts("123") { true }.load()
                assertEquals(FailureKind.AUTHENTICATION, (result as ApiResult.Failure).kind)
            }
            val invalid = HttpCoolapkRepository(endpoints = endpoints, requestSession = { error("must not capture") }).notificationCounts("other") { true }.load()
            assertEquals(FailureKind.INVALID_ARGUMENT, (invalid as ApiResult.Failure).kind)
            assertEquals(0, server.requestCount)
        }
    }
    @Test fun scopeChangeBeforeCaptureDuringCaptureAndStaleRevisionNeverSend() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val endpoints = ApiEndpoints(server.url("/"), server.url("/"))
            val alreadyChanged = HttpCoolapkRepository(endpoints = endpoints, requestSession = { error("must not capture") })
            try { alreadyChanged.notificationCounts("123") { false }.load(); fail("must cancel") } catch (_: CancellationException) { }
            var current = true
            val changedDuringCapture = HttpCoolapkRepository(endpoints = endpoints, requestSession = { current = false; session() })
            try { changedDuringCapture.notificationCounts("123") { current }.load(); fail("must cancel") } catch (_: CancellationException) { }
            val staleRevision = HttpCoolapkRepository(endpoints = endpoints, requestSession = { session(revision = { false }) })
            try { staleRevision.notificationCounts("123") { true }.load(); fail("must cancel") } catch (_: CancellationException) { }
            assertEquals(0, server.requestCount)
        }
    }
    @Test fun accountGenerationChangeRejectsLateResponse() = rejectsLateResponse(changeAccount = true)
    @Test fun cookieRevisionChangeRejectsLateResponse() = rejectsLateResponse(changeAccount = false)
    private fun rejectsLateResponse(changeAccount: Boolean) = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("""{"data":{"atme":8}}""").setBodyDelay(300, TimeUnit.MILLISECONDS))
            val account = AtomicBoolean(true); val revision = AtomicBoolean(true); var captures = 0
            val repo = HttpCoolapkRepository(endpoints = ApiEndpoints(server.url("/"), server.url("/")), requestSession = {
                captures++; session(current = { account.get() }, revision = { revision.get() })
            })
            val pending = async(Dispatchers.Default) { repo.notificationCounts("123") { account.get() }.load() }
            assertEquals("/v6/notification/checkCount", withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) }!!.path)
            if (changeAccount) account.set(false) else revision.set(false)
            try { pending.await(); fail("stale counts published") } catch (_: CancellationException) { }
            assertEquals(1, captures); assertEquals(1, server.requestCount)
        }
    }
    @Test fun malformedEnvelopeAndDataReportFailureWithoutFallbackOrRetry() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val repo = HttpCoolapkRepository(endpoints = ApiEndpoints(server.url("/"), server.url("/")), requestSession = { session() })
            for (body in listOf("""{"data":[]}""", """{"data":null}""", "not-json")) {
                server.enqueue(MockResponse().setBody(body))
                assertTrue(repo.notificationCounts("123") { true }.load() is ApiResult.Failure)
            }
            assertEquals(3, server.requestCount)
        }
    }
}
