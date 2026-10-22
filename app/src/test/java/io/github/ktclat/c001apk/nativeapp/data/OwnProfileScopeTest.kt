// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data
import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Synthetic loopback only. No accounts, cookies or external services. */
class OwnProfileScopeTest {
    @Test fun switchDuringCaptureNeverSendsARequest() = runBlocking {
        MockWebServer().use { server ->
            server.start(); var current = true
            val repo = HttpCoolapkRepository(endpoints = ApiEndpoints(server.url("/"),server.url("/")), requestSession = {
                current = false
                RequestSession({ null }, { true }, { _, _ -> }, isSessionCurrent = { true })
            })
            try { repo.ownProfile("123") { current }.load(); fail("must cancel") } catch (_: CancellationException) { }
            assertEquals(0,server.requestCount)
        }
    }
    @Test fun noSessionFailsWithoutRequest() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val repo = HttpCoolapkRepository(endpoints = ApiEndpoints(server.url("/"),server.url("/")))
            assertEquals(FailureKind.AUTHENTICATION,(repo.ownProfile("123") { true }.load() as ApiResult.Failure).kind)
            assertEquals(0,server.requestCount)
        }
    }
    @Test fun lateResponseAndSameIdNewGenerationAreRejected() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("""{"data":{"uid":123,"feed":4}}""").setBodyDelay(200,TimeUnit.MILLISECONDS))
            val current = AtomicBoolean(true); var captures = 0
            val repo = HttpCoolapkRepository(endpoints = ApiEndpoints(server.url("/"),server.url("/")),requestSession = {
                captures++
                RequestSession({ null }, { true }, { _, _ -> }, isSessionCurrent = { true })
            })
            val pending = async(Dispatchers.Default) { repo.ownProfile("123") { current.get() }.load() }
            val request = withContext(Dispatchers.IO) { server.takeRequest(3,TimeUnit.SECONDS) }
            assertEquals("/v6/user/profile?uid=123",request!!.path)
            current.set(false) // The captured UID is unchanged; the root generation has changed.
            try { pending.await(); fail("stale response published") } catch (_: CancellationException) { }
            assertEquals(1,captures)
        }
    }
    @Test fun exactFeedListContractMatchesFlutter() = runBlocking {
        var path = ""; var params = emptyMap<String,String>()
        val repo = HttpPersonalListRepository { p,q -> path=p; params=q; ApiResult.Success(kotlinx.serialization.json.JsonArray(emptyList())) }
        repo.load(PersonalListRequest(PersonalListKind.FEED),"123",PageCursor())
        assertEquals("v6/user/feedList",path)
        assertEquals(mapOf("page" to "1","uid" to "123","showAnonymous" to "0","isIncludeTop" to "1"),params)
    }
}
