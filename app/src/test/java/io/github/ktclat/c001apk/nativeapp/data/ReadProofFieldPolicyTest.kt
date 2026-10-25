package io.github.ktclat.c001apk.nativeapp.data

import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class ReadProofFieldPolicyTest {
    @Test fun exactFlutterSyntaxAllowsProofFieldsButRejectsProtectedAndOriginalAliases() {
        listOf("", "_v2_post_token", "_captcha_token", "_readProof9", "_" + "x".repeat(63)).forEach { assertTrue(it, ReadProofFieldPolicy.allows(it, emptyList())) }
        listOf("_", "__", "___", "_route", "_redirect", "_port", "_scheme", "_credentials", "_" + "x".repeat(64), "captcha", "_with space", "_bad-name", "_é", "_UID", "_first_item", "_lastItem", "_page", "_i_d", "_token", "_Cookie", "_SESSID", "_X_App_Device", "_x_app_token", "_deviceId", "_signature", "_authorization", "_url", "_method", "_validate").forEach { assertFalse(it, ReadProofFieldPolicy.allows(it, emptyList())) }
        // Exhaust the actual protocol header names, rather than a hand-picked partial matrix.
        ClientProtocol("synthetic-field-policy-device", clockSeconds = { 1_700_000_000 }).headers().keys.forEach { header ->
            assertFalse(header, ReadProofFieldPolicy.allows("_" + header.replace('-', '_'), emptyList()))
        }
        assertFalse(ReadProofFieldPolicy.allows("_custom_argument", listOf("customArgument")))
        assertFalse(ReadProofFieldPolicy.allows("_captcha_token", listOf("_captcha_token")))
        assertFalse(ReadProofFieldPolicy.allows("_captcha_token", listOf("captcha-token")))
    }
    @Test fun alternateProofFieldKeepsExactReadTargetAndCannotBecomeCookieHeaderOrAnotherOperation() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val session = RequestSession(cookieFor = { null }, isCurrent = { true }, acceptCookies = { _, _ -> },
                deviceCode = "synthetic-field-device", isGuest = true, isReadSessionCurrent = { true })
            val transport = CoolapkHttpTransport(endpoints = ApiEndpoints(server.url("/"), server.url("/")))
                .forCapturedReadSession(session)
            server.enqueue(MockResponse().setBody("""{"messageStatus":"err_request_captcha_v2","messageExtra":{"captchaId":"abcdefghijklmnop","captchaField":"_captcha_token"}}"""))
            val original = mapOf("id" to "42", "page" to "2", "firstItem" to "8", "lastItem" to "7")
            val failure = transport.get("v6/collection/itemList", original) as ApiResult.Failure
            val challenge = requireNotNull(failure.challenge)
            assertTrue(transport.isCaptchaContinuationCurrent(challenge))
            val proof = "NEC:abcdefgh:synthetic-field-proof"
            val replay = transport.withCaptchaProof(challenge, proof)
            server.enqueue(MockResponse().setBody("{\"data\":[]}"))
            assertTrue(replay.get("v6/collection/itemList", original) is ApiResult.Success)
            assertTrue(replay.get("v6/collection/itemList", original) is ApiResult.Failure)
            server.takeRequest(); val verified = server.takeRequest()
            assertEquals("GET", verified.method); assertEquals("/v6/collection/itemList", verified.requestUrl!!.encodedPath)
            original.forEach { (key, value) -> assertEquals(value, verified.requestUrl!!.queryParameter(key)) }
            assertEquals(proof, verified.requestUrl!!.queryParameter("_captcha_token"))
            assertNull(verified.getHeader("_captcha_token")); assertNull(verified.getHeader("Cookie")); assertEquals(2, server.requestCount)
        }
    }
    @Test fun changedTargetIsRejectedWithoutDispatchAndCannotReuseTheAttempt() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val transport = CoolapkHttpTransport(endpoints = ApiEndpoints(server.url("/"), server.url("/")))
                .forCapturedReadSession(RequestSession({ null }, { true }, { _, _ -> }, deviceCode = "synthetic-field-device", isGuest = true, isReadSessionCurrent = { true }))
            server.enqueue(MockResponse().setBody("""{"messageStatus":"err_request_captcha_v2","messageExtra":{"captchaId":"abcdefghijklmnop","captchaField":"_readProof9"}}"""))
            val original = mapOf("id" to "42", "page" to "2")
            val failure = transport.get("v6/collection/itemList", original) as ApiResult.Failure
            val replay = transport.withCaptchaProof(requireNotNull(failure.challenge), "NEC:abcdefgh:synthetic-field-proof")
            assertTrue(replay.get("v6/collection/itemList", original + ("id" to "99")) is ApiResult.Failure)
            assertTrue(replay.get("v6/collection/itemList", original) is ApiResult.Failure)
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun normalizedOriginalCollisionNeverCreatesAReadContinuation() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val transport = CoolapkHttpTransport(endpoints = ApiEndpoints(server.url("/"), server.url("/")))
                .forCapturedReadSession(RequestSession({ null }, { true }, { _, _ -> }, deviceCode = "synthetic-field-device", isGuest = true, isReadSessionCurrent = { true }))
            server.enqueue(MockResponse().setBody("""{"messageStatus":"err_request_captcha_v2","messageExtra":{"captchaId":"abcdefghijklmnop","captchaField":"_custom_argument"}}"""))
            val failure = transport.get("v6/main/indexV8", mapOf("customArgument" to "immutable")) as ApiResult.Failure
            assertFalse(transport.isCaptchaContinuationCurrent(requireNotNull(failure.challenge)))
            assertEquals(1, server.requestCount)
        }
    }
}
