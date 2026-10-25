// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.ContextWrapper
import android.content.Intent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
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
import java.util.concurrent.CopyOnWriteArrayList

/** Actual NativeApp list → typed card leaf → exact feed ID → HTTP detail → body → Back.
 * Only sockets/images are replaced; this is not a repository mock or a real-service claim.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeTemplateNativeAppTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val requests = CopyOnWriteArrayList<Request>()
    private val unexpected = CopyOnWriteArrayList<String>()
    private val opened = CopyOnWriteArrayList<Intent>()
    private val sessionOwner = SessionViewModelStore()
    private lateinit var images: IconFixtureImages
    private lateinit var client: OkHttpClient
    private class MemoryLocal : JsonStateStore<LocalState> {
        override val data = MutableStateFlow(LocalState())
        override suspend fun update(transform: (LocalState) -> LocalState) { data.value = transform(data.value) }
    }
    @After fun close() {
        sessionOwner.viewModelStore.clear()
        if (::client.isInitialized) { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
        if (::images.isInitialized) images.close()
        assertTrue("No external or mutation requests: $unexpected", unexpected.isEmpty())
    }
    @Test fun normalHomeToDetailAndBackUsesExplicitUrlNotContainerOrEntityId() = exercise(1f, false, "normal")
    @Test fun largeFontHomeToDetailAndBackRemainsReadable() = exercise(1.6f, false, "font1.6")
    @Test fun darkHomeToDetailAndBackRetainsImages() = exercise(1f, true, "dark")
    @Test fun homeEventReadsEventFieldsAndOnlyExplicitRegistrationOpensBrowser() = exercise(1f, false, "event", "event")
    @Test fun homeDyhShowsBothSourceTabsAndKeepsPagedContent() = exercise(1f, false, "dyh", "dyh")
    @Test fun homeCoolpicPreservesColonTagAcrossAllThreeTabs() = exercise(1.6f, true, "coolpic", "coolpic")
    @Test fun eventReadFailureRetriesWithoutAutomaticBrowserFallback() = exercise(1f, false, "event-retry", "event", "error")
    @Test fun linkedListReadFailureRetriesTheSelectedTab() = exercise(1f, false, "dyh-retry", "dyh", "error")
    @Test fun missingEventIsExplicitAndCanBeReloaded() = exercise(1f, false, "event-empty", "event", "empty")
    @Test fun validEmptyPictureListCanBeExplicitlyRefreshed() = exercise(1.6f, true, "coolpic-empty", "coolpic", "empty")

    @Test fun selectedSourceTabAndScrollSurviveChildFeedAndBackWithoutRefetch() = exercise(1f, false, "source-child-return", "dyh", childReturn = true)

    private fun exercise(scale: Float, dark: Boolean, name: String, destination: String = "feed", firstResponse: String? = null, childReturn: Boolean = false) {
        images = IconFixtureImages(compose.activity)
        val container = AppContainer(compose.activity.application).also { it.nativeUpdates.enabled = false }
        AppContainer::class.java.getDeclaredField("local").apply { isAccessible = true }.set(container, LocalRepository(MemoryLocal()))
        val store = AccountStore(object : CredentialPersistence {
            override fun read(): ByteArray? = null
            override fun write(bytes: ByteArray) = Unit
            override fun reset() = Unit
        }, guestDeviceId = "synthetic-home-device")
        val home = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/home-cards/templates.json")).readText()).jsonObject["data"]!!.jsonArray[5].jsonObject
        // The child ID deliberately differs from its explicit source URL; navigation must use URL.
        val target = when (destination) { "event" -> "/event/1086"; "dyh" -> "/dyh/55"; "coolpic" -> "/t/星空%3A2026?type=8"; else -> "/feed/9102" }
        val children = home["entities"]!!.jsonArray.mapIndexed { index, child -> JsonObject(child.jsonObject +
            ("id" to JsonPrimitive("not-route-id")) + if (index == 2) mapOf("url" to JsonPrimitive(target)) else emptyMap()) }
        val homeEnvelope = buildJsonObject { put("data", JsonArray(listOf(JsonObject(home + ("entities" to JsonArray(children)))))) }.toString()
        client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            if (request.method != "GET" || request.url.host !in setOf("api.coolapk.com", "api2.coolapk.com") ||
                path !in setOf("/v6/main/indexV8", "/v6/feed/detail", "/v6/feed/replyList", "/v6/event/detail", "/v6/dyhArticle/list", "/v6/picture/list")) {
                unexpected += "${request.method} $path"; throw IOException("No sockets in synthetic test")
            }
            requests += request
            val firstLinked = path in setOf("/v6/event/detail", "/v6/dyhArticle/list", "/v6/picture/list") && requests.count { it.url.encodedPath == path } == 1
            val status = if (firstLinked && firstResponse == "error") 503 else 200
            val body = if (firstLinked && firstResponse == "empty") {
                if (destination == "event") """{"data":null}""" else """{"data":[]}"""
            } else when (path) {
                "/v6/main/indexV8" -> homeEnvelope
                "/v6/feed/detail" -> """{"data":{"id":${request.url.queryParameter("id")},"entityType":"feed","uid":7,"userInfo":{"uid":7,"username":"合成详情作者"},"message":"从首页图文栏目进入的完整详情正文；不是栏目标题或列表摘要。HOME_CARD_DETAIL_TAIL","message_raw_output":"[]","replynum":0}}"""
                "/v6/event/detail" -> """{"data":{"id":1086,"title":"完整合成活动标题","description":"**规则原样保留**","notice_rule":"产品数量：8","content":"活动正文尾部 EVENT_ROUTE_TAIL","time_reg_end":1792116000,"action_url":"https://example.com/synthetic-registration"}}"""
                "/v6/dyhArticle/list", "/v6/picture/list" -> {
                    val type = request.url.queryParameter("type")!!; val page = request.url.queryParameter("page")!!
                    if (childReturn) buildJsonObject { put("data", buildJsonArray {
                        repeat(20) { i -> add(buildJsonObject { put("id", 9300 + i); put("entityType", "feed"); put("uid", 7); put("username", "合成栏目作者"); put("message", "${type}栏目第${page}页正文 $i") }) }
                    }) }.toString() else
                    """{"data":[{"id":${9200 + page.toInt()},"entityType":"feed","uid":7,"username":"合成栏目作者","message":"${type}栏目第${page}页正文"}]}"""
                }
                else -> """{"data":[]}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("synthetic")
                .body(body.toResponseBody()).build() // NEVER chain.proceed.
        }.build()
        val login = HttpLoginTransport(client)
        val repository = HttpCoolapkRepository(client = client, requestSession = { store.captureRequestSession() })
        val social = SocialRepository(store) { snapshot -> CoolapkHttpTransport(client = client, requestSession = { store.requestSessionFor(snapshot) }) }
        val ctor = AccountRuntime::class.java.getDeclaredConstructor(AccountStore::class.java, HttpLoginTransport::class.java,
            LoginCoordinator::class.java, HttpCoolapkRepository::class.java, SocialRepository::class.java).apply { isAccessible = true }
        val accounts = ctor.newInstance(store, login, LoginCoordinator(store, login, "synthetic-home-device"), repository, social)
        val browserContext = object : ContextWrapper(compose.activity) { override fun startActivity(intent: Intent) { opened += Intent(intent) } }
        compose.setContent {
            val state by store.state.collectAsState()
            sessionOwner.select(state.generation)
            val model = remember(state.generation) { ViewModelProvider(sessionOwner, viewModelFactory { initializer { BrowseViewModel(repository) } })[BrowseViewModel::class.java] }
            CompositionLocalProvider(LocalViewModelStoreOwner provides sessionOwner, LocalMediaImageLoader provides images.loader, LocalContext provides browserContext,
                LocalDensity provides Density(LocalDensity.current.density, scale),
                LocalReadingStyles provides readingStyles(ReadingPreset.COMFORTABLE, FontChoice.NOTO_SANS)) {
                MaterialTheme(colorScheme = fixedColorScheme(0, dark), typography = appTypography(FontChoice.NOTO_SANS)) {
                    key(state.generation) { NativeApp(model, container, AppSettings(floatingNavigation = true, dynamicColor = false, openInBrowser = true), accounts, state) }
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("home-entries:card-5").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("home-entries:card-5").performScrollToIndex(2)
        compose.onNodeWithTag("home-entry:card-5:2").assertIsDisplayed()
        compose.onNodeWithText("离线内容 3").assertIsDisplayed()
        compose.waitUntil(5_000) { images.loaded.contains("https://fixture.invalid/icon-3.png") }
        val before = compose.onNodeWithTag("home-entry:card-5:2").fetchSemanticsNode().boundsInRoot
        capture("$name-home")
        compose.onNodeWithTag("home-entry:card-5:2").performClick()
        if (firstResponse != null) {
            val message = if (firstResponse == "error") "请求失败（HTTP 503）" else if (destination == "event") "未找到活动，可能已下架" else "暂无内容"
            awaitText(message)
            compose.onNodeWithTag("event-registration").assertDoesNotExist()
            assertTrue(opened.isEmpty())
            capture("$name-initial-state")
            if (firstResponse == "empty" && destination != "event") compose.onNodeWithTag("$destination-refresh").performScrollTo().performClick()
            else compose.onNodeWithText("重试").performScrollTo().performClick()
        }
        if (destination == "feed") {
            awaitText("HOME_CARD_DETAIL_TAIL")
            compose.onNode(hasText("HOME_CARD_DETAIL_TAIL", substring = true)).assertIsDisplayed()
            assertEquals("9102", requests.single { it.url.encodedPath == "/v6/feed/detail" }.url.queryParameter("id"))
        } else if (destination == "event") {
            awaitText("EVENT_ROUTE_TAIL")
            compose.onNodeWithText("完整合成活动标题").assertIsDisplayed()
            compose.onNodeWithText("**规则原样保留**").assertIsDisplayed()
            val eventRequests = requests.filter { it.url.encodedPath == "/v6/event/detail" }
            assertEquals(if (firstResponse == null) 1 else 2, eventRequests.size)
            assertTrue(eventRequests.all { it.url.queryParameter("id") == "1086" })
            assertTrue(opened.isEmpty())
            compose.onNodeWithTag("event-registration").performScrollTo().performClick()
            assertEquals("https://example.com/synthetic-registration", opened.single().dataString)
        } else {
            val initialType = if (destination == "dyh") "all" else "recommend"
            awaitText("${initialType}栏目第1页正文")
            val types = if (destination == "dyh") listOf("square") else listOf("hot", "newest")
            types.forEach { type ->
                compose.onNodeWithTag("$destination-tab:$type").performClick()
                awaitText("${type}栏目第1页正文")
            }
            if (childReturn) {
                compose.onNodeWithTag("content-list").performScrollToIndex(10)
                val beforeChild = compose.onNodeWithTag("feed-body:9310").fetchSemanticsNode().boundsInRoot
                compose.onNodeWithTag("feed-body:9310").assertIsDisplayed().performClick()
                awaitText("HOME_CARD_DETAIL_TAIL")
                assertEquals("9310", requests.single { it.url.encodedPath == "/v6/feed/detail" }.url.queryParameter("id"))
                compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
                compose.onNodeWithTag("dyh-tab:square").assertIsSelected()
                compose.onNodeWithTag("feed-body:9310").assertIsDisplayed()
                assertEquals(beforeChild, compose.onNodeWithTag("feed-body:9310").fetchSemanticsNode().boundsInRoot)
                assertEquals(2, requests.count { it.url.encodedPath == "/v6/dyhArticle/list" })
                capture("$name-restored")
            } else {
            compose.onNodeWithTag("content-list").performScrollToNode(hasText("加载更多"))
            compose.onNodeWithText("加载更多").performClick()
            awaitText("${types.last()}栏目第2页正文")
            val sourceRequests = requests.filter { it.url.encodedPath == if (destination == "dyh") "/v6/dyhArticle/list" else "/v6/picture/list" }
            assertEquals(List(if (firstResponse == null) 1 else 2) { initialType } + types + types.last(), sourceRequests.map { it.url.queryParameter("type") })
            assertEquals("2", sourceRequests.last().url.queryParameter("page"))
            assertEquals("9201", sourceRequests.last().url.queryParameter("lastItem"))
            if (destination == "coolpic") assertTrue(sourceRequests.all { it.url.queryParameter("tag") == "星空:2026" })
            else assertTrue(sourceRequests.all { it.url.queryParameter("dyhId") == "55" })
            }
        }
        capture("$name-detail")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("home-entry:card-5:2").assertIsDisplayed()
        assertEquals(before, compose.onNodeWithTag("home-entry:card-5:2").fetchSemanticsNode().boundsInRoot)
        assertEquals(1, requests.count { it.url.encodedPath == "/v6/main/indexV8" })
        assertEquals(if (destination == "feed" || childReturn) 1 else 0, requests.count { it.url.encodedPath == "/v6/feed/detail" })
        capture("$name-back")
    }
    private fun awaitText(text: String) = compose.waitUntil(10_000) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.runOnUiThread {
            val view = compose.activity.findViewById<View>(android.R.id.content)
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        File("build/verification/home-templates/root-$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
