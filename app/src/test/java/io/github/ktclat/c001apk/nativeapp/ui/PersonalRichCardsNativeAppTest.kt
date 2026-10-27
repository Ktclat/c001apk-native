// SPDX-License-Identifier: AGPL-3.0-only
// Real NativeApp + personal repository/decoder; every API/image response is synthetic/offline.
package io.github.ktclat.c001apk.nativeapp.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import io.github.ktclat.c001apk.nativeapp.AppContainer
import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.data.*
import io.github.ktclat.c001apk.nativeapp.feature.media.LocalMediaImageLoader
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
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PersonalRichCardsNativeAppTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val requests = CopyOnWriteArrayList<Request>()
    private val unexpected = CopyOnWriteArrayList<String>()
    private lateinit var client: OkHttpClient
    private lateinit var images: IconFixtureImages
    private lateinit var store: AccountStore
    private val owner = SessionViewModelStore()
    private class MemoryLocal : JsonStateStore<LocalState> {
        override val data = MutableStateFlow(LocalState())
        override suspend fun update(transform: (LocalState) -> LocalState) { data.value = transform(data.value) }
    }
    private val feed = """{"id":"9101","entityType":"feed","uid":"7","username":"合成作者","userAvatar":"https://fixture.invalid/icon-4.png","message_title":"我的完整卡片","message":"正文与图片一起展示","device_title":"合成设备","likenum":0,"replynum":2,"userAction":{"like":1},"picArr":["https://fixture.invalid/icon-1.png","https://fixture.invalid/icon-2.png"],"forwardSourceType":"feed","forwardSourceFeed":{"id":"9201","uid":"8","username":"转发作者","message":"转发正文仍然可读"},"replyRows":[{"id":"9301","entityType":"feed_reply","uid":"9","username":"热评作者","message":"热评没有丢失"}],"extra_title":"原生链接卡片","extra_url":"/feed/9202","relationRows":[{"id":"44","entityType":"topic","title":"关联话题","url":"/t/fixture-topic"}]}"""
    private val reply = """{"id":"9302","entityType":"feed_reply","uid":"7","username":"回复作者","message":"我的回复包含图片","url":"/feed/9501","likenum":0,"replynum":1,"userAction":{"like":1},"picArr":["https://fixture.invalid/icon-3.png"],"replyRows":[{"id":"9303","entityType":"feed_reply","username":"嵌套作者","message":"楼中楼仍然展示"}]}"""
    private val end = """{"id":"9999","entityType":"card","entityTemplate":"noMoreDataCard","title":"没有更多"}"""
    private val templates = """{"id":"9601","entityType":"card","entityTemplate":"messageCard","description":"账号列表也支持消息卡片"},{"id":"9602","entityType":"card","entityTemplate":"iconLinkGridCard","entities":[{"id":"9603","title":"打开关联动态","pic":"https://fixture.invalid/icon-5.png","url":"/feed/9603"}]}"""
    @After fun close() {
        owner.viewModelStore.clear()
        if (::images.isInitialized) images.close()
        if (::client.isInitialized) { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
        assertTrue("No unexpected API/write $unexpected", unexpected.isEmpty())
        assertTrue(requests.all { it.method == "GET" })
    }
    @Test fun myFeedsMainGalleryForwardAuthorAndBack() {
        showMy(); openMy("动态"); awaitTag("feed:9101")
        assertReadOnlyFeed(); awaitImages(1, 2, 4); capture("feed-initial")
        compose.onNodeWithTag("media-cell-0").performClick(); awaitTag("media-gallery")
        compose.onNodeWithTag("media-page-count").assertTextEquals("1/2")
        capture("feed-gallery", dialog = true)
        compose.onNodeWithTag("media-back").performClick(); compose.onNodeWithTag("media-gallery").assertDoesNotExist()
        compose.onNodeWithTag("feed-body:9101").performScrollTo().performClick(); awaitText("已打开详情 9101")
        capture("feed-open-detail"); back(); awaitTag("feed:9101")
        // Baseline is a fresh account-bound first-page read on Back, not scroll retention.
        assertEquals(2, calls("/v6/user/feedList", "uid", "123"))
        compose.onNodeWithText("@转发作者：转发正文仍然可读").performScrollTo().performClick()
        awaitText("已打开详情 9201"); capture("feed-open-forward"); back(); awaitTag("feed:9101")
        compose.onNodeWithContentDescription("查看合成作者的主页").performScrollTo().performClick()
        awaitTag("user-name:7"); capture("feed-open-author"); back(); awaitTag("feed:9101")
        compose.onNodeWithText("热评作者：热评没有丢失").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("关联话题").performScrollTo().assertIsDisplayed(); capture("feed-reference-and-relations")
        assertEquals(listOf("9101", "9201"), requests.filter { it.url.encodedPath == "/v6/feed/detail" }.map { it.url.queryParameter("id") })
        assertEquals("7", requests.single { it.url.encodedPath == "/v6/user/space" }.url.queryParameter("uid"))
    }
    @Test fun myLikesKeepsLikeAuthorAndKnownTemplateChildren() {
        showMy(); openMy("我的赞"); awaitTag("feed:9101")
        compose.onNodeWithText("喜欢者赞了这条内容").assertIsDisplayed(); assertReadOnlyFeed()
        awaitImages(1, 2, 4); capture("likes-rich-feed")
        compose.onNodeWithTag("personal-list").performScrollToNode(hasText("账号列表也支持消息卡片"))
        compose.onNodeWithText("账号列表也支持消息卡片").assertIsDisplayed()
        compose.onNodeWithTag("personal-list").performScrollToNode(hasTestTag("icon-link-entry-0"))
        awaitImages(5); capture("likes-known-templates")
        compose.onNodeWithText("此卡片的模板或嵌套内容暂不支持").assertDoesNotExist()
        compose.onNodeWithText("加载更多").assertDoesNotExist()
        compose.onNodeWithTag("icon-link-entry-0").performClick(); awaitText("已打开详情 9603")
        back(); awaitTag("feed:9101"); assertReadOnlyFeed()
    }
    @Test fun myRepliesAndReceivedRepliesUseExplicitFeedUrlNotReplyId() {
        showMy(); openMy("我的回复"); awaitTag("personal-reply-open:9302")
        awaitImages(3); awaitText("楼中楼仍然展示"); compose.onNodeWithText("楼中楼仍然展示").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("reply-like:9302").assertIsNotEnabled()
        compose.onNodeWithText("回复", substring = false).assertDoesNotExist(); capture("reply-rich-row")
        compose.onNodeWithTag("personal-reply-open:9302").performClick(); awaitText("已打开详情 9501")
        back(); awaitTag("personal-reply-open:9302")
        compose.onNodeWithText("我收到的回复").performClick(); awaitTag("personal-reply-open:9302")
        assertEquals(1, calls("/v6/user/replyToMeList")); capture("received-reply")
        assertEquals(listOf("9501"), requests.filter { it.url.encodedPath == "/v6/feed/detail" }.map { it.url.queryParameter("id") })
        assertFalse(requests.any { it.url.encodedPath == "/v6/feed/replyList" && it.url.queryParameter("feedType") == "feed_reply" })
    }
    @Test fun collectionRowsStayOnAccountScopedCollectionRouteAndCanOpenFeed() {
        showMy(); openMy("我的收藏"); awaitTag("entity-card:collection:88")
        compose.onNodeWithTag("entity-card:collection:88").performClick(); awaitTag("feed:9101")
        val items = requests.single { it.url.encodedPath == "/v6/collection/itemList" }
        assertEquals("88", items.url.queryParameter("id")); assertEquals("123", items.url.queryParameter("uid"))
        assertReadOnlyFeed(); awaitImages(1, 2, 4); capture("collection-items-rich-feed")
        compose.onNodeWithTag("feed-body:9101").performClick(); awaitText("已打开详情 9101")
        back(); awaitTag("feed:9101"); back(); awaitTag("entity-card:collection:88")
        capture("collection-back")
    }
    @Test @Config(qualifiers = "w320dp-h600dp-320dpi") fun darkLargeFontCanReachImagesTemplatesAndBack() {
        showMy(dark = true, scale = 2f); openMy("我的赞"); awaitTag("feed:9101")
        capture("likes-dark-large-font-top")
        compose.onNodeWithTag("personal-list").performScrollToNode(hasTestTag("icon-link-entry-0"))
        compose.onNodeWithTag("icon-link-entry-0").assertIsDisplayed(); awaitImages(5)
        capture("likes-dark-large-font-template")
        compose.onNodeWithTag("icon-link-entry-0").performClick(); awaitText("已打开详情 9603")
        back(); awaitTag("feed:9101"); compose.onNodeWithTag("feed-more:9101").assertDoesNotExist()
    }
    private fun showMy(dark: Boolean = false, scale: Float = 1f) {
        images = IconFixtureImages(compose.activity)
        store = AccountStore(object : CredentialPersistence {
            override fun read(): ByteArray? = null
            override fun write(bytes: ByteArray) = Unit
            override fun reset() = Unit
        }, "synthetic-personal-device")
        val cookie = Cookie.Builder().name("token").value("synthetic-personal-owner").domain("coolapk.com")
            .path("/").secure().expiresAt(System.currentTimeMillis() + 3_600_000).build()
        check(store.saveAuthenticated("123", "合成账号", "synthetic-personal-device", listOf(cookie), store.snapshot()))
        client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); requests += request
            if (request.method != "GET" || request.url.host !in setOf("api.coolapk.com", "api2.coolapk.com")) {
                unexpected += "${request.method} ${request.url.encodedPath}"; throw IOException("No sockets")
            }
            val path = request.url.encodedPath
            val body = when (path) {
                "/v6/main/indexV8", "/v6/feed/replyList" -> """{"data":[]}"""
                "/v6/user/profile" -> """{"data":{"uid":"123","username":"合成账号","feed":3,"follow":2,"fans":1}}"""
                "/v6/notification/checkCount" -> """{"data":{"atme":0,"atcommentme":0,"feedlike":0,"contacts_follow":0,"message":0,"notification":0}}"""
                "/v6/user/space" -> """{"data":{"uid":"7","username":"合成作者","follow":2,"fans":1}}"""
                "/v6/user/feedList" -> if (request.url.queryParameter("uid") == "7") """{"data":[]}""" else """{"data":[$feed,$end]}"""
                "/v6/user/likeList" -> """{"data":[${feed.dropLast(1)},"likeUserInfo":{"username":"喜欢者"}},$templates,$end]}"""
                "/v6/user/replyList", "/v6/user/replyToMeList" -> """{"data":[$reply,$end]}"""
                "/v6/collection/list" -> """{"data":[{"id":"88","entityType":"collection","title":"合成收藏夹","item_num":1},$end]}"""
                "/v6/collection/itemList" -> """{"data":[$feed,$end]}"""
                "/v6/feed/detail" -> """{"data":{"id":"${request.url.queryParameter("id")}","entityType":"feed","uid":"7","username":"合成作者","message":"已打开详情 ${request.url.queryParameter("id")}"}}"""
                else -> { unexpected += path; throw IOException("Unexpected offline request") }
            }
            if (path in setOf("/v6/user/likeList", "/v6/user/replyList", "/v6/user/replyToMeList", "/v6/collection/list", "/v6/collection/itemList") || path == "/v6/user/feedList" && request.url.queryParameter("uid") == "123") {
                check(request.header("Cookie").orEmpty().contains("token=synthetic-personal-owner"))
                check(request.url.queryParameter("page") == "1")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
                .body(body.toResponseBody()).build() // NEVER chain.proceed; no live service/CAPTCHA/write.
        }.build()
        val repository = HttpCoolapkRepository(client = client, requestSession = { store.captureRequestSession() })
        val login = HttpLoginTransport(client)
        val social = SocialRepository(store) { snapshot -> CoolapkHttpTransport(client = client, requestSession = { store.requestSessionFor(snapshot) }) }
        val ctor = AccountRuntime::class.java.getDeclaredConstructor(AccountStore::class.java, HttpLoginTransport::class.java,
            LoginCoordinator::class.java, HttpCoolapkRepository::class.java, SocialRepository::class.java).apply { isAccessible = true }
        val accounts = ctor.newInstance(store, login, LoginCoordinator(store, login, "synthetic-personal-device"), repository, social)
        val container = AppContainer(compose.activity.application).also { it.nativeUpdates.enabled = false }
        AppContainer::class.java.getDeclaredField("local").apply { isAccessible = true }.set(container, LocalRepository(MemoryLocal()))
        val model = BrowseViewModel(repository)
        owner.viewModelStore.put("personal-cards", model)
        compose.setContent {
            val state by store.state.collectAsState()
            CompositionLocalProvider(LocalMediaImageLoader provides images.loader,
                LocalDensity provides Density(LocalDensity.current.density, scale),
                LocalReadingStyles provides readingStyles(ReadingPreset.COMFORTABLE, FontChoice.NOTO_SANS)) {
                MaterialTheme(colorScheme = fixedColorScheme(0, dark), typography = appTypography(FontChoice.NOTO_SANS)) {
                    NativeApp(model, container, AppSettings(floatingNavigation = false, dynamicColor = false), accounts, state)
                }
            }
        }
        awaitTag("standard-navigation"); compose.onNodeWithText("我的").performClick(); awaitTag("my-root-list")
        awaitText("合成账号")
    }
    private fun openMy(label: String) {
        compose.onNode(hasText(label) and hasClickAction() and hasAnyAncestor(hasTestTag("my-root-list"))).performScrollTo().performClick()
    }
    private fun assertReadOnlyFeed() {
        compose.onNodeWithTag("feed-like:9101").assertIsNotEnabled()
        compose.onNodeWithTag("feed-more:9101").assertDoesNotExist()
    }
    private fun awaitTag(tag: String) = compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitText(text: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitImages(vararg ids: Int) = compose.waitUntil(5_000) { compose.onRoot().fetchSemanticsNode(); ids.all { "https://fixture.invalid/icon-$it.png" in images.loaded } }
    private fun calls(path: String, key: String? = null, value: String? = null) = requests.count { it.url.encodedPath == path && (key == null || it.url.queryParameter(key) == value) }
    private fun back() { compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() } }
    private fun capture(name: String, dialog: Boolean = false) {
        compose.waitForIdle()
        val bitmap = compose.runOnUiThread {
            val view = if (dialog) requireNotNull(ShadowDialog.getShownDialogs().last { it.isShowing }.window).decorView
                else compose.activity.findViewById<View>(android.R.id.content)
            check(view.width > 0 && view.height > 0)
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val out = File("build/verification/personal-rich-cards").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
        File(out, "$name.txt").writeText("Real NativeApp; synthetic account and intercepted GETs only. No live acceptance.\n" +
            "Paths=${requests.map { it.url.encodedPath }}\nLoaded images=${images.loaded.sorted()}\n" + compose.onAllNodes(isRoot(), useUnmergedTree = true).printToString())
    }
}
