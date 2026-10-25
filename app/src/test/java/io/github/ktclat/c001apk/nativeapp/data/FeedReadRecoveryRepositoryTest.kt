// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class FeedReadRecoveryRepositoryTest {
    private lateinit var api: MockWebServer
    private lateinit var web: MockWebServer
    private val current = AtomicBoolean(true)
    private var accepted = 0
    private val captcha = """{"messageStatus":"err_request_captcha_v2","message":"captcha","messageExtra":{"captchaId":"abcdefghijklmnop","captchaField":"_v2_post_token"}}"""
    @Before fun setup() { api = MockWebServer().apply { start() }; web = MockWebServer().apply { start() }; current.set(true); accepted = 0 }
    @After fun stop() { api.shutdown(); web.shutdown() }
    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/feed-reading/$name.json")).readText()
    private fun session(guest: Boolean = true): RequestSession {
        lateinit var value: RequestSession
        value = RequestSession({ "token=synthetic; SESSID=synthetic" }, { current.get() }, { _, _ -> accepted++ },
            deviceCode = "synthetic-device", isSessionCurrent = { current.get() }, isGuest = guest,
            acceptChallengeCookies = { _, _ -> value })
        return value
    }
    private fun repo(session: RequestSession = session(), client: OkHttpClient = OkHttpClient()) = HttpCoolapkRepository(client,
        ApiEndpoints(api.url("/"), api.url("/"), web.url("/")), ClientProtocol("synthetic-device", { 1770000000 }), requestSession = { session })
    @Test fun captchaPublicReadRetainsWholeOrdinaryBodyAndPicturesButNotAccountAction() = runBlocking {
        api.enqueue(MockResponse().setBody(captcha)); web.enqueue(MockResponse().setBody(fixture("ordinary-detail")).addHeader("Set-Cookie", "SESSID=public-must-not-be-adopted"))
        val item = (repo().feedDetail("42001") as ApiResult.Success).value
        assertTrue(item.publicRead); assertTrue(item.bodyTruncated)
        assertEquals(12_000, item.message.length)
        assertTrue((item.fullPostBody as FullPostBody.Available).html.endsWith("REAL_DETAIL_COMPLETE_SUFFIX"))
        assertEquals(1, item.imageUrls.size); assertEquals(ExplicitLikeState.UNKNOWN, item.feed?.likeState)
        assertEquals(listOf("50001", "50002"), item.highlightedReplies.map { it.id })
        assertEquals(ExplicitLikeState.UNKNOWN, item.highlightedReplies.first().replyInteraction?.likeState)
        assertEquals(ExplicitLikeState.UNKNOWN, item.highlightedReplies.first().replies.first().replyInteraction?.likeState)
        assertNull(item.accountDetailRetry); assertEquals(0, accepted)
        val request = web.takeRequest(); assertEquals("GET", request.method); assertEquals("/feed/42001", request.path)
        listOf("Cookie", "Authorization", "Proxy-Authorization").forEach { assertNull(request.getHeader(it)) }
        assertTrue(request.getHeader("X-App-Token")!!.startsWith("v3")); assertEquals(1, api.requestCount); assertEquals(1, web.requestCount)
    }
    @Test fun successfulApiUsesFullBodyWithoutPublicRequest() = runBlocking {
        api.enqueue(MockResponse().setBody(fixture("ordinary-detail")))
        val item = (repo().feedDetail("42001") as ApiResult.Success).value
        assertFalse(item.publicRead); assertTrue((item.fullPostBody as FullPostBody.Available).html.endsWith("REAL_DETAIL_COMPLETE_SUFFIX")); assertEquals(0, web.requestCount)
    }
    @Test fun structuredPublicBodyKeepsOrderedTextImagesAndTailNotSummary() = runBlocking {
        api.enqueue(MockResponse().setResponseCode(401)); web.enqueue(MockResponse().setBody(fixture("article-detail")))
        val item = (repo().feedDetail("42002") as ApiResult.Success).value
        val article = item.fullPostBody as FullPostBody.Structured
        assertEquals(4, article.blocks.size); assertTrue(article.blocks[1] is ArticleBlock.Image)
        assertTrue((article.blocks.last() as ArticleBlock.Text).message.endsWith("ARTICLE_COMPLETE_SUFFIX")); assertNotEquals(item.message, (article.blocks.first() as ArticleBlock.Text).message)
    }
    @Test fun publicReaderDropsClientCredentialInterceptorsAndAuth() = runBlocking {
        api.enqueue(MockResponse().setResponseCode(401)); web.enqueue(MockResponse().setBody(fixture("ordinary-detail")))
        val client = OkHttpClient.Builder().addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Authorization", "synthetic-private").header("Cookie", "synthetic-private").build()) }.build()
        assertTrue(repo(client = client).feedDetail("42001") is ApiResult.Success)
        val request = web.takeRequest(); assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("Cookie"))
    }
    @Test fun challengeWithAppOnlyHtmlIsTypedErrorNotEmptyOrSummarySuccess() = runBlocking {
        api.enqueue(MockResponse().setBody(captcha)); web.enqueue(MockResponse().setBody("<html>请用酷安手机APP扫码查看详情</html>"))
        val result = repo().feedDetail("42001") as ApiResult.Failure
        assertEquals(FailureKind.CAPTCHA, result.kind); assertTrue(result.message.contains("未提供可读取正文")); assertNull(result.detailRead)
    }
    @Test fun responseIdMustMatchRouteOnApiAndPublic() = runBlocking {
        api.enqueue(MockResponse().setBody(fixture("ordinary-detail")))
        assertEquals(FailureKind.INVALID_RESPONSE, (repo().feedDetail("42002") as ApiResult.Failure).kind)
        api.enqueue(MockResponse().setResponseCode(401)); web.enqueue(MockResponse().setBody(fixture("ordinary-detail")))
        assertEquals(FailureKind.AUTHENTICATION, (repo().feedDetail("42002") as ApiResult.Failure).kind)
    }
    @Test fun noFallbackFor403RiskRateMalformedOrServerError() = runBlocking {
        val responses = listOf(MockResponse().setResponseCode(403), MockResponse().setBody("""{"status":-10,"message":"网络环境"}"""), MockResponse().setResponseCode(429), MockResponse().setBody("not-json"), MockResponse().setResponseCode(500))
        for (response in responses) { api.enqueue(response); assertTrue(repo().feedDetail("42001") is ApiResult.Failure) }
        assertEquals(0, web.requestCount)
    }
    @Test fun publicRedirectIsNotFollowed() = runBlocking {
        api.enqueue(MockResponse().setResponseCode(401)); web.enqueue(MockResponse().setResponseCode(302).addHeader("Location", api.url("/secret")))
        assertTrue(repo().feedDetail("42001") is ApiResult.Failure); assertEquals(1, api.requestCount); assertEquals(1, web.requestCount)
    }
    @Test fun publicOwnerChangeCannotPublishOrAdoptCookies() = runBlocking {
        api.enqueue(MockResponse().setResponseCode(401)); web.enqueue(MockResponse().setBody(fixture("ordinary-detail")).setBodyDelay(250, TimeUnit.MILLISECONDS))
        val task = async { repo().feedDetail("42001") }
        assertNotNull(withContext(Dispatchers.IO) { web.takeRequest(3, TimeUnit.SECONDS) }); current.set(false)
        try { task.await(); fail("stale owner") } catch (_: CancellationException) { }
        assertEquals(0, accepted)
    }
    @Test fun authenticatedChallengeRequiresHumanProofAndExplicitSingleReadRetry() = runBlocking {
        api.enqueue(MockResponse().setBody(captcha)); web.enqueue(MockResponse().setBody("<html>app only</html>"))
        val result = repo(session(false)).feedDetail("42001") as ApiResult.Failure
        val pending = requireNotNull(result.detailRead); assertEquals(1, api.requestCount)
        assertTrue(pending.acceptHumanProof("NEC:abcdefgh:synthetic-proof")); assertEquals(1, api.requestCount)
        api.enqueue(MockResponse().setBody(fixture("ordinary-detail")))
        assertTrue(pending.retryOnce() is ApiResult.Success); assertTrue(pending.retryOnce() is ApiResult.Failure)
        api.takeRequest(); val retry = api.takeRequest(); assertEquals("synthetic-proof", retry.requestUrl!!.queryParameter("_v2_post_token")?.substringAfterLast(':'))
        assertEquals(2, api.requestCount); assertEquals(1, web.requestCount)
    }
    @Test fun publicSuccessRetainsAccountRecoveryAndCancellationDoesNotRetry() = runBlocking {
        api.enqueue(MockResponse().setBody(captcha)); web.enqueue(MockResponse().setBody(fixture("ordinary-detail")))
        val item = (repo(session(false)).feedDetail("42001") as ApiResult.Success).value
        val pending = requireNotNull(item.accountDetailRetry); pending.cancel()
        assertFalse(pending.acceptHumanProof("NEC:abcdefgh:synthetic-proof")); assertTrue(pending.retryOnce() is ApiResult.Failure)
        assertEquals(1, api.requestCount)
    }
    @Test fun overlargePublicResponseDoesNotBecomeDetailSuccess() = runBlocking {
        api.enqueue(MockResponse().setResponseCode(401)); web.enqueue(MockResponse().setBody("x".repeat(4*1024*1024+1)))
        assertTrue(repo().feedDetail("42001") is ApiResult.Failure)
    }
    @Test fun publicSummaryWithoutMessageIsNotPromotedToDetailBody() = runBlocking {
        api.enqueue(MockResponse().setResponseCode(401)); web.enqueue(MockResponse().setBody("""{"data":{"id":42001,"entityType":"feed","title":"SUMMARY","description":"LIST_PREVIEW_ONLY"}}"""))
        assertEquals(FailureKind.AUTHENTICATION, (repo().feedDetail("42001") as ApiResult.Failure).kind)
    }
    @Test fun secondChallengeDoesNotCreateFallbackOrVerificationLoop() = runBlocking {
        api.enqueue(MockResponse().setBody(captcha)); web.enqueue(MockResponse().setBody("<html>app only</html>"))
        val pending = requireNotNull((repo(session(false)).feedDetail("42001") as ApiResult.Failure).detailRead)
        assertTrue(pending.acceptHumanProof("NEC:abcdefgh:synthetic-proof")); api.enqueue(MockResponse().setBody(captcha))
        val retried = pending.retryOnce() as ApiResult.Failure
        assertNull(retried.detailRead); assertEquals(FailureKind.CAPTCHA, retried.kind)
        assertEquals(2, api.requestCount); assertEquals(1, web.requestCount)
    }
    @Test fun ownerChangeBeforeVerifiedRetryDoesNotDispatch() = runBlocking {
        api.enqueue(MockResponse().setBody(captcha)); web.enqueue(MockResponse().setBody("<html>app only</html>"))
        val pending = requireNotNull((repo(session(false)).feedDetail("42001") as ApiResult.Failure).detailRead)
        assertFalse(pending.acceptHumanProof("NOT_A_PROVIDER_PROOF"))
        assertTrue(pending.acceptHumanProof("NEC:abcdefgh:synthetic-proof")); current.set(false)
        assertTrue(pending.retryOnce() is ApiResult.Failure); assertEquals(1, api.requestCount)
    }
    @Test fun cancellingPublicBodyStopsReadWithoutPublishing() = runBlocking {
        api.enqueue(MockResponse().setResponseCode(401)); web.enqueue(MockResponse().setBody(fixture("ordinary-detail")).setBodyDelay(2, TimeUnit.SECONDS))
        val task = async { repo().feedDetail("42001") }
        assertNotNull(withContext(Dispatchers.IO) { web.takeRequest(3, TimeUnit.SECONDS) }); task.cancelAndJoin(); assertTrue(task.isCancelled)
        assertEquals(0, accepted)
    }

    @Test fun leadingZeroFeedRouteAcceptsSameNumericIdentityButNotAnotherFeed() = runBlocking {
        api.enqueue(MockResponse().setBody(fixture("ordinary-detail")))
        assertEquals("42001", (repo().feedDetail("00042001") as ApiResult.Success).value.id)
        assertEquals(0, web.requestCount)
    }

}
