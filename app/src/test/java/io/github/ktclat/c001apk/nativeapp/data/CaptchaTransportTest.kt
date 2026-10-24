// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import okhttp3.ResponseBody.Companion.toResponseBody

class CaptchaTransportTest {
    private fun transport(server: MockWebServer, session: (() -> RequestSession)? = null) = CoolapkHttpTransport(
        endpoints = ApiEndpoints(server.url("/"), server.url("/")), protocol = ClientProtocol("synthetic-device", { 1770000000 }), requestSession = session)
    private fun challenge(field: String = "_v2_post_token") = """{"messageStatus":"err_request_captcha_v2","messageExtra":{"captchaId":"abcdefghijklmnop","captchaField":"$field"}}"""
    @Test fun exactMarkersOnlyAndSourceDefaults() {
        val graphic = runCatching { ApiDecoder.envelope("""{"messageStatus":"err_request_captcha"}""") }.exceptionOrNull() as ApiProblem
        assertTrue(graphic.failure.challenge is ApiChallenge.Graphic)
        val generic = runCatching { ApiDecoder.envelope("""{"code":9,"message":"captcha required"}""") }.exceptionOrNull() as ApiProblem
        assertNull(generic.failure.challenge)
        val fallback = runCatching { ApiDecoder.envelope("""{"messageStatus":"err_request_captcha_v2","extra":"bad-json"}""") }.exceptionOrNull() as ApiProblem
        assertEquals("414e5c9b866a03db03f860a1a9101672", (fallback.failure.challenge as ApiChallenge.Netease).captchaId)
    }
    @Test fun exactRejectedFormGetsOneProofAndOneReplay() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody(challenge())); server.enqueue(MockResponse().setBody("{\"data\":true}"))
            val transport = transport(server)
            val failure = transport.post("v6/feed/reply", mapOf("message" to "synthetic"), mapOf("id" to "4")) as ApiResult.Failure
            val bound = failure.challenge as ApiChallenge.Netease
            assertTrue(bound.operationId.isNotEmpty())
            val replay = transport.withCaptchaProof(bound, "NEC:abcdefgh:human-proof")
            assertTrue(replay.post("v6/feed/reply", mapOf("message" to "synthetic"), mapOf("id" to "4")) is ApiResult.Success)
            server.takeRequest()
            val retry = server.takeRequest()
            assertTrue(retry.body.readUtf8().contains("_v2_post_token=NEC%3Aabcdefgh%3Ahuman-proof"))
            assertNull(retry.getHeader("Cookie"))
            assertTrue(replay.post("v6/feed/reply", mapOf("message" to "synthetic"), mapOf("id" to "4")) is ApiResult.Failure)
            assertEquals(2, server.requestCount)
            assertTrue(runCatching { transport.withCaptchaProof(bound, "NEC:abcdefgh:another") }.isFailure)
        }
    }
    @Test fun wrongOperationCannotConsumeProofOnAnotherEndpoint() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody(challenge()))
            val transport = transport(server)
            val bound = (transport.post("v6/feed/reply", mapOf("message" to "original")) as ApiResult.Failure).challenge!!
            val replay = transport.withCaptchaProof(bound, "NEC:abcdefgh:proof")
            assertTrue(replay.post("v6/feed/createFeed", mapOf("message" to "original")) is ApiResult.Failure)
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun blankFieldSelectsOnlySingleRequestValidateCookie() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody(challenge(""))); server.enqueue(MockResponse().setBody("{\"data\":[]}")); server.enqueue(MockResponse().setBody("{\"data\":[]}"))
            val transport = transport(server)
            val bound = (transport.get("v6/feed/detail", mapOf("id" to "4")) as ApiResult.Failure).challenge!!
            val replay = transport.withCaptchaProof(bound, "NEC:abcdefgh:proof")
            replay.get("v6/feed/detail", mapOf("id" to "4")); transport.get("v6/feed/detail", mapOf("id" to "5"))
            server.takeRequest()
            assertEquals("validate=NEC%3Aabcdefgh%3Aproof", server.takeRequest().getHeader("Cookie"))
            assertNull(server.takeRequest().getHeader("Cookie"))
        }
    }
    @Test fun originalSessionRotationRejectsProofBeforeNetwork() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody(challenge()))
            var current = true
            val transport = transport(server) { RequestSession({"uid=synthetic; token=fixture"},{true},{_,_->},"device",{current}) }
            val bound = (transport.post("v6/feed/reply", mapOf("message" to "original")) as ApiResult.Failure).challenge!!
            val replay = transport.withCaptchaProof(bound, "NEC:abcdefgh:proof")
            current = false
            assertTrue(replay.post("v6/feed/reply", mapOf("message" to "original")) is ApiResult.Failure)
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun graphicRequiresExactServerSuccessAndExplicitReplay() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("{\"messageStatus\":\"err_request_captcha\"}"))
            val transport = transport(server)
            val bound = (transport.post("v6/feed/reply", mapOf("message" to "original")) as ApiResult.Failure).challenge!!
            assertTrue(runCatching { transport.withGraphicVerification(bound) }.isFailure)
            server.enqueue(MockResponse().setHeader("Content-Type","image/png").setBody("synthetic-image-bytes"))
            assertTrue(transport.getCaptchaImage() is ApiResult.Success)
            server.takeRequest(); val image = server.takeRequest().requestUrl!!
            assertEquals("270", image.queryParameter("w")); assertEquals("113",image.queryParameter("h"))
            server.enqueue(MockResponse().setBody("{\"data\":\"验证通过\"}"))
            assertTrue(transport.validateGraphicCaptcha("Ab12") is ApiResult.Success)
            val validate = server.takeRequest()
            val body = validate.body.readUtf8()
            assertTrue(body.contains("name=\"code\"")); assertTrue(body.contains("Ab12")); assertFalse(body.contains("NEC:"))
            server.enqueue(MockResponse().setBody("{\"data\":true}"))
            assertTrue(transport.withGraphicVerification(bound).post("v6/feed/reply", mapOf("message" to "original")) is ApiResult.Success)
            assertEquals(4,server.requestCount)
        }
    }
    @Test fun graphicRejectsHtmlOversizeAndInvalidCodeWithoutReplay() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("{\"messageStatus\":\"err_request_captcha\"}"))
            val transport = transport(server)
            val bound = (transport.post("v6/feed/reply", mapOf("message" to "original")) as ApiResult.Failure).challenge!!
            server.enqueue(MockResponse().setHeader("Content-Type","text/html").setBody("<script>not image</script>"))
            assertTrue(transport.getCaptchaImage() is ApiResult.Failure)
            server.enqueue(MockResponse().setHeader("Content-Type","image/png").setBody("x".repeat(1024*1024+1)))
            assertTrue(transport.getCaptchaImage() is ApiResult.Failure)
            assertTrue(transport.validateGraphicCaptcha("bad") is ApiResult.Failure)
            server.enqueue(MockResponse().setBody("{\"data\":true}"))
            assertTrue(transport.validateGraphicCaptcha("abcd") is ApiResult.Failure)
            assertTrue(runCatching { transport.withGraphicVerification(bound) }.isFailure)
        }
    }
    @Test fun proofExpiresAndRequiresSessionBindingForAccountCookies() = runBlocking {
        MockWebServer().use { server ->
            server.start(); var clock = 0L
            val transport = CoolapkHttpTransport(endpoints = ApiEndpoints(server.url("/"),server.url("/")), protocol = ClientProtocol("synthetic", {1770000000}), monotonicMillis = {clock})
            server.enqueue(MockResponse().setBody(challenge()))
            val bound = (transport.get("v6/feed/detail",mapOf("id" to "1")) as ApiResult.Failure).challenge!!
            clock = 300001
            assertTrue(runCatching { transport.withCaptchaProof(bound,"NEC:abcdefgh:proof") }.isFailure)
            val unbound = transport(server) { RequestSession({"uid=fixture"},{true},{_,_->},"device") }
            server.enqueue(MockResponse().setBody(challenge()))
            val unsupported = unbound.get("v6/feed/detail",mapOf("id" to "1")) as ApiResult.Failure
            assertEquals(FailureKind.CAPTCHA, unsupported.kind)
            assertFalse(unbound.isCaptchaContinuationCurrent(unsupported.challenge!!))
            assertEquals(2,server.requestCount)
        }
    }
    @Test fun getProofIsQueryOnlyAndRepeatedChallengeNeverAutoRetries() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody(challenge())); server.enqueue(MockResponse().setBody(challenge()))
            val transport = transport(server)
            val bound = (transport.get("v6/feed/detail",mapOf("id" to "1")) as ApiResult.Failure).challenge!!
            val replay = transport.withCaptchaProof(bound,"NEC:abcdefgh:proof")
            assertTrue(replay.get("v6/feed/detail",mapOf("id" to "1")) is ApiResult.Failure)
            server.takeRequest(); val request = server.takeRequest()
            assertEquals("NEC:abcdefgh:proof",request.requestUrl!!.queryParameter("_v2_post_token"))
            assertEquals(2,server.requestCount)
            assertTrue(runCatching { replay.withCaptchaProof(bound,"NEC:abcdefgh:proof") }.isFailure)
        }
    }
    @Test fun sharedTransportOrdinaryReadsNeverStayPinnedAfterChallengeOrSwitch() = runBlocking {
        MockWebServer().use { server ->
            server.start(); var generation = 1; var cookie = "uid=1; token=synthetic; SESSID=initial"
            fun capture(): RequestSession {
                val expectedGeneration = generation; val expectedCookie = cookie
                return RequestSession({expectedCookie},{generation == expectedGeneration},{_,_->},"device-$expectedGeneration",
                    {generation == expectedGeneration && cookie == expectedCookie},
                    {_,_-> cookie = "uid=1; token=synthetic; SESSID=challenge"; capture()})
            }
            val shared = transport(server) { capture() }
            server.enqueue(MockResponse().setBody(challenge()))
            assertTrue(shared.get("v6/feed/detail",mapOf("id" to "1")) is ApiResult.Failure)
            cookie = "uid=1; token=synthetic; SESSID=ordinary-fresh"
            server.enqueue(MockResponse().setBody("{\"data\":[]}"))
            assertTrue(shared.get("v6/feed/detail",mapOf("id" to "2")) is ApiResult.Success)
            generation = 2; cookie = "uid=2; token=other-synthetic"
            server.enqueue(MockResponse().setBody("{\"data\":[]}"))
            assertTrue(shared.get("v6/feed/detail",mapOf("id" to "3")) is ApiResult.Success)
            server.takeRequest()
            assertEquals("uid=1; token=synthetic; SESSID=ordinary-fresh",server.takeRequest().getHeader("Cookie"))
            assertEquals("uid=2; token=other-synthetic",server.takeRequest().getHeader("Cookie"))
        }
    }
    @Test fun proofExpiryAfterWriteWasSentIsUncertainNotSafeToRetry() = runBlocking {
        var clock = 0L; var calls = 0
        val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            val body = if (calls == 1) challenge() else { clock = 300001; "{\"data\":1}" }
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        val transport = CoolapkHttpTransport(client = client, protocol = ClientProtocol("synthetic", {1770000000}), monotonicMillis = {clock})
        val bound = (transport.post("v6/feed/like",mapOf("id" to "1")) as ApiResult.Failure).challenge!!
        clock = 299999
        val replay = transport.withCaptchaProof(bound,"NEC:abcdefgh:proof")
        assertEquals(FailureKind.UNCERTAIN,(replay.post("v6/feed/like",mapOf("id" to "1")) as ApiResult.Failure).kind)
        assertEquals(2,calls)
    }
    @Test fun localCookieFailureAfterSuccessfulWriteRemainsUncertain() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("{\"data\":1}"))
            val transport = transport(server) { RequestSession({"uid=fixture"},{true},{_,_-> throw IllegalArgumentException("synthetic bounded-store failure")},"device",{true}) }
            assertEquals(FailureKind.UNCERTAIN,(transport.post("v6/feed/like",mapOf("id" to "1")) as ApiResult.Failure).kind)
            assertEquals(1,server.requestCount)
        }
    }
    @Test fun malformedOrOtherChallengeProofNeverLeavesClient() {
        val challenge = ApiChallenge.Netease("abcdefghijklmnop", "")
        assertFalse(challenge.acceptsProof("NEC:wrong-id:proof"))
        assertFalse(challenge.acceptsProof("NEC:abcdefgh:bad\r\nCookie:x"))
        assertFalse(challenge.acceptsProof("NEC:abcdefgh:"))
    }
}
