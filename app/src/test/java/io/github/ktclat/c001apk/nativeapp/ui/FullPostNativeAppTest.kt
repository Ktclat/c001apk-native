// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import java.io.File
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
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
import io.github.ktclat.c001apk.nativeapp.feature.feed.SyntheticFeedImageLoader
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
import org.robolectric.shadows.ShadowDialog
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Actual NativeApp -> generation-owned BrowseViewModel -> HttpCoolapkRepository -> decoder -> reader.
 * Every HTTP response and browser launch is intercepted in memory; no chain.proceed, DNS or accounts.
 * These are source fixtures, not a claim of execution or device acceptance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FullPostNativeAppTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val requests = CopyOnWriteArrayList<Request>()
    private val unexpected = CopyOnWriteArrayList<String>()
    private val browserIntents = CopyOnWriteArrayList<Intent>()
    private val clients = mutableListOf<OkHttpClient>()
    private val detailCount = AtomicInteger()
    private val detailEntered = CountDownLatch(1)
    private val releaseDetail = CountDownLatch(1)
    private val oldResponseReturned = CountDownLatch(1)
    private val sessionOwner = SessionViewModelStore()
    private lateinit var activeModel: BrowseViewModel
    private lateinit var accounts: AccountRuntime
    private lateinit var container: AppContainer
    private lateinit var images: SyntheticFeedImageLoader
    private var blockFirstDetail = false
    private var structuredArticle = false
    private var browserUnavailable = false
    private val fullMessage = buildString {
        val paragraph = "这是一段离线合成的长正文，用来检查原生阅读的段落、行距与页面切换。内容不来自真实账户，也不会向服务器提交。读到下一段时，界面应回到该段开头；返回列表时，原来的位置应保持不变。\n"
        while (length < 12_000) append(paragraph)
    }.take(12_000) + "GENUINE_DETAIL_TAIL"
    private class MemoryLocal : JsonStateStore<LocalState> {
        override val data = MutableStateFlow(LocalState())
        override suspend fun update(transform: (LocalState) -> LocalState) {
            data.value = LocalRepository.validateState(transform(data.value))
        }
    }
    private fun feed(id: String, message: String) = buildJsonObject {
        put("id", id); put("entityType", "feed"); put("uid", "9")
        put("username", "Synthetic reader fixture"); put("message", message)
        put("replynum", 0); put("likenum", 0)
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
        }, guestDeviceId = "synthetic-reader-guest")
        for (id in listOf("1", "2")) {
            val token = Cookie.Builder().name("token").value("synthetic-reader-$id")
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
                "/v6/main/indexV8" -> buildJsonObject { put("data", JsonArray((1000..1039).map { feed("$it", "Parent preview $it") })) }.toString()
                "/v6/feed/detail" -> {
                    val number = detailCount.incrementAndGet()
                    detailEntered.countDown()
                    if (blockFirstDetail && number == 1 && !releaseDetail.await(10, TimeUnit.SECONDS)) throw IOException("Synthetic detail gate timed out")
                    val message = if (request.header("Cookie").orEmpty().contains("token=synthetic-reader-2")) "FRESH_ACCOUNT_TWO" else fullMessage
                    val detail = feed(request.url.queryParameter("id")!!, if (structuredArticle) "article preview" else message)
                    val fields = detail.toMutableMap()
                    if (structuredArticle) fields["message_raw_output"] = JsonPrimitive("[{\"type\":\"video\",\"message\":\"STRUCTURED_NOT_RENDERED\"}]")
                    if (number == 1) oldResponseReturned.countDown()
                    buildJsonObject { put("data", JsonObject(fields)) }.toString()
                }
                else -> """{"data":[]}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
                .body(body.toResponseBody()).build() // NEVER chain.proceed.
        }.build()
        val loginClient = OkHttpClient.Builder().addInterceptor { throw IOException("Synthetic login: no external I/O") }.build()
        clients += listOf(client, loginClient)
        val repository = HttpCoolapkRepository(client = client, requestSession = { store.captureRequestSession() })
        val social = SocialRepository(store) { snapshot -> CoolapkHttpTransport(client = client, requestSession = { store.requestSessionFor(snapshot) }) }
        val login = HttpLoginTransport(loginClient)
        val ctor = AccountRuntime::class.java.getDeclaredConstructor(AccountStore::class.java, HttpLoginTransport::class.java,
            LoginCoordinator::class.java, HttpCoolapkRepository::class.java, SocialRepository::class.java).apply { isAccessible = true }
        accounts = ctor.newInstance(store, login, LoginCoordinator(store, login, "synthetic-reader-device"), repository, social)
    }
    @After fun cleanup() {
        releaseDetail.countDown()
        sessionOwner.viewModelStore.clear()
        clients.forEach { it.dispatcher.cancelAll(); it.connectionPool.evictAll(); it.dispatcher.executorService.shutdownNow() }
        images.close()
        assertTrue("No image sockets: ${images.unexpectedUrls}", images.unexpectedUrls.isEmpty())
        assertTrue("No unexpected transport: $unexpected", unexpected.isEmpty())
    }
    private fun show(initialRoute: String? = null) {
        val browserContext = object : ContextWrapper(compose.activity) {
            override fun startActivity(intent: Intent) {
                if (browserUnavailable) throw ActivityNotFoundException("Synthetic missing browser")
                browserIntents += Intent(intent)
            }
        }
        compose.setContent {
            val state by accounts.store.state.collectAsState()
            sessionOwner.select(state.generation)
            val model = remember(state.generation) {
                ViewModelProvider(sessionOwner, viewModelFactory { initializer { BrowseViewModel(accounts.repository) } })[BrowseViewModel::class.java]
            }
            SideEffect { activeModel = model }
            MaterialTheme(colorScheme = fixedColorScheme(0, false), typography = appTypography(FontChoice.NOTO_SANS)) {
                CompositionLocalProvider(LocalContext provides browserContext, LocalViewModelStoreOwner provides sessionOwner,
                    LocalReadingStyles provides readingStyles(ReadingPreset.COMFORTABLE, FontChoice.NOTO_SANS)) {
                    key(state.generation) { NativeApp(model, container,
                        AppSettings(floatingNavigation = true, dynamicColor = false, font = FontChoice.NOTO_SANS,
                            readingPreset = ReadingPreset.COMFORTABLE), accounts, state, initialRoute = initialRoute) }
                }
            }
        }
    }
    private fun awaitTag(tag: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
    /** Supplementary bounded viewport evidence; only runs in the coordinator's permitted gate. */
    private fun capture(name: String, dialog: Boolean = false) {
        compose.waitForIdle()
        // Verify the error is in the sole current dialog; never substitute the activity behind it.
        if (dialog) {
            compose.onAllNodes(isDialog()).assertCountEquals(1)
            compose.onNode(hasText("无法打开原文") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        }
        val bitmap = compose.runOnUiThread {
            val view = if (dialog) {
                val shown = ShadowDialog.getShownDialogs().filter { it.isShowing }.distinct()
                check(shown.size == 1) { "Expected exactly one showing error dialog" }
                val current = requireNotNull(ShadowDialog.getLatestDialog())
                check(current === shown.single()) { "Latest dialog is not the current error window" }
                requireNotNull(current.window).decorView
            } else compose.activity.findViewById<View>(android.R.id.content)
            check(view.width > 0 && view.height > 0)
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val file = File("build/verification/$name").apply { parentFile?.mkdirs() }
        try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
    private fun homeIds() = activeModel.states.value.getValue("home").items.map { it.id }
    private fun scrollPosition() = compose.onNodeWithTag("content-list").fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun visibleFeedPositions(): Map<String, Float> {
        val viewport = compose.onNodeWithTag("content-list").fetchSemanticsNode().boundsInRoot
        return compose.onAllNodes(SemanticsMatcher("Feed card") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("feed:") == true
        }).fetchSemanticsNodes().filter { it.boundsInRoot.bottom > viewport.top && it.boundsInRoot.top < viewport.bottom }
            .associate { it.config[SemanticsProperties.TestTag] to it.boundsInRoot.top }
    }
    @Test fun actualRootLoadsRealTailPagesFromTopAndReturnsToUnchangedParentList() {
        blockFirstDetail = true
        show()
        awaitTag("content-list")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("feed-body:1000").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("content-list").performScrollToIndex(6)
        val beforeIds = homeIds()
        val beforePosition = scrollPosition()
        val beforeVisible = visibleFeedPositions()
        assertTrue(beforeVisible.isNotEmpty())
        compose.onNodeWithTag("feed-body:1006").performClick()
        awaitTag("feed-detail-loading")
        compose.waitUntil(10_000) { compose.onRoot().fetchSemanticsNode(); detailEntered.count == 0L }
        compose.onNodeWithTag("full-post-progress").assertDoesNotExist()
        releaseDetail.countDown()
        awaitTag("full-post-page")
        compose.onNodeWithTag("full-post-page").assertTextEquals(fullMessage.take(4000))
        compose.onNodeWithTag("full-post-pending").assertDoesNotExist()
        compose.onNodeWithTag("reader-preparing").assertDoesNotExist()
        compose.onNodeWithTag("feed-detail-loading").assertDoesNotExist()
        val item = activeModel.states.value.getValue("feed:1006").items.single()
        assertEquals(12_000, item.message.length)
        assertEquals(fullMessage, (item.fullPostBody as FullPostBody.Available).html)
        compose.onNodeWithTag("full-post-progress").assertIsDisplayed()
        capture("native-full-post-first-page.png")
        repeat(3) { page ->
            compose.onNodeWithText("下一段").performScrollTo().assertIsDisplayed().performClick()
            compose.waitForIdle()
            // Verify real page-start visibility after scrolling to the previous page's bottom.
            compose.onNodeWithTag("full-post-progress").assertIsDisplayed()
                .assertTextContains("第 ${page + 2}/4 段", substring = true)
            val viewport = compose.onNodeWithTag("feed-detail-list").getUnclippedBoundsInRoot()
            val pageBounds = compose.onNodeWithTag("full-post-page").getUnclippedBoundsInRoot()
            assertTrue("New page beginning must be inside the viewport", pageBounds.top >= viewport.top && pageBounds.top < viewport.bottom)
        }
        compose.onNodeWithTag("full-post-page").assertTextEquals("GENUINE_DETAIL_TAIL").assertIsDisplayed()
        compose.onNodeWithText("下一段").assertIsNotEnabled()
        capture("native-full-post-final-tail.png")
        compose.onNodeWithContentDescription("返回").performClick()
        awaitTag("content-list")
        compose.waitForIdle()
        assertEquals(beforeIds, homeIds())
        assertEquals(beforePosition, scrollPosition(), 0.001f)
        val afterVisible = visibleFeedPositions()
        assertEquals(beforeVisible.keys, afterVisible.keys)
        beforeVisible.forEach { (id, y) -> assertEquals(y, afterVisible.getValue(id), 1f) }
        assertEquals(1, requests.count { it.url.encodedPath == "/v6/main/indexV8" })
        assertEquals(1, detailCount.get())
    }
    @Test fun structuredArticleBrowserActionEmitsExternalIntentNotSameRouteNavigation() {
        structuredArticle = true
        show("feed:42")
        awaitTag("full-post-limit")
        compose.onNodeWithTag("full-post-limit").assertTextEquals(fullPostLimitMessage(FullPostBodyLimit.UNSUPPORTED_ARTICLE))
        compose.onAllNodesWithText("STRUCTURED_NOT_RENDERED", substring = true).assertCountEquals(0)
        compose.onNodeWithTag("full-post-progress").assertDoesNotExist()
        compose.onNodeWithText("浏览器打开原文").performScrollTo().assertIsDisplayed()
        capture("native-full-post-unsupported-article.png")
        compose.onNodeWithText("浏览器打开原文").performClick()
        assertEquals(1, browserIntents.size)
        assertEquals(Intent.ACTION_VIEW, browserIntents.single().action)
        assertEquals("https://www.coolapk.com/feed/42", browserIntents.single().dataString)
        assertNull(browserIntents.single().component)
        compose.onNodeWithTag("full-post-limit").assertExists()
        assertEquals(1, detailCount.get())
    }
    @Test fun unavailableBrowserIsVisibleAndDoesNotClaimArticleWasOpened() {
        structuredArticle = true; browserUnavailable = true
        show("feed:42")
        awaitTag("full-post-limit")
        compose.onNodeWithText("浏览器打开原文").performScrollTo().performClick()
        compose.onNodeWithText("无法打开原文").assertIsDisplayed()
        capture("native-full-post-browser-unavailable.png", dialog = true)
        compose.onNodeWithText("知道了").performClick()
        compose.onNodeWithTag("full-post-limit").assertExists()
        assertTrue(browserIntents.isEmpty())
        assertEquals(1, detailCount.get())
    }
    @Test fun generationOwnedActualRootRejectsLateAccountOneDetailAfterSwitch() {
        accounts.store.switchTo("1"); blockFirstDetail = true
        show("feed:42")
        awaitTag("feed-detail-loading")
        compose.waitUntil(10_000) { compose.onRoot().fetchSemanticsNode(); detailEntered.count == 0L }
        val oldModel = activeModel
        compose.runOnIdle { accounts.store.switchTo("2") }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("FRESH_ACCOUNT_TWO").fetchSemanticsNodes().isNotEmpty() }
        assertNotSame(oldModel, activeModel)
        releaseDetail.countDown()
        compose.waitUntil(10_000) { compose.onRoot().fetchSemanticsNode(); oldResponseReturned.count == 0L }
        compose.onNodeWithText("FRESH_ACCOUNT_TWO").assertIsDisplayed()
        compose.onAllNodesWithText("GENUINE_DETAIL_TAIL", substring = true).assertCountEquals(0)
        assertTrue(oldModel.states.value["feed:42"]?.items.isNullOrEmpty())
        val detailRequests = requests.filter { it.url.encodedPath == "/v6/feed/detail" }
        assertEquals(2, detailRequests.size)
        assertTrue(detailRequests.first().header("Cookie").orEmpty().contains("token=synthetic-reader-1"))
        assertTrue(detailRequests.last().header("Cookie").orEmpty().contains("token=synthetic-reader-2"))
    }
}
