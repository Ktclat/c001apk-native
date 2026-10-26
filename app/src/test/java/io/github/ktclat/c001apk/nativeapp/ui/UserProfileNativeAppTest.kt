// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.ktclat.c001apk.nativeapp.AppContainer
import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.data.*
import io.github.ktclat.c001apk.nativeapp.feature.feed.SyntheticFeedImageLoader
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
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real NativeApp + HTTP repository/decoder. All responses/artwork are explicitly synthetic;
 * every network request terminates at this fixture, never a live service or account mutation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UserProfileNativeAppTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val requests = CopyOnWriteArrayList<Request>()
    private val unexpected = CopyOnWriteArrayList<String>()
    private val owner = SessionViewModelStore()
    private lateinit var images: SyntheticFeedImageLoader
    private lateinit var client: OkHttpClient
    private lateinit var store: AccountStore
    private val memory = MemoryLocal()
    private lateinit var browseModel: BrowseViewModel
    private var profileFailure = false
    private var feedFailure = false
    private var emptyFeed = false
    private var missingMetadata = false
    private var longMetadata = false
    private var longPreview = false
    @Volatile private var holdPreview = false
    private val previewEntered = CountDownLatch(1)
    private val previewRelease = CountDownLatch(1)
    private val previewParser: (String) -> ParsedPost = { html ->
        if (holdPreview) {
            previewEntered.countDown()
            check(previewRelease.await(15, TimeUnit.SECONDS)) { "Synthetic return-preview gate timed out" }
        }
        decodePostHtml(html)
    }
    private val oversizedAvatar = "https://synthetic.invalid/" + "a".repeat(2100) + ".png"
    private class MemoryLocal : JsonStateStore<LocalState> {
        override val data = MutableStateFlow(LocalState())
        override suspend fun update(transform: (LocalState) -> LocalState) { data.value = transform(data.value) }
    }
    @After fun close() {
        previewRelease.countDown()
        owner.viewModelStore.clear()
        if (::client.isInitialized) { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
        if (::images.isInitialized) {
            images.close()
            // This one explicit long-avatar case is a synthetic image failure, never a remote request.
            assertEquals(if (longMetadata) setOf(oversizedAvatar) else emptySet<String>(), images.unexpectedUrls)
        }
        assertTrue("No unexpected request/write: $unexpected", unexpected.isEmpty())
    }
    @Test fun avatarOpensSpaceProfileAndChildBackRetainsBothIndependentReads() {
        show()
        awaitTag("feed:42")
        compose.onNodeWithContentDescription("查看合成作者7的主页").performClick()
        awaitTag("user-name:7")
        compose.onNodeWithTag("user-name:7").assertTextEquals("合成用户7")
        compose.onNodeWithText("24 动态").assertIsDisplayed()
        compose.onNodeWithText("106 赞").assertIsDisplayed()
        compose.onNodeWithText("48 粉丝").assertIsDisplayed()
        awaitImages(); awaitText("合成用户动态7")
        compose.onNodeWithText("合成用户动态7").assertIsDisplayed()
        capture("after-avatar-user-route")
        compose.onNodeWithTag("content-list").performScrollToNode(hasTestTag("feed-body:707"))
        compose.onNodeWithTag("feed-body:707").performClick()
        awaitTag("feed:707")
        compose.onNodeWithContentDescription("查看合成作者8的主页").performClick()
        awaitTag("user-name:8")
        compose.onNodeWithTag("user-name:8").assertTextEquals("合成用户8")
        compose.onNodeWithTag("user-name:7").assertDoesNotExist()
        back(); awaitTag("feed:707"); back()
        awaitTag("feed:707")
        compose.onNodeWithTag("content-list").performScrollToIndex(0)
        compose.onNodeWithTag("user-name:7").assertTextEquals("合成用户7")
        assertEquals(2, calls("/v6/user/space"))
        assertEquals(2, calls("/v6/user/feedList"))
        capture("after-child-back")
        back(); awaitTag("feed:42")
        assertEquals(1, calls("/v6/main/indexV8"))
    }
    @Test fun childBackRetainsExistingUserFeedPreviewHeightWhileParserIsPending() {
        longPreview = true; show(initialRoute = "user:7")
        awaitTag("user-name:7"); awaitTag("feed:707")
        compose.onNodeWithTag("content-list").performScrollToNode(hasTestTag("feed-body:707"))
        awaitText("合成用户动态7")
        compose.onNodeWithTag("content-list").performScrollToNode(hasTestTag("feed-body:707"))
        val originalScroll = compose.onNodeWithTag("content-list").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("Open the child from a genuinely scrolled user feed", originalScroll > 0f)
        val originalTop = compose.onNodeWithTag("feed-body:707").fetchSemanticsNode().positionInRoot.y
        val measuredHeight = compose.onNodeWithTag("post-preview-block", useUnmergedTree = true).fetchSemanticsNode().size.height
        assertTrue("The fixture must have a genuinely tall prepared preview", measuredHeight > 300)
        capture("long-preview-prepared")
        compose.onNodeWithTag("feed-body:707").performClick()
        awaitTag("feed:707")
        compose.runOnIdle { holdPreview = true }
        back()
        compose.waitUntil(15_000) { compose.onRoot().fetchSemanticsNode(); previewEntered.count == 0L }
        // No corrective scroll or parser release before comparing the first restored pending layout.
        compose.onNodeWithText("正在排版…").assertExists()
        capture("long-preview-child-back-pending")
        assertEquals("Keep the old user route's owner-scoped reserved preview height", measuredHeight,
            compose.onNodeWithTag("post-preview-block", useUnmergedTree = true).fetchSemanticsNode().size.height)
        assertTrue("Reuse the existing route-owner geometry store", browseModel.previewGeometry.size() > 0)
        assertEquals("Retain the nonzero list scroll while parsing", originalScroll,
            compose.onNodeWithTag("content-list").fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange].value(), .001f)
        assertEquals("Preserve the restored body offset without corrective scrolling", originalTop,
            compose.onNodeWithTag("feed-body:707").fetchSemanticsNode().positionInRoot.y, 1f)
        previewRelease.countDown()
        awaitText("合成用户动态7")
        assertEquals(1, calls("/v6/user/space")); assertEquals(1, calls("/v6/user/feedList"))
        capture("after-long-preview-child-back")
    }
    @Test fun emptyFeedKeepsProfileAndExplicitRefreshReloadsBothSlices() {
        emptyFeed = true; show(initialRoute = "user:7")
        awaitTag("user-name:7"); awaitText("暂无内容")
        awaitImages(); capture("after-empty-feed")
        compose.onNodeWithContentDescription("刷新用户主页").performClick()
        compose.waitUntil(15_000) { calls("/v6/user/space") == 2 && calls("/v6/user/feedList") == 2 }
        awaitTag("user-name:7"); compose.onNodeWithTag("user-name:7").assertTextEquals("合成用户7")
    }
    @Test fun profileFailureLeavesWorkingFeedAndRetryDoesNotReloadFeed() {
        profileFailure = true; show(initialRoute = "user:7")
        awaitTag("user-profile-retry:7"); awaitTag("feed:707"); awaitText("合成用户动态7")
        compose.onNodeWithText("合成用户动态7").assertIsDisplayed()
        capture("after-profile-error")
        compose.runOnIdle { profileFailure = false }
        compose.onNodeWithTag("user-profile-retry:7").performClick()
        awaitTag("user-name:7")
        assertEquals(2, calls("/v6/user/space")); assertEquals(1, calls("/v6/user/feedList"))
        compose.onNodeWithTag("content-list").performScrollToNode(hasTestTag("feed-body:707"))
        compose.onNodeWithTag("feed-body:707").assertIsDisplayed()
    }
    @Test fun feedFailureLeavesProfileAndFeedRetryDoesNotReloadProfile() {
        feedFailure = true; show(initialRoute = "user:7")
        awaitTag("user-name:7"); awaitText("重试")
        awaitImages(); capture("after-feed-error")
        compose.runOnIdle { feedFailure = false }
        compose.onNodeWithTag("content-list").performScrollToNode(hasText("重试"))
        compose.onNodeWithText("重试").performClick()
        awaitTag("feed:707")
        assertEquals(1, calls("/v6/user/space")); assertEquals(2, calls("/v6/user/feedList"))
    }
    @Test fun missingMetadataDoesNotInventNameAvatarOrZeroCounts() {
        missingMetadata = true; emptyFeed = true; show(initialRoute = "user:7")
        awaitTag("user-name:7")
        compose.onNodeWithTag("user-name:7").assertTextEquals("用户名未提供")
        compose.onNodeWithText("0 动态").assertDoesNotExist(); compose.onNodeWithText("0 关注").assertDoesNotExist()
        compose.onNodeWithTag("user-cover:7").assertDoesNotExist()
        assertTrue(images.loadedUrls.isEmpty())
        capture("after-missing-metadata")
    }
    @Test fun blockedUserShowsIdentityWithoutReadingOrLeakingFeed() {
        memory.data.value = LocalState(blacklist = Blacklist(userBlackList = listOf("7")))
        show(initialRoute = "user:7")
        awaitTag("user-name:7"); awaitTag("user-feed-blocked")
        compose.onNodeWithContentDescription("刷新用户主页").performClick()
        compose.waitUntil(15_000) { calls("/v6/user/space") == 2 }
        assertEquals(0, calls("/v6/user/feedList")); compose.onNodeWithTag("feed:707").assertDoesNotExist()
        capture("after-blocked-user")
    }
    @Test fun localFollowStoresOnlyVerifiedProfileIdentityWithoutNetworkMutation() {
        emptyFeed = true; show(initialRoute = "user:7")
        awaitTag("user-name:7")
        compose.onNodeWithText("本地关注", substring = false).performClick()
        compose.waitUntil(15_000) { memory.data.value.follows.isNotEmpty() }
        assertEquals(LocalFollowUser("7", "合成用户7", SyntheticFeedImageLoader.AVATAR), memory.data.value.follows.single())
        assertTrue(requests.all { it.method == "GET" })
    }
    @Test fun longProfileMetadataStillFollowsWithValidBoundedLocalIdentity() {
        longMetadata = true; emptyFeed = true; show(initialRoute = "user:7")
        awaitTag("user-name:7")
        compose.waitUntil(15_000) { oversizedAvatar in images.unexpectedUrls }
        compose.onNodeWithTag("content-list").performScrollToNode(hasText("本地关注", substring = false))
        compose.onNodeWithText("本地关注", substring = false).performClick()
        compose.waitUntil(15_000) { memory.data.value.follows.isNotEmpty() }
        val saved = memory.data.value.follows.single()
        assertEquals("7", saved.uid)
        assertEquals("x".repeat(199), saved.name) // Does not split the emoji across the 200-unit boundary.
        assertNull(saved.avatar) // Drop the optional oversized URL; never truncate/rewrite its target.
        assertTrue(requests.all { it.method == "GET" })
        capture("after-long-profile-local-follow")
    }
    @Test fun darkLargeFontHeaderWrapsAndFeedRemainsReachable() {
        show(scale = 1.6f, dark = true, initialRoute = "user:7")
        awaitTag("user-name:7"); awaitImages()
        capture("after-dark-font1.6")
        compose.onNodeWithTag("content-list").performScrollToNode(hasTestTag("feed-body:707"))
        compose.onNodeWithTag("feed-body:707").assertIsDisplayed().performClick()
        awaitTag("feed:707")
    }
    @Test fun twoTimesFontKeepsLocalFollowAndManageTargetsReachable() {
        emptyFeed = true; show(scale = 2f, initialRoute = "user:7")
        awaitTag("user-name:7"); awaitImages()
        compose.onNodeWithTag("content-list").performScrollToNode(hasText("管理名单"))
        compose.onNodeWithText("本地关注", substring = false).assertIsDisplayed()
        compose.onNodeWithText("管理名单").assertIsDisplayed()
        val rootBounds = compose.onRoot().getUnclippedBoundsInRoot()
        for (label in listOf("本地关注", "管理名单")) {
            val bounds = compose.onNodeWithText(label, substring = false).getUnclippedBoundsInRoot()
            assertTrue(bounds.left >= rootBounds.left && bounds.right <= rootBounds.right)
        }
        capture("after-font2-local-controls")
    }
    @Test fun malformedUserRouteMakesNoProfileOrFeedRequests() {
        show(initialRoute = "user:0")
        awaitText("用户 ID 格式无效，未读取用户资料")
        compose.onNodeWithContentDescription("刷新用户主页").assertIsNotEnabled()
        assertTrue(requests.isEmpty())
    }
    private fun awaitImages() = compose.waitUntil(15_000) {
        images.loadedUrls.containsAll(setOf(SyntheticFeedImageLoader.AVATAR, SyntheticFeedImageLoader.LANDSCAPE))
    }
    private fun show(scale: Float = 1f, dark: Boolean = false, initialRoute: String? = null) {
        images = SyntheticFeedImageLoader(compose.activity)
        val container = AppContainer(compose.activity.application).also { it.nativeUpdates.enabled = false }
        AppContainer::class.java.getDeclaredField("local").apply { isAccessible = true }.set(container, LocalRepository(memory))
        store = AccountStore(object : CredentialPersistence {
            override fun read(): ByteArray? = null
            override fun write(bytes: ByteArray) = Unit
            override fun reset() = Unit
        }, guestDeviceId = "synthetic-profile-device")
        client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); val path = request.url.encodedPath
            if (request.method != "GET" || request.url.host !in setOf("api.coolapk.com", "api2.coolapk.com") ||
                path !in setOf("/v6/main/indexV8", "/v6/feed/detail", "/v6/feed/replyList", "/v6/user/space", "/v6/user/feedList")) {
                unexpected += "${request.method} $path"; throw IOException("Sockets disabled in fixture")
            }
            requests += request
            val uid = request.url.queryParameter("uid") ?: "7"
            val profileName = if (longMetadata) "x".repeat(199) + "😀" + "y".repeat(399) else "合成用户$uid"
            val profileAvatar = if (longMetadata) oversizedAvatar else SyntheticFeedImageLoader.AVATAR
            val status = if ((path == "/v6/user/space" && profileFailure) || (path == "/v6/user/feedList" && feedFailure)) 503 else 200
            val body = when (path) {
                "/v6/main/indexV8" -> """{"data":[${post("42", "7", "合成首页动态")}]}"""
                "/v6/user/space" -> if (missingMetadata) """{"data":{"uid":"$uid"}}""" else """{"data":{"uid":"$uid","username":"根级名称不应覆盖嵌套名称","userInfo":{"uid":"$uid","username":"$profileName"},"userAvatar":"$profileAvatar","cover":"${SyntheticFeedImageLoader.LANDSCAPE}","level":6,"feed":24,"be_like_num":106,"follow":12,"fans":48}}"""
                "/v6/user/feedList" -> if (emptyFeed) """{"data":[]}""" else """{"data":[${post("70$uid", uid, "合成用户动态$uid" + if (longPreview) "返回时仍应保留长预览高度。".repeat(30) else "")}]}"""
                "/v6/feed/detail" -> """{"data":${post(request.url.queryParameter("id")!!, "8", "合成详情正文")}}"""
                else -> """{"data":[]}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("synthetic")
                .body(body.toResponseBody()).build() // Never chain.proceed.
        }.build()
        val login = HttpLoginTransport(client)
        val repository = HttpCoolapkRepository(client = client, requestSession = { store.captureRequestSession() })
        val social = SocialRepository(store) { snapshot -> CoolapkHttpTransport(client = client, requestSession = { store.requestSessionFor(snapshot) }) }
        val ctor = AccountRuntime::class.java.getDeclaredConstructor(AccountStore::class.java, HttpLoginTransport::class.java,
            LoginCoordinator::class.java, HttpCoolapkRepository::class.java, SocialRepository::class.java).apply { isAccessible = true }
        val accounts = ctor.newInstance(store, login, LoginCoordinator(store, login, "synthetic-profile-device"), repository, social)
        compose.setContent {
            val state by store.state.collectAsState()
            owner.select(state.generation)
            val model = remember(state.generation) { ViewModelProvider(owner, viewModelFactory { initializer { BrowseViewModel(repository) } })[BrowseViewModel::class.java] }
            browseModel = model
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner, LocalMediaImageLoader provides images.loader,
                LocalPreviewPostParser provides previewParser,
                LocalDensity provides Density(LocalDensity.current.density, scale),
                LocalReadingStyles provides readingStyles(ReadingPreset.COMFORTABLE, FontChoice.NOTO_SANS)) {
                MaterialTheme(colorScheme = fixedColorScheme(0, dark), typography = appTypography(FontChoice.NOTO_SANS)) {
                    key(state.generation) { NativeApp(model, container, AppSettings(floatingNavigation = true, dynamicColor = false), accounts, state, initialRoute) }
                }
            }
        }
    }
    private fun post(id: String, uid: String, message: String) = """{"id":"$id","entityType":"feed","uid":"$uid","username":"合成作者$uid","userAvatar":"${SyntheticFeedImageLoader.AVATAR}","message":"$message","message_raw_output":"[]","replynum":0}"""
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
        File("build/verification/user-profile/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
