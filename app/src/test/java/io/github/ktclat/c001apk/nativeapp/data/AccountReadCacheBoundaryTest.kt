// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

import io.github.ktclat.c001apk.nativeapp.domain.*
import io.github.ktclat.c001apk.nativeapp.social.NotificationReadAttempt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

/** Deterministic cancellation exactly after helper checks and before the guarded memory mutation. */
class AccountReadCacheBoundaryTest {
    private val proof = "NEC:abcdefgh:synthetic-boundary-proof"
    private val challenge = """{"messageStatus":"err_request_captcha_v2","messageExtra":{"captchaId":"abcdefghijklmnop","captchaField":""}}"""
    private fun raw() = RequestSession(cookieFor = { "uid=123; token=synthetic; SESSID=original" },
        isCurrent = { true }, acceptCookies = { _, _ -> error("Verification must not persist cookies") },
        deviceCode = "synthetic-boundary-device", isSessionCurrent = { true }, isReadSessionCurrent = { true },
        readOwner = Any(), diagnosticGeneration = 1)
    @Test fun cancellationAtPublishBoundaryPreventsSameVersionProofCache() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val source = raw(); val memory = ReadSessionMemory(); val attempt = NotificationReadAttempt()
            val transport = CoolapkHttpTransport(endpoints = ApiEndpoints(server.url("/"), server.url("/")))
            var pending: PendingRead<JsonElement>? = null; var mutations = 0
            server.enqueue(MockResponse().setBody(challenge))
            accountReadContinuation("123", attempt::isCurrent, { source }, transport, memory,
                cacheMutation = { action ->
                    if (++mutations == 3) attempt.cancel() // After the final current() check.
                    attempt.mutateCache(action)
                }, attach = { failure, value -> pending = value; failure }) { it.get("v6/notification/checkCount") }
            val cap = requireNotNull(pending); assertTrue(cap.acceptHumanProof(proof))
            server.enqueue(MockResponse().setBody("{\"data\":{\"atme\":1}}"))
            try { cap.retryOnce(); fail("Cancelled cache commit succeeded") } catch (_: CancellationException) { }
            assertEquals(3, mutations); assertEquals(2, server.requestCount)
            assertNull(memory.capture(source).session.readValidationCookie!!.invoke())
            assertEquals(DetailVerificationState.CLOSED, cap.state.value); assertTrue(cap.retryOnce() is ApiResult.Failure)
        }
    }
    @Test fun cancellationAtRevocationBoundaryCannotEraseSameVersionAcceptedMemory() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val source = raw(); val memory = ReadSessionMemory(); val attempt = NotificationReadAttempt()
            val initial = memory.capture(source); assertTrue(memory.publish(initial, emptyList(), proof))
            val accepted = memory.capture(source).session.readValidationCookie!!.invoke(); assertNotNull(accepted)
            val transport = CoolapkHttpTransport(endpoints = ApiEndpoints(server.url("/"), server.url("/")))
            var pending: PendingRead<JsonElement>? = null; var mutations = 0
            server.enqueue(MockResponse().setBody(challenge))
            val rejected = accountReadContinuation("123", attempt::isCurrent, { source }, transport, memory,
                cacheMutation = { action -> if (++mutations == 2) attempt.cancel(); attempt.mutateCache(action) },
                attach = { failure, value -> pending = value; failure }) { it.get("v6/notification/checkCount") }
            assertTrue(rejected is ApiResult.Failure); assertNull(pending)
            assertEquals(2, mutations); assertEquals(1, server.requestCount)
            assertEquals(accepted, memory.capture(source).session.readValidationCookie!!.invoke())
        }
    }
    @Test fun cancelledOrdinaryAcceptedReadCannotRotateSessionOrClearValidation() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val source = raw(); val memory = ReadSessionMemory(); val attempt = NotificationReadAttempt()
            val cookie = Cookie.Builder().name("SESSID").value("accepted").hostOnlyDomain(server.hostName).path("/").build()
            assertTrue(memory.publish(memory.capture(source), listOf(cookie), proof))
            val accepted = memory.capture(source).session.readValidationCookie!!.invoke()
            val transport = CoolapkHttpTransport(endpoints = ApiEndpoints(server.url("/"), server.url("/")))
            var mutations = 0
            server.enqueue(MockResponse().setBody("{\"data\":{\"atme\":1}}").addHeader("Set-Cookie", "SESSID=late-rotation; Path=/"))
            try {
                accountReadContinuation("123", attempt::isCurrent, { source }, transport, memory,
                    cacheMutation = { action -> if (++mutations == 2) attempt.cancel(); attempt.mutateCache(action) },
                    attach = { failure, _ -> failure }) { it.get("v6/notification/checkCount") }
                fail("Cancelled ordinary read published")
            } catch (_: CancellationException) { }
            assertEquals(2, mutations); assertEquals(1, server.requestCount)
            val retained = memory.capture(source).session
            assertEquals(accepted, retained.readValidationCookie!!.invoke())
            assertTrue(retained.cookieFor(server.url("/")).orEmpty().contains("SESSID=accepted"))
            assertFalse(retained.cookieFor(server.url("/")).orEmpty().contains("late-rotation"))
        }
    }
    @Test fun cancelledCaptureCannotReplaceAnotherCurrentBinding() {
        val memory = ReadSessionMemory(); val newer = raw(); val older = raw(); val attempt = NotificationReadAttempt()
        assertTrue(memory.publish(memory.capture(newer), emptyList(), proof))
        val accepted = memory.capture(newer).session.readValidationCookie!!.invoke()
        try {
            memory.capture(older) { action -> attempt.cancel(); attempt.mutateCache(action) }
            fail("Cancelled capture replaced the binding")
        } catch (_: CancellationException) { }
        assertEquals(accepted, memory.capture(newer).session.readValidationCookie!!.invoke())
    }
    @Test fun identityCallbacksAndClocksNeverRunUnderAttemptMonitor() {
        val attempt = NotificationReadAttempt()
        fun outside(): Boolean { assertFalse("Foreign identity callback under attempt monitor", Thread.holdsLock(attempt)); return true }
        val source = raw().copy(isCurrent = ::outside, isSessionCurrent = ::outside, isReadSessionCurrent = ::outside)
        val memory = ReadSessionMemory(clock = { outside(); 100L }, wallClock = { outside(); 100L })
        val captured = memory.capture(source, attempt::mutateCache)
        assertTrue(memory.revokeOnChallenge(captured))
        val url = "https://api.coolapk.com/".toHttpUrl()
        val cookie = Cookie.Builder().name("SESSID").value("accepted").domain("coolapk.com").path("/").secure().build()
        assertTrue(memory.publish(captured, listOf(cookie), proof))
        memory.capture(source, attempt::mutateCache).session.acceptCookies(url,
            listOf("SESSID=rotated; Domain=coolapk.com; Path=/; Secure"))
    }

}
