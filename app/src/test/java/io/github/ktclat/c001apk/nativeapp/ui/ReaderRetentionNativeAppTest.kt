// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.ktclat.c001apk.nativeapp.AppContainer
import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.data.*
import io.github.ktclat.c001apk.nativeapp.domain.FullPostBody
import io.github.ktclat.c001apk.nativeapp.domain.FullPostBodyLimit
import io.github.ktclat.c001apk.nativeapp.domain.ReplyOrder
import io.github.ktclat.c001apk.nativeapp.feature.feed.SyntheticFeedImageLoader
import io.github.ktclat.c001apk.nativeapp.feature.media.LocalMediaImageLoader
import io.github.ktclat.c001apk.nativeapp.local.*
import io.github.ktclat.c001apk.nativeapp.settings.*
import io.github.ktclat.c001apk.nativeapp.social.SocialRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Actual NativeApp, generation-owned BrowseViewModel, HTTP repository, decoder and full-post reader.
 * Every HTTP/image/browser request terminates in memory. There is no chain.proceed or real login.
 * Navigation uses actual body-link/reply UI, never an injected route callback or injected list state.
 * These are unexecuted source fixtures until the coordinator authorizes an execution gate.
 * StateRestorationTester coverage is not represented as a device/process-death test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderRetentionNativeAppTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val requests = CopyOnWriteArrayList<Request>()
    private val unexpected = CopyOnWriteArrayList<String>()
    private val browserIntents = CopyOnWriteArrayList<Intent>()
    private val clients = mutableListOf<OkHttpClient>()
    private val owners = mutableListOf<SessionViewModelStore>()
    private val gates = CopyOnWriteArrayList<DetailGate>()
    private val responses = ConcurrentHashMap<String, DetailResponse>()
    private var owner = SessionViewModelStore().also { owners += it }
    private lateinit var activeModel: BrowseViewModel
    private lateinit var accounts: AccountRuntime
    private lateinit var container: AppContainer
    private lateinit var images: SyntheticFeedImageLoader
    private val routeIds = (42..58).toList()
    private val original = readerBody("ORIGINAL")
    private val replacement = readerBody("REPLACEMENT")
    private val linksLabel get() = "本段链接（${routeIds.size}）"

    private data class ReaderBody(val html: String, val pages: List<String>)
    private data class DetailResponse(val html: String, val likes: Int = 0, val structured: Boolean = false)
    private class DetailGate(val id: String) {
        val claimed = AtomicBoolean()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
    }
    private class MemoryLocal : JsonStateStore<LocalState> {
        override val data = MutableStateFlow(LocalState())
        override suspend fun update(transform: (LocalState) -> LocalState) {
            data.value = LocalRepository.validateState(transform(data.value))
        }
    }

    /** Only inline anchors: decoded pages have independently constructed exact 4000-unit text. */
    private fun readerBody(name: String): ReaderBody {
        val pages = mutableListOf<String>()
        val html = buildString {
            repeat(4) { page ->
                val prefix = "${name}_SEGMENT_${page + 1}_START"
                val suffix = "${name}_SEGMENT_${page + 1}_END"
                val labels = routeIds.joinToString("") { "打开动态$it" }
                val prose = "这是离线合成阅读正文用于验证原生返回位置不来自真实账户"
                val needed = 4000 - prefix.length - labels.length - suffix.length
                val fill = prose.repeat((needed + prose.length - 1) / prose.length).take(needed)
                pages += prefix + fill + labels + suffix
                append(prefix); append(fill)
                routeIds.forEach { id -> append("<a href='/feed/$id'>打开动态$id</a>") }
                append(suffix)
            }
        }
        check(pages.all { it.length == 4000 })
        return ReaderBody(html, pages)
    }

    private fun feed(id: String, response: DetailResponse) = buildJsonObject {
        put("id", id); put("entityType", "feed"); put("uid", "9")
        put("username", "Synthetic retention fixture"); put("message", response.html)
        put("replynum", 0); put("likenum", response.likes)
        if (response.structured) put("message_raw_output", "[{\"type\":\"text\",\"text\":\"STRUCTURED_NOT_RENDERED\"}]")
    }

    @Before fun setup() {
        images = SyntheticFeedImageLoader(compose.activity)
        container = AppContainer(compose.activity.application)
        container.nativeUpdates.enabled = false
        AppContainer::class.java.getDeclaredField("local").apply { isAccessible = true }
            .set(container, LocalRepository(MemoryLocal()))
        val store = AccountStore(object : CredentialPersistence {
            override fun read(): ByteArray? = null
            override fun write(bytes: ByteArray) = Unit
            override fun reset() = Unit
        }, guestDeviceId = "synthetic-retention-guest")
        for (id in listOf("1", "2")) {
            val token = Cookie.Builder().name("token").value("synthetic-retention-$id")
                .domain("coolapk.com").path("/").secure().expiresAt(System.currentTimeMillis() + 3_600_000).build()
            assertTrue(store.saveAuthenticated(id, "Synthetic $id", "synthetic-device-$id", listOf(token), store.snapshot()))
        }
        store.useGuest()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            if (request.method != "GET" || request.url.host !in setOf("api.coolapk.com", "api2.coolapk.com") ||
                path !in setOf("/v6/main/indexV8", "/v6/feed/detail", "/v6/feed/replyList")) {
                unexpected += "${request.method} $path"
                throw IOException("Unrecognized synthetic request; sockets are disabled")
            }
            requests += request
            val body = when (path) {
                "/v6/main/indexV8" -> buildJsonObject {
                    put("data", JsonArray(routeIds.map { feed("$it", DetailResponse("Parent preview $it")) }))
                }.toString()
                "/v6/feed/detail" -> {
                    val id = requireNotNull(request.url.queryParameter("id"))
                    // Capture the response before blocking: a later replacement cannot rewrite this request.
                    val response = responses[id] ?: DetailResponse(original.html)
                    val gate = gates.firstOrNull { it.id == id && it.claimed.compareAndSet(false, true) }
                    try {
                        gate?.entered?.countDown()
                        if (gate != null && !gate.release.await(10, TimeUnit.SECONDS)) throw IOException("Synthetic detail gate timed out")
                        buildJsonObject { put("data", feed(id, response)) }.toString()
                    } finally { gate?.returned?.countDown() }
                }
                else -> """{"data":[]}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
                .body(body.toResponseBody()).build() // NEVER chain.proceed.
        }.build()
        val loginClient = OkHttpClient.Builder().addInterceptor { request ->
            unexpected += "login ${request.request().method} ${request.request().url.encodedPath}"
            throw IOException("Synthetic login: no external I/O")
        }.build()
        clients += listOf(client, loginClient)
        val repository = HttpCoolapkRepository(client = client, requestSession = { store.captureRequestSession() })
        val social = SocialRepository(store) { snapshot -> CoolapkHttpTransport(client = client, requestSession = { store.requestSessionFor(snapshot) }) }
        val login = HttpLoginTransport(loginClient)
        val ctor = AccountRuntime::class.java.getDeclaredConstructor(AccountStore::class.java, HttpLoginTransport::class.java,
            LoginCoordinator::class.java, HttpCoolapkRepository::class.java, SocialRepository::class.java).apply { isAccessible = true }
        accounts = ctor.newInstance(store, login, LoginCoordinator(store, login, "synthetic-retention-device"), repository, social)
    }

    @After fun cleanup() {
        releaseAll()
        owners.forEach { it.viewModelStore.clear() }
        clients.forEach { it.dispatcher.cancelAll(); it.connectionPool.evictAll(); it.dispatcher.executorService.shutdownNow() }
        if (::images.isInitialized) {
            images.close()
            assertTrue("No image sockets: ${images.unexpectedUrls}", images.unexpectedUrls.isEmpty())
        }
        assertTrue("No unexpected transport: $unexpected", unexpected.isEmpty())
        assertTrue("No browser fallback was requested in these native-route fixtures", browserIntents.isEmpty())
    }
    private fun releaseAll() = gates.forEach { it.release.countDown() }
    private fun gateNextDetail(id: String) = DetailGate(id).also { gates += it }

    /** Finally encloses setContent too, so assertion/setup failure cannot leave an interceptor blocked. */
    private fun withRoot(initialRoute: String? = "feed:42", restoration: Boolean = false,
        block: (StateRestorationTester?) -> Unit) {
        try {
            val restorer = if (restoration) StateRestorationTester(compose) else null
            val browserContext = object : ContextWrapper(compose.activity) {
                override fun startActivity(intent: Intent) { browserIntents += Intent(intent) }
            }
            val content: @Composable () -> Unit = {
                val state by accounts.store.state.collectAsState()
                val currentOwner = owner
                currentOwner.select(state.generation)
                val model = remember(currentOwner, state.generation) {
                    ViewModelProvider(currentOwner, viewModelFactory { initializer { BrowseViewModel(accounts.repository) } })[BrowseViewModel::class.java]
                }
                SideEffect { activeModel = model }
                MaterialTheme(colorScheme = fixedColorScheme(0, false), typography = appTypography(FontChoice.NOTO_SANS)) {
                    CompositionLocalProvider(LocalContext provides browserContext, LocalViewModelStoreOwner provides currentOwner,
                        LocalMediaImageLoader provides images.loader,
                        LocalReadingStyles provides readingStyles(ReadingPreset.COMFORTABLE, FontChoice.NOTO_SANS)) {
                        key(state.generation) { NativeApp(model, container,
                            AppSettings(floatingNavigation = true, dynamicColor = false, font = FontChoice.NOTO_SANS,
                                readingPreset = ReadingPreset.COMFORTABLE), accounts, state, initialRoute = initialRoute) }
                    }
                }
            }
            if (restorer == null) compose.setContent(content) else restorer.setContent(content)
            block(restorer)
        } finally { releaseAll() }
    }

    private fun awaitTag(tag: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
    private fun awaitPage(id: String, page: Int, body: ReaderBody = original) {
        val expected = body.pages[page - 1]
        compose.waitUntil(10_000) {
            val current = compose.onAllNodesWithTag("full-post-page").fetchSemanticsNodes()
            compose.onAllNodesWithTag("feed:$id").fetchSemanticsNodes().size == 1 &&
                current.size == 1 && current.single().config[SemanticsProperties.Text].single().text == expected
        }
        compose.onNodeWithTag("full-post-progress").assertTextContains("第 $page/4 段", substring = true)
        compose.onNodeWithTag("full-post-page").assertTextEquals(expected)
        compose.waitForIdle()
    }
    private fun awaitReplies(id: String, order: ReplyOrder = ReplyOrder.RECENT_REPLY, authorOnly: Boolean = false) {
        compose.waitUntil(10_000) {
            compose.onRoot().fetchSemanticsNode()
            activeModel.states.value["ordered:$id:${order.name}:$authorOnly"]?.let { it.loaded && !it.loading } == true
        }
    }
    /** Oversized IDs remain readable, but the existing local-history limit reports an honest error. */
    private fun dismissExpectedHistorySaveFailure() {
        val error = hasText("无法保存浏览历史，原有数据未重置") and hasAnyAncestor(isDialog())
        compose.waitUntil(10_000) { compose.onAllNodes(error).fetchSemanticsNodes().size == 1 }
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        compose.onNode(hasText("提示") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNode(error).assertTextEquals("无法保存浏览历史，原有数据未重置").assertIsDisplayed()
        compose.onNode(hasText("知道了") and hasAnyAncestor(isDialog())).assertIsDisplayed().performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
    }
    private fun awaitGate(gate: DetailGate) = compose.waitUntil(10_000) {
        compose.onRoot().fetchSemanticsNode(); gate.entered.count == 0L
    }
    private fun detailCalls(id: String) = requests.count { it.url.encodedPath == "/v6/feed/detail" && it.url.queryParameter("id") == id }
    private fun replyCalls(id: String) = requests.count { it.url.encodedPath == "/v6/feed/replyList" && it.url.queryParameter("id") == id }
    private fun homeCalls() = requests.count { it.url.encodedPath == "/v6/main/indexV8" }
    private fun toolbarBack() = compose.onNodeWithContentDescription("返回").performClick()
    private fun systemBack() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
    private fun openHomeFeed(id: String = "42") {
        awaitTag("feed-body:$id")
        compose.onNodeWithTag("feed-body:$id").performScrollTo().performClick()
        awaitPage(id, 1); awaitReplies(id)
    }
    private fun nextPage(id: String, page: Int, body: ReaderBody = original) {
        compose.onNodeWithText("下一段").performScrollTo().assertIsDisplayed().performClick()
        awaitPage(id, page, body)
    }
    private fun selectPage(id: String, from: Int, to: Int, body: ReaderBody = original) {
        require(to >= from)
        for (page in from + 1..to) nextPage(id, page, body)
    }
    private fun prepareLinkDeparture(): Viewport {
        compose.onNodeWithText(linksLabel).performScrollTo().assertIsDisplayed()
        compose.waitForIdle()
        return viewport().also(::assertReallyScrolled)
    }
    /** Caller prepares/captures before navigation. This helper must not silently scroll the departure. */
    private fun openFeedLink(id: String) {
        compose.onNodeWithText(linksLabel).assertIsDisplayed().performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        // The target may not yet be composed in the dialog's lazy list. Scroll that real list,
        // not the underlying reader, and only then resolve/click the corresponding link.
        compose.onNode(hasScrollToIndexAction() and hasAnyAncestor(isDialog()))
            .performScrollToIndex(routeIds.indexOf(id.toInt()))
        compose.onNode(hasText("打开动态$id") and hasAnyAncestor(isDialog())).assertIsDisplayed().performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
    }
    /** Only before departure/filter setup: resolve an off-screen lazy item through its real list. */
    private fun prepareCommentControls() {
        compose.onNodeWithTag("feed-detail-list").performScrollToKey("comment-controls")
    }
    private fun enterGuestReplyChild(): Viewport {
        prepareCommentControls()
        compose.onNodeWithText("回复动态").performScrollTo().assertIsDisplayed()
        compose.waitForIdle()
        val before = viewport().also(::assertReallyScrolled)
        compose.onNodeWithText("回复动态").performClick()
        compose.onNodeWithText("登录后回复").assertIsDisplayed()
        compose.onNodeWithTag("full-post-reader").assertDoesNotExist()
        return before
    }

    private data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float)
    private data class Viewport(val progress: String, val page: String, val position: Float, val maximum: Float,
        val viewport: Bounds, val pageBounds: Bounds, val anchors: Map<String, Bounds>)
    private fun bounds(node: SemanticsNodeInteraction): Bounds {
        val value = node.getUnclippedBoundsInRoot()
        val density = compose.activity.resources.displayMetrics.density
        return Bounds(value.left.value * density, value.top.value * density, value.right.value * density, value.bottom.value * density)
    }
    private fun text(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text].single().text
    private fun viewport(): Viewport {
        val list = compose.onNodeWithTag("feed-detail-list")
        val range = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        val anchors = listOf("上一段", "下一段", linksLabel, "回复动态").mapNotNull { label ->
            val node = compose.onNodeWithText(label)
            if (node.isDisplayed()) label to bounds(node) else null
        }.toMap()
        assertTrue("At least one actual footer/control must remain visible", anchors.isNotEmpty())
        return Viewport(text("full-post-progress"), text("full-post-page"), range.value(), range.maxValue(),
            bounds(list), bounds(compose.onNodeWithTag("full-post-page")), anchors)
    }
    private fun assertReallyScrolled(value: Viewport) {
        assertTrue("Fixture must leave the initial scroll offset", value.position > 0f)
        assertTrue("Long page beginning must really be above this viewport", value.pageBounds.top < value.viewport.top)
    }
    private fun assertBounds(label: String, before: Bounds, after: Bounds) {
        assertEquals("$label left", before.left, after.left, 1f)
        assertEquals("$label top", before.top, after.top, 1f)
        assertEquals("$label right", before.right, after.right, 1f)
        assertEquals("$label bottom", before.bottom, after.bottom, 1f)
    }
    /** Never scroll here: an after-return corrective scroll would hide the defect under test. */
    private fun assertReturned(before: Viewport) {
        compose.waitForIdle()
        val after = viewport()
        assertEquals(before.progress, after.progress); assertEquals(before.page, after.page)
        assertEquals("Actual list semantics position", before.position, after.position, .001f)
        assertEquals("Actual scroll extent", before.maximum, after.maximum, .001f)
        assertBounds("viewport", before.viewport, after.viewport)
        assertBounds("unclipped full page", before.pageBounds, after.pageBounds)
        assertEquals("Same visible footer controls", before.anchors.keys, after.anchors.keys)
        before.anchors.forEach { (label, geometry) -> assertBounds(label, geometry, after.anchors.getValue(label)) }
    }
    private fun assertAtStart() {
        compose.waitForIdle()
        val list = compose.onNodeWithTag("feed-detail-list")
        assertEquals(0f, list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), .001f)
        compose.onNodeWithTag("full-post-progress").assertIsDisplayed()
        val page = bounds(compose.onNodeWithTag("full-post-page"))
        val viewport = bounds(list)
        assertTrue("New body's first text must be inside the initial viewport", page.top >= viewport.top && page.top < viewport.bottom)
        compose.onNodeWithText("上一段").assertIsNotEnabled()
    }
    /** Bounded supplementary PNG only after assertions, and only when this suite is authorized to run. */
    private fun capture(name: String) {
        compose.waitForIdle()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        val bitmap = compose.runOnUiThread {
            val view = compose.activity.findViewById<View>(android.R.id.content)
            check(view.width > 0 && view.height > 0)
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        try {
            val file = File("build/verification/reader-retention/$name.png").apply { parentFile?.mkdirs() }
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    @Test fun repeatedGuestReplyChildReturnsSameSegmentAndActualGeometryThroughBothBackPaths() = withRoot(initialRoute = null) {
        openHomeFeed()
        selectPage("42", 1, 3)
        prepareCommentControls()
        compose.onNodeWithText("最新").performScrollTo().performClick()
        awaitReplies("42", ReplyOrder.NEWEST)
        compose.onNodeWithText("只看楼主").performScrollTo().assertIsDisplayed().performClick()
        awaitReplies("42", ReplyOrder.NEWEST, true)
        val repliesBefore = replyCalls("42")
        repeat(2) { round ->
            val before = enterGuestReplyChild()
            if (round == 0) toolbarBack() else systemBack()
            awaitPage("42", 3)
            assertReturned(before)
            compose.onNodeWithText("最新").assertIsSelected()
            compose.onNodeWithText("只看楼主").assertIsSelected()
            assertEquals(1, detailCalls("42")); assertEquals(repliesBefore, replyCalls("42"))
            capture("guest-reply-return-$round")
        }
        toolbarBack(); awaitTag("content-list")
        assertEquals(1, homeCalls())
    }

    @Test fun equalBodyFeedRoutesKeepIndependentLatestPositionsAndSelfLinksDoNotAddHistory() = withRoot(initialRoute = null) {
        openHomeFeed(); selectPage("42", 1, 2)
        val a2 = prepareLinkDeparture()
        openFeedLink("43"); awaitPage("43", 1); awaitReplies("43"); assertAtStart()
        selectPage("43", 1, 3)
        val b3 = prepareLinkDeparture()
        openFeedLink("42"); awaitPage("42", 2); assertReturned(a2)
        nextPage("42", 3)
        val latestA3 = prepareLinkDeparture()
        repeat(2) {
            openFeedLink("42"); awaitPage("42", 3); assertReturned(latestA3)
            assertEquals(1, detailCalls("42")); assertEquals(1, replyCalls("42"))
        }
        toolbarBack(); awaitPage("43", 3); assertReturned(b3)
        systemBack(); awaitPage("42", 3); assertReturned(latestA3)
        capture("feed-cycle-latest-a")
        toolbarBack(); awaitTag("content-list")
        assertEquals(1, homeCalls()); assertEquals(1, detailCalls("42")); assertEquals(1, detailCalls("43"))
    }

    @Test fun identicalBodyResetsAcrossDifferentAccountAndNewSameAccountGeneration() {
        accounts.store.switchTo("1")
        withRoot {
            awaitPage("42", 1); awaitReplies("42"); selectPage("42", 1, 3); prepareLinkDeparture()
            val accountOneModel = activeModel
            compose.runOnIdle { accounts.store.switchTo("2") }
            awaitPage("42", 1); assertAtStart(); assertNotSame(accountOneModel, activeModel)
            assertEquals(2, detailCalls("42"))
            selectPage("42", 1, 3); prepareLinkDeparture()
            val accountTwoModel = activeModel
            val generation = accounts.store.state.value.generation
            compose.runOnIdle {
                // The final account ID is unchanged, but this is a genuinely new account generation.
                accounts.store.useGuest(); accounts.store.switchTo("2")
            }
            awaitPage("42", 1); assertAtStart(); assertNotSame(accountTwoModel, activeModel)
            assertEquals("2", accounts.store.state.value.activeId)
            assertTrue(accounts.store.state.value.generation > generation)
            assertEquals(3, detailCalls("42"))
            val detail = requests.filter { it.url.encodedPath == "/v6/feed/detail" }
            assertTrue(detail.first().header("Cookie").orEmpty().contains("token=synthetic-retention-1"))
            assertTrue(detail.drop(1).all { it.header("Cookie").orEmpty().contains("token=synthetic-retention-2") })
            capture("identical-body-new-generation-start")
        }
    }

    @Test fun generationSwitchWhileParentIsBeneathFeedChildCannotResurrectParentBookmark() {
        accounts.store.switchTo("1")
        withRoot {
            awaitPage("42", 1); awaitReplies("42"); selectPage("42", 1, 3); prepareLinkDeparture()
            openFeedLink("43"); awaitPage("43", 1); awaitReplies("43")
            val oldModel = activeModel
            compose.runOnIdle { accounts.store.switchTo("2") }
            // Fixture initialRoute deliberately reopens 42; production does not promise this reopening.
            awaitPage("42", 1); assertAtStart(); assertNotSame(oldModel, activeModel)
            compose.onNodeWithTag("feed:43").assertDoesNotExist()
            assertEquals(2, detailCalls("42")); assertEquals(1, detailCalls("43"))
            toolbarBack(); awaitTag("content-list")
        }
    }

    @Test fun equalBodyMetadataRefreshRetainsSegmentAndActualGeometry() = withRoot {
        awaitPage("42", 1); awaitReplies("42"); selectPage("42", 1, 3)
        val before = prepareLinkDeparture()
        responses["42"] = DetailResponse(original.html, likes = 1)
        compose.runOnIdle { activeModel.load("feed:42", refresh = true) }
        compose.waitUntil(10_000) {
            compose.onRoot().fetchSemanticsNode()
            val state = activeModel.states.value["feed:42"]
            detailCalls("42") == 2 && state?.loading == false && state.items.single().feed?.likeCount == 1
        }
        awaitPage("42", 3); assertReturned(before)
        assertEquals(1, replyCalls("42")); capture("equal-body-metadata-refresh")
    }

    @Test fun changedOrdinaryAndUnsupportedBodiesResetInsteadOfReusingOldPageOrOffset() = withRoot {
        awaitPage("42", 1); awaitReplies("42"); selectPage("42", 1, 3); prepareLinkDeparture()
        responses["42"] = DetailResponse(replacement.html)
        compose.runOnIdle { activeModel.load("feed:42", refresh = true) }
        awaitPage("42", 1, replacement); assertAtStart()
        compose.onAllNodesWithText("ORIGINAL_SEGMENT_", substring = true).assertCountEquals(0)
        capture("changed-body-start")
        selectPage("42", 1, 3, replacement); prepareLinkDeparture()
        responses["42"] = DetailResponse("<table><tr><td>UNSUPPORTED_REPLACEMENT</td></tr></table>")
        compose.runOnIdle { activeModel.load("feed:42", refresh = true) }
        awaitTag("full-post-limit")
        compose.onNodeWithTag("full-post-limit").assertTextEquals(fullPostLimitMessage(FullPostBodyLimit.UNSUPPORTED_MARKUP)).assertIsDisplayed()
        compose.onNodeWithTag("full-post-progress").assertDoesNotExist()
        compose.onNodeWithTag("full-post-page").assertDoesNotExist()
        compose.onAllNodesWithText("REPLACEMENT_SEGMENT_", substring = true).assertCountEquals(0)
        assertEquals(0f, compose.onNodeWithTag("feed-detail-list").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value(), .001f)
        capture("unsupported-body-reset")
        toolbarBack(); awaitTag("content-list")
        assertEquals(3, detailCalls("42"))
    }

    @Test fun cancelledGatedRefreshCannotPublishIntoNewGenerationWithSameRoute() = withRoot {
        awaitPage("42", 1); awaitReplies("42"); selectPage("42", 1, 3)
        val oldModel = activeModel
        responses["42"] = DetailResponse(replacement.html)
        val gate = gateNextDetail("42")
        compose.runOnIdle { activeModel.load("feed:42", refresh = true) }
        awaitGate(gate)
        enterGuestReplyChild()
        // New generation receives the original body; the blocked old request captured replacement.
        responses["42"] = DetailResponse(original.html)
        compose.runOnIdle { accounts.store.switchTo("2") }
        awaitPage("42", 1); assertAtStart(); assertNotSame(oldModel, activeModel)
        gate.release.countDown()
        compose.waitUntil(10_000) { compose.onRoot().fetchSemanticsNode(); gate.returned.count == 0L }
        awaitPage("42", 1); assertAtStart()
        compose.onAllNodesWithText("REPLACEMENT_SEGMENT_", substring = true).assertCountEquals(0)
        assertEquals(original.html, (oldModel.states.value.getValue("feed:42").items.single().fullPostBody as FullPostBody.Available).html)
        assertEquals(3, detailCalls("42"))
    }

    @Test fun moreThanTwelvePriorRoutesDropsOldHistoryAndReopeningCannotRestoreDroppedReader() = withRoot(initialRoute = null) {
        openHomeFeed(); selectPage("42", 1, 3)
        for (id in 43..55) {
            prepareLinkDeparture(); openFeedLink("$id"); awaitPage("$id", 1); awaitReplies("$id")
        }
        // Current 55 plus only the twelve previous routes 43..54; 42 is no longer in history.
        for (id in 54 downTo 43) {
            toolbarBack(); awaitPage("$id", 1); awaitReplies("$id")
        }
        toolbarBack(); awaitTag("content-list")
        compose.onNodeWithTag("feed-detail-list").assertDoesNotExist()
        assertEquals(1, homeCalls())
        openHomeFeed(); assertAtStart()
        assertTrue("Evicted/dropped detail must have been fetched again", detailCalls("42") >= 2)
        capture("dropped-route-reopened-at-start")
    }

    @Test fun stillNavigableDetailWhoseBodyWasEvictedRefetchesAndStartsHonestly() = withRoot {
        awaitPage("42", 1); awaitReplies("42"); selectPage("42", 1, 3)
        for (id in 43..54) {
            prepareLinkDeparture(); openFeedLink("$id"); awaitPage("$id", 1); awaitReplies("$id")
        }
        // Thirteen feed+reply pairs exceed the 24-state data target, but 42 remains in route history.
        assertNull("This fixture must really evict the fetched body", activeModel.states.value["feed:42"])
        for (id in 53 downTo 43) {
            toolbarBack(); awaitPage("$id", 1); awaitReplies("$id")
        }
        val gate = gateNextDetail("42")
        toolbarBack(); awaitGate(gate)
        compose.onNodeWithTag("feed-detail-loading").assertIsDisplayed()
        compose.onNodeWithTag("full-post-progress").assertDoesNotExist()
        compose.onNodeWithTag("full-post-page").assertDoesNotExist()
        assertEquals(2, detailCalls("42"))
        gate.release.countDown()
        awaitPage("42", 1); awaitReplies("42"); assertAtStart()
        capture("data-eviction-genuine-refetch-start")
    }

    @Test fun oversizedNumericFeedIdRemainsReadableWithoutCreatingAnUnsafeBookmark() {
        val id = "7".repeat(257)
        withRoot(initialRoute = "feed:$id") {
            awaitPage(id, 1); dismissExpectedHistorySaveFailure(); awaitReplies(id); assertAtStart()
            compose.onNodeWithTag("reader-position-unavailable")
                .assertTextEquals("此动态地址过长，阅读位置不会保留。").assertIsDisplayed()
            assertEquals(0, activeModel.readerPositions.size)
            selectPage(id, 1, 3)
            enterGuestReplyChild()
            toolbarBack(); awaitPage(id, 1); dismissExpectedHistorySaveFailure(); assertAtStart()
            compose.onNodeWithTag("reader-position-unavailable")
                .assertTextEquals("此动态地址过长，阅读位置不会保留。").assertIsDisplayed()
            assertEquals("Unbounded metadata must never become a reader bookmark", 0, activeModel.readerPositions.size)
            assertTrue(activeModel.readerPositions.size <= MAX_READER_BOOKMARKS)
            assertFalse(activeModel.readerPositions.feedIds().contains(id))
            assertEquals(1, detailCalls(id)); assertEquals(1, replyCalls(id))
            capture("oversized-feed-id-readable-safe-restart")
            nextPage(id, 2)
            responses[id] = DetailResponse(replacement.html)
            compose.runOnIdle { activeModel.load("feed:$id", refresh = true) }
            awaitPage(id, 1, replacement); assertAtStart()
            // The route/id/history-setting effect keys stay equal during this same-ID refresh.
            compose.onAllNodes(isDialog()).assertCountEquals(0)
            compose.onNodeWithTag("reader-position-unavailable")
                .assertTextEquals("此动态地址过长，阅读位置不会保留。").assertIsDisplayed()
            compose.onAllNodesWithText("ORIGINAL_SEGMENT_", substring = true).assertCountEquals(0)
            assertEquals("Changed body with no bookmark must still reset safely", 0, activeModel.readerPositions.size)
            assertEquals(2, detailCalls(id)); assertEquals(1, replyCalls(id))
            capture("oversized-feed-id-changed-body-start")
        }
    }

    @Test fun savedCompositionWithSurvivingModelRetainsReaderButIsNotAProcessDeathClaim() =
        withRoot(initialRoute = null, restoration = true) { restorer ->
            openHomeFeed(); selectPage("42", 1, 3)
            val before = prepareLinkDeparture()
            val model = activeModel
            requireNotNull(restorer).emulateSavedInstanceStateRestore()
            awaitPage("42", 3); assertSame(model, activeModel); assertReturned(before)
            assertEquals(1, detailCalls("42")); assertEquals(1, replyCalls("42")); assertEquals(1, homeCalls())
            capture("same-model-saved-composition")
        }

    @Test fun savedNavigationWithFreshOwnerWaitsForRealBodyAndDoesNotRestoreProcessBookmark() =
        withRoot(initialRoute = null, restoration = true) { restorer ->
            openHomeFeed(); selectPage("42", 1, 3); prepareLinkDeparture()
            val model = activeModel
            val oldOwner = owner
            val gate = gateNextDetail("42")
            compose.runOnIdle {
                // Explicitly discard the owner/data cache. Merely recreating an Activity would not do this.
                owner = SessionViewModelStore().also { owners += it }
                oldOwner.viewModelStore.clear()
            }
            requireNotNull(restorer).emulateSavedInstanceStateRestore()
            awaitGate(gate); assertNotSame(model, activeModel)
            compose.onNodeWithTag("feed-detail-loading").assertIsDisplayed()
            compose.onNodeWithTag("full-post-progress").assertDoesNotExist()
            compose.onNodeWithTag("full-post-page").assertDoesNotExist()
            compose.onAllNodesWithText("接口正文已完整载入", substring = true).assertCountEquals(0)
            assertEquals(2, detailCalls("42"))
            assertEquals("Saved route, not initialRoute injection, returned to detail", 1, homeCalls())
            gate.release.countDown()
            awaitPage("42", 1); awaitReplies("42"); assertAtStart()
            capture("fresh-owner-no-process-bookmark")
        }
}
