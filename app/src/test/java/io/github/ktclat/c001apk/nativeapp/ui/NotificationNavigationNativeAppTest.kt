// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.ktclat.c001apk.nativeapp.AppContainer
import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.data.*
import io.github.ktclat.c001apk.nativeapp.local.*
import io.github.ktclat.c001apk.nativeapp.settings.*
import io.github.ktclat.c001apk.nativeapp.social.SocialRepository
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real NativeApp, notification decoder and signed/session-scoped repositories; all wire data and
 * credentials are synthetic. Every HTTP call ends in this interceptor without chain.proceed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationNavigationNativeAppTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val owner = SessionViewModelStore()
    private val requests = CopyOnWriteArrayList<Request>()
    private val unexpected = CopyOnWriteArrayList<String>()
    private lateinit var store: AccountStore
    private lateinit var client: OkHttpClient
    @Volatile private var pageTwoFailure = false
    @Volatile private var listFailure: String? = null
    @Volatile private var unavailableFeed = false
    @Volatile private var commentsFailure = false
    @Volatile private var delayedFeed: CountDownLatch? = null
    private var pagedReplies = false
    private var repeatedLikes = false
    private var repeatedLikePages = false
    private var missingReplyLink = false
    @Volatile private var cappedList = false
    @Volatile private var delayedFirstAccount: CountDownLatch? = null
    private class MemoryLocal : JsonStateStore<LocalState> {
        override val data = MutableStateFlow(LocalState())
        override suspend fun update(transform: (LocalState) -> LocalState) { data.value = transform(data.value) }
    }
    @After fun close() {
        delayedFirstAccount?.countDown(); delayedFeed?.countDown()
        owner.viewModelStore.clear()
        if (::client.isInitialized) { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
        assertTrue("No external or write requests: $unexpected", unexpected.isEmpty())
    }
    @Test fun fourActualMyShortcutsOpenRealFeedAndPublicUserDestinations() {
        show()
        compose.onNode(hasText("我的") and hasClickAction()).performClick()
        awaitTag("my-root-list")
        val cases = listOf(
            Triple("MENTIONS", "feed:101:0", "101"), Triple("REPLIES", "feed_reply:202:0", "303"),
            Triple("LIKES", "feed_reply:606:0", "707"), Triple("FOLLOWERS", "notification:808:0", "user:11"))
        for ((category,row,target) in cases) {
            compose.onNodeWithTag("my-notice-$category").performScrollTo().performClick()
            awaitTag("notice-open:$row")
            if (category == "MENTIONS") { awaitText("合成提及动态正文"); capture("after-notification-list") }
            openRow(row)
            if (target.startsWith("user:")) {
                awaitTag("user-name:11"); awaitText("合成用户动态11"); capture("after-follower-user")
            } else {
                awaitTag("feed:$target"); awaitText("合成详情完整正文")
                assertEquals(target,requests.last { it.url.encodedPath == "/v6/feed/detail" }.url.queryParameter("id"))
                if (category == "REPLIES") capture("after-comment-root-feed")
            }
            back(); awaitTag("notification-list"); back(); awaitTag("my-root-list")
        }
        assertTrue(requests.none { it.url.encodedPath == "/v6/feed/detail" && it.url.queryParameter("id") in setOf("202","606") })
        requests.filter { it.url.encodedPath.startsWith("/v6/notification/") }.forEach { request ->
            assertEquals("synthetic-device-1",request.header("X-App-Device"))
            assertTrue(request.header("Cookie").orEmpty().contains("token=synthetic-account-1"))
        }
    }
    @Test fun noteTextAndFirstAnchorOpenRelatedFeedWithoutBrowserFallback() {
        show("notifications:MENTIONS"); awaitText("合成提及动态正文")
        compose.onNodeWithTag("notification-list").performScrollToNode(hasTestTag("notice-open:notification:403:2"))
        compose.onNodeWithText("在这条动态中提到了你").assertExists()
        openRow("notification:403:2"); awaitTag("feed:404"); awaitText("合成详情完整正文")
        back(); awaitTag("notification-list")
        compose.onNodeWithTag("notification-list").performScrollToNode(hasTestTag("notice-unavailable:notification:405:3"))
        compose.onNodeWithText("此内容地址暂不支持，未打开外部链接").assertIsDisplayed()
        compose.onNodeWithTag("notice-row:notification:405:3").assertHasNoClickAction()
        capture("after-unsupported-target")
        assertEquals(listOf("404"),requests.filter { it.url.encodedPath == "/v6/feed/detail" }.map { it.url.queryParameter("id") })
    }
    @Test fun repeatedLikeRecordsKeepEachActorAndMixedEntityDestination() {
        repeatedLikes = true; show("notifications:LIKES")
        awaitText("点赞用户")
        compose.onNodeWithTag("notice-actor:feed_reply:606:0").performClick()
        awaitTag("user-name:10"); back(); awaitTag("notification-list")
        compose.onNodeWithTag("notification-list").performScrollToNode(hasTestTag("notice-actor:feed_reply:606:1"))
        compose.onNodeWithTag("notice-actor:feed_reply:606:1").assertTextEquals("第二位点赞用户").performClick()
        awaitTag("user-name:12"); back(); awaitTag("notification-list")
        openRow("feed:606:2"); awaitTag("feed:606")
        assertEquals(1,calls("/v6/notification/feedLikeList"))
        assertEquals(listOf("606"),requests.filter { it.url.encodedPath == "/v6/feed/detail" }.map { it.url.queryParameter("id") })
        assertEquals(listOf("10","12"),requests.filter { it.url.encodedPath == "/v6/user/space" }.map { it.url.queryParameter("uid") })
    }
    @Test fun categoryPaginationFailureRetryAndChildBackRetainNonzeroPositionAndPages() {
        pagedReplies = true; pageTwoFailure = true; show("notifications:MENTIONS")
        awaitText("合成提及动态正文")
        compose.onNodeWithText("@我的动态").performClick(); compose.onNodeWithText("@我的评论").performClick()
        awaitTag("notice-open:feed_reply:201:0")
        compose.onNodeWithTag("notification-list").performScrollToNode(hasText("加载更多"))
        compose.onNodeWithText("加载更多").performClick(); awaitTag("notification-error")
        compose.onNodeWithText("暂无内容").assertDoesNotExist()
        compose.runOnIdle { pageTwoFailure = false }
        compose.onNodeWithText("重试").performScrollTo().performClick()
        awaitTag("notice-open:feed_reply:209:8")
        compose.onNodeWithTag("notification-list").performScrollToNode(hasTestTag("notice-open:feed_reply:209:8"))
        val offset = compose.onNodeWithTag("notification-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue(offset > 0f)
        capture("after-paginated-list")
        compose.onNodeWithTag("notice-open:feed_reply:209:8").performClick()
        awaitTag("feed:309"); awaitText("合成详情完整正文"); back(); awaitTag("notification-list")
        compose.onNodeWithText("@我的评论").assertIsDisplayed()
        compose.onNodeWithTag("notice-open:feed_reply:209:8").assertIsDisplayed()
        assertEquals(offset,compose.onNodeWithTag("notification-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), .001f)
        assertEquals(3,calls("/v6/notification/atCommentMeList"))
        val pagination = requests.filter { it.url.encodedPath == "/v6/notification/atCommentMeList" && it.url.queryParameter("page") == "2" }
        assertEquals(2,pagination.size); assertEquals(pagination[0].url,pagination[1].url)
        assertEquals("201",pagination[0].url.queryParameter("firstItem")); assertEquals("208",pagination[0].url.queryParameter("lastItem"))
        capture("after-child-back-retained")
    }
    @Test fun changedAccountRejectsLateOldRowsAndLoadsNewGeneration() {
        delayedFirstAccount = CountDownLatch(1); show("notifications:MENTIONS")
        compose.waitUntil(5_000) { compose.onRoot().fetchSemanticsNode(); calls("/v6/notification/atMeList") == 1 }
        compose.runOnIdle { store.switchTo("2") }
        awaitText("新账号通知正文")
        delayedFirstAccount!!.countDown()
        compose.waitForIdle()
        compose.onNodeWithText("合成提及动态正文").assertDoesNotExist()
        openRow("feed:902:0"); awaitTag("feed:902")
        val notices = requests.filter { it.url.encodedPath == "/v6/notification/atMeList" }
        assertEquals(2,notices.size)
        assertEquals("synthetic-device-1",notices[0].header("X-App-Device")); assertEquals("synthetic-device-2",notices[1].header("X-App-Device"))
        assertTrue(notices[1].header("Cookie").orEmpty().contains("token=synthetic-account-2"))
    }
    @Test fun listFailureStaysVisibleAndManualRetryDoesNotPretendEmptySuccess() {
        listFailure = "network"; show("notifications:MENTIONS"); awaitTag("notification-error")
        compose.onNodeWithText("暂无内容").assertDoesNotExist()
        capture("after-list-error")
        compose.runOnIdle { listFailure = null }; compose.onNodeWithText("重试").performClick()
        awaitText("合成提及动态正文"); assertEquals(2,calls("/v6/notification/atMeList"))
    }
    @Test fun expiredLoginOffersRealLoginAndNeverAutoAcknowledgesNotification() {
        listFailure = "auth"; show("notifications:MENTIONS"); awaitTag("notification-error")
        compose.onNodeWithText("重新登录").performClick(); awaitText("密码")
        assertEquals(1,calls("/v6/notification/atMeList"))
    }
    @Test fun readChallengeRemainsExplicitWithoutClaimingVerificationOrAutomaticRetry() {
        listFailure = "captcha"; show("notifications:MENTIONS"); awaitTag("notification-error")
        compose.onNodeWithText("服务端要求验证码，当前请求未完成").assertExists()
        compose.onNodeWithText("暂无内容").assertDoesNotExist()
        compose.waitForIdle(); assertEquals(1,calls("/v6/notification/atMeList"))
    }
    @Test fun unavailableTargetIsAnExplicitReaderFailureNotAPlaceholderOrEmptyFeed() {
        unavailableFeed = true; show("notifications:MENTIONS"); awaitText("合成提及动态正文")
        openRow("feed:101:0"); awaitText("该动态不存在或暂不可见")
        compose.onNodeWithText("合成详情完整正文").assertDoesNotExist()
        compose.onNodeWithText("回复动态").assertDoesNotExist(); compose.onNodeWithText("评论 0").assertDoesNotExist()
        compose.onNodeWithText("只看楼主").assertDoesNotExist()
        capture("after-unavailable-feed")
        back(); awaitTag("notification-list"); awaitText("合成提及动态正文")
        assertEquals(1,calls("/v6/notification/atMeList")); assertEquals(1,calls("/v6/feed/detail"))
    }
    @Test fun replyWithoutExplicitRootCannotUseItsOwnCommentIdAsFeedId() {
        missingReplyLink = true; show("notifications:REPLIES")
        awaitTag("notice-unavailable:feed_reply:202:0")
        compose.onNodeWithText("未提供可打开的内容地址，内容可能已失效").assertExists()
        compose.onNodeWithTag("notice-row:feed_reply:202:0").assertHasNoClickAction()
        assertEquals(0,calls("/v6/feed/detail"))
    }
    @Test fun existingSystemDropdownUsesTheSameInternalOnlyNotificationPolicy() {
        show("notifications:MENTIONS"); awaitText("合成提及动态正文")
        compose.onNodeWithText("@我的动态").performClick(); compose.onNodeWithText("系统通知").performClick()
        awaitTag("notice-open:notification:501:0"); openRow("notification:501:0")
        awaitTag("feed:502"); awaitText("合成详情完整正文"); back(); awaitTag("notification-list")
        compose.onNodeWithText("系统通知").assertIsDisplayed()
        compose.onNodeWithTag("notification-list").performScrollToNode(hasTestTag("notice-unavailable:notification:503:1"))
        compose.onNodeWithTag("notice-row:notification:503:1").assertHasNoClickAction()
        assertEquals(1,calls("/v6/notification/list"))
        assertEquals(listOf("502"),requests.filter { it.url.encodedPath == "/v6/feed/detail" }.map { it.url.queryParameter("id") })
    }
    @Test fun localFiveHundredRowCapStopsPagingAndRefreshReplacesRatherThanContinues() {
        cappedList = true; show("notifications:MENTIONS"); awaitTag("notice-open:feed:10001:0")
        compose.onNodeWithTag("notification-list").performScrollToIndex(501)
        compose.onNodeWithText("已加载500条，达到本次浏览上限；可刷新以更新列表。").assertIsDisplayed()
        compose.onNodeWithText("加载更多").assertDoesNotExist()
        assertEquals(1,calls("/v6/notification/atMeList"))
        capture("after-local-list-cap")
        compose.onNodeWithTag("notification-list").performScrollToIndex(0)
        compose.runOnIdle { cappedList = false }
        compose.onNodeWithText("刷新").performClick(); awaitText("合成提及动态正文")
        compose.onNodeWithTag("notice-row:feed:10001:0").assertDoesNotExist()
        compose.onNodeWithTag("notification-list").performScrollToNode(hasText("加载更多"))
        compose.onNodeWithText("加载更多").assertIsDisplayed()
        compose.onNodeWithText("已加载500条，达到本次浏览上限；可刷新以更新列表。").assertDoesNotExist()
        val reads = requests.filter { it.url.encodedPath == "/v6/notification/atMeList" }
        assertEquals(2,reads.size); assertTrue(reads.all { it.url.queryParameter("page") == "1" })
    }
    @Test fun validPostWithFailedCommentsKeepsRealPostAndCommentControls() {
        commentsFailure = true; show("feed:101")
        awaitText("合成详情完整正文"); awaitText("请求失败（HTTP 503）")
        compose.onNodeWithText("回复动态").assertIsDisplayed(); compose.onNodeWithText("只看楼主").assertIsDisplayed()
        compose.onNodeWithText("重新加载评论").assertIsDisplayed()
    }
    @Test fun pendingFirstDetailReadDoesNotBecomeAnUnavailablePost() {
        delayedFeed = CountDownLatch(1); show("feed:101")
        awaitTag("feed-detail-loading")
        compose.onNodeWithText("回复动态").assertExists()
        compose.onNodeWithText("该动态不存在或暂不可见").assertDoesNotExist()
        delayedFeed!!.countDown(); awaitText("合成详情完整正文")
    }
    @Test fun sameReplyIdOnThreeLikePagesKeepsDistinctActorsAndLoadsThirdPage() {
        repeatedLikePages = true; show("notifications:LIKES"); awaitText("分页点赞者10")
        for (uid in listOf(12,14)) {
            compose.onNodeWithTag("notification-list").performScrollToNode(hasText("加载更多"))
            compose.onNodeWithText("加载更多").performClick(); awaitText("分页点赞者$uid")
        }
        compose.onNodeWithTag("notification-list").performScrollToNode(hasTestTag("notice-actor:feed_reply:606:2"))
        compose.onNodeWithTag("notice-actor:feed_reply:606:2").assertTextEquals("分页点赞者14").performClick()
        awaitTag("user-name:14"); back(); awaitTag("notification-list")
        for (index in 0..2) {
            compose.onNodeWithTag("notification-list").performScrollToNode(hasTestTag("notice-actor:feed_reply:606:$index"))
            compose.onNodeWithTag("notice-actor:feed_reply:606:$index").assertTextEquals("分页点赞者${10+index*2}")
        }
        compose.onNodeWithText("加载更多").assertDoesNotExist()
        val reads = requests.filter { it.url.encodedPath == "/v6/notification/feedLikeList" }
        assertEquals(listOf("1","2","3"),reads.map { it.url.queryParameter("page") })
        reads.drop(1).forEach { assertEquals("606",it.url.queryParameter("firstItem")); assertEquals("606",it.url.queryParameter("lastItem")) }
    }
    private fun openRow(identity: String) {
        compose.onNodeWithTag("notification-list").performScrollToNode(hasTestTag("notice-open:$identity"))
        compose.onNodeWithTag("notice-open:$identity").performClick()
    }
    private fun show(initialRoute: String? = null) {
        val container = AppContainer(compose.activity.application).also { it.nativeUpdates.enabled = false }
        AppContainer::class.java.getDeclaredField("local").apply { isAccessible = true }.set(container, LocalRepository(MemoryLocal()))
        store = AccountStore(object : CredentialPersistence {
            private var bytes: ByteArray? = null
            override fun read() = bytes?.copyOf()
            override fun write(bytes: ByteArray) { this.bytes = bytes.copyOf() }
            override fun reset() { bytes = null }
        }, guestDeviceId = "synthetic-notification-device")
        for (id in listOf("1", "2")) store.saveAuthenticated(id, "合成账号$id", "synthetic-device-$id", listOf(Cookie.Builder()
            .name("token").value("synthetic-account-$id").domain("coolapk.com").path("/").secure()
            .expiresAt(System.currentTimeMillis() + 3_600_000).build()), store.snapshot())
        store.switchTo("1")
        val allowed = setOf("/v6/main/indexV8", "/v6/notification/atMeList", "/v6/notification/atCommentMeList", "/v6/notification/feedLikeList", "/v6/notification/contactsFollowList", "/v6/notification/list", "/v6/notification/checkCount", "/v6/user/profile", "/v6/feed/detail", "/v6/feed/replyList", "/v6/user/space", "/v6/user/feedList")
        client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); val path = request.url.encodedPath
            val loginPreparation = request.method == "GET" && request.url.host == "account.coolapk.com" &&
                ((path == "/auth/login" && request.url.query == "type=mobile") || (path == "/auth/loginByCoolApk" && request.url.query == null))
            if (!loginPreparation && (request.method != "GET" || request.url.host !in setOf("api.coolapk.com", "api2.coolapk.com") || path !in allowed)) {
                unexpected += "${request.method} $path"; throw IOException("Sockets disabled in fixture")
            }
            requests += request
            if (loginPreparation) {
                // Opening the existing login page prepares only these two GETs; it must never
                // inherit the active account token or submit credentials in this route test.
                assertFalse(request.header("Cookie").orEmpty().contains("synthetic-account-"))
                return@addInterceptor Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
                    .body("<body data-request-hash='synthetic-request-hash'></body>".toResponseBody()).build()
            }
            val uid = request.url.queryParameter("uid") ?: "7"
            val page = request.url.queryParameter("page") ?: "1"
            val accountOne = request.header("Cookie").orEmpty().contains("token=synthetic-account-1")
            if (path == "/v6/notification/atMeList" && accountOne && delayedFirstAccount?.await(15, TimeUnit.SECONDS) == false)
                throw IOException("Synthetic list gate timed out")
            if (path == "/v6/feed/detail" && delayedFeed?.await(15, TimeUnit.SECONDS) == false)
                throw IOException("Synthetic detail gate timed out")
            var body = when(path) {
                "/v6/notification/atMeList" -> """{"data":[
                    {"id":"101","entityType":"feed","uid":"7","username":"提及作者","message":"合成提及动态正文"},
                    {"id":"202","entityType":"feed_reply","uid":"8","username":"评论作者","message":"合成评论节选","url":"/feed/303?rid=202"},
                    {"id":"403","entityType":"notification","fromuid":"9","uid":"1","fromusername":"通知作者","note":"在<a href='/feed/404'>这条动态</a>中提到了你"},
                    {"id":"405","entityType":"notification","fromuid":"9","fromusername":"未支持的通知","note":"请查看<a href='https://example.invalid/feed/999'>外部页面</a>"}
                ]}"""
                "/v6/notification/atCommentMeList" -> """{"data":[{"id":"202","entityType":"feed_reply","uid":"8","username":"评论作者","message":"合成评论节选","url":"/feed/303?rid=202"}]}"""
                "/v6/notification/feedLikeList" -> """{"data":[{"id":"606","entityType":"feed_reply","uid":"1","username":"合成账号1","message":"被赞的合成评论","url":"https://www.coolapk.com/feed/707?rid=606","likeUserInfo":{"uid":"10","username":"点赞用户"},"infoHtml":"评论"}]}"""
                "/v6/notification/contactsFollowList" -> """{"data":[{"id":"808","entityType":"notification","type":"contacts_follow","fromuid":"11","uid":"1","fromusername":"新关注用户","note":"关注了你","url":"/u/11"}]}"""
                "/v6/notification/list" -> """{"data":[{"id":"501","entityType":"notification","fromuid":"99","fromusername":"合成系统通知","note":"<a href='/feed/502'>系统相关动态</a>"},{"id":"503","entityType":"notification","note":"<a href='https://example.invalid/'>外部系统消息</a>"},{"entityType":"card","entityTemplate":"noMoreDataCard"}]}"""
                "/v6/user/profile" -> """{"data":{"uid":"$uid","username":"合成账号$uid"}}"""
                "/v6/notification/checkCount" -> """{"data":{"atme":1,"atcommentme":1,"feedlike":1,"contacts_follow":1,"message":0,"notification":0}}"""
                "/v6/feed/detail" -> """{"data":${post(request.url.queryParameter("id")!!, "合成详情完整正文")}}"""
                "/v6/user/space" -> """{"data":{"uid":"$uid","username":"合成用户$uid","userInfo":{"uid":"$uid","username":"合成用户$uid"},"feed":1,"follow":2,"fans":3}}"""
                "/v6/user/feedList" -> """{"data":[${post("90$uid", "合成用户动态$uid")},{"id":"end","entityType":"card","entityTemplate":"noMoreDataCard"}]}"""
                else -> """{"data":[]}"""
            }
            if (path == "/v6/notification/atMeList" && cappedList) body = """{"data":[""" + (10001..10500).joinToString(",") { post(it.toString(),"合成上限通知$it") } + "]}"
            if (path == "/v6/notification/atMeList" && !accountOne) body = """{"data":[${post("902","新账号通知正文")},{"id":"end","entityType":"card","entityTemplate":"noMoreDataCard"}]}"""
            if (path == "/v6/notification/feedLikeList" && repeatedLikes) body = """{"data":[
                {"id":"606","entityType":"feed_reply","uid":"1","username":"作者","message":"第一条点赞记录","url":"/feed/707?rid=606","likeUserInfo":{"uid":"10","username":"点赞用户"}},
                {"id":"606","entityType":"feed_reply","uid":"1","username":"作者","message":"第二条点赞记录","url":"/feed/707?rid=606","likeUserInfo":{"uid":"12","username":"第二位点赞用户"}},
                {"id":"606","entityType":"feed","uid":"13","username":"同ID的动态作者","message":"另一类实体"},
                {"entityType":"card","entityTemplate":"noMoreDataCard"}]}"""
            if (path == "/v6/notification/feedLikeList" && repeatedLikePages) {
                val actor = 8+page.toInt()*2
                body = """{"data":[{"id":"606","entityType":"feed_reply","uid":"1","username":"作者","message":"分页点赞记录$page","url":"/feed/707?rid=606","likeUserInfo":{"uid":"$actor","username":"分页点赞者$actor"}}""" +
                    (if (page == "3") ",{\"entityType\":\"card\",\"entityTemplate\":\"noMoreDataCard\"}" else "") + "]}"
            }
            if (path == "/v6/notification/atCommentMeList" && pagedReplies) {
                val ids = if (page == "1") 201..208 else 209..209
                body = """{"data":[""" + ids.joinToString(",") { id -> """{"id":"$id","entityType":"feed_reply","uid":"8","username":"评论作者$id","message":"分页合成评论$id","url":"/feed/${id+100}?rid=$id"}""" } +
                    (if (page == "1") "" else """,{"entityType":"card","entityTemplate":"noMoreDataCard"}""") + "]}"
            }
            if (path == "/v6/notification/atCommentMeList" && missingReplyLink)
                body = """{"data":[{"id":"202","entityType":"feed_reply","uid":"8","username":"评论作者","message":"无根动态地址的评论"}]}"""
            if (path == "/v6/feed/detail" && unavailableFeed) body = """{"code":404,"message":"动态不存在"}"""
            if (path == "/v6/notification/atMeList") body = when(listFailure) {
                "auth" -> """{"code":401,"message":"synthetic authentication rejected"}"""
                "captcha" -> """{"messageStatus":"err_request_captcha","message":"synthetic challenge"}"""
                else -> body
            }
            val status = if ((path == "/v6/notification/atMeList" && listFailure == "network") ||
                (path == "/v6/notification/atCommentMeList" && page == "2" && pageTwoFailure) ||
                (path == "/v6/feed/replyList" && commentsFailure)) 503 else 200
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("synthetic").body(body.toResponseBody()).build()
        }.build()
        val login = HttpLoginTransport(client)
        val repository = HttpCoolapkRepository(client = client, requestSession = { store.captureRequestSession() })
        val social = SocialRepository(store) { snapshot -> CoolapkHttpTransport(client = client, requestSession = { store.requestSessionFor(snapshot) }) }
        val ctor = AccountRuntime::class.java.getDeclaredConstructor(AccountStore::class.java, HttpLoginTransport::class.java,
            LoginCoordinator::class.java, HttpCoolapkRepository::class.java, SocialRepository::class.java).apply { isAccessible = true }
        val accounts = ctor.newInstance(store, login, LoginCoordinator(store, login, "synthetic-device"), repository, social)
        compose.setContent {
            val state by store.state.collectAsState()
            owner.select(state.generation)
            val model = remember(state.generation) { ViewModelProvider(owner, viewModelFactory { initializer { BrowseViewModel(repository) } })[BrowseViewModel::class.java] }
            MaterialTheme(colorScheme = fixedColorScheme(0, false), typography = appTypography(FontChoice.NOTO_SANS)) {
                key(state.generation) { NativeApp(model, container, AppSettings(dynamicColor = false), accounts, state, initialRoute) }
            }
        }
    }
    private fun post(id: String, text: String) = """{"id":"$id","entityType":"feed","uid":"7","username":"合成作者","message":"$text","message_raw_output":"[]","replynum":0}"""
    private fun calls(path: String) = requests.count { it.url.encodedPath == path }
    private fun awaitTag(tag: String) = compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitText(text: String) = compose.waitUntil(15_000) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    private fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.runOnUiThread {
            val view = compose.activity.findViewById<View>(android.R.id.content)
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        File("build/verification/notifications/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
