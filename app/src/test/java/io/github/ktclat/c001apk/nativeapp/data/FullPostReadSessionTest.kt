// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

import io.github.ktclat.c001apk.nativeapp.account.AccountStore
import io.github.ktclat.c001apk.nativeapp.account.CredentialPersistence
import io.github.ktclat.c001apk.nativeapp.account.captureRequestSession
import io.github.ktclat.c001apk.nativeapp.domain.ApiResult
import io.github.ktclat.c001apk.nativeapp.domain.ContentItem
import io.github.ktclat.c001apk.nativeapp.domain.FullPostBody
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Cookie
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

/**
 * Synthetic source-only coverage for the fixed ordinary-read snapshot contract.
 * An application interceptor supplies every response without proceeding down the chain;
 * DNS is fail-closed as a second guard. No sockets, account services, or disk vaults are used.
 * Browse results remain generation-gated, not revision-gated; cookie commits retain their
 * independent revision gate. These are not social-operation or challenge-session tests.
 */
class FullPostReadSessionTest {
    private val api = "https://api.coolapk.com/v6/feed/detail".toHttpUrl()
    private val fullHtml = "<p>" + "x".repeat(12_001) + "SYNTHETIC_FULL_DETAIL_TAIL</p>"

    private class Memory : CredentialPersistence {
        private var bytes: ByteArray? = null
        override fun read(): ByteArray? = bytes?.copyOf()
        override fun write(bytes: ByteArray) { this.bytes = bytes.copyOf() }
        override fun reset() { bytes = null }
    }

    private fun store() = AccountStore(Memory(), guestDeviceId = "synthetic-stable-guest-device")

    /** Test fixture only: no login request, real identity, or account persistence is involved. */
    private fun seedAuthenticated(store: AccountStore, id: String, version: String = "initial") {
        fun cookie(name: String, value: String) = Cookie.Builder()
            .name(name).value(value).domain("coolapk.com").path("/").secure().build()
        assertTrue(store.saveAuthenticated(
            id, "Synthetic account $id", "synthetic-account-device-$id",
            listOf(cookie("uid", id), cookie("token", "synthetic-token-$id-$version"),
                cookie("SESSID", "synthetic-session-$id-$version")), store.snapshot(),
        ))
    }

    private fun sessionCookie(value: String) = "SESSID=$value; Domain=coolapk.com; Path=/; Secure"

    /** A bounded latch establishes ordering; the timeout detects a broken test, not a race. */
    private class Gate {
        private val entered = CompletableDeferred<Unit>()
        private val released = CountDownLatch(1)
        fun block() {
            entered.complete(Unit)
            if (!released.await(10, TimeUnit.SECONDS)) throw IOException("Synthetic response gate was not released")
        }
        suspend fun awaitEntered() { withTimeout(10_000) { entered.await() } }
        fun open() { released.countDown() }
    }

