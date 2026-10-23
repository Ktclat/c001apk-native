// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import java.io.File
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real NativeApp navigation -> account-scoped production repositories -> intercepted wire request.
 * All credentials and rows are synthetic. Neither interceptor ever calls chain.proceed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MyRootRequestRoutingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val myScroll = androidx.compose.foundation.ScrollState(0)
    private var unknownProfile = false
    private var reportedInsets = Triple(0, 0, 0)
    private var previousSystemFontScale: Float? = null
    private var verifiedProfileRequests = 0
    private var verifiedCountsRequests = 0
    private val countsRequests = CopyOnWriteArrayList<Request>()
    private val profileRequests = CopyOnWriteArrayList<Request>()
    private lateinit var images: io.github.ktclat.c001apk.nativeapp.feature.feed.SyntheticFeedImageLoader
    private val requests = CopyOnWriteArrayList<Request>()
    private val unexpected = CopyOnWriteArrayList<String>()
    private val clients = mutableListOf<OkHttpClient>()
    private lateinit var accounts: AccountRuntime
    private lateinit var container: AppContainer
    @Volatile private var delayedFirstCollection: CountDownLatch? = null
    private val firstCollectionReturned = CountDownLatch(1)
    private val notices = listOf(
        "@我的动态" to "/v6/notification/atMeList",
        "@我的评论" to "/v6/notification/atCommentMeList",
        "我收到的赞" to "/v6/notification/feedLikeList",
        "好友关注" to "/v6/notification/contactsFollowList",
        "私信与信息" to "/v6/message/list",
    )
    private val personal = listOf(
        "关注" to "/v6/user/followList", "粉丝" to "/v6/user/fansList",
        "我的常去" to "/v6/user/recentHistoryList", "我的收藏" to "/v6/collection/list",
        "我的赞" to "/v6/user/likeList", "我的回复" to "/v6/user/replyList",
    )
    private class MemoryLocal : JsonStateStore<LocalState> {
        override val data = MutableStateFlow(LocalState())
        override suspend fun update(transform: (LocalState) -> LocalState) {
            data.value = LocalRepository.validateState(transform(data.value))
        }
    }

    @Before fun setup() {
        images = io.github.ktclat.c001apk.nativeapp.feature.feed.SyntheticFeedImageLoader(compose.activity)
        container = AppContainer(compose.activity.application)
        container.nativeUpdates.enabled = false // Suppress the unrelated real release-check client.
        AppContainer::class.java.getDeclaredField("local").apply { isAccessible = true }
            .set(container, LocalRepository(MemoryLocal()))
        val store = AccountStore(object : CredentialPersistence {
            private var saved: ByteArray? = null
            override fun read() = saved?.copyOf()
            override fun write(bytes: ByteArray) { saved = bytes.copyOf() }
            override fun reset() { saved = null }
        })
        for (id in listOf("1", "2")) {
            val cookie = Cookie.Builder().name("token").value("synthetic-account-$id")
                .domain("coolapk.com").path("/").secure()
                .expiresAt(System.currentTimeMillis() + 3_600_000).build()
            assertTrue(store.saveAuthenticated(id, "synthetic user $id", "synthetic-device-$id", listOf(cookie), store.snapshot()))
        }
        store.switchTo("1")
        val allowed = (notices + personal).map { it.second }.toSet() + setOf("/v6/main/indexV8", "/v6/user/profile", "/v6/user/feedList", "/v6/notification/checkCount")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.host !in setOf("api.coolapk.com", "api2.coolapk.com") || request.url.encodedPath !in allowed || request.method != "GET") {
                unexpected += "${request.method} ${request.url.encodedPath}"
                throw IOException("Unexpected synthetic route; sockets are disabled")
            }
            val isProfile = request.url.encodedPath == "/v6/user/profile"
            val isCounts = request.url.encodedPath == "/v6/notification/checkCount"
            // Independently verify both My side reads; destination assertions below stay exact.
            if (isProfile) profileRequests += request else if (isCounts) countsRequests += request else requests += request
            var body = """{"data":[]}"""
            if (isCounts) {
                if (request.url.query != null || request.body != null ||
                    listOf("1", "2").count { request.header("Cookie").orEmpty().contains("token=synthetic-account-$it") } != 1) {
                    unexpected += "Counts account/query/body mismatch"
                    throw IOException("Synthetic counts must use the exact authenticated GET")
                }
                body = """{"data":{"atme":1,"atcommentme":0,"feedlike":100,"contacts_follow":5,"message":3,"notification":4}}"""
            }
            if (isProfile) {
                val uid = request.url.queryParameter("uid")
                val cookie = request.header("Cookie").orEmpty()
                if (uid !in setOf("1", "2") || !cookie.contains("token=synthetic-account-$uid")) {
                    unexpected += "Profile account/UID mismatch"
                    throw IOException("Synthetic profile must be account-bound")
                }
                val avatar = io.github.ktclat.c001apk.nativeapp.feature.feed.SyntheticFeedImageLoader.PROFILE_AVATAR
                body = """{"data":{"uid":"$uid","username":"synthetic profile $uid","userAvatar":"$avatar","level":7,"experience":25,"next_level_experience":100,"feed":12,"follow":34,"fans":56}}"""
                if (unknownProfile) body = """{"data":{"uid":"$uid","username":"synthetic profile $uid"}}"""
            }
            if (request.url.encodedPath == "/v6/collection/list" && delayedFirstCollection != null) {
                val first = request.header("Cookie").orEmpty().contains("synthetic-account-1")
                if (first && delayedFirstCollection?.await(10, TimeUnit.SECONDS) == false) throw IOException("Synthetic gate timed out")
                val label = if (first) "stale-account-one" else "fresh-account-two"
                body = """{"data":[{"entityType":"collection","id":"55","title":"$label"}]}"""
                if (first) firstCollectionReturned.countDown()
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
                .body(body.toResponseBody()).build()
            // No chain.proceed: even official URL/cookie-policy checks never reach DNS or sockets.
        }.build()
        val loginClient = OkHttpClient.Builder().addInterceptor {
            // Login preparation is a real root side-effect but must remain offline and never submit credentials.
            if (it.request().method != "GET") unexpected += "Unexpected login write"
            throw IOException("Synthetic login fixture: no external transport")
        }.build()
        clients += listOf(client, loginClient)
        val repository = HttpCoolapkRepository(client = client, requestSession = { store.captureRequestSession() })
        val social = SocialRepository(store) { snapshot -> CoolapkHttpTransport(client = client, requestSession = { store.requestSessionFor(snapshot) }) }
        val login = HttpLoginTransport(loginClient)
        val ctor = AccountRuntime::class.java.getDeclaredConstructor(AccountStore::class.java,
            HttpLoginTransport::class.java, LoginCoordinator::class.java, HttpCoolapkRepository::class.java,
            SocialRepository::class.java).apply { isAccessible = true }
        accounts = ctor.newInstance(store, login, LoginCoordinator(store, login, "synthetic-device"), repository, social)
    }
    @After fun cleanup() {
        images.close()
        compose.runOnUiThread {
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(compose.activity.window.decorView, null)
            previousSystemFontScale?.let(org.robolectric.RuntimeEnvironment::setFontScale)
        }
        assertEquals("Every profile request must be verified", verifiedProfileRequests, profileRequests.size)
        assertEquals("Every counts request must be verified", verifiedCountsRequests, countsRequests.size)
        assertTrue("No unexpected image route: ${images.unexpectedUrls}", images.unexpectedUrls.isEmpty())
        delayedFirstCollection?.countDown()
        clients.forEach { it.dispatcher.cancelAll(); it.connectionPool.evictAll(); it.dispatcher.executorService.shutdownNow() }
        assertTrue("No unexpected request permitted: $unexpected", unexpected.isEmpty())
    }

    @Test fun fiveActualMyNoticeCardsSelectExactInitialWireCategory() {
        showMy()
        compose.onNodeWithText("synthetic profile 1").assertIsDisplayed()
        compose.onNodeWithText("Lv.7").assertExists()
        compose.onNodeWithText("25/100").assertExists()
        listOf("12", "34", "56").forEach { compose.onNodeWithText(it).assertExists() }
        compose.waitUntil(5_000) { images.loadedUrls.contains(io.github.ktclat.c001apk.nativeapp.feature.feed.SyntheticFeedImageLoader.PROFILE_AVATAR) }
        capture("native-my-authenticated-393x852-synthetic-no-live-account.png")
        for ((label, path) in notices) {
            val before = requests.size
            clickMy(label)
            awaitRequest(path, before)
            assertAccount(requests.drop(before).single(), "1")
            assertEquals(path, requests.drop(before).single().url.encodedPath)
            backToMy()
        }
    }
    @Test fun sixActualMyPersonalShortcutsDispatchExactScopedQueries() {
        showMy()
        for ((label, path) in personal) {
            val before = requests.size
            clickMy(label)
            awaitRequest(path, before)
            compose.onNodeWithTag("personal-list").assertExists()
            val request = requests.drop(before).single()
            assertAccount(request, "1")
            assertEquals("1", request.url.queryParameter("page"))
            if (path == "/v6/collection/list") {
                assertNull(request.url.queryParameter("uid"))
                assertEquals("0", request.url.queryParameter("showDefault"))
            } else assertEquals("1", request.url.queryParameter("uid"))
            backToMy()
        }
    }
    @Test fun actualMyFeedStatisticDispatchesExactFlutterQuery() {
        showMy()
        val before = requests.size
        clickMy("动态")
        awaitRequest("/v6/user/feedList", before)
        compose.onNodeWithTag("personal-list").assertExists()
        val request = requests.drop(before).single()
        assertAccount(request, "1")
        assertEquals("/v6/user/feedList", request.url.encodedPath)
        assertEquals(setOf("page", "uid", "showAnonymous", "isIncludeTop"), request.url.queryParameterNames)
        assertEquals("1", request.url.queryParameter("page"))
        assertEquals("1", request.url.queryParameter("uid"))
        assertEquals("0", request.url.queryParameter("showAnonymous"))
        assertEquals("1", request.url.queryParameter("isIncludeTop"))
        backToMy()
    }
    @Test fun guestProtectedShortcutsOpenRealLoginWithoutAccountListRequests() {
        accounts.store.useGuest()
        showMy()
        // FOLLOW/FAN are intentionally absent for guests; the other9 protected cards remain.
        for (label in notices.map { it.first } + personal.drop(2).map { it.first }) {
            val before = requests.size
            clickMy(label)
            compose.onNodeWithText("登录酷安账号").assertExists()
            compose.waitForIdle()
            assertEquals(before, requests.size)
            backToMy()
        }
        compose.onNode(hasText("粉丝") and hasClickAction()).assertDoesNotExist()
        assertTrue("Guest must not request an own profile", profileRequests.isEmpty())
        assertTrue("Guest must not request unread counts", countsRequests.isEmpty())
    }
    @Test fun actualAccountToolbarSwitchChangesNextRequestScopeAndGuestModeGatesIt() {
        showMy()
        compose.onNodeWithContentDescription("账号管理").performClick()
        compose.onNode(hasText("切换") and isEnabled()).performScrollTo().performClick()
        compose.waitUntil(5_000) { accounts.store.state.value.activeId == "2" }
        backToMy()
        compose.onNodeWithText("synthetic profile 2").assertExists()
        val before = requests.size
        clickMy("粉丝")
        awaitRequest("/v6/user/fansList", before)
        val request = requests.drop(before).single()
        assertAccount(request, "2")
        assertEquals("2", request.url.queryParameter("uid"))
        backToMy()
        compose.onNodeWithContentDescription("账号管理").performClick()
        compose.onNodeWithText("使用游客模式（保留账号）").performScrollTo().performClick()
        compose.waitUntil(5_000) { accounts.store.state.value.activeId == null }
        backToMy()
        val guestBefore = requests.size
        clickMy("我的收藏")
        compose.onNodeWithText("登录酷安账号").assertExists()
        assertEquals(guestBefore, requests.size)
        assertEquals(2, accounts.store.state.value.accounts.size)
    }
    @Test fun accountGenerationChangeDiscardsLatePersonalRowsInActualRoot() {
        delayedFirstCollection = CountDownLatch(1)
        showMy()
        val before = requests.size
        clickMy("我的收藏")
        awaitRequest("/v6/collection/list", before)
        // Simulate an external authenticated-account change while this actual route is loading.
        compose.runOnIdle { accounts.store.switchTo("2") }
        compose.waitUntil(5_000) { requests.drop(before).count { it.url.encodedPath == "/v6/collection/list" } == 2 }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("fresh-account-two").fetchSemanticsNodes().isNotEmpty() }
        delayedFirstCollection!!.countDown()
        compose.waitUntil(5_000) { firstCollectionReturned.count == 0L }
        compose.waitForIdle()
        compose.onNodeWithText("stale-account-one").assertDoesNotExist()
        compose.onNodeWithText("fresh-account-two").assertExists()
        val scoped = requests.drop(before)
        assertEquals(2, scoped.size)
        assertAccount(scoped[0], "1"); assertAccount(scoped[1], "2")
        compose.onNodeWithText("fresh-account-two").assertIsDisplayed()
        capture("native-collection-populated-393x852-synthetic-no-live-account.png")
    }
    @Test @Config(qualifiers = "w369dp-h822dp-520dpi")
    fun populatedHeaderAtReportedUserViewport() {
        showMy(widthPx = 1200, heightPx = 2670, floating = true)
        assertViewport(1200, 2670, 3.25f)
        assertPopulatedHeader()
        capture("native-my-populated-user369x821-dpr3_25-synthetic.png")
        verifyLastActionAboveVisibleDock("user369x821")
    }
    @Test @Config(qualifiers = "w320dp-h480dp-160dpi")
    fun populatedHeaderAtCompactLargeTextViewport() {
        showMy(fontScale = 1.6f, widthPx = 320, heightPx = 480, floating = true)
        assertViewport(320, 480, 1f)
        assertPopulatedHeader()
        capture("native-my-populated-320x480-font1_6-synthetic.png")
        verifyLastActionAboveVisibleDock("compact320x480-font1_6")
    }
    @Test fun populatedHeaderDarkCapture() {
        showMy(dark = true)
        assertPopulatedHeader()
        capture("native-my-populated-393-dark-synthetic.png")
    }
    @Test @Config(qualifiers = "w369dp-h822dp-520dpi")
    fun unreadBadgeRowsAtReportedViewportLight() = verifyUnreadGeometry(dark = false, scale = 1f)
    @Test @Config(qualifiers = "w369dp-h822dp-520dpi")
    fun unreadBadgeRowsAtReportedViewportDarkTwoTimesText() = verifyUnreadGeometry(dark = true, scale = 2f)
    private fun verifyUnreadGeometry(dark: Boolean, scale: Float) {
        compose.runOnUiThread {
            previousSystemFontScale = org.robolectric.RuntimeEnvironment.getFontScale()
            org.robolectric.RuntimeEnvironment.setFontScale(scale)
        }
        showMy(fontScale = scale, dark = dark, widthPx = 1200, heightPx = 2670, floating = true)
        val fixtureInsets = androidx.core.view.WindowInsetsCompat.Builder()
            .setInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars(), androidx.core.graphics.Insets.of(0, 140, 0, 0))
            .setInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars(), androidx.core.graphics.Insets.NONE)
            .setInsets(androidx.core.view.WindowInsetsCompat.Type.systemGestures(), androidx.core.graphics.Insets.of(0, 179, 0, 0))
            .setInsets(androidx.core.view.WindowInsetsCompat.Type.ime(), androidx.core.graphics.Insets.NONE)
            .setVisible(androidx.core.view.WindowInsetsCompat.Type.statusBars(), true)
            .setVisible(androidx.core.view.WindowInsetsCompat.Type.navigationBars(), false).build()
        compose.runOnUiThread {
            val decor = compose.activity.window.decorView
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(decor) { view, _ -> androidx.core.view.ViewCompat.onApplyWindowInsets(view, fixtureInsets) }
            androidx.core.view.ViewCompat.dispatchApplyWindowInsets(decor, fixtureInsets)
        }
        compose.waitForIdle(); assertViewport(1200, 2670, 3.25f)
        assertEquals(scale, compose.activity.resources.configuration.fontScale, .001f)
        assertEquals(scale, compose.activity.application.resources.configuration.fontScale, .001f)
        assertEquals("Known fixture values are separate, never summed", Triple(140, 179, 0), reportedInsets)
        val label = if (dark) "dark-font200" else "light-font100"
        capture("native-my-unread-$label-top-reported-insets-synthetic.png")
        for ((index, category) in listOf("MENTIONS", "REPLIES", "LIKES", "FOLLOWERS", "MESSAGES").withIndex()) {
            val row = compose.onNodeWithTag("my-notice-$category").performScrollTo().assertIsDisplayed()
            val node = row.fetchSemanticsNode()
            assertTrue(node.boundsInRoot.height >= 48f * 3.25f)
            assertTrue("Row is clear of status bars", node.boundsInRoot.top >= 140f)
            assertTrue("Row center is clear of top gesture area", node.boundsInRoot.center.y >= 179f)
            val text = compose.onNodeWithText(notices[index].first, useUnmergedTree = true)
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            text.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty()); assertTrue("Notice label must not clip", layouts.none { it.hasVisualOverflow })
            val badge = compose.onNodeWithTag("my-notice-badge-$category", useUnmergedTree = true)
            if (category == "REPLIES") badge.assertDoesNotExist() else {
                val bounds = badge.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue(bounds.left >= text.fetchSemanticsNode().boundsInRoot.right)
                assertTrue(bounds.right <= node.boundsInRoot.right)
                assertTrue(bounds.top >= node.boundsInRoot.top && bounds.bottom <= node.boundsInRoot.bottom)
            }
            assertEquals(1, node.config[androidx.compose.ui.semantics.SemanticsProperties.Text].size)
        }
        capture("native-my-unread-$label-tail-reported-insets-synthetic.png")
        verifyLastActionAboveVisibleDock("unread-$label-reported-insets")
    }
    @Test fun unknownHeaderNeverInventsCountsOrLevel() {
        unknownProfile = true
        showMy()
        compose.onAllNodesWithText("未提供").assertCountEquals(3)
        compose.onNodeWithText("Lv.0").assertDoesNotExist()
        compose.onNodeWithText("0/1").assertDoesNotExist()
        compose.onNodeWithText("0").assertDoesNotExist()
        compose.onNodeWithContentDescription("刷新个人资料").assertIsEnabled()
        capture("native-my-unknown-393-synthetic.png")
    }
    private fun verifyLastActionAboveVisibleDock(label: String) {
        val list = compose.onNodeWithTag("my-root-list")
        // A real gesture may hide the dock; short content is allowed to keep it visible.
        list.performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.runOnIdle { kotlinx.coroutines.runBlocking { myScroll.scrollTo(myScroll.maxValue) } }
        compose.waitForIdle()
        assertEquals(myScroll.maxValue, myScroll.value)
        val recovery = list.fetchSemanticsNode().config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.CustomActions)
            ?.singleOrNull { it.label == SHOW_FLOATING_DOCK_ACTION }
        if (recovery != null) compose.runOnIdle { assertTrue(recovery.action()) }
        compose.waitForIdle()
        val dock = compose.onNodeWithTag("floating-navigation").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val last = compose.onNode(hasText("私信与信息") and hasClickAction() and hasAnyAncestor(hasTestTag("my-root-list")))
            .assertIsDisplayed().assertIsEnabled()
        val node = last.fetchSemanticsNode()
        val density = compose.activity.resources.displayMetrics.density
        assertTrue("Entire clipped final action must remain >=48dp", node.boundsInRoot.height >= 48f * density)
        assertTrue("Entire final touch target must remain >=48dp", node.touchBoundsInRoot.height >= 48f * density)
        assertTrue("Final visual target clears visible dock", node.boundsInRoot.bottom <= dock.top - 8f * density + 1f)
        assertTrue("Final touch target clears visible dock", node.touchBoundsInRoot.bottom <= dock.top - 8f * density + 1f)
        capture("native-my-end-visible-dock-$label-synthetic.png")
        val before = requests.size
        last.performClick()
        awaitRequest("/v6/message/list", before)
        assertAccount(requests.drop(before).single(), "1")
        backToMy()
        compose.runOnIdle { kotlinx.coroutines.runBlocking { myScroll.scrollTo(0) } }
        compose.onNodeWithText("synthetic profile 1").assertIsDisplayed()
    }
    private fun assertPopulatedHeader() {
        for (label in listOf("动态", "关注", "粉丝", "本地收藏", "浏览历史", "我的常去", "我的收藏", "我的赞", "我的回复")) {
            val bounds = compose.onNode(hasText(label) and hasClickAction() and hasAnyAncestor(hasTestTag("my-root-list")))
                .getUnclippedBoundsInRoot()
            assertTrue("$label keeps >=48dp target height", (bounds.bottom - bounds.top).value >= 48f)
        }
        val refreshBounds = compose.onNodeWithContentDescription("刷新个人资料").getUnclippedBoundsInRoot()
        assertTrue((refreshBounds.right - refreshBounds.left).value >= 48f && (refreshBounds.bottom - refreshBounds.top).value >= 48f)

        compose.onNodeWithText("synthetic profile 1").assertIsDisplayed()
        compose.onNodeWithText("Lv.7").assertExists()
        compose.onNodeWithText("25/100").assertExists()
        listOf("12", "34", "56").forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithContentDescription("刷新个人资料").assertIsEnabled()
        compose.waitUntil(5_000) { images.loadedUrls.contains(io.github.ktclat.c001apk.nativeapp.feature.feed.SyntheticFeedImageLoader.PROFILE_AVATAR) }
    }
    private fun assertViewport(width: Int, height: Int, density: Float) {
        val view = compose.activity.findViewById<View>(android.R.id.content)
        assertEquals(width, view.width); assertEquals(height, view.height)
        assertEquals(density, compose.activity.resources.displayMetrics.density, .001f)
        val bounds = compose.onRoot().getUnclippedBoundsInRoot()
        assertEquals(width / density, (bounds.right - bounds.left).value, .01f)
        assertEquals(height / density, (bounds.bottom - bounds.top).value, .01f)
        File("build/verification/native-my-viewport-${width}x${height}-synthetic.txt").apply { parentFile?.mkdirs() }.writeText(
            "Synthetic Android shadow display/window, not a device measurement.\n" +
                "physical=${view.width}x${view.height}; density=$density; logical=${(bounds.right - bounds.left).value}x${(bounds.bottom - bounds.top).value}\n" +
                "No user system-bar inset values are inferred; this fixture uses its Android test window insets.\n")
    }
    private fun showMy(fontScale: Float = 1f, dark: Boolean = false, widthPx: Int? = null, heightPx: Int? = null, floating: Boolean = false) {
        if (widthPx != null && heightPx != null) compose.runOnUiThread {
            org.robolectric.Shadows.shadowOf(org.robolectric.shadows.ShadowDisplay.getDefaultDisplay()).apply {
                setWidth(widthPx); setHeight(heightPx); setRealWidth(widthPx); setRealHeight(heightPx)
            }
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
            compose.activity.window.setLayout(widthPx, heightPx)
        }
        val model = BrowseViewModel(accounts.repository)
        compose.setContent {
            val state by accounts.store.state.collectAsState()
            val density = LocalDensity.current.density
            val drawing = androidx.compose.foundation.layout.WindowInsets.safeDrawing.getTop(LocalDensity.current)
            val gesture = androidx.compose.foundation.layout.WindowInsets.systemGestures.getTop(LocalDensity.current)
            val bottom = androidx.compose.foundation.layout.WindowInsets.safeDrawing.getBottom(LocalDensity.current)
            androidx.compose.runtime.SideEffect { reportedInsets = Triple(drawing, gesture, bottom) }
            CompositionLocalProvider(
                io.github.ktclat.c001apk.nativeapp.feature.media.LocalMediaImageLoader provides images.loader,
                LocalDensity provides Density(density, fontScale),
                LocalReadingStyles provides readingStyles(ReadingPreset.COMFORTABLE, FontChoice.NOTO_SANS),
            ) {
                MaterialTheme(colorScheme = fixedColorScheme(0, dark), typography = appTypography(FontChoice.NOTO_SANS)) {
                    NativeApp(model, container, AppSettings(dynamicColor = false, floatingNavigation = floating), accounts, state, myScrollState = myScroll)
                }
            }
        }
        // Synchronize the actual Compose root while awaiting the real asynchronous Home request.
        // A raw StateFlow-only poll does not perform the semantics/idle synchronization used by
        // every subsequent UI interaction. Keep the original state predicate and 5000ms bound.
        try {
            compose.waitUntil(5_000) {
                compose.onRoot(useUnmergedTree = true).fetchSemanticsNode()
                model.states.value["home"]?.loading == false
            }
        } catch (failure: ComposeTimeoutException) {
            // Observe, then rethrow the ORIGINAL failure; never rescue/retry/extend this gate.
            val rootState = model.states.value.mapValues { (_, state) ->
                "loading=${state.loading}, loaded=${state.loaded}, rows=${state.items.size}, error=${state.error}"
            }
            val lifecycle = compose.activity.lifecycle.currentState
            val safePaths = requests.map { "${it.method} ${it.url.encodedPath}" }
            val notes = StringBuilder("lifecycle=$lifecycle\nstates=$rootState\npaths=$safePaths\nunexpected=$unexpected\n")
            runCatching { notes.append(compose.onRoot(useUnmergedTree = true).printToString()) }
                .onFailure { notes.append("semantics observation failed: ${it.javaClass.name}\n") }
            runCatching { File("build/verification/my-root-readiness-failure-synthetic.txt").apply { parentFile?.mkdirs() }.writeText(notes.toString()) }
            throw failure
        }
        val profilesBefore = profileRequests.size
        val countsBefore = countsRequests.size
        compose.onNode(hasText("我的") and hasClickAction()).performClick()
        compose.onNodeWithTag("my-root-list").assertExists()
        awaitOwnProfile(profilesBefore)
        awaitCounts(countsBefore)
        compose.waitForIdle()
    }
    /** Actual Compose Android view, native graphics; never HTML or a redrawn mock screen. */
    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.runOnUiThread {
            val view = compose.activity.findViewById<View>(android.R.id.content)
            check(view.width > 0 && view.height > 0)
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val file = File("build/verification/$name").apply { parentFile?.mkdirs() }
        try {
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        fun sha(relative: String) = java.security.MessageDigest.getInstance("SHA-256")
            .digest(File("..", relative).readBytes()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val metadata = kotlinx.serialization.json.buildJsonObject {
            put("syntheticOnly", kotlinx.serialization.json.JsonPrimitive(true))
            put("sourceCommit", kotlinx.serialization.json.JsonPrimitive(io.github.ktclat.c001apk.nativeapp.diagnostics.NativeBuildInfo.COMMIT))
            put("sourceBranch", kotlinx.serialization.json.JsonPrimitive(io.github.ktclat.c001apk.nativeapp.diagnostics.NativeBuildInfo.BRANCH))
            put("sourceWorktree", kotlinx.serialization.json.JsonPrimitive(io.github.ktclat.c001apk.nativeapp.diagnostics.NativeBuildInfo.WORKTREE))
            put("sourceFingerprint", kotlinx.serialization.json.JsonPrimitive(io.github.ktclat.c001apk.nativeapp.diagnostics.NativeBuildInfo.SOURCE_FINGERPRINT))
            put("fingerprintScope", kotlinx.serialization.json.JsonPrimitive(io.github.ktclat.c001apk.nativeapp.diagnostics.NativeBuildInfo.FINGERPRINT_SCOPE))
            put("baselineIntegrationCommit", kotlinx.serialization.json.JsonPrimitive("fcf99a5c77345ce64e5df9876c835983cd3a40ed"))
            put("baselineSourceTree", kotlinx.serialization.json.JsonPrimitive("12c2fe802225441d126c51a6372a73a812bab645"))
            put("testSourceSha256", kotlinx.serialization.json.JsonPrimitive(sha("app/src/test/java/io/github/ktclat/c001apk/nativeapp/ui/MyRootRequestRoutingTest.kt")))
            put("dashboardSourceSha256", kotlinx.serialization.json.JsonPrimitive(sha("app/src/main/java/io/github/ktclat/c001apk/nativeapp/ui/MyDashboard.kt")))
            put("lifecycleSourceSha256", kotlinx.serialization.json.JsonPrimitive(sha("app/src/main/java/io/github/ktclat/c001apk/nativeapp/ui/OwnProfileDashboard.kt")))
            put("statusTopPx", kotlinx.serialization.json.JsonPrimitive(reportedInsets.first))
            put("gestureTopPx", kotlinx.serialization.json.JsonPrimitive(reportedInsets.second))
            put("bottomPx", kotlinx.serialization.json.JsonPrimitive(reportedInsets.third))
            put("systemFontScale", kotlinx.serialization.json.JsonPrimitive(compose.activity.resources.configuration.fontScale))
            put("fontChoice", kotlinx.serialization.json.JsonPrimitive("NOTO_SANS"))
        }
        File(file.parentFile, file.nameWithoutExtension + ".json").writeText(metadata.toString())
    }
    private fun clickMy(label: String) {
        compose.onNode(hasText(label) and hasClickAction() and hasAnyAncestor(hasTestTag("my-root-list")))
            .performScrollTo().performClick()
    }
    private fun backToMy() {
        val profilesBefore = profileRequests.size
        val countsBefore = countsRequests.size
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("my-root-list").assertExists()
        awaitOwnProfile(profilesBefore)
        awaitCounts(countsBefore)
    }
    private fun awaitOwnProfile(before: Int) {
        assertEquals("No unaccounted profile request between root entries", verifiedProfileRequests, before)
        val uid = personalListAccountId(accounts.store.state.value)
        if (uid == null) {
            compose.waitForIdle()
            assertEquals("Guest navigation must not request profile", before, profileRequests.size)
            return
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("synthetic profile $uid").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        val request = profileRequests.drop(before).single()
        assertAccount(request, uid)
        assertEquals("/v6/user/profile", request.url.encodedPath)
        assertEquals(setOf("uid"), request.url.queryParameterNames)
        assertEquals(uid, request.url.queryParameter("uid"))
        verifiedProfileRequests++
    }
    private fun awaitCounts(before: Int) {
        assertEquals("No unaccounted counts request between root entries", verifiedCountsRequests, before)
        val uid = personalListAccountId(accounts.store.state.value)
        if (uid == null) {
            compose.waitForIdle()
            assertEquals("Guest navigation must not request counts", before, countsRequests.size)
            return
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("my-notice-MENTIONS") and SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "1 条未读")).fetchSemanticsNodes().size == 1
        }
        compose.waitForIdle()
        val request = countsRequests.drop(before).single()
        assertAccount(request, uid)
        assertEquals("/v6/notification/checkCount", request.url.encodedPath)
        assertNull(request.url.query); assertNull(request.body)
        verifiedCountsRequests++
    }
    private fun awaitRequest(path: String, after: Int) {
        compose.waitUntil(5_000) { requests.drop(after).any { it.url.encodedPath == path } }
        compose.waitForIdle()
    }
    private fun assertAccount(request: Request, id: String) {
        assertEquals("GET", request.method)
        val cookie = request.header("Cookie").orEmpty()
        assertTrue("Synthetic account $id must own this request", cookie.contains("token=synthetic-account-$id"))
        assertFalse("Other synthetic account must not leak", cookie.contains("synthetic-account-${if (id == "1") "2" else "1"}"))
    }
}
