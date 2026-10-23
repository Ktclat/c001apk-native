// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.ktclat.c001apk.nativeapp.AppContainer
import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.data.*
import io.github.ktclat.c001apk.nativeapp.domain.*
import io.github.ktclat.c001apk.nativeapp.feature.feed.SyntheticFeedImageLoader
import io.github.ktclat.c001apk.nativeapp.feature.media.*
import io.github.ktclat.c001apk.nativeapp.local.*
import io.github.ktclat.c001apk.nativeapp.settings.*
import io.github.ktclat.c001apk.nativeapp.social.SocialRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrl
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
import java.util.concurrent.atomic.AtomicInteger

/** Actual NativeApp, real HTTP repository/decoder/parser, synthetic account runtime and app fonts.
 * All API/image/browser effects terminate in fixture code. Never chain.proceed or a live account.
 * Execution and capture are intentionally deferred to the coordinator's serialized heavy gate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StructuredArticleNativeAppTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val requests = CopyOnWriteArrayList<Request>()
    private val unexpected = CopyOnWriteArrayList<String>()
    private val browserIntents = CopyOnWriteArrayList<Intent>()
    private val imageRequests = CopyOnWriteArrayList<String>()
    private val clients = mutableListOf<OkHttpClient>()
    private val responses = ConcurrentHashMap<String, JsonObject>()
    private val owner = SessionViewModelStore()
    private val detailEntered = CountDownLatch(1)
    private val detailRelease = CountDownLatch(1)
    private val parserEntered = CountDownLatch(1)
    private val parserRelease = CountDownLatch(1)
    private val parserReturned = CountDownLatch(1)
    private val parserCalls = AtomicInteger()
    private val imageGates = CopyOnWriteArrayList<CompletableDeferred<Unit>>()
    private lateinit var activeModel: BrowseViewModel
    private lateinit var accounts: AccountRuntime
    private lateinit var container: AppContainer
    private lateinit var ordinaryImages: SyntheticFeedImageLoader
    private lateinit var articleImages: ArticleImageLoader
    private var gateDetail = false
    private var gateFirstParser = false
    private var failFirstParser = false
    private var browserUnavailable = false
    private var detailSetCookie: String? = null
    private var fontScale = 1f
    private var imagePlan: suspend (String) -> ArticleImageResult = { readyImage() }
    private val parser: (FullPostBody, () -> Unit) -> PreparedFullPost = { input, active ->
        assertNotSame("Actual root parsing must not run on Main", Looper.getMainLooper(), Looper.myLooper())
        val number = parserCalls.incrementAndGet()
        if (failFirstParser && number == 1) throw IllegalStateException("Synthetic real-root framework parse failure")
        if (gateFirstParser && number == 1) {
            parserEntered.countDown()
            check(parserRelease.await(15, TimeUnit.SECONDS)) { "Synthetic parser gate timed out" }
            parserReturned.countDown()
        }
        prepareFullPost(input, active)
    }
    private class MemoryLocal : JsonStateStore<LocalState> {
        override val data = MutableStateFlow(LocalState())
        override suspend fun update(transform: (LocalState) -> LocalState) { data.value = LocalRepository.validateState(transform(data.value)) }
    }
    private fun readyImage(): ArticleImageResult = ArticleImageResult.Ready(
        Bitmap.createBitmap(24, 18, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(58, 131, 172)) })
    private fun base(id: String, message: String = "ORDINARY_PREVIEW_MUST_NOT_REPLACE_ARTICLE") = buildJsonObject {
        put("id", id); put("entityType", "feed"); put("uid", "7"); put("username", "synthetic author")
        put("message", message); put("likenum", 0); put("replynum", 0)
    }
    private fun textArticle(id: String, html: String) = JsonObject(base(id) + ("message_raw_output" to JsonPrimitive(buildJsonArray {
        add(buildJsonObject { put("type", "text"); put("message", html) })
    }.toString())))
    private fun mediaArticle(vararg urls: String?, caption: String = "PLAIN_IMAGE_CAPTION") = JsonObject(base("42") + ("message_raw_output" to JsonPrimitive(buildJsonArray {
        urls.forEach { url -> add(buildJsonObject { put("type", "image"); put("url", url?.let(::JsonPrimitive) ?: JsonNull); put("description", caption) }) }
    }.toString())))
    /** Mirrors fixtures/complete-article-envelope.json exactly, including the deliberately unused fields. */
    private fun completeArticle() = buildJsonObject {
        for ((key, value) in base("42")) put(key, value)
        put("message_title", "ORDINARY_TITLE_MUST_NOT_DUPLICATE_ARTICLE")
        put("title", "合成结构化文章 **标题**")
        put("message_cover", "https://image.coolapk.com/synthetic-cover@640x360.jpg")
        put("picArr", buildJsonArray { add("https://image.coolapk.com/ordinary-grid-must-not-duplicate@100x100.jpg") })
        put("userInfo", buildJsonObject { put("uid", 7); put("username", "synthetic author") })
        put("message_raw_output", """[{"type":"text","message":"开头 **强调**，<i>斜体</i>与<a href=\"/feed/43\">关联动态</a>。\n\n第二段。"},{"type":"image","url":"https://image.coolapk.com/synthetic-article@640x480.jpg","description":"合成图片说明，不能作为 HTML 处理。"},{"type":"shareUrl","url":"https://www.coolapk.com/feed/44","title":"来源卡片","subTitle":"SOURCE_DOES_NOT_RENDER_SUBTITLE","logo":"https://image.coolapk.com/source-does-not-render-logo.jpg"},{"type":"text","message":"文章末尾 ARTICLE_EXACT_TAIL"}]""")
    }

    @Before fun setup() {
        ordinaryImages = SyntheticFeedImageLoader(compose.activity)
        articleImages = ArticleImageLoader { url -> imageRequests += url; imagePlan(url) }
        container = AppContainer(compose.activity.application)
        container.nativeUpdates.enabled = false
        AppContainer::class.java.getDeclaredField("local").apply { isAccessible = true }.set(container, LocalRepository(MemoryLocal()))
        val store = AccountStore(object : CredentialPersistence {
            override fun read(): ByteArray? = null
            override fun write(bytes: ByteArray) = Unit
            override fun reset() = Unit
        }, guestDeviceId = "synthetic-article-guest")
        for (id in listOf("1", "2")) {
            val cookie = Cookie.Builder().name("token").value("synthetic-article-$id").domain("coolapk.com").path("/").secure()
                .expiresAt(System.currentTimeMillis() + 3_600_000).build()
            assertTrue(store.saveAuthenticated(id, "Synthetic $id", "synthetic-device-$id", listOf(cookie), store.snapshot()))
        }
        store.useGuest()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); val path = request.url.encodedPath
            if (request.method != "GET" || request.url.host !in setOf("api.coolapk.com", "api2.coolapk.com") ||
                path !in setOf("/v6/main/indexV8", "/v6/feed/detail", "/v6/feed/replyList", "/v6/user/feedList", "/v6/user/profile")) {
                unexpected += "${request.method} ${request.url}"; throw IOException("Unknown synthetic request; sockets disabled")
            }
            requests += request
            val payload = when (path) {
                "/v6/main/indexV8" -> buildJsonObject { put("data", JsonArray((42..81).map { base("$it", "Parent preview $it") })) }
                "/v6/feed/detail" -> {
                    val id = requireNotNull(request.url.queryParameter("id"))
                    val value = responses[id] ?: if (id == "42") completeArticle() else textArticle(id, "CHILD_$id")
                    detailEntered.countDown()
                    if (gateDetail && detailCalls(id) == 1) check(detailRelease.await(15, TimeUnit.SECONDS))
                    buildJsonObject { put("data", value) }
                }
                "/v6/user/profile" -> buildJsonObject { put("data", buildJsonObject { put("uid", "7"); put("username", "Synthetic user seven") }) }
                else -> buildJsonObject { put("data", buildJsonArray {}) }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
                .apply { if (path == "/v6/feed/detail") detailSetCookie?.let { addHeader("Set-Cookie", it) } }
                .body(payload.toString().toResponseBody()).build() // Never chain.proceed.
        }.build()
        val loginClient = OkHttpClient.Builder().addInterceptor { unexpected += "LOGIN"; throw IOException("No login I/O") }.build()
        clients += listOf(client, loginClient)
        val repository = HttpCoolapkRepository(client = client, requestSession = { store.captureRequestSession() })
        val social = SocialRepository(store) { snapshot -> CoolapkHttpTransport(client = client, requestSession = { store.requestSessionFor(snapshot) }) }
        val login = HttpLoginTransport(loginClient)
        val ctor = AccountRuntime::class.java.getDeclaredConstructor(AccountStore::class.java, HttpLoginTransport::class.java,
            LoginCoordinator::class.java, HttpCoolapkRepository::class.java, SocialRepository::class.java).apply { isAccessible = true }
        accounts = ctor.newInstance(store, login, LoginCoordinator(store, login, "synthetic-article-device"), repository, social)
    }
    @After fun cleanup() {
        detailRelease.countDown(); parserRelease.countDown(); imageGates.forEach { it.complete(Unit) }
        owner.viewModelStore.clear()
        clients.forEach { it.dispatcher.cancelAll(); it.connectionPool.evictAll(); it.dispatcher.executorService.shutdownNow() }
        ordinaryImages.close()
        assertTrue("Unexpected ordinary image requests: ${ordinaryImages.unexpectedUrls}", ordinaryImages.unexpectedUrls.isEmpty())
        assertTrue("No unexpected request/write: $unexpected", unexpected.isEmpty())
    }
    private fun show(initialRoute: String? = "feed:42", widthPx: Int? = null, heightPx: Int? = null) {
        require((widthPx == null) == (heightPx == null))
        // Same exact-size Android shadow display/window path as MyRootRequestRoutingTest.
        if (widthPx != null && heightPx != null) compose.runOnUiThread {
            org.robolectric.Shadows.shadowOf(org.robolectric.shadows.ShadowDisplay.getDefaultDisplay()).apply {
                setWidth(widthPx); setHeight(heightPx); setRealWidth(widthPx); setRealHeight(heightPx)
            }
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
            compose.activity.window.setLayout(widthPx, heightPx)
        }
        val browserContext = object : ContextWrapper(compose.activity) {
            override fun startActivity(intent: Intent) {
                if (browserUnavailable) throw ActivityNotFoundException("Synthetic missing browser")
                browserIntents += Intent(intent)
            }
        }
        compose.setContent {
            val state by accounts.store.state.collectAsState()
            owner.select(state.generation)
            val model = remember(state.generation) {
                ViewModelProvider(owner, viewModelFactory { initializer { BrowseViewModel(accounts.repository) } })[BrowseViewModel::class.java]
            }
            SideEffect { activeModel = model }
            val density = LocalDensity.current.density
            MaterialTheme(colorScheme = fixedColorScheme(0, false), typography = appTypography(FontChoice.NOTO_SANS)) {
                CompositionLocalProvider(LocalContext provides browserContext, LocalViewModelStoreOwner provides owner,
                    LocalDensity provides Density(density, fontScale), LocalMediaImageLoader provides ordinaryImages.loader,
                    LocalArticleImageLoader provides articleImages, LocalFullPostParser provides parser,
                    LocalReadingStyles provides readingStyles(ReadingPreset.COMFORTABLE, FontChoice.NOTO_SANS)) {
                    key(state.generation) { NativeApp(model, container,
                        AppSettings(floatingNavigation = true, dynamicColor = false, font = FontChoice.NOTO_SANS,
                            readingPreset = ReadingPreset.COMFORTABLE, openInBrowser = true), accounts, state, initialRoute = initialRoute) }
                }
            }
        }
    }
    private fun awaitTag(tag: String) = compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitText(value: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(value, substring = true).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitGate(gate: CountDownLatch) = compose.waitUntil(15_000) { compose.onRoot().fetchSemanticsNode(); gate.count == 0L }
    private fun detailCalls(id: String) = requests.count { it.url.encodedPath == "/v6/feed/detail" && it.url.queryParameter("id") == id }
    private fun systemBack() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
    /** Back targets the sole shown Dialog window, not the Activity dispatcher behind that window. */
    private fun viewerWindowBack() {
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        compose.onNode(hasTestTag("article-media-viewer") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.runOnUiThread {
            val shown = org.robolectric.shadows.ShadowDialog.getShownDialogs().filter { it.isShowing }.distinct()
            check(shown.size == 1) { "Expected exactly one showing viewer Dialog" }
            val current = requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog())
            check(current === shown.single()) { "Latest Dialog is not the current viewer window" }
            check(current is androidx.activity.ComponentDialog)
            val window = requireNotNull(current.window)
            check(window !== compose.activity.window && window.decorView !== compose.activity.window.decorView)
            check(window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0)
            check(window.callback === current) { "Back must use the current Dialog window callback" }
            check(current.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK)))
            check(current.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK)))
        }
    }
    private fun toolbarBack() = compose.onNodeWithContentDescription("返回").performClick()
    private fun progress() = compose.onNodeWithTag("full-post-progress").fetchSemanticsNode().config[SemanticsProperties.Text].single().text
    private fun listPosition() = compose.onNodeWithTag("feed-detail-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun openInlineLink(label: String, count: Int) {
        compose.onNodeWithText("本段链接（$count）").performScrollTo().performClick()
        compose.onNode(hasText(label) and hasAnyAncestor(isDialog())).performScrollTo().performClick()
    }
    private fun dismissFeedback(message: String) {
        compose.onNode(hasText(message) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNode(hasText("知道了") and hasAnyAncestor(isDialog())).performClick()
    }
    private fun refresh42AndAwait() {
        val expected = ApiDecoder.feedDetail(responses.getValue("42")).fullPostBody
        val before = detailCalls("42")
        compose.runOnIdle { activeModel.load("feed:42", refresh = true) }
        compose.waitUntil(15_000) {
            compose.onAllNodes(isRoot()).fetchSemanticsNodes()
            detailCalls("42") == before + 1 && activeModel.states.value["feed:42"]?.let {
                !it.loading && it.items.singleOrNull()?.fullPostBody == expected
            } == true
        }
        compose.waitForIdle()
    }
    private fun openMediaDetails(index: Int = 0) {
        compose.onNodeWithTag("article-media-$index-open").performScrollTo().performClick()
        awaitTag("article-media-$index-details")
    }
    private fun failureTrees(): String = runCatching {
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
        val count = roots.fetchSemanticsNodes().size
        "rootCount=$count\n" + (0 until minOf(count, 8)).joinToString("\n") { index ->
            "root[$index]\n${roots[index].printToString().take(32_000)}"
        }
    }.getOrElse { "tree unavailable: ${it.javaClass.simpleName}: ${it.message}" }

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.runOnUiThread {
            val view = compose.activity.findViewById<View>(android.R.id.content)
            check(view.width > 0 && view.height > 0)
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        try {
            val file = File("build/verification/structured-article/$name.png").apply { parentFile?.mkdirs() }
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    /** NativeApp chrome and safety-progress strip remain present. This is not the article-only
     * Flutter engine frame or a real-device/system-bar measurement. Both use the existing seed;
     * image pixels are generated by readyImage(), never imported from the reference captures.
     */
    private fun captureCompleteArticleAtViewport(widthPx: Int, heightPx: Int, scale: Float, name: String) {
        fontScale = scale
        responses["42"] = completeArticle() // Same short source in both variants, not the long 4/4 fixture.
        show(widthPx = widthPx, heightPx = heightPx)
        awaitTag("article-page"); awaitTag("article-media-0-ready"); awaitTag("article-media-1-ready")
        compose.waitForIdle()
        val view = compose.activity.findViewById<View>(android.R.id.content)
        val density = compose.activity.resources.displayMetrics.density
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        assertEquals(widthPx, view.width); assertEquals(heightPx, view.height)
        assertEquals(3.25f, density, .001f)
        assertEquals(widthPx / density, (root.right - root.left).value, .01f)
        assertEquals(heightPx / density, (root.bottom - root.top).value, .01f)
        for (index in 0..1) {
            // Ready is the real Compose Image node, not a status label or placeholder.
            compose.onNodeWithTag("article-media-$index-ready", useUnmergedTree = true)
                .assertContentDescriptionEquals("静态图片")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Image))
            compose.onNodeWithTag("article-media-$index-loading").assertDoesNotExist()
            compose.onNodeWithTag("article-media-$index-unavailable").assertDoesNotExist()
        }
        assertTrue(progress().contains("第 1/1 段"))
        compose.onNodeWithTag("article-text--1").assertTextEquals("合成结构化文章 标题")
        compose.onNodeWithTag("article-text-3").assertTextEquals("文章末尾 ARTICLE_EXACT_TAIL")
        val sourceOrder = listOf("article-media-slot-0", "article-text--1", "article-text-0", "article-media-slot-1", "article-share-2", "article-text-3")
            .map { compose.onNodeWithTag(it).getUnclippedBoundsInRoot().top.value }
        assertTrue("Cover/title/text/image/share/tail source order", sourceOrder.zipWithNext().all { (a, b) -> a < b })
        assertTrue(ordinaryImages.loadedUrls.isEmpty())
        assertTrue(imageRequests.none { "ordinary-grid" in it || "source-does-not-render-logo" in it })
        assertEquals(1, detailCalls("42"))
        assertEquals(0f, listPosition(), .001f)
        val before = completeArticleFrameMetrics()
        capture("$name-top")
        compose.onNodeWithTag("article-text-3").performScrollTo().assertIsDisplayed()
        compose.waitForIdle()
        val tail = compose.onNodeWithTag("article-text-3").getUnclippedBoundsInRoot()
        val list = compose.onNodeWithTag("feed-detail-list").getUnclippedBoundsInRoot()
        assertTrue("Actual final article text is fully inside the list viewport", tail.top >= list.top && tail.bottom <= list.bottom)
        val after = completeArticleFrameMetrics()
        capture("$name-tail")
        val sameState = before["scrollAxisValue"] == after["scrollAxisValue"] && before["boundsDp"] == after["boundsDp"]
        val captureDir = File("build/verification/structured-article")
        val samePng = File(captureDir, "$name-top.png").readBytes().contentEquals(File(captureDir, "$name-tail.png").readBytes())
        File(captureDir, "$name.metrics.json").writeText(buildJsonObject {
            put("provenance", "Synthetic NativeApp Android shadow window, not a device or article-only Flutter engine capture")
            put("physicalWidthPx", view.width); put("physicalHeightPx", view.height); put("density", density)
            put("logicalWidthDp", (root.right - root.left).value); put("logicalHeightDp", (root.bottom - root.top).value)
            put("fontScale", scale); put("fixture", "completeArticle short 1/1")
            put("topFrame", "$name-top.png"); put("tailFrame", "$name-tail.png")
            put("beforeTailScroll", before); put("afterTailScroll", after)
            put("sameScrollAndGeometry", sameState); put("identicalPngBytes", samePng)
            put("tailIsIndependentFrame", !sameState && !samePng)
            put("tailDuplicateOf", if (sameState || samePng) JsonPrimitive("$name-top.png") else JsonNull)
            put("scrollUnits", "Compose LazyColumn semantic axis units, not pixel offsets")
            put("referenceCaveat", "Same source/viewport only; native chrome, safety progress, layout and palette algorithm are not pixel-parity claims")
        }.toString())
    }

    private fun completeArticleFrameMetrics(): JsonObject {
        fun bounds(node: SemanticsNodeInteraction) = buildJsonArray {
            val rect = node.getUnclippedBoundsInRoot()
            add(rect.left.value); add(rect.top.value); add(rect.right.value); add(rect.bottom.value)
        }
        val caption = compose.onNodeWithText("合成图片说明，不能作为 HTML 处理。", useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        caption.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> assertTrue(action(layouts)) }
        val layout = layouts.single()
        assertEquals(14.sp, layout.layoutInput.style.fontSize)
        assertEquals(20.sp, layout.layoutInput.style.lineHeight)
        assertEquals(appTypography(FontChoice.NOTO_SANS).bodyMedium.letterSpacing, layout.layoutInput.style.letterSpacing)
        assertEquals(3.25f, layout.layoutInput.density.density, .001f)
        assertEquals(fontScale, layout.layoutInput.density.fontScale, .001f)
        val axis = compose.onNodeWithTag("feed-detail-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        return buildJsonObject {
            put("scrollAxisValue", axis.value()); put("scrollAxisMax", axis.maxValue())
            put("boundsFormat", "unclipped [left, top, right, bottom] dp in NativeApp root")
            put("boundsDp", buildJsonObject {
                for (tag in listOf("feed-detail-list", "article-page", "full-post-progress", "article-media-slot-0",
                    "article-text--1", "article-text-0", "article-media-slot-1", "article-media-block-1", "article-share-2", "article-text-3")) {
                    put(tag, bounds(compose.onNodeWithTag(tag)))
                }
                put("caption", bounds(caption))
            })
            put("captionToShareGapDp", (compose.onNodeWithTag("article-share-2").getUnclippedBoundsInRoot().top -
                caption.getUnclippedBoundsInRoot().bottom).value)
            put("captionTextLayout", buildJsonObject {
                put("widthPx", layout.size.width); put("heightPx", layout.size.height)
                put("fontSize", layout.layoutInput.style.fontSize.toString())
                put("lineHeight", layout.layoutInput.style.lineHeight.toString())
                put("letterSpacing", layout.layoutInput.style.letterSpacing.toString())
                put("density", layout.layoutInput.density.density); put("fontScale", layout.layoutInput.density.fontScale)
                put("lineCount", layout.lineCount)
                put("linesPx", buildJsonArray {
                    for (line in 0 until layout.lineCount) add(buildJsonObject {
                        put("top", layout.getLineTop(line)); put("bottom", layout.getLineBottom(line))
                        put("baseline", layout.getLineBaseline(line))
                    })
                })
            })
        }
    }

    @Test @Config(qualifiers = "w369dp-h822dp-520dpi")
    fun completeArticleAtExactUserViewportTopAndActualTail() {
        captureCompleteArticleAtViewport(1200, 2670, 1f, "complete-user1200x2670-dpr3_25-font1")
    }

    @Test @Config(qualifiers = "w320dp-h480dp-520dpi")
    fun completeArticleAtExactCompactLargeTextViewportTopAndActualTail() {
        captureCompleteArticleAtViewport(1040, 1560, 1.6f, "complete-compact1040x1560-dpr3_25-font1_6")
    }

    @Test fun gatedDetailThenPreparationShowsNoCompleteBodyOrImageUntilBothAreReady() {
        // Locally generated pixels, not copied third-party artwork. Source URL geometry matches
        // the shared Flutter fixture, but reference image content/palette are not pixel-parity claims.
        gateDetail = true; gateFirstParser = true
        show(); awaitGate(detailEntered)
        compose.onNodeWithTag("feed-detail-loading").assertIsDisplayed()
        compose.onNodeWithTag("full-post-progress").assertDoesNotExist()
        assertTrue(imageRequests.isEmpty())
        detailRelease.countDown(); awaitGate(parserEntered); awaitTag("reader-preparing")
        compose.onNodeWithTag("feed-detail-list").assertDoesNotExist()
        compose.onNodeWithTag("article-page").assertDoesNotExist()
        assertTrue(imageRequests.isEmpty())
        parserRelease.countDown(); awaitTag("article-page"); awaitTag("article-media-0-ready")
        compose.onNodeWithTag("full-post-progress").assertTextEquals("结构化正文已排版 · 第 1/1 段 · 图片状态见各图")
        compose.onNodeWithTag("article-text--1").assertTextEquals("合成结构化文章 标题")
        compose.onNodeWithTag("article-text-3").assertTextEquals("文章末尾 ARTICLE_EXACT_TAIL")
        compose.onNodeWithText("合成图片说明，不能作为 HTML 处理。").assertExists()
        compose.onNodeWithTag("article-share-2").assertExists()
        val sourceOrder = listOf("article-media-slot-0", "article-text--1", "article-text-0", "article-media-slot-1", "article-share-2", "article-text-3")
            .map { compose.onNodeWithTag(it).getUnclippedBoundsInRoot().top.value }
        assertTrue("Cover, title and every source block must remain in order", sourceOrder.zipWithNext().all { (a, b) -> a < b })
        for (forbidden in listOf("ORDINARY_PREVIEW_MUST_NOT_REPLACE_ARTICLE", "ORDINARY_TITLE_MUST_NOT_DUPLICATE_ARTICLE",
            "SOURCE_DOES_NOT_RENDER_SUBTITLE", "接口正文已完整载入")) compose.onAllNodesWithText(forbidden, substring = true).assertCountEquals(0)
        assertTrue(ordinaryImages.loadedUrls.isEmpty())
        assertTrue(imageRequests.none { "ordinary-grid" in it || "source-does-not-render-logo" in it })
        assertEquals(1, detailCalls("42")); assertTrue(requests.all { it.method == "GET" })
        val detail = requests.single { it.url.encodedPath == "/v6/feed/detail" }
        assertEquals("42", detail.url.queryParameter("id"))
        assertEquals(setOf("id"), detail.url.queryParameterNames)
        awaitTag("article-media-1-ready"); capture("complete-top")
        compose.onNodeWithTag("article-text--1").performScrollTo(); capture("complete-title")
        compose.onNodeWithTag("article-media-block-1").performScrollTo().assertIsDisplayed(); capture("complete-image-caption-share")
        compose.onNodeWithTag("article-text-3").performScrollTo().assertIsDisplayed(); capture("complete-final-tail")
    }

    @Test fun backWhileActualRootPreparationIsPendingDisposesTheResultAndMediaWork() {
        gateFirstParser = true
        show(); awaitGate(parserEntered); awaitTag("reader-preparing")
        toolbarBack(); awaitTag("content-list")
        parserRelease.countDown(); awaitGate(parserReturned); compose.waitForIdle()
        compose.onNodeWithTag("article-page").assertDoesNotExist()
        compose.onNodeWithTag("full-post-progress").assertDoesNotExist()
        assertTrue(imageRequests.isEmpty()); assertEquals(1, detailCalls("42"))
    }

    @Test fun emptyArrayUsesOrdinaryTitleAndGridWithoutArticleDecorations() {
        responses["42"] = JsonObject(base("42", "ORDINARY_BODY") + mapOf(
            "message_raw_output" to JsonPrimitive("[]"), "title" to JsonPrimitive("ARTICLE_TITLE_NOT_USED"),
            "message_cover" to JsonPrimitive("https://image.coolapk.com/cover-not-used.jpg"),
            "message_title" to JsonPrimitive("ORDINARY_TITLE"), "picArr" to buildJsonArray { add(SyntheticFeedImageLoader.LANDSCAPE) }))
        show(); awaitTag("full-post-page")
        compose.onNodeWithTag("full-post-page").assertTextEquals("ORDINARY_BODY")
        compose.onNodeWithText("ORDINARY_TITLE").assertExists()
        compose.onAllNodesWithText("ARTICLE_TITLE_NOT_USED").assertCountEquals(0)
        compose.onNodeWithTag("article-page").assertDoesNotExist()
        compose.waitUntil(15_000) { ordinaryImages.loadedUrls.contains(SyntheticFeedImageLoader.LANDSCAPE) }
        assertTrue(imageRequests.isEmpty())
    }

    @Test fun realAnchorRoutingSupportsNativeChildSelfUserAndExplicitExternalActions() {
        val links = linkedMapOf("child" to "/feed/43", "self" to "/feed/42", "user" to "/u/7",
            "official external" to "https://www.coolapk.com/about", "other https" to "https://example.test/article")
        responses["42"] = textArticle("42", links.entries.joinToString(" ") { "<a href='${it.value}'>${it.key}</a>" })
        show(); awaitTag("article-page"); assertTrue(browserIntents.isEmpty()); assertEquals(0, detailCalls("43"))
        openInlineLink("self", 5); awaitTag("article-page"); assertEquals(1, detailCalls("42"))
        openInlineLink("child", 5); awaitText("CHILD_43"); assertEquals(1, detailCalls("43"))
        toolbarBack(); awaitTag("article-page")
        openInlineLink("user", 5)
        compose.waitUntil(15_000) { requests.any { it.url.encodedPath == "/v6/user/feedList" && it.url.queryParameter("uid") == "7" } }
        systemBack(); awaitTag("article-page")
        openInlineLink("official external", 5); openInlineLink("other https", 5)
        assertEquals(listOf("https://www.coolapk.com/about", "https://example.test/article"), browserIntents.map { it.dataString })
        assertTrue(browserIntents.all { it.action == Intent.ACTION_VIEW && it.component == null })
        assertEquals(1, detailCalls("42")); assertTrue(imageRequests.isEmpty())
    }

    @Test fun shareCardUsesRealNativeRouteAndUnsupportedLinksGiveFeedbackWithoutIntent() {
        show(); awaitTag("article-page")
        compose.onNodeWithTag("article-share-2").performScrollTo().performClick()
        awaitText("CHILD_44"); assertEquals(1, detailCalls("44")); assertTrue(browserIntents.isEmpty())
        toolbarBack(); awaitTag("article-page")
        val bad = listOf("javascript:alert(1)", "https://user@example.test/path", "file:///tmp/secret", "not a url")
        for ((index, url) in bad.withIndex()) {
            responses["42"] = textArticle("42", "<a href='$url'>bad $index</a>")
            compose.runOnIdle { activeModel.load("feed:42", refresh = true) }
            awaitText("bad $index"); openInlineLink("bad $index", 1)
            dismissFeedback("此链接暂不支持，未打开外部地址")
            assertTrue(browserIntents.isEmpty())
        }
    }

    @Test fun browserFailureAndUnsupportedOriginalAreVisibleWithoutPretendingSuccess() {
        browserUnavailable = true
        responses["42"] = textArticle("42", "<a href='https://example.test/story'>external</a>")
        show(); awaitTag("article-page"); openInlineLink("external", 1)
        dismissFeedback("未找到可打开此链接的浏览器")
        assertTrue(browserIntents.isEmpty())
        responses["42"] = JsonObject(base("42") + ("message_raw_output" to JsonPrimitive("[{\"type\":\"video\"}]")))
        compose.runOnIdle { activeModel.load("feed:42", refresh = true) }; awaitTag("full-post-limit")
        compose.onNodeWithText("浏览器打开原文").performScrollTo().performClick()
        dismissFeedback("无法打开原文")
        browserUnavailable = false
        compose.onNodeWithText("浏览器打开原文").performScrollTo().performClick()
        assertEquals("https://www.coolapk.com/feed/42", browserIntents.single().dataString)
        assertEquals(2, detailCalls("42")); compose.onNodeWithTag("full-post-limit").assertExists()
        assertEquals(0, activeModel.readerPositions.size)
    }

    @Test fun asynchronousImageSuccessPreservesSourceSlotAndParentGeometry() {
        val gate = CompletableDeferred<Unit>().also { imageGates += it }
        imagePlan = { gate.await(); readyImage() }
        responses["42"] = JsonObject(base("42") + ("message_raw_output" to JsonPrimitive(buildJsonArray {
            add(buildJsonObject { put("type", "image"); put("url", "https://image.coolapk.com/test@640x360.jpg"); put("description", "caption") })
            add(buildJsonObject { put("type", "shareUrl"); put("url", "https://example.test/follow"); put("title", "AFTER_IMAGE_SHARE") })
        }.toString())))
        show(); awaitTag("article-media-0-loading")
        val before = compose.onNodeWithTag("article-media-slot-0").getUnclippedBoundsInRoot()
        val beforeBlock = compose.onNodeWithTag("article-media-block-0").getUnclippedBoundsInRoot()
        val beforeShare = compose.onNodeWithTag("article-share-1").getUnclippedBoundsInRoot()
        val beforeArticle = compose.onNodeWithTag("article-page").getUnclippedBoundsInRoot()
        val beforePosition = listPosition()
        val beforeRange = compose.onNodeWithTag("feed-detail-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue()
        assertFalse(progress().contains("全部")); assertFalse(progress().contains("完整载入"))
        gate.complete(Unit); awaitTag("article-media-0-ready"); compose.waitForIdle()
        assertEquals(before, compose.onNodeWithTag("article-media-slot-0").getUnclippedBoundsInRoot())
        assertEquals(beforeBlock, compose.onNodeWithTag("article-media-block-0").getUnclippedBoundsInRoot())
        assertEquals(beforeShare, compose.onNodeWithTag("article-share-1").getUnclippedBoundsInRoot())
        assertEquals(beforeArticle, compose.onNodeWithTag("article-page").getUnclippedBoundsInRoot())
        assertEquals(beforePosition, listPosition(), .001f)
        assertEquals(beforeRange, compose.onNodeWithTag("feed-detail-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue(), .001f)
        capture("media-ready-fixed-geometry")
    }

    @Test fun mediaFailureReasonsRemainVisibleAndRetryIsExplicitAndDuplicateSafe() {
        val attempts = AtomicInteger()
        imagePlan = { if (attempts.incrementAndGet() == 1) ArticleImageResult.Unavailable(ArticleImageFailure.NETWORK) else readyImage() }
        responses["42"] = mediaArticle("https://image.coolapk.com/retry@640x480.jpg")
        show(); awaitTag("article-media-0-unavailable")
        compose.onNodeWithTag("article-media-0-unavailable").assertTextEquals("加载失败 · 点击查看")
        assertEquals(1, attempts.get()); openMediaDetails()
        compose.onNode(hasText("图片加载失败") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithTag("article-media-0-retry").performScrollTo().performClick(); awaitTag("article-media-0-ready")
        assertEquals(2, attempts.get())
        val cases = listOf(ArticleImageFailure.ENCODED_LIMIT to "图片超过 8 MiB 阅读限制或内容为空",
            ArticleImageFailure.SOURCE_LIMIT to "图片尺寸超过阅读限制", ArticleImageFailure.UNSUPPORTED_FORMAT to "暂不支持此图片格式或动画")
        for ((index, pair) in cases.withIndex()) {
            imagePlan = { ArticleImageResult.Unavailable(pair.first) }
            responses["42"] = mediaArticle("https://image.coolapk.com/failure-$index@640x480.jpg")
            refresh42AndAwait(); awaitTag("article-media-0-unavailable"); openMediaDetails()
            compose.onNode(hasText(pair.second) and hasAnyAncestor(isDialog())).assertIsDisplayed()
            compose.onNodeWithTag("article-media-0-retry").assertDoesNotExist()
            compose.onNodeWithTag("article-media-0-ready").assertDoesNotExist()
            compose.onNodeWithTag("article-media-0-details-close").performClick()
            assertFalse(progress().contains("完整载入"))
        }
        val before = imageRequests.size
        responses["42"] = mediaArticle("https://untrusted.example/image.jpg")
        refresh42AndAwait(); awaitTag("article-media-0-unavailable")
        compose.onNodeWithTag("article-media-0-unavailable").assertTextEquals("地址受限 · 点击查看")
        openMediaDetails()
        compose.onNode(hasText("图片地址不可用或不符合安全限制") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        assertEquals(before, imageRequests.size)
        compose.onNodeWithTag("article-media-0-details-close").performClick()
        capture("media-blocked-visible")
    }

    @Test fun duplicateImageTapSelectsItsExactOccurrenceAndViewerHasNoTransferActions() {
        val url = "https://image.coolapk.com/repeated@640x480.jpg"
        responses["42"] = mediaArticle(url, url)
        show(); awaitTag("article-media-1-ready")
        compose.onNodeWithTag("article-media-1-open").performScrollTo().performClick(); awaitTag("article-media-viewer")
        compose.onNodeWithTag("article-media-viewer-position").assertTextEquals("图片 2/2")
        compose.onNodeWithTag("article-media-viewer-next").assertIsNotEnabled()
        compose.onNodeWithTag("article-media-viewer-previous").performClick()
        compose.onNodeWithTag("article-media-viewer-position").assertTextEquals("图片 1/2")
        compose.onNodeWithTag("article-media-viewer-next").performClick()
        compose.onNodeWithTag("article-media-viewer-position").assertTextEquals("图片 2/2")
        for (label in listOf("保存", "分享", "下载", "Save", "Share", "Download"))
            compose.onAllNodes(hasText(label, substring = true) and hasAnyAncestor(isDialog())).assertCountEquals(0)
        val before = listPosition()
        compose.onNodeWithTag("article-media-viewer-close").performClick()
        compose.onNodeWithTag("article-media-viewer").assertDoesNotExist(); assertEquals(before, listPosition(), .001f)
        compose.onNodeWithTag("article-media-1-open").performClick(); awaitTag("article-media-viewer")
        try {
            viewerWindowBack(); compose.onNodeWithTag("article-media-viewer").assertDoesNotExist()
            assertEquals(before, listPosition(), .001f); assertEquals(1, detailCalls("42")); assertTrue(browserIntents.isEmpty())
        } catch (failure: AssertionError) {
            fun snapshot() = "generation=${accounts.store.state.value.generation}, detailCalls=${detailCalls("42")}, " +
                "images=${imageRequests.size}, browserIntents=${browserIntents.size}, activityFinishing=${compose.activity.isFinishing}, " +
                "states=${activeModel.states.value.mapValues { (_, state) -> "loaded=${state.loaded}, loading=${state.loading}, items=${state.items.map { it.id }}, error=${state.error}" }}"
            val beforeInspection = snapshot()
            val trees = failureTrees()
            failure.addSuppressed(AssertionError("Viewer Dialog-window Back failure; savedPosition=$before; before=$beforeInspection; after=${snapshot()}\n$trees"))
            throw failure // Preserve the original close, retained-list geometry and request assertions.
        }
    }

    @Test fun bodyReplacementClosesViewerAndPreventsNonCooperativeOldMediaPublication() {
        val gate = CompletableDeferred<Unit>().also { imageGates += it }
        val entered = AtomicInteger(); val returned = AtomicInteger()
        imagePlan = { entered.incrementAndGet(); withContext(NonCancellable) { gate.await() }; returned.incrementAndGet(); readyImage() }
        responses["42"] = mediaArticle("https://image.coolapk.com/old@640x480.jpg")
        show(); awaitTag("article-media-0-loading")
        openMediaDetails(); compose.onNodeWithTag("article-media-0-view").performClick(); awaitTag("article-media-viewer")
        compose.waitUntil(15_000) { entered.get() >= 2 }
        responses["42"] = textArticle("42", "NEW_BODY_NO_IMAGES")
        compose.runOnIdle { activeModel.load("feed:42", refresh = true) }; awaitText("NEW_BODY_NO_IMAGES")
        compose.onNodeWithTag("article-media-viewer").assertDoesNotExist()
        gate.complete(Unit); compose.waitUntil(15_000) { compose.onRoot().fetchSemanticsNode(); returned.get() >= 2 }
        compose.onNodeWithTag("article-media-0-ready").assertDoesNotExist()
        compose.onNodeWithTag("article-media-slot-0").assertDoesNotExist()
        compose.onNodeWithText("NEW_BODY_NO_IMAGES").assertExists(); assertEquals(0f, listPosition(), .001f)
    }

    @Test fun accountSwitchWhilePreparationIsPendingCannotPublishOldArticleOrStartItsImages() {
        gateFirstParser = true; accounts.store.switchTo("1")
        show(); awaitGate(parserEntered); awaitTag("reader-preparing")
        val oldModel = activeModel
        responses["42"] = textArticle("42", "FRESH_ACCOUNT_TWO_ARTICLE")
        compose.runOnIdle { accounts.store.switchTo("2") }
        awaitText("FRESH_ACCOUNT_TWO_ARTICLE"); assertNotSame(oldModel, activeModel)
        parserRelease.countDown(); awaitGate(parserReturned); compose.waitForIdle()
        compose.onAllNodesWithText("ARTICLE_EXACT_TAIL", substring = true).assertCountEquals(0)
        assertTrue(imageRequests.isEmpty()); assertEquals(2, detailCalls("42"))
        val detail = requests.filter { it.url.encodedPath == "/v6/feed/detail" }
        assertTrue(detail[0].header("Cookie").orEmpty().contains("token=synthetic-article-1"))
        assertTrue(detail[1].header("Cookie").orEmpty().contains("token=synthetic-article-2"))
    }

    @Test @Config(qualifiers = "w320dp-h480dp-420dpi")
    fun compactFontScaleOnePointSixCanReachTheExactLongFinalTail() {
        fontScale = 1.6f
        val source = "合成正文".repeat(3000) + "ARTICLE_LONG_EXACT_FINAL_TAIL"
        responses["42"] = textArticle("42", source)
        show(); awaitTag("article-page")
        val seen = StringBuilder()
        repeat(4) { page ->
            seen.append(compose.onNodeWithTag("article-text-0").fetchSemanticsNode().config[SemanticsProperties.Text].single().text)
            if (page < 3) compose.onNodeWithText("下一段").performScrollTo().performClick()
        }
        assertEquals(source, seen.toString())
        compose.onNodeWithTag("full-post-progress").assertTextContains("第 4/4 段", substring = true)
        compose.onNodeWithTag("article-text-0").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("已到结构化正文末尾").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("下一段").assertIsNotEnabled()
        capture("compact-font-1-6-final-tail")
    }
    @Test fun actualInlineTextTapUsesNativeAppRoutingWithoutTheSupplementaryLinkMenu() {
        responses["42"] = textArticle("42", "before <a href='/feed/43'>TAP_CHILD</a> after")
        show(); awaitTag("article-text-0")
        val node = compose.onNodeWithTag("article-text-0").performScrollTo()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> assertTrue(action(layouts)) }
        val layout = layouts.single()
        val index = layout.layoutInput.text.text.indexOf("TAP_CHILD")
        assertTrue(index >= 0)
        val target = layout.getBoundingBox(index + 1).center
        node.performTouchInput { click(target) }
        awaitText("CHILD_43"); assertEquals(1, detailCalls("43")); assertTrue(browserIntents.isEmpty())
    }

    @Test fun ordinaryReadCookieRevisionKeepsArticleOwnerAndEqualSourceBookmarkCompatible() {
        accounts.store.switchTo("1")
        detailSetCookie = "SESSID=synthetic-article-refreshed; Domain=coolapk.com; Path=/; Secure"
        responses["42"] = textArticle("42", "x".repeat(12_000) + "SESSION_ARTICLE_TAIL")
        val before = accounts.store.snapshot()
        val generation = accounts.store.state.value.generation
        show(); awaitTag("article-page")
        val model = activeModel
        assertEquals(generation, accounts.store.state.value.generation)
        assertTrue(accounts.store.isCurrent(before))
        assertTrue(accounts.store.snapshot().revision > before.revision)
        assertTrue(accounts.store.snapshot().cookieHeader("https://api.coolapk.com/v6/feed/detail".toHttpUrl())
            .contains("SESSID=synthetic-article-refreshed"))
        repeat(2) { compose.onNodeWithText("下一段").performScrollTo().performClick() }
        compose.onNodeWithTag("full-post-progress").assertTextContains("第 3/4 段", substring = true)
        val progressBefore = progress()
        val rectBefore = compose.onNodeWithTag("article-text-0").getUnclippedBoundsInRoot()
        val positionBefore = listPosition()
        responses["42"] = JsonObject(responses.getValue("42") + ("likenum" to JsonPrimitive(1)))
        compose.runOnIdle { activeModel.load("feed:42", refresh = true) }
        compose.waitUntil(15_000) {
            compose.onRoot().fetchSemanticsNode()
            activeModel.states.value["feed:42"]?.let { !it.loading && it.items.single().feed?.likeCount == 1 } == true
        }
        assertSame(model, activeModel); assertEquals(generation, accounts.store.state.value.generation)
        assertEquals(progressBefore, progress()); assertEquals(positionBefore, listPosition(), .001f)
        assertEquals(rectBefore, compose.onNodeWithTag("article-text-0").getUnclippedBoundsInRoot())
        assertEquals(2, detailCalls("42"))
    }

    @Test fun equalMetadataRefreshRetainsOpenViewerOccurrenceAndDoesNotRefetchItsImages() {
        val url = "https://image.coolapk.com/equal@640x480.jpg"
        responses["42"] = mediaArticle(url, url)
        show(); awaitTag("article-media-1-ready")
        compose.onNodeWithTag("article-media-1-open").performScrollTo().performClick()
        awaitTag("article-viewer-1-ready")
        val requestsBefore = imageRequests.toList()
        val positionBefore = listPosition()
        val rectBefore = compose.onNodeWithTag("article-media-block-1").getUnclippedBoundsInRoot()
        responses["42"] = JsonObject(responses.getValue("42") + ("likenum" to JsonPrimitive(1)))
        refresh42AndAwait()
        compose.onNodeWithTag("article-media-viewer").assertExists()
        compose.onNodeWithTag("article-media-viewer-position").assertTextEquals("图片 2/2")
        assertEquals(requestsBefore, imageRequests.toList())
        compose.onNodeWithTag("article-media-viewer-close").performClick()
        assertEquals(positionBefore, listPosition(), .001f)
        assertEquals(rectBefore, compose.onNodeWithTag("article-media-block-1").getUnclippedBoundsInRoot())
        assertEquals(2, detailCalls("42"))
    }

    @Test fun extremeWideFailedImageKeepsReachableDetailsWithoutChangingSourceGeometry() {
        responses["42"] = mediaArticle("https://image.coolapk.com/extreme@32768x1.jpg")
        imagePlan = { ArticleImageResult.Unavailable(ArticleImageFailure.ENCODED_LIMIT) }
        show(); awaitTag("article-media-0-unavailable")
        val slot = compose.onNodeWithTag("article-media-slot-0").getUnclippedBoundsInRoot()
        assertEquals("Thin source gets a fixed first-measure 48dp safe hit region", 48f, (slot.bottom - slot.top).value, .01f)
        val inner = compose.onNodeWithTag("article-media-source-0").getUnclippedBoundsInRoot()
        assertTrue("Inner image retains source ratio without being stretched to the safety minimum", (inner.bottom - inner.top).value <= 1f)
        val more = compose.onNodeWithTag("article-media-0-open").performScrollTo().assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("Details action remains at least 48dp high", (more.bottom - more.top).value >= 48f)
        val beforeDetails = compose.onNodeWithTag("article-media-slot-0").getUnclippedBoundsInRoot()
        openMediaDetails()
        compose.onNode(hasText("图片超过 8 MiB 阅读限制或内容为空") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithTag("article-media-0-retry").assertDoesNotExist()
        compose.onNodeWithTag("article-media-0-view").performClick(); awaitTag("article-media-viewer")
        compose.onNodeWithTag("article-media-viewer-close").performClick()
        assertEquals(beforeDetails, compose.onNodeWithTag("article-media-slot-0").getUnclippedBoundsInRoot())
    }

    @Test fun actualRootRuntimeParseFailureHasNoBookmarkAndCanRetryWithoutAnotherRequest() {
        failFirstParser = true
        show(); awaitTag("full-post-limit")
        compose.onNodeWithTag("full-post-limit").assertTextEquals(fullPostLimitMessage(FullPostBodyLimit.PARSING_ERROR))
        compose.onNodeWithTag("article-page").assertDoesNotExist()
        assertEquals(0, activeModel.readerPositions.size); assertTrue(imageRequests.isEmpty())
        compose.onNodeWithText("重新排版正文").performScrollTo().performClick()
        awaitTag("article-page"); awaitTag("article-media-0-ready")
        compose.onNodeWithTag("article-text-3").assertTextEquals("文章末尾 ARTICLE_EXACT_TAIL")
        compose.onNodeWithTag("full-post-limit").assertDoesNotExist()
        assertEquals(1, detailCalls("42")); assertEquals(2, parserCalls.get())
    }

    @Test fun changedArticleClosesAnOpenLinkMenuAndDoesNotReuseItsOldTargets() {
        responses["42"] = textArticle("42", "<a href='/feed/43'>OLD_TARGET</a>")
        show(); awaitTag("article-page")
        compose.onNodeWithText("本段链接（1）").performScrollTo().performClick()
        compose.onNode(hasText("OLD_TARGET") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        responses["42"] = textArticle("42", "NEW_BODY_WITHOUT_LINKS")
        refresh42AndAwait(); awaitText("NEW_BODY_WITHOUT_LINKS")
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onAllNodesWithText("OLD_TARGET", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("本段链接（1）").assertCountEquals(0)
        assertEquals(0, detailCalls("43")); assertTrue(browserIntents.isEmpty())
    }

}
