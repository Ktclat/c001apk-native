package io.github.ktclat.c001apk.nativeapp.account

import io.github.ktclat.c001apk.nativeapp.data.*
import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/** In-memory application interceptor returns before DNS/socket use. All values are synthetic. */
class GuestSessionTransportTest {
    private fun store() = AccountStore(object : CredentialPersistence {
        override fun read(): ByteArray? = null
        override fun write(bytes: ByteArray) = Unit
        override fun reset() = Unit
    }, guestDeviceId = "synthetic-stable-guest")
    private fun response(request: Request, body: String, cookie: String? = null, code: Int = 200) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Synthetic")
            .apply { cookie?.let { addHeader("Set-Cookie", it) } }.body(body.toResponseBody()).build()

    @Test fun firstPublicGetSucceedsAndNextGetUsesBoundGuestSession() = runBlocking {
        val store = store(); val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.add(chain.request())
            response(chain.request(), """{"data":true}""", "SESSID=synthetic-guest; Path=/; Secure")
        }.build()
        val transport = CoolapkHttpTransport(client = client, requestSession = { store.captureRequestSession() })
        assertTrue(transport.get("v6/feed/detail", mapOf("id" to "4")) is ApiResult.Success)
        assertTrue(transport.get("v6/feed/detail", mapOf("id" to "5")) is ApiResult.Success)
        assertEquals(2, requests.size)
        assertNull(requests.first().header("Cookie"))
        assertEquals("SESSID=synthetic-guest", requests.last().header("Cookie"))
        assertEquals(requests.first().header("X-App-Device"), requests.last().header("X-App-Device"))
        assertNotNull(requests.last().header("X-App-Device"))
        assertNull(store.snapshot().accountId)
    }
    @Test fun cookieBearingGuestCaptchaRemainsVisibleWithoutContinuation() = runBlocking {
        val store = store(); var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            if (requests == 1) response(chain.request(), """{"data":true}""", "SESSID=synthetic-guest; Path=/; Secure")
            else response(chain.request(), """{"messageStatus":"err_request_captcha"}""")
        }.build()
        val transport = CoolapkHttpTransport(client = client, requestSession = { store.captureRequestSession() })
        assertTrue(transport.get("v6/feed/detail") is ApiResult.Success)
        val result = transport.get("v6/feed/detail") as ApiResult.Failure
        assertEquals(FailureKind.CAPTCHA, result.kind)
        assertFalse(transport.isCaptchaContinuationCurrent(result.challenge!!))
        assertTrue(transport.getCaptchaImage() is ApiResult.Failure)
        assertEquals(2, requests)
    }
    @Test fun generationChangedDuringResponseRejectsBodyAndCookie() = runBlocking {
        val store = store()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            store.useGuest()
            response(chain.request(), """{"data":true}""", "SESSID=synthetic-late; Path=/; Secure")
        }.build()
        val transport = CoolapkHttpTransport(client = client, requestSession = { store.captureRequestSession() })
        try { transport.get("v6/feed/detail"); fail("Stale response was not cancelled") }
        catch (_: CancellationException) { }
        assertTrue(store.snapshot().cookies.isEmpty())
    }
    @Test fun http403IsStillVisibleAndDoesNotPretendToBeFixed() = runBlocking {
        val store = store()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain.request(), "Synthetic forbidden", "SESSID=synthetic-denied; Path=/; Secure", 403)
        }.build()
        val result = CoolapkHttpTransport(client = client, requestSession = { store.captureRequestSession() }).get("v6/feed/detail") as ApiResult.Failure
        assertEquals(FailureKind.HTTP, result.kind)
        assertTrue(store.snapshot().cookies.isEmpty())
    }
}
