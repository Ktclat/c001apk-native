package io.github.ktclat.c001apk.nativeapp.account

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/** Interceptor fixtures are entirely local. No real service or credentials are contacted. */
class HttpLoginTransportTest {
    @Test fun requestHashRequiresQuotedBodyAttribute() {
        assertEquals("synthetic", HttpLoginTransport.extractRequestHash("<BODY data-request-hash='synthetic'>"))
        assertNull(HttpLoginTransport.extractRequestHash("<script>data-request-hash='bad'</script>"))
        assertNull(HttpLoginTransport.extractRequestHash("<body data-request-hash=bad>"))
    }
    @Test fun preparedSessionFlowsIntoSinglePostWithoutPersistingPassword() = runTest {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); requests.add(request)
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            when (requests.size) {
                1 -> response.addHeader("Set-Cookie", "SESSID=synthetic-session; Path=/; Secure").body("fixture".toResponseBody())
                2 -> response.body("<body data-request-hash='synthetic-hash'>".toResponseBody())
                else -> response.addHeader("Set-Cookie", "uid=123; Domain=coolapk.com; Path=/; Secure")
                    .addHeader("Set-Cookie", "username=synthetic; Domain=coolapk.com; Path=/; Secure")
                    .addHeader("Set-Cookie", "token=synthetic-token; Domain=coolapk.com; Path=/; Secure")
                    .body("{\"status\":1}".toResponseBody())
            }.build()
        }.build()
        val transport = HttpLoginTransport(client)
        val preparation = transport.prepare()
        val result = transport.submit(preparation, "synthetic-user", "synthetic-password".toCharArray(), "", null)
        assertTrue(result is LoginReply.Authenticated)
        assertEquals(3, requests.size)
        assertTrue(requests[1].header("Cookie")!!.contains("SESSID=synthetic-session"))
        val form = requests[2].body as FormBody
        val values = (0 until form.size).associate { form.name(it) to form.value(it) }
        assertEquals("synthetic-hash", values["requestHash"])
        assertEquals("synthetic-password", values["password"])
        assertEquals("POST", requests[2].method)
        transport.clear()
        try { transport.submit(preparation, "test", charArrayOf('x'), "", null); fail() } catch (_: IllegalStateException) { }
    }
    @Test fun redirectIsNotFollowedAndNoPostIsReplayed() = runTest {
        var count = 0
        val transport = HttpLoginTransport(OkHttpClient.Builder().addInterceptor { chain ->
            count++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("redirect")
                .addHeader("Location", "https://untrusted.example/").body("".toResponseBody()).build()
        }.build())
        try { transport.prepare(); fail() } catch (_: IllegalStateException) { }
        assertEquals(1, count)
    }
    @Test fun accountChallengeRequiresItsOwnExplicitConfiguration() {
        val explicit = Json.parseToJsonElement("""{"captchaId":"1234567890abcdef","captchaField":"_account_proof"}""").jsonObject
        assertEquals("_account_proof", HttpLoginTransport.explicitAccountChallenge(explicit)?.field)
        val unsupported = Json.parseToJsonElement("""{"messageStatus":"err_request_captcha_v2"}""").jsonObject
        assertNull(HttpLoginTransport.explicitAccountChallenge(unsupported))
        val absentField = Json.parseToJsonElement("""{"captchaId":"1234567890abcdef"}""").jsonObject
        assertNull(HttpLoginTransport.explicitAccountChallenge(absentField))
        for (field in listOf("", " ")) {
            val blankField = Json.parseToJsonElement("""{"captchaId":"1234567890abcdef","captchaField":"$field"}""").jsonObject
            assertNull(HttpLoginTransport.explicitAccountChallenge(blankField))
        }
    }
}
