// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.TimeUnit

class UserSpaceRepositoryTest {
    private lateinit var server: MockWebServer
    @Before fun setup() { server = MockWebServer().apply { start() } }
    @After fun close() { server.shutdown() }
    private fun repository(session: (() -> RequestSession)? = null) = HttpCoolapkRepository(
        endpoints = ApiEndpoints(server.url("/"), server.url("/")), requestSession = session)
    @Test fun spaceReadIsExactGuestGetAndNeverOwnAccountProfileOrSocialMutation() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":{"uid":7,"userInfo":{"username":"nested"}}}"""))
        val value = (repository().userProfile("7") as ApiResult.Success).value
        assertEquals("nested", value.userName)
        val request = server.takeRequest(); assertEquals("GET", request.method); assertEquals("/v6/user/space?uid=7", request.path)
        assertNull(request.getHeader("Cookie")); assertEquals(0L, request.bodySize)
    }
    @Test fun invalidIdsAreRejectedBeforeAnyRequest() = runBlocking {
        val repo = repository()
        for (uid in listOf("", "0", "000", "-7", "7?uid=8", "x", "1".repeat(33)))
            assertEquals(FailureKind.INVALID_ARGUMENT, (repo.userProfile(uid) as ApiResult.Failure).kind)
        assertEquals(0, server.requestCount)
    }
    @Test fun challengeIsAnExplicitFailureWithoutAutomaticRetryOrPublicFallback() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"messageStatus":"err_request_captcha","message":"private-text"}"""))
        val result = repository().userProfile("7") as ApiResult.Failure
        assertEquals(FailureKind.CAPTCHA, result.kind); assertFalse(result.message.contains("private-text")); assertEquals(1, server.requestCount)
        assertNull(result.pageRead); assertNull(result.detailRead)
    }
    @Test fun accountSwitchDuringReadRejectsResultAndCookies() = runBlocking {
        var current = true; var cookies = false
        server.enqueue(MockResponse().setBody("""{"data":{"uid":7,"username":"old"}}""").setBodyDelay(300, TimeUnit.MILLISECONDS).addHeader("Set-Cookie", "SESSID=old; Path=/"))
        val repo = repository { RequestSession(cookieFor = { null }, isCurrent = { current }, acceptCookies = { _, _ -> cookies = true }) }
        val task = async { repo.userProfile("7") }
        assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }); current = false
        try { task.await(); fail("stale profile published") } catch (_: CancellationException) { }
        assertFalse(cookies)
    }
}
