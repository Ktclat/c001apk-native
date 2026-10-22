// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Synthetic fixtures only; never touches production endpoints, accounts or credentials. */
class RepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: HttpCoolapkRepository
    @Before fun setup() {
        server = MockWebServer().apply { start() }
        repository = HttpCoolapkRepository(endpoints = ApiEndpoints(server.url("/"), server.url("/")), protocol = ClientProtocol("synthetic-test-device", { 1770000000 }), installTimeSeconds = 100)
    }
    @After fun teardown() { server.shutdown() }
    private fun enqueue(body: String, status: Int = 200) { server.enqueue(MockResponse().setResponseCode(status).setBody(body)) }
    @Test fun homeDecodesCardsAndCursor() = runBlocking {
        enqueue("""{"data":[{"id":1,"entityType":"feed","message":"hello","userInfo":{"username":"tester","userAvatar":"https://example.com/avatar"},"likenum":"2","picArr":["https://example.com/a","file:///private"]},{"id":"2","entityType":"imageTextCard","entities":[{"id":3,"entityType":"feed","message":"child"}]}]}""")
        val page = (repository.home() as ApiResult.Success).value
        assertEquals("tester", page.items[0].userName)
        assertEquals(2, page.items[0].likeCount)
        assertEquals(1, page.items[0].imageUrls.size)
        assertEquals("child", page.items[1].children[0].message)
        assertEquals(PageCursor(2, "1", "2"), page.nextCursor)
        val request = server.takeRequest()
        assertEquals("/v6/main/indexV8", request.requestUrl!!.encodedPath)
        assertEquals("100", request.requestUrl!!.queryParameter("installTime"))
        assertTrue(request.getHeader("X-App-Token")!!.startsWith("v3"))
        assertNull(request.getHeader("Cookie"))
    }
    @Test fun paginationPreservesCursorAndStopsEmpty() = runBlocking {
        enqueue("""{"data":[]}""")
        val result = repository.home(PageCursor(2, "11", "22")) as ApiResult.Success
        assertNull(result.value.nextCursor)
        val request = server.takeRequest().requestUrl!!
        assertEquals("11", request.queryParameter("firstItem"))
        assertEquals("22", request.queryParameter("lastItem"))
    }
    @Test fun repeatedLastIdTerminatesPagination() = runBlocking {
        enqueue("""{"data":[{"id":"22"}]}""")
        assertNull((repository.home(PageCursor(2, "11", "22")) as ApiResult.Success).value.nextCursor)
    }
    @Test fun searchEncodesUserInputAsQuery() = runBlocking {
        enqueue("""{"data":[]}""")
        repository.search("你好 &page=666/#")
        val url = server.takeRequest().requestUrl!!
        assertEquals("你好 &page=666/#", url.queryParameter("searchValue"))
        assertEquals("1", url.queryParameter("page"))
    }
    @Test fun topicTabsPreserveServerRoutesWithoutFollowingThem() = runBlocking {
        enqueue("""{"data":{"id":4,"entityType":"topic","title":"Kotlin","tabList":[{"title":"讨论","url":"/page?url=/topic/feedList?tag=Kotlin"}]}}""")
        val item = (repository.topicDetail("Kotlin") as ApiResult.Success).value
        assertEquals("讨论", item.tabs.single().title)
        assertEquals("/v6/topic/newTagDetail", server.takeRequest().requestUrl!!.encodedPath)
        enqueue("""{"data":[]}""")
        repository.page("https://evil.invalid/steal")
        assertEquals("/v6/page/dataList", server.takeRequest().requestUrl!!.encodedPath)
    }
    @Test fun challengesAreNotEmptySuccessOrAutoRetried() = runBlocking {
        val bodies = listOf(
            """{"messageStatus":"err_captcha","message":"secret body must not leak"}""" to FailureKind.CAPTCHA,
            """{"code":401,"message":"login"}""" to FailureKind.AUTHENTICATION,
            """{"status":-10,"message":"network"}""" to FailureKind.RISK_CONTROL,
            """{"messageStatus":"err_request_need_upgrade_new_version"}""" to FailureKind.CLIENT_UPGRADE,
            """{"code":429}""" to FailureKind.RATE_LIMIT,
        )
        bodies.forEach { (body, kind) ->
            enqueue(body)
            val result = repository.home() as ApiResult.Failure
            assertEquals(kind, result.kind)
            assertFalse(result.message.contains("secret"))
        }
        assertEquals(bodies.size, server.requestCount)
    }
    @Test fun malformedPayloadsFailSafely() = runBlocking {
        listOf("<html>secret</html>", "[]", "{}", "{\"data\":{}}", "{\"data\":[null]}").forEach {
            enqueue(it)
            assertEquals(FailureKind.INVALID_RESPONSE, (repository.home() as ApiResult.Failure).kind)
        }
    }
    @Test fun redirectsDoNotLeakProtocolHeadersToAnotherHost() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.com/"))
        assertEquals(FailureKind.HTTP, (repository.home() as ApiResult.Failure).kind)
        assertEquals(1, server.requestCount)
    }
    @Test fun invalidNumericIdsNeverReachNetwork() = runBlocking {
        assertEquals(FailureKind.INVALID_ARGUMENT, (repository.feedDetail("../secrets") as ApiResult.Failure).kind)
        assertEquals(0, server.requestCount)
    }
    @Test fun appDetailAndRepliesMatchSourceContract() = runBlocking {
        enqueue("""{"data":{"id":10,"entityType":"apk","packageName":"com.example.app"}}""")
        repository.appDetail("com.example.app")
        val app = server.takeRequest().requestUrl!!
        assertEquals("/v6/apk/detail", app.encodedPath)
        assertEquals("1", app.queryParameter("installed"))
        enqueue("""{"data":[]}""")
        repository.replies("12")
        assertEquals("12", server.takeRequest().requestUrl!!.queryParameter("id"))
    }
    @Test fun cancellationCancelsCallRatherThanPublishingFailure() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        val job = async { repository.home() }
        assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) })
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }
    @Test fun productAndUserRoutesMatchSource() = runBlocking {
        enqueue("""{"data":[{"id":5,"entityType":"product","dateline":"1234"}]}""")
        val categories = repository.productCategories() as ApiResult.Success
        assertEquals(1234L, categories.value.items[0].publishedAtSeconds)
        assertEquals("/v6/product/categoryList", server.takeRequest().requestUrl!!.encodedPath)
        enqueue("""{"data":{"id":5,"entityType":"product"}}""")
        repository.productDetail("5")
        assertEquals("/v6/product/detail", server.takeRequest().requestUrl!!.encodedPath)
        enqueue("""{"data":{"uid":7,"username":"synthetic"}}""")
        repository.userProfile("7")
        assertEquals("7", server.takeRequest().requestUrl!!.queryParameter("uid"))
        enqueue("""{"data":[]}""")
        repository.userFeed("7")
        assertEquals("/v6/user/feedList", server.takeRequest().requestUrl!!.encodedPath)
    }
    @Test fun staleSessionNeverPublishesOrStoresCookies() = runBlocking {
        var current = true
        var stored = false
        val scoped = HttpCoolapkRepository(endpoints = ApiEndpoints(server.url("/"), server.url("/")),
            protocol = ClientProtocol("synthetic-test-device", { 1770000000 }),
            requestSession = { RequestSession({ "synthetic=fixture" }, { current }, { _, _ -> stored = true }, deviceCode = "synthetic-test-device") })
        server.enqueue(MockResponse().setBody("{\"data\":[]}").setBodyDelay(300, TimeUnit.MILLISECONDS))
        val job = async { scoped.home() }
        val request = withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) }!!
        assertEquals("synthetic=fixture", request.getHeader("Cookie"))
        current = false
        try { job.await(); fail("Stale session must cancel") } catch (_: CancellationException) { }
        assertFalse(stored)
    }
    @Test fun hugeResponseFailsWithoutUnboundedAllocation() = runBlocking {
        enqueue("x".repeat(4 * 1024 * 1024 + 1))
        assertEquals(FailureKind.INVALID_RESPONSE, (repository.home() as ApiResult.Failure).kind)
    }
    @Test fun advancedSearchAndNestedRepliesEncodeSourceContract() = runBlocking {
        enqueue("""{"data":[]}""")
        repository.searchAdvanced("kotlin", SearchOptions(type = "feed", feedType = "feed", sort = "dateline", strict = true, pageType = "topic", pageParam = "Kotlin"))
        val search = server.takeRequest().requestUrl!!
        assertEquals("1", search.queryParameter("isStrict"))
        assertEquals("Kotlin", search.queryParameter("pageParam"))
        enqueue("""{"data":[]}""")
        repository.orderedReplies("12", ReplyOrder.POPULAR, authorOnly = true)
        val replies = server.takeRequest().requestUrl!!
        assertEquals("popular", replies.queryParameter("listType"))
        assertEquals("1", replies.queryParameter("fromFeedAuthor"))
        enqueue("""{"data":[]}""")
        repository.replyThread("34")
        val thread = server.takeRequest().requestUrl!!
        assertEquals("feed_reply", thread.queryParameter("feedType"))
        assertEquals("0", thread.queryParameter("discussMode"))
    }
    @Test fun pictureAndDyhPathsAreFixed() = runBlocking {
        enqueue("""{"data":[]}""")
        repository.pictures("摄影", "hot")
        assertEquals("/v6/picture/list", server.takeRequest().requestUrl!!.encodedPath)
        enqueue("""{"data":[]}""")
        repository.dyhArticles("55", "all")
        assertEquals("/v6/dyhArticle/list", server.takeRequest().requestUrl!!.encodedPath)
    }
    @Test fun decoderBoundsTotalCardsNotJustDepth() = runBlocking {
        enqueue("{\"data\":[" + (1..501).joinToString(",") { "{\"id\":$it}" } + "]}")
        assertEquals(FailureKind.INVALID_RESPONSE, (repository.home() as ApiResult.Failure).kind)
    }
    @Test fun chronologicalFeedsExcludePinnedRows() = runBlocking {
        enqueue("""{"data":[]}""")
        repository.chronologicalUserFeed("123")
        assertEquals("0", server.takeRequest().requestUrl!!.queryParameter("isIncludeTop"))
    }
    @Test fun followFiltersMatchFlutterRoutesAndTitles() = runBlocking {
        FollowType.entries.forEach { type ->
            enqueue("""{"data":[]}""")
            repository.following(type)
            val url = server.takeRequest().requestUrl!!
            assertEquals("/v6/page/dataList", url.encodedPath)
            assertEquals(type.route, url.queryParameter("url"))
            assertEquals(type.title, url.queryParameter("title"))
        }
        assertEquals("/page?url=V9_HOME_TAB_FOLLOW&type=circle", FollowType.USERS.route)
        assertEquals("全部关注", FollowType.ALL.title)
    }
    @Test fun sourceCompatibleSyntheticTokenVectors() {
        assertEquals("v3JDJ5JDA0JE5qazRNREJsT0RBdk16VXhZemxsTnVOeS96WDhRWDE5NXNheklDN05mbE1ZRy9EaDN4cEhP", ClientProtocol.token("synthetic-test-device", 1770000000))
        assertEquals("v3JDJ5JDA0JE5qazRNREJsWlRNdllUUTFaV1ppWU9zaW9qYzhta0NSeFgvUVlnWjhCbVouTmppMjdBMGku", ClientProtocol.token("synthetic-test-device", 1770000099))
        assertEquals("v3JDJ5JDA0JE5tRmpaVGhrWXpFdlkyUmtaREkxWmVzMnNhSDd3eklCLkEuRG1naEVNc1J6cjNMQjRkVUhH", ClientProtocol.token("synthetic-test-device", 1791921600))
    }
}
