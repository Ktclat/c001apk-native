// SPDX-License-Identifier: AGPL-3.0-only
// Narrow apk/detail behavior adapted from Ktclat/c001apk fb3e06c api_failure.dart.
package io.github.ktclat.c001apk.nativeapp.data

import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

/** Loopback-only regression fixtures. Never fetch public pages or contact the provider. */
class DetailFailureContractTest {
    private val unavailable = "该应用未在酷安上架"
    private fun transport(server: MockWebServer, session: RequestSession? = null) = CoolapkHttpTransport(
        endpoints = ApiEndpoints(server.url("/"), server.url("/")),
        protocol = ClientProtocol("synthetic-detail-device", { 1770000000 }),
        requestSession = session?.let { { it } },
    )

    @Test fun unavailableAppRequiresExactEndpointStatusAndKnownMessage() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val transport = transport(server)
            data class Case(val path: String, val status: String, val message: String, val expected: String)
            val generic = failure(FailureKind.BUSINESS).message
            val cases = listOf(
                Case("v6/apk/detail", "-2", "应用不存在", unavailable),
                Case("v6/apk/detail", "\"-2\"", "应用未上架", unavailable),
                Case("v6/feed/detail", "-2", "应用不存在", generic),
                Case("v6/apk/detailExtra", "-2", "应用不存在", generic),
                Case("v6/apk/detail/other", "-2", "应用不存在", generic),
                Case("v6/apk/detail", "-3", "应用不存在", generic),
                Case("v6/apk/detail", "-2", "unknown private server explanation", generic),
            )
            cases.forEach { case ->
                server.enqueue(MockResponse().setBody("""{"status":${case.status},"message":"${case.message}"}"""))
                val result = transport.get(case.path) as ApiResult.Failure
                assertEquals(case.toString(), FailureKind.BUSINESS, result.kind)
                assertEquals(case.toString(), case.expected, result.message)
                assertNull(result.challenge)
                assertEquals("/${case.path}", server.takeRequest().requestUrl!!.encodedPath)
            }
            assertEquals(cases.size, server.requestCount)
        }
    }

    @Test fun appStatusDoesNotOverrideExistingAccessFailureClasses() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val transport = transport(server)
            val cases = listOf(
                """"message":"应用不存在，网络环境异常"""" to FailureKind.RISK_CONTROL,
                """"message":"应用不存在","messageStatus":"err_request_need_upgrade_new_version"""" to FailureKind.CLIENT_UPGRADE,
                """"message":"应用不存在","messageStatus":"err_request_captcha"""" to FailureKind.CAPTCHA,
                """"message":"应用不存在，需要登录"""" to FailureKind.AUTHENTICATION,
                """"message":"应用不存在","code":401""" to FailureKind.AUTHENTICATION,
                """"message":"应用不存在，请求频繁"""" to FailureKind.RATE_LIMIT,
                """"message":"应用不存在","code":429""" to FailureKind.RATE_LIMIT,
            )
            cases.forEach { (fields, kind) ->
                server.enqueue(MockResponse().setBody("""{"status":-2,$fields}"""))
                val result = transport.get("v6/apk/detail") as ApiResult.Failure
                assertEquals(fields, kind, result.kind)
                assertEquals(failure(kind).message, result.message)
            }
            assertEquals(cases.size, server.requestCount)
        }
    }

    @Test fun failedEnvelopesDoNotAcceptCookiesOrEnableGuestContinuation() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            var accepted = 0
            val session = RequestSession(
                cookieFor = { "SESSID=synthetic" }, isCurrent = { true },
                acceptCookies = { _, _ -> accepted++ }, deviceCode = "synthetic-detail-device",
                isSessionCurrent = { true }, acceptChallengeCookies = { _, _ -> accepted++; null }, isGuest = true,
            )
            val transport = transport(server, session)
            server.enqueue(MockResponse().addHeader("Set-Cookie", "SESSID=changed")
                .setBody("""{"status":-2,"message":"应用不存在"}"""))
            assertEquals(unavailable, (transport.get("v6/apk/detail") as ApiResult.Failure).message)
            server.enqueue(MockResponse().addHeader("Set-Cookie", "SESSID=changed")
                .setBody("""{"status":-2,"message":"应用不存在","messageStatus":"err_request_captcha"}"""))
            val captcha = transport.get("v6/apk/detail") as ApiResult.Failure
            assertEquals(FailureKind.CAPTCHA, captcha.kind)
            assertNotNull(captcha.challenge)
            assertFalse(transport.isCaptchaContinuationCurrent(captcha.challenge!!))
            assertEquals(0, accepted)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun httpMalformedAndOversizedResponsesKeepTheirExistingFailures() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val transport = transport(server)
            val cases = listOf(
                Triple(401, """{"status":-2,"message":"应用不存在"}""", FailureKind.AUTHENTICATION),
                Triple(429, """{"status":-2,"message":"应用不存在"}""", FailureKind.RATE_LIMIT),
                Triple(403, """{"status":-2,"message":"应用不存在"}""", FailureKind.HTTP),
                Triple(200, "<html>应用不存在</html>", FailureKind.INVALID_RESPONSE),
                Triple(200, """{"status":-2,"message":"应用不存在","data": }""", FailureKind.INVALID_RESPONSE),
                Triple(200, "x".repeat(4 * 1024 * 1024 + 1), FailureKind.INVALID_RESPONSE),
                Triple(200, """{"status":-2,"message":"应用不存在","data":""" + "[".repeat(1000) + "0" + "]".repeat(1000) + "}", FailureKind.INVALID_RESPONSE),
            )
            cases.forEach { (status, body, kind) ->
                server.enqueue(MockResponse().setResponseCode(status).setBody(body))
                val result = transport.get("v6/apk/detail") as ApiResult.Failure
                assertEquals(kind, result.kind)
                assertNotEquals(unavailable, result.message)
            }
            assertEquals(cases.size, server.requestCount)
        }
    }

    @Test fun repositorySurfacesAppMessageAndPreservesFeedAccessFailureWithoutFallback() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val repository = HttpCoolapkRepository(
                endpoints = ApiEndpoints(server.url("/"), server.url("/")),
                protocol = ClientProtocol("synthetic-detail-device", { 1770000000 }),
            )
            server.enqueue(MockResponse().setBody("""{"status":-2,"message":"应用不存在"}"""))
            assertEquals(unavailable, (repository.appDetail("com.example.app") as ApiResult.Failure).message)
            assertEquals("/v6/apk/detail", server.takeRequest().requestUrl!!.encodedPath)
            listOf("err_login" to FailureKind.AUTHENTICATION, "err_request_captcha" to FailureKind.CAPTCHA).forEach { (marker, kind) ->
                server.enqueue(MockResponse().setBody("""{"messageStatus":"$marker"}"""))
                val result = repository.feedDetail("123") as ApiResult.Failure
                assertEquals(kind, result.kind)
                assertEquals(failure(kind).message, result.message)
                assertEquals("/v6/feed/detail", server.takeRequest().requestUrl!!.encodedPath)
            }
            assertEquals(3, server.requestCount)
        }
    }
}
