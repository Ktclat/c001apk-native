// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.size
import androidx.core.graphics.Insets
import androidx.core.view.DisplayCutoutCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import io.github.ktclat.c001apk.nativeapp.AppContainer
import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.data.*
import io.github.ktclat.c001apk.nativeapp.domain.*
import io.github.ktclat.c001apk.nativeapp.local.*
import io.github.ktclat.c001apk.nativeapp.settings.*
import io.github.ktclat.c001apk.nativeapp.social.SocialRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.max

/**
 * OPTIONAL next regression gate. Not part of immutable build38's 27-case adaptive suite.
 * Real NativeApp, synthetic repositories/account/local state, actual Android View PNGs only.
 * The visible-dock cases intentionally retain strict >=48dp and full-containment requirements;
 * current source has a physical short-height conflict, so this file carries NO pass claim.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w480dp-h240dp-land-320dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShortWindowRootStateRenderingTest {
    @get:Rule(order = 0) val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule(order = 1) val testName = TestName()
    @get:Rule(order = 2) val diagnostics = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            runCatching { capture("${description.methodName}-FAILED") }
            runCatching { output("${description.methodName}-FAILED.txt").writeText(
                "${error.javaClass.name}: ${error.message}\nrequests=$requests\nunexpected=$unexpected\n" +
                    compose.onRoot(useUnmergedTree = true).printToString()) }
        }
    }
    private enum class Root { HOME, ONLINE_FOLLOW, LOCAL_FOLLOW, HOME_TOPICS, MY, TOPIC_DETAIL }
    private enum class State { EMPTY, ERROR, LOADING }
    private class MemoryStore : JsonStateStore<LocalState> {
        override val data = MutableStateFlow(LocalState())
        override suspend fun update(transform: (LocalState) -> LocalState) { data.value = LocalRepository.validateState(transform(data.value)) }
    }
    private val requests = CopyOnWriteArrayList<String>()
    private val unexpected = CopyOnWriteArrayList<String>()
    private val clients = mutableListOf<OkHttpClient>()
    private val myScroll = ScrollState(0)
    private var totalConsumedY = 0f
    private val scrollObserver = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            totalConsumedY += consumed.y
            return Offset.Zero
        }
    }
    private var cutoutLeft = 0
    private var cutoutRight = 0
    private val density get() = compose.activity.resources.displayMetrics.density
    private val dock get() = compose.onNodeWithTag("floating-navigation")
    private val list get() = compose.onNodeWithTag("content-list")
    private val retry get() = compose.onNode(hasText("重试") and hasClickAction())

    @After fun closeFixture() {
        clients.forEach { it.dispatcher.cancelAll(); it.connectionPool.evictAll(); it.dispatcher.executorService.shutdownNow() }
        assertTrue("No live endpoint or unexpected repository method is permitted: $unexpected", unexpected.isEmpty())
    }

    @Test fun myFinalActionMustFitAboveVisibleDockAt480x240Font2() {
        render(Root.MY, State.EMPTY)
        compose.runOnIdle { runBlocking { myScroll.scrollTo(myScroll.maxValue) } }
        compose.waitForIdle()
        assertEquals("Must really be at My end", myScroll.maxValue, myScroll.value)
        assertAboveVisibleDock(compose.onNode(hasText("原生客户端版本与更新") and hasClickAction()), "my-root-list", clickable = true)
    }
    @Test fun homeEmptyMessageMustFitAboveVisibleDockAt480x240Font2() {
        render(Root.HOME, State.EMPTY)
        reachLazyEndWithVisibleDock()
        assertAboveVisibleDock(compose.onNodeWithText("暂无内容"), "content-list", clickable = false)
    }
    @Test fun onlineFollowEmptyMessageMustFitAboveVisibleDockAt480x240Font2() {
        render(Root.ONLINE_FOLLOW, State.EMPTY)
        reachLazyEndWithVisibleDock()
        assertAboveVisibleDock(compose.onNodeWithText("暂无内容"), "content-list", clickable = false)
    }
    @Test fun homeLoadingIndicatorMustFitAboveVisibleDockAt480x240Font2() {
        render(Root.HOME, State.LOADING)
        reachLazyEndWithVisibleDock()
        assertAboveVisibleDock(compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)), "content-list", clickable = false)
        assertTrue("Synthetic pending state must not open a repository request", requests.isEmpty())
    }
    @Test fun onlineFollowLongErrorRetryMustFitAboveVisibleDockAt480x240Font2() {
        render(Root.ONLINE_FOLLOW, State.ERROR)
        reachLazyEndWithVisibleDock()
        assertAboveVisibleDock(retry, "content-list", clickable = true)
    }
    @Test fun homeTopicsPageErrorRetryMustFitAboveVisibleDockAt480x240Font2() {
        render(Root.HOME_TOPICS, State.ERROR)
        reachLazyEndWithVisibleDock()
        assertAboveVisibleDock(retry, "content-list", clickable = true)
        assertTrue(requests.contains("page:V11_VERTICAL_TOPIC"))
    }
    @Test fun onlineFollowLongErrorRetryWithSideCutoutMustFitAboveVisibleDock() {
        render(Root.ONLINE_FOLLOW, State.ERROR, sideCutout = true)
        reachLazyEndWithVisibleDock()
        assertAboveVisibleDock(retry, "content-list", clickable = true)
    }

    @Test fun localFollowEmptyManagementActionMustFitAboveVisibleDockAt480x240Font2() {
        render(Root.LOCAL_FOLLOW, State.EMPTY, sideCutout = true)
        reachLazyEndWithVisibleDock("local-follow-list")
        assertAboveVisibleDock(compose.onNodeWithText("管理本地关注（0）"), "local-follow-list", clickable = true)
    }
    @Test fun myDockCanReappearAtAbsoluteEndWithoutCoveringFinalAction() {
        render(Root.MY, State.EMPTY)
        val myList = compose.onNodeWithTag("my-root-list")
        dragForward("my-root-list")
        compose.waitForIdle()
        dock.assertDoesNotExist()
        compose.runOnIdle { runBlocking { myScroll.scrollTo(myScroll.maxValue) } }
        compose.waitForIdle()
        dock.assertDoesNotExist()
        myList.performTouchInput { swipe(Offset(centerX, height * .10f), Offset(centerX, height * .60f), durationMillis = 600) }
        compose.waitForIdle()
        dock.assertIsDisplayed()
        compose.runOnIdle { runBlocking { myScroll.scrollTo(myScroll.maxValue) } }
        compose.waitForIdle()
        assertEquals(myScroll.maxValue, myScroll.value)
        assertAboveVisibleDock(compose.onNode(hasText("原生客户端版本与更新") and hasClickAction()), "my-root-list", clickable = true)
    }
    @Test fun onlineFollowDockCanReappearAtAbsoluteEndWithoutCoveringRetry() {
        render(Root.ONLINE_FOLLOW, State.ERROR, sideCutout = true)
        dragForward()
        dock.assertDoesNotExist()
        reachLazyEndWithVisibleDock()
        assertAboveVisibleDock(retry, "content-list", clickable = true)
    }

    // Separate real reachability checks, not substitutes for the visible-dock regression gates.
    @Test fun onlineFollowRetryIsReachableAfterActualUserScrollHidesDock() {
        render(Root.ONLINE_FOLLOW, State.ERROR, sideCutout = true)
        dragForward()
        dock.assertDoesNotExist()
        retry.performScrollTo()
        compose.waitForIdle()
        dock.assertDoesNotExist()
        assertFullyInsideContent(retry, "content-list", clickable = true)
        val before = requests.count { it == "following" }
        retry.performClick()
        compose.waitUntil(5_000) { requests.count { it == "following" } == before + 1 }
        capture(testName.methodName)
    }
    @Test fun myFinalActionIsReachableAfterActualUserScrollHidesDock() {
        render(Root.MY, State.EMPTY)
        dragForward("my-root-list")
        compose.waitForIdle()
        dock.assertDoesNotExist()
        val action = compose.onNode(hasText("原生客户端版本与更新") and hasClickAction())
        action.performScrollTo()
        compose.waitForIdle()
        dock.assertDoesNotExist()
        assertFullyInsideContent(action, "my-root-list", clickable = true)
        capture(testName.methodName)
    }
    @Test fun topicDetailErrorRetryIsReachableWithoutInventingATopicSidebar() {
        render(Root.TOPIC_DETAIL, State.ERROR, sideCutout = true)
        dock.assertDoesNotExist()
        compose.onNodeWithTag("navigation-rail").assertDoesNotExist()
        retry.performScrollTo()
        compose.waitForIdle()
        assertFullyInsideContent(retry, "content-list", clickable = true)
        val before = requests.count { it == "topicDetail:合成话题" }
        retry.performClick()
        compose.waitUntil(5_000) { requests.count { it == "topicDetail:合成话题" } == before + 1 }
        capture(testName.methodName)
    }

    /** ScrollBy is real accessibility UserInput, not programmatic setup. Explicitly reveal using
     * 24 consumed dp, then return those same24dp (<28 hide threshold) to the absolute end. */
    private fun reachLazyEndWithVisibleDock(listTag: String = "content-list") {
        val list = compose.onNodeWithTag(listTag)
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        compose.waitForIdle()
        var range = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals("Must reach absolute lazy end", range.maxValue(), range.value(), .01f)
        if (compose.onAllNodesWithTag("floating-navigation").fetchSemanticsNodes().isEmpty()) {
            val beforeReverse = totalConsumedY
            list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -24f * density) }
            compose.waitForIdle()
            range = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            // Lazy semantic ranges encode an item index, so their difference is NOT pixel motion
            // when crossing an item boundary. Observe actual nested-scroll consumption instead.
            assertEquals("Reveal must consume exactly24dp", 24f * density, totalConsumedY - beforeReverse, 1f)
            dock.assertIsDisplayed()
            list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 24f * density) }
            compose.waitForIdle()
        }
        range = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals("Final measurement must still be at absolute end", range.maxValue(), range.value(), .01f)
        assertDock()
    }
    private fun assertAboveVisibleDock(node: SemanticsNodeInteraction, listTag: String, clickable: Boolean) {
        assertDock()
        val viewport = compose.onNodeWithTag(listTag).getUnclippedBoundsInRoot()
        val full = node.getUnclippedBoundsInRoot()
        val dockTop = dock.getUnclippedBoundsInRoot().top.value
        val availableHeight = dockTop - 8f - viewport.top.value
        val requiredHeight = max(if (clickable) 48f else 0f, full.size.height.value)
        capture(testName.methodName)
        assertTrue("Physical fit conflict: only${availableHeight}dp above dock, target needs${requiredHeight}dp. " +
            "Do not shrink hitboxes, remove assertions, replace the dock with a rail, or add more tail padding.", availableHeight >= requiredHeight - .5f)
        assertFullyInsideContent(node, listTag, clickable)
        val bottom = if (clickable) node.fetchSemanticsNode().touchBoundsInRoot.bottom / density else full.bottom.value
        assertTrue("Entire target, not its center, must clear visible dock by8dp", bottom <= dockTop - 8f + .5f)
    }
    private fun assertFullyInsideContent(node: SemanticsNodeInteraction, listTag: String, clickable: Boolean) {
        node.assertIsDisplayed()
        val full = node.getUnclippedBoundsInRoot()
        val semantic = node.fetchSemanticsNode()
        val viewport = compose.onNodeWithTag(listTag).getUnclippedBoundsInRoot()
        assertTrue("Visual content must be vertically unclipped", abs(full.size.height.value - semantic.boundsInRoot.height / density) <= .5f)
        assertTrue("Visual content must be horizontally unclipped", abs(full.size.width.value - semantic.boundsInRoot.width / density) <= .5f)
        val target = if (clickable) {
            node.assertHasClickAction()
            semantic.touchBoundsInRoot.also {
                assertTrue("Real touch width must stay >=48dp", it.width / density >= 47.5f)
                assertTrue("Real touch height must stay >=48dp", it.height / density >= 47.5f)
            }
        } else semantic.boundsInRoot
        assertTrue("Entire target top must be inside actual content viewport", target.top / density >= viewport.top.value - .5f)
        assertTrue("Entire target bottom must be inside actual content viewport", target.bottom / density <= viewport.bottom.value + .5f)
        assertTrue("Entire target must clear left cutout", target.left / density >= cutoutLeft - .5f)
        assertTrue("Entire target must clear right cutout", target.right / density <= 480f - cutoutRight + .5f)
    }
    private fun assertDock() {
        dock.assertIsDisplayed()
        compose.onNodeWithTag("navigation-rail").assertDoesNotExist()
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        assertEquals(480f, root.size.width.value, .5f); assertEquals(240f, root.size.height.value, .5f)
        val rect = dock.getUnclippedBoundsInRoot()
        assertEquals("Font2 dock height", 72f, rect.size.height.value, .5f)
        assertEquals("Use bottom system inset exactly once", 240f - 24f - 8f, rect.bottom.value, .5f)
        assertTrue("Still a horizontal floating dock", rect.size.width.value > 2f * rect.size.height.value)
        listOf("首页", "关注", "我的").forEach { label ->
            val touch = rootTab(label).assertIsDisplayed().assertHasClickAction().fetchSemanticsNode().touchBoundsInRoot
            assertTrue(touch.width / density >= 47.5f && touch.height / density >= 47.5f)
            assertTrue(touch.left / density >= cutoutLeft && touch.right / density <= 480f - cutoutRight)
        }
    }
    private fun rootTab(label: String) = compose.onNode(hasText(label) and
        SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected) and hasAnyAncestor(hasTestTag("floating-navigation")))
    private fun dragForward(listTag: String = "content-list") {
        val list = compose.onNodeWithTag(listTag)
        val viewport = list.fetchSemanticsNode().boundsInRoot
        val overlay = dock.fetchSemanticsNode().boundsInRoot
        // At240dp height, a75%-height start is inside the overlay and legitimately cannot
        // scroll the underlying content. Begin in the actual unobstructed content band.
        val startY = minOf(viewport.height * .75f, overlay.top - viewport.top - 12f * density)
        val endY = maxOf(8f * density, startY - 80f * density)
        assertTrue("The real pointer must start above the overlay", viewport.top + startY < overlay.top)
        assertTrue("There must be enough visible content for a genuine drag", startY - endY > 48f * density)
        val before = totalConsumedY
        list.performTouchInput {
            swipe(Offset(centerX, startY), Offset(centerX, endY), durationMillis = 600)
        }
        compose.waitForIdle()
        assertTrue("Real content consumption must exceed28dp before expecting hide: " +
            "${(before - totalConsumedY) / density}dp", before - totalConsumedY > 28f * density)
    }
    private fun awaitText(value: String) { compose.waitUntil(5_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }; compose.waitForIdle() }

    private fun render(target: Root, fixture: State, sideCutout: Boolean = false) {
        cutoutLeft = if (sideCutout) 36 else 0; cutoutRight = if (sideCutout) 12 else 0
        val container = AppContainer(compose.activity.application)
        container.nativeUpdates.enabled = false
        val local = LocalRepository(MemoryStore())
        AppContainer::class.java.getDeclaredField("local").apply { isAccessible = true }.set(container, local)
        val accounts = syntheticAccounts()
        val accountSnapshot = accounts.store.state.value
        val repository = Proxy.newProxyInstance(CoolapkRepository::class.java.classLoader, arrayOf(CoolapkRepository::class.java)) { _, method, args ->
            val name = when (method.name) {
                "home", "following" -> method.name
                "page", "topicDetail" -> "${method.name}:${args!![0]}"
                "toString" -> return@newProxyInstance "ShortWindowSyntheticRepository"
                else -> { unexpected += method.name; throw AssertionError("Unexpected repository call ${method.name}") }
            }
            requests += name
            val selectedRequest = when (target) {
                Root.HOME -> name == "home"
                Root.ONLINE_FOLLOW -> name == "following"
                Root.HOME_TOPICS -> name == "page:V11_VERTICAL_TOPIC"
                Root.MY, Root.LOCAL_FOLLOW -> false
                Root.TOPIC_DETAIL -> name == "topicDetail:合成话题"
            }
            if (selectedRequest && fixture == State.ERROR) ApiResult.Failure(FailureKind.NETWORK, LONG_ERROR)
            else ApiResult.Success(Page<ContentItem>(emptyList(), null))
        } as CoolapkRepository
        val model = BrowseViewModel(repository)
        if (fixture == State.LOADING) {
            // Synthetic pending BrowseState, not a sleeping thread or a real network request.
            // load("home") sees loading=true and does not launch a repository request.
            @Suppress("UNCHECKED_CAST")
            val flow = BrowseViewModel::class.java.getDeclaredField("mutable").apply { isAccessible = true }
                .get(model) as MutableStateFlow<Map<String, BrowseState>>
            flow.value = mapOf("home" to BrowseState(loading = true))
        }
        compose.runOnUiThread { WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false) }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f),
                LocalReadingStyles provides readingStyles(ReadingPreset.COMFORTABLE, FontChoice.NOTO_SANS)) {
                MaterialTheme(colorScheme = flutterReferenceColorScheme(), typography = appTypography(FontChoice.NOTO_SANS)) {
                    Box(Modifier.fillMaxSize().nestedScroll(scrollObserver)) {
                        NativeApp(model, container, AppSettings(floatingNavigation = true, dynamicColor = false, secretFollow = target == Root.LOCAL_FOLLOW), accounts,
                            accountSnapshot, initialRoute = if (target == Root.TOPIC_DETAIL) "topic:合成话题" else null, myScrollState = myScroll)
                    }
                }
            }
        }
        compose.runOnUiThread {
            fun px(dp: Int) = (dp * density).toInt()
            val builder = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, px(24), 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, px(24)))
                .setVisible(WindowInsetsCompat.Type.systemBars(), true)
            if (sideCutout) builder.setDisplayCutout(DisplayCutoutCompat(Rect(px(cutoutLeft), 0, px(cutoutRight), 0),
                listOf(Rect(0, px(48), px(cutoutLeft), px(96)), Rect(px(480 - cutoutRight), px(48), px(480), px(96)))))
            ViewCompat.dispatchApplyWindowInsets(compose.activity.window.decorView, builder.build())
        }
        if (target !in listOf(Root.HOME, Root.TOPIC_DETAIL)) {
            awaitText("暂无内容")
            when (target) {
                Root.ONLINE_FOLLOW, Root.LOCAL_FOLLOW -> rootTab("关注").performClick()
                Root.MY -> rootTab("我的").performClick()
                Root.HOME_TOPICS -> compose.onNodeWithTag("home-category:3").performClick()
                else -> Unit
            }
        }
        when {
            target == Root.MY -> compose.waitForIdle()
            target == Root.LOCAL_FOLLOW -> awaitText("尚未关注用户。添加用户后，将合并他们的公开动态。")
            fixture == State.ERROR -> awaitText(LONG_ERROR)
            fixture == State.EMPTY -> awaitText("暂无内容")
            else -> {
                compose.waitUntil(5_000) { compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).fetchSemanticsNodes().size == 1 &&
                    compose.onAllNodesWithText("正在读取本地过滤设置").fetchSemanticsNodes().isEmpty() }
                compose.waitForIdle()
            }
        }
        val window = compose.onRoot().getUnclippedBoundsInRoot()
        assertEquals("Actual rendered width, not only qualifier metadata", 480f, window.size.width.value, .5f)
        assertEquals("Actual rendered height, not only qualifier metadata", 240f, window.size.height.value, .5f)
    }
    private fun syntheticAccounts(): AccountRuntime {
        val store = AccountStore(object : CredentialPersistence {
            private var bytes: ByteArray? = null
            override fun read() = bytes?.copyOf()
            override fun write(bytes: ByteArray) { this.bytes = bytes.copyOf() }
            override fun reset() { bytes = null }
        })
        store.saveAuthenticated("99", "合成短窗账号", "synthetic-device", listOf(Cookie.Builder().name("token")
            .value("synthetic-not-a-real-token").domain("coolapk.com").path("/").secure()
            .expiresAt(System.currentTimeMillis() + 3_600_000).build()), store.snapshot())
        val client = OkHttpClient.Builder().addInterceptor { unexpected += "blocked:${it.request().url}"; throw IOException("No network in short-window fixture") }.build()
        clients += client
        val endpoint = ApiEndpoints("http://127.0.0.1:9/".toHttpUrl(), "http://127.0.0.1:9/".toHttpUrl())
        val login = HttpLoginTransport(client)
        val social = SocialRepository(store) { snapshot -> CoolapkHttpTransport(client = client, endpoints = endpoint, requestSession = { store.requestSessionFor(snapshot) }) }
        val constructor = AccountRuntime::class.java.getDeclaredConstructor(AccountStore::class.java, HttpLoginTransport::class.java,
            LoginCoordinator::class.java, HttpCoolapkRepository::class.java, SocialRepository::class.java).apply { isAccessible = true }
        return constructor.newInstance(store, login, LoginCoordinator(store, login, "synthetic-device"), HttpCoolapkRepository(client = client, endpoints = endpoint), social)
    }
    private fun output(name: String) = File("build/verification/short-window-root-state/$name").apply { parentFile?.mkdirs() }
    private fun capture(name: String) {
        val bitmap = compose.runOnUiThread {
            val view = compose.activity.findViewById<View>(android.R.id.content)
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        output("$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
    companion object {
        private val LONG_ERROR = "合成网络错误：请求尚未完成。请保留此提示，检查连接后重试。此段落只用于短窗口、大字体和侧边挖孔的真实布局验证。".repeat(5)
    }
}