    private fun body(text: String, gate: Gate?): ResponseBody {
        if (gate == null) return text.toResponseBody("application/json".toMediaType())
        val source = object : ForwardingSource(Buffer().writeUtf8(text)) {
            private var firstRead = true
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (firstRead) { firstRead = false; gate.block() }
                return super.read(sink, byteCount)
            }
        }.buffer()
        return object : ResponseBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength() = text.toByteArray(Charsets.UTF_8).size.toLong()
            override fun source(): BufferedSource = source
        }
    }

    private fun response(request: Request, cookie: String? = null, bodyGate: Gate? = null): Response {
        val json = buildJsonObject {
            putJsonObject("data") { put("id", "42"); put("entityType", "feed"); put("message", fullHtml) }
        }.toString()
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic")
            .apply { cookie?.let { addHeader("Set-Cookie", sessionCookie(it)) } }
            .body(body(json, bodyGate)).build()
    }

    private class Harness(store: AccountStore, respond: (Request, Int) -> Response) : Closeable {
        val requests = CopyOnWriteArrayList<Request>()
        val captures = CopyOnWriteArrayList<RequestSession>()
        val callCancelled = CompletableDeferred<Unit>()
        private val client = OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    throw AssertionError("Synthetic interceptor must never reach DNS")
            })
            .eventListener(object : EventListener() {
                override fun canceled(call: Call) { callCancelled.complete(Unit) }
            })
            .addInterceptor { chain ->
                val request = chain.request()
                requests.add(request)
                respond(request, requests.size)
            }.build()
        val repository = HttpCoolapkRepository(
            client = client,
            protocol = ClientProtocol("synthetic-protocol-device", { 1_770_000_000 }),
            requestSession = { store.captureRequestSession().also { captures.add(it) } },
        )
        override fun close() {
            client.dispatcher.cancelAll()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun assertFullDetail(result: ApiResult<ContentItem>) {
        assertTrue("Expected a full-detail success, not a failure", result is ApiResult.Success)
        val item = (result as ApiResult.Success).value
        assertEquals(fullHtml, (item.fullPostBody as FullPostBody.Available).html)
        assertEquals(fullHtml.take(12_000), item.message)
        assertTrue(item.bodyTruncated)
    }

    private suspend fun assertCancelled(read: Deferred<ApiResult<ContentItem>>) {
        try {
            withTimeout(10_000) { read.await() }
            fail("Cancelled/stale detail must not return either a body or an ApiResult failure")
        } catch (cancelled: CancellationException) {
            if (cancelled is TimeoutCancellationException) throw cancelled
        }
        assertTrue(read.isCancelled)
    }

    @Test fun guestSameResponseSetCookieSucceedsAndEachDetailCapturesExactlyOnce() = runBlocking {
        val store = store()
        val before = store.snapshot()
        Harness(store) { request, index -> response(request, if (index == 1) "synthetic-guest-refreshed" else null) }.use { h ->
            assertFullDetail(h.repository.feedDetail("42"))
            assertEquals(1, h.captures.size)
            assertTrue(h.captures.single().isGuest)
            assertNull(h.requests.single().header("Cookie"))
            assertTrue(store.isCurrent(before))
            assertEquals("SESSID=synthetic-guest-refreshed", store.snapshot().cookieHeader(api))

            assertFullDetail(h.repository.feedDetail("42"))
            assertEquals(2, h.captures.size)
            assertEquals(2, h.requests.size)
            assertEquals("SESSID=synthetic-guest-refreshed", h.requests[1].header("Cookie"))
            assertEquals(h.requests[0].header("X-App-Device"), h.requests[1].header("X-App-Device"))
            assertNotNull(h.requests[1].header("X-App-Device"))
            assertNull(store.snapshot().accountId)
        }
    }

    @Test fun authenticatedSameResponseSetCookieSucceedsAndEachDetailCapturesExactlyOnce() = runBlocking {
        val store = store()
        seedAuthenticated(store, "1")
        val before = store.snapshot()
        val originalCookie = before.cookieHeader(api)
        Harness(store) { request, index -> response(request, if (index == 1) "synthetic-account-refreshed" else null) }.use { h ->
            assertFullDetail(h.repository.feedDetail("42"))
            assertEquals(1, h.captures.size)
            assertFalse(h.captures.single().isGuest)
            assertEquals(originalCookie, h.requests.single().header("Cookie"))
            assertTrue(store.isCurrent(before))
            assertFalse(h.captures.single().isSessionCurrent!!.invoke())
            val refreshedCookie = store.snapshot().cookieHeader(api)
            assertTrue(refreshedCookie.contains("SESSID=synthetic-account-refreshed"))
            assertFalse(refreshedCookie.contains("SESSID=synthetic-session-1-initial"))

            assertFullDetail(h.repository.feedDetail("42"))
            assertEquals(2, h.captures.size)
            assertEquals(2, h.requests.size)
            assertEquals(refreshedCookie, h.requests[1].header("Cookie"))
            assertEquals(h.requests[0].header("X-App-Device"), h.requests[1].header("X-App-Device"))
            assertEquals("1", store.snapshot().accountId)
        }
    }

    @Test fun callerCancellationPropagatesAndCancelsTheHeldSyntheticCall() = runBlocking {
        val store = store()
        val responseGate = Gate()
        Harness(store) { request, _ ->
            responseGate.block()
            response(request, "synthetic-cancelled-response")
        }.use { h ->
            val read = async { h.repository.feedDetail("42") }
            try {
                responseGate.awaitEntered()
                read.cancel(CancellationException("Synthetic caller cancellation"))
                withTimeout(10_000) { h.callCancelled.await() }
                assertCancelled(read)
                assertEquals(1, h.captures.size)
                assertEquals(1, h.requests.size)
                assertEquals("", store.snapshot().cookieHeader(api))
            } finally {
                responseGate.open()
                read.cancelAndJoin()
            }
        }
    }

    private suspend fun accountSwitchRejectsOldResponse(duringBody: Boolean) = coroutineScope {
        val store = store()
        seedAuthenticated(store, "1")
        seedAuthenticated(store, "2")
        store.switchTo("1")
        val before = store.snapshot()
        val originalCookie = before.cookieHeader(api)
        val gate = Gate()
        Harness(store) { request, _ ->
            if (!duringBody) gate.block()
            response(request, "synthetic-stale-account-response", if (duringBody) gate else null)
        }.use { h ->
            val read = async { h.repository.feedDetail("42") }
            try {
                gate.awaitEntered()
                assertEquals(originalCookie, h.requests.single().header("Cookie"))
                store.switchTo("2")
                val replacementCookie = store.snapshot().cookieHeader(api)
                assertFalse(store.isCurrent(before))
                gate.open()
                assertCancelled(read)
                assertEquals(1, h.captures.size)
                assertEquals(1, h.requests.size)
                assertEquals("2", store.snapshot().accountId)
                assertEquals(replacementCookie, store.snapshot().cookieHeader(api))
                store.switchTo("1")
                assertEquals(originalCookie, store.snapshot().cookieHeader(api))
                assertFalse(store.isCurrent(before))
            } finally {
                gate.open()
                read.cancelAndJoin()
            }
        }
    }

    @Test fun accountSwitchBeforeResponseRejectsOldDetailAndCookies() = runBlocking {
        accountSwitchRejectsOldResponse(duringBody = false)
    }

    @Test fun accountSwitchDuringBodyReadRejectsOldDetailAndCookies() = runBlocking {
        accountSwitchRejectsOldResponse(duringBody = true)
    }

    @Test fun sameAccountNewGenerationDuringBodyReadRejectsOldDetailAndCookies() = runBlocking {
        val store = store()
        seedAuthenticated(store, "1")
        val before = store.snapshot()
        val gate = Gate()
        Harness(store) { request, _ -> response(request, "synthetic-stale-generation-response", gate) }.use { h ->
            val read = async { h.repository.feedDetail("42") }
            try {
                gate.awaitEntered()
                seedAuthenticated(store, "1", "renewed")
                val renewed = store.snapshot()
                assertEquals(before.accountId, renewed.accountId)
                assertTrue(renewed.generation > before.generation)
                assertFalse(store.isCurrent(before))
                val renewedCookie = renewed.cookieHeader(api)
                gate.open()
                assertCancelled(read)
                assertEquals(1, h.captures.size)
                assertEquals(1, h.requests.size)
                assertEquals(renewedCookie, store.snapshot().cookieHeader(api))
                assertTrue(renewedCookie.contains("SESSID=synthetic-session-1-renewed"))
            } finally {
                gate.open()
                read.cancelAndJoin()
            }
        }
    }

    private suspend fun externalRotationKeepsReadButRejectsStaleCookie(authenticated: Boolean) = coroutineScope {
        val store = store()
        if (authenticated) seedAuthenticated(store, "1")
        else assertTrue(store.acceptCookies(store.snapshot(), api, listOf(sessionCookie("synthetic-guest-initial"))))
        val before = store.snapshot()
        val originalCookie = before.cookieHeader(api)
        val gate = Gate()
        Harness(store) { request, index ->
            response(request, if (index == 1) "synthetic-stale-response-cookie" else null, if (index == 1) gate else null)
        }.use { h ->
            val read = async { h.repository.feedDetail("42") }
            try {
                gate.awaitEntered()
                assertEquals(originalCookie, h.requests.single().header("Cookie"))
                assertTrue(store.acceptCookies(store.snapshot(), api, listOf(sessionCookie("synthetic-external-rotation"))))
                val rotated = store.snapshot()
                assertEquals(before.accountId, rotated.accountId)
                assertEquals(before.generation, rotated.generation)
                assertTrue(store.isCurrent(before))
                assertTrue(h.captures.single().isCurrent())
                assertFalse(h.captures.single().isSessionCurrent!!.invoke())
                val rotatedCookie = rotated.cookieHeader(api)
                gate.open()

                // Deliberately preserve ordinary browsing behavior: revision change alone does
                // not cancel a generation-current body. The stale response cannot replace cookies.
                assertFullDetail(withTimeout(10_000) { read.await() })
                assertEquals(1, h.captures.size)
                assertEquals(rotatedCookie, store.snapshot().cookieHeader(api))
                assertTrue(rotatedCookie.contains("SESSID=synthetic-external-rotation"))
                assertFalse(rotatedCookie.contains("synthetic-stale-response-cookie"))

                assertFullDetail(h.repository.feedDetail("42"))
                assertEquals(2, h.captures.size)
                assertEquals(2, h.requests.size)
                assertEquals(rotatedCookie, h.requests[1].header("Cookie"))
                assertEquals(rotatedCookie, store.snapshot().cookieHeader(api))
            } finally {
                gate.open()
                read.cancelAndJoin()
            }
        }
    }

    @Test fun guestExternalCookieRotationKeepsReadAndRejectsStaleResponseCookie() = runBlocking {
        externalRotationKeepsReadButRejectsStaleCookie(authenticated = false)
    }

    @Test fun authenticatedExternalCookieRotationKeepsReadAndRejectsStaleResponseCookie() = runBlocking {
        externalRotationKeepsReadButRejectsStaleCookie(authenticated = true)
    }
}
