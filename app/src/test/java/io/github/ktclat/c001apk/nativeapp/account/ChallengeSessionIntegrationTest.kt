package io.github.ktclat.c001apk.nativeapp.account

import io.github.ktclat.c001apk.nativeapp.data.*
import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/** All responses are intercepted in memory. No DNS, live server, real credentials, or writes. */
class ChallengeSessionIntegrationTest {
    private val api = "https://api.coolapk.com/".toHttpUrl()
    private fun store(): AccountStore = AccountStore(object : CredentialPersistence {
        override fun read(): ByteArray? = null
        override fun write(bytes: ByteArray) = Unit
        override fun reset() = Unit
    }).also { store ->
        val cookies = listOf("uid=123; Domain=.coolapk.com; Path=/; Secure", "token=synthetic; Domain=.coolapk.com; Path=/; Secure")
            .map { Cookie.parse(api, it)!! }
        check(store.saveAuthenticated("123", "Synthetic", "test-device", cookies, store.snapshot()))
    }
    @Test fun exactChallengeImageValidationResponsesAdvancePinnedSessionForExplicitReplay() = runBlocking {
        val store = store()
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); requests.add(request)
            val index = requests.size
            val body = when (index) { 1 -> """{"messageStatus":"err_request_captcha"}"""; 2 -> "synthetic-image"; 3 -> """{"data":"验证通过"}"""; else -> """{"data":true}""" }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic")
                .apply { if (index <= 3) addHeader("Set-Cookie", "SESSID=session$index; Path=/; Secure") }
                .body(body.toResponseBody((if (index == 2) "image/png" else "application/json").toMediaType())).build()
        }.build()
        val captured = store.snapshot()
        val transport = CoolapkHttpTransport(client = client, requestSession = { store.requestSessionFor(captured) }).forSingleOperation()
        val form = mapOf("message" to "synthetic")
        val rejection = transport.post("v6/feed/reply", form) as ApiResult.Failure
        val challenge = rejection.challenge!!
        assertFalse(captured.sameIdentityAndSession(store.snapshot()))
        assertTrue(transport.isCaptchaContinuationCurrent(challenge))
        assertTrue(transport.getCaptchaImage() is ApiResult.Success)
        assertTrue(transport.validateGraphicCaptcha("Ab12") is ApiResult.Success)
        assertEquals(3, requests.size) // Verification does not replay the original mutation.
        assertTrue(transport.withGraphicVerification(challenge).post("v6/feed/reply", form) is ApiResult.Success)
        assertEquals(4, requests.size)
        assertFalse(requests[0].header("Cookie")!!.contains("SESSID="))
        for (index in 1..3) assertTrue(requests[index].header("Cookie")!!.contains("SESSID=session$index"))
        assertTrue(requests.all { it.url.host == "api.coolapk.com" && it.url.isHttps })
    }
    @Test fun unrelatedRotationCancelsContinuationBeforeAnotherRequest() = runBlocking {
        val store = store(); var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic")
                .addHeader("Set-Cookie", "SESSID=challenge; Path=/; Secure")
                .body("""{"messageStatus":"err_request_captcha"}""".toResponseBody()).build()
        }.build()
        val captured = store.snapshot()
        val transport = CoolapkHttpTransport(client = client, requestSession = { store.requestSessionFor(captured) }).forSingleOperation()
        val challenge = (transport.post("v6/feed/reply", mapOf("message" to "synthetic")) as ApiResult.Failure).challenge!!
        store.acceptCookies(store.snapshot(), api, listOf("SESSID=unrelated; Path=/; Secure"))
        assertFalse(transport.isCaptchaContinuationCurrent(challenge))
        assertTrue(transport.getCaptchaImage() is ApiResult.Failure)
        assertEquals(1, requests)
    }
    @Test fun guestAdapterPreservesVisibleCaptchaFailureWithoutContinuation() = runBlocking {
        val guest = AccountStore(object : CredentialPersistence {
            override fun read(): ByteArray? = null
            override fun write(bytes: ByteArray) = Unit
            override fun reset() = Unit
        })
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            assertNull(chain.request().header("Cookie"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic")
                .body("""{"messageStatus":"err_request_captcha"}""".toResponseBody()).build()
        }.build()
        val transport = CoolapkHttpTransport(client = client, requestSession = { guest.captureRequestSession() })
        val failure = transport.get("v6/feed/detail", mapOf("id" to "4")) as ApiResult.Failure
        assertEquals(FailureKind.CAPTCHA, failure.kind)
        assertFalse(transport.isCaptchaContinuationCurrent(failure.challenge!!))
        assertTrue(transport.getCaptchaImage() is ApiResult.Failure)
        assertEquals(1, requests)
    }

}
