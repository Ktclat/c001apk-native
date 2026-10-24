// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class TransportTest {
    @Test fun postSeparatesQueryAndFormWithoutRetry() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("{\"data\":null}"))
            val transport = CoolapkHttpTransport(endpoints = ApiEndpoints(server.url("/"), server.url("/")), protocol = ClientProtocol("synthetic", { 1770000000 }))
            assertTrue(transport.post("v6/feed/reply", mapOf("message" to "hello & world"), mapOf("id" to "123")) is ApiResult.Success)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("123", request.requestUrl!!.queryParameter("id"))
            assertEquals("message=hello%20%26%20world", request.body.readUtf8())
            assertNull(request.getHeader("Cookie"))
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun multipartContainsOnlyExplicitTextFields() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("{\"data\":true}"))
            val transport = CoolapkHttpTransport(endpoints = ApiEndpoints(server.url("/"), server.url("/")), protocol = ClientProtocol("synthetic", { 1770000000 }))
            transport.post("v6/message/send", mapOf("message" to "synthetic"), multipart = true)
            val request = server.takeRequest()
            assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
            assertTrue(request.body.readUtf8().contains("name=\"message\""))
        }
    }
    @Test fun sessionDeviceBindsBothSignatureAndHeader() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("{\"data\":[]}"))
            val transport = CoolapkHttpTransport(endpoints = ApiEndpoints(server.url("/"), server.url("/")),
                protocol = ClientProtocol("anonymous-device", { 1770000000 }),
                requestSession = { RequestSession({ "synthetic=fixture" }, { true }, { _, _ -> }, "account-device") })
            assertTrue(transport.get("v6/feed/detail") is ApiResult.Success)
            val request = server.takeRequest()
            assertEquals("account-device", request.getHeader("X-App-Device"))
            assertEquals(ClientProtocol.token("account-device", 1770000000), request.getHeader("X-App-Token"))
        }
    }
    @Test fun cookieWithoutBoundDeviceNeverLeavesClient() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val transport = CoolapkHttpTransport(endpoints = ApiEndpoints(server.url("/"), server.url("/")),
                requestSession = { RequestSession({ "synthetic=fixture" }, { true }, { _, _ -> }) })
            assertEquals(FailureKind.INVALID_ARGUMENT, (transport.get("v6/feed/detail") as ApiResult.Failure).kind)
            assertEquals(0, server.requestCount)
        }
    }
    @Test fun writesReturnUncertainWithoutAutomaticReplay() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val transport = CoolapkHttpTransport(endpoints = ApiEndpoints(server.url("/"), server.url("/")), protocol = ClientProtocol("synthetic", { 1770000000 }))
            server.enqueue(MockResponse().setResponseCode(503))
            assertEquals(FailureKind.UNCERTAIN, (transport.post("v6/feed/reply", mapOf("message" to "fixture")) as ApiResult.Failure).kind)
            server.enqueue(MockResponse().setBody("unexpected response"))
            assertEquals(FailureKind.UNCERTAIN, (transport.get("v6/message/delete", mutation = true) as ApiResult.Failure).kind)
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun messageImageRedirectOnlyResolvesApprovedHttpsImageHost() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val transport = CoolapkHttpTransport(endpoints = ApiEndpoints(server.url("/"), server.url("/")), protocol = ClientProtocol("synthetic", { 1770000000 }))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://image.coolapk.com/synthetic.jpg"))
            assertEquals("https://image.coolapk.com/synthetic.jpg", (transport.getImageLocation("123") as ApiResult.Success).value)
            val request = server.takeRequest()
            assertEquals("s", request.requestUrl!!.queryParameter("type"))
            for (url in listOf("http://image.coolapk.com/a", "https://127.0.0.1/a", "https://image.coolapk.com:444/a", "https://image.coolapk.com.evil.invalid/a", "https://user@image.coolapk.com/a")) {
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", url))
                assertTrue(transport.getImageLocation("123") is ApiResult.Failure)
            }
            assertEquals(6, server.requestCount)
        }
    }
    @Test fun nonApiHostsAndTraversalFailBeforeNetwork() = runBlocking {
        val transport = CoolapkHttpTransport(endpoints = ApiEndpoints("https://example.com/".toHttpUrl(), "https://example.com/".toHttpUrl()))
        assertTrue(transport.get("v6/feed/detail") is ApiResult.Failure)
        assertTrue(transport.get("../feed/detail") is ApiResult.Failure)
        val alternatePort = CoolapkHttpTransport(endpoints = ApiEndpoints("https://api.coolapk.com:444/".toHttpUrl()))
        assertTrue(alternatePort.get("v6/feed/detail") is ApiResult.Failure)
    }
}
