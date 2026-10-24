package io.github.ktclat.c001apk.nativeapp.data

import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class FullPostRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: HttpCoolapkRepository
    @Before fun setup() {
        server = MockWebServer().apply { start() }
        repository = repo()
    }
    @After fun teardown() { server.shutdown() }
    private fun repo(session: (() -> RequestSession)? = null) = HttpCoolapkRepository(
        endpoints = ApiEndpoints(server.url("/"), server.url("/")),
        protocol = ClientProtocol("synthetic-test-device", { 1770000000 }), requestSession = session)
    private fun response(message: String) = buildJsonObject {
        putJsonObject("data") { put("id", "42"); put("entityType", "feed"); put("message", message) }
    }.toString()
    @Test fun realDetailMessageUsesExistingEndpointAndPreviewStaysBounded() = runBlocking {
        val html = "x".repeat(12_000) + "REAL_DETAIL_TAIL"
        server.enqueue(MockResponse().setBody(response(html)))
        val item = (repository.feedDetail("42") as ApiResult.Success).value
        assertEquals(html, (item.fullPostBody as FullPostBody.Available).html)
        assertEquals(12_000, item.message.length)
        val request = server.takeRequest()
        assertEquals("/v6/feed/detail", request.requestUrl!!.encodedPath)
        assertEquals("42", request.requestUrl!!.queryParameter("id"))
        assertEquals(1, server.requestCount)
    }
    @Test fun fourMiBTransportBoundaryIsNotRaisedForFullReading() = runBlocking {
        val prefix = "{\"data\":{\"id\":\"42\",\"entityType\":\"feed\",\"message\":\"small\",\"padding\":\""
        val suffix = "\"}}"
        val exact = prefix + "x".repeat(4 * 1024 * 1024 - prefix.length - suffix.length) + suffix
        assertEquals(4 * 1024 * 1024, exact.toByteArray().size)
        server.enqueue(MockResponse().setBody(exact))
        assertTrue(repository.feedDetail("42") is ApiResult.Success)
        server.enqueue(MockResponse().setBody(exact + " "))
        val failure = repository.feedDetail("42") as ApiResult.Failure
        assertEquals(FailureKind.INVALID_RESPONSE, failure.kind)
        assertTrue(failure.message.contains("4 MiB"))
    }
    @Test fun staleAccountGenerationCancelsDetailAndCookiePublication() = runBlocking {
        val current = AtomicBoolean(true)
        val cookiesStored = AtomicBoolean(false)
        val scoped = repo { RequestSession({ null }, { current.get() }, { _, _ -> cookiesStored.set(true) }) }
        server.enqueue(MockResponse().setBody(response("x".repeat(20_000))).setBodyDelay(400, TimeUnit.MILLISECONDS))
        val job = async { scoped.feedDetail("42") }
        assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) })
        current.set(false)
        try { job.await(); fail("stale generation must not return a detail") } catch (_: CancellationException) { }
        assertFalse(cookiesStored.get())
    }
    @Test fun cancellationStopsInFlightFullDetailRead() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = async { repository.feedDetail("42") }
        assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) })
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }
    @Test fun invalidDetailIdDoesNotDispatch() = runBlocking {
        assertEquals(FailureKind.INVALID_ARGUMENT, (repository.feedDetail("../42") as ApiResult.Failure).kind)
        assertEquals(0, server.requestCount)
    }
}
