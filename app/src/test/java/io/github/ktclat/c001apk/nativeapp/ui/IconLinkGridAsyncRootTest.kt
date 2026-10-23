// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import io.github.ktclat.c001apk.nativeapp.AppContainer
import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.data.*
import io.github.ktclat.c001apk.nativeapp.domain.*
import io.github.ktclat.c001apk.nativeapp.feature.media.LocalMediaImageLoader
import io.github.ktclat.c001apk.nativeapp.local.*
import io.github.ktclat.c001apk.nativeapp.settings.*
import io.github.ktclat.c001apk.nativeapp.social.SocialRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Cookie
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Independent reproduction of V3: actual asynchronous PagedPostText before grid, await ready both sides of Back, no corrective scroll. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconLinkGridAsyncRootTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var images: IconFixtureImages
    private lateinit var client: OkHttpClient
    private lateinit var store: AccountStore
    private val longBody = "滚动后查看入口。".repeat(120)
    private val requests = mutableListOf<String>()
    private val blocked = mutableListOf<String>()
    private val homeState = LazyListState()
    private val holdReturnParser = AtomicBoolean(false)
    private val returnParserEntered = CountDownLatch(1)
    private val returnParserFinished = CountDownLatch(1)
    private val releaseReturnParser = CountDownLatch(1)
    private val parser: (String) -> ParsedPost = { html ->
        if (holdReturnParser.get()) {
            returnParserEntered.countDown()
            try {
                check(releaseReturnParser.await(15, TimeUnit.SECONDS)) { "Controlled parser was not released" }
                decodePostHtml(html)
            } finally { returnParserFinished.countDown() }
        } else decodePostHtml(html)
    }
    private class MemoryLocal : JsonStateStore<LocalState> {
        override val data = MutableStateFlow(LocalState())
        override suspend fun update(transform: (LocalState) -> LocalState) { data.value = transform(data.value) }
    }
    @After fun close() {
        releaseReturnParser.countDown()
        if (::images.isInitialized) images.close()
        if (::client.isInitialized) { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
        assertTrue("No actual transport: $blocked", blocked.isEmpty())
    }
    @Test fun homeChildBackRetainsPageAndScrollWithoutRefetch() = exercise(1f, "normal")
    @Test @Config(qualifiers = "w320dp-h600dp-320dpi") fun compactHome() = exercise(1f, "compact")
    @Test @Config(qualifiers = "w700dp-h393dp-land-320dpi") fun landscapeHome() = exercise(1f, "landscape")
    @Test fun largeFontHome() = exercise(1.6f, "font1.6")
    private fun exercise(scale: Float, name: String) {
        try {
        render(scale)
        compose.waitUntil(5_000) { requests.contains("home") }
        awaitBodyReady()
        compose.onNodeWithTag("content-list").performScrollToNode(hasTestTag("icon-link-grid"))
        compose.onNodeWithTag("icon-link-page-1").performClick()
        compose.onNodeWithTag("icon-link-entry-5").performScrollTo().assertIsDisplayed()
        compose.waitUntil(5_000) { (6..10).all { images.loaded.contains("https://fixture.invalid/icon-$it.png") } }
        capture("$name-before")
        val index = homeState.firstVisibleItemIndex
        val offset = homeState.firstVisibleItemScrollOffset
        val readyHeight = compose.onNodeWithTag("post-preview-block").fetchSemanticsNode().size.height
        assertTrue("Exercise genuine positive scroll", index > 0 || offset > 0)
        compose.onNodeWithTag("icon-link-entry-5").performClick()
        compose.onNodeWithText("已打开入口六").assertIsDisplayed()
        assertEquals(listOf("home", "topicDetail:入口六"), requests)
        holdReturnParser.set(true)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(5_000) { compose.onRoot().fetchSemanticsNode(); returnParserEntered.count == 0L }
        compose.onNodeWithText("正在排版…").assertExists()
        val pendingHeight = compose.onNodeWithTag("post-preview-block").fetchSemanticsNode().size.height
        File("build/verification/icon-grid-async/$name-pending.txt").apply { parentFile!!.mkdirs() }.writeText(
            "readyHeight=$readyHeight pendingHeight=$pendingHeight before=$index:$offset pending=${homeState.firstVisibleItemIndex}:${homeState.firstVisibleItemScrollOffset} requests=$requests\n")
        capture("$name-pending-back")
        assertEquals("First pending measure must retain whole block height", readyHeight, pendingHeight)
        assertEquals("No initial index clamp", index, homeState.firstVisibleItemIndex)
        assertEquals("No initial offset clamp", offset, homeState.firstVisibleItemScrollOffset)
        releaseReturnParser.countDown()
        awaitBodyReady()
        capture("$name-settled-back")
        File("build/verification/icon-grid-async/$name-state.txt").apply { parentFile!!.mkdirs() }.writeText(
            "before=$index:$offset after=${homeState.firstVisibleItemIndex}:${homeState.firstVisibleItemScrollOffset} requests=$requests\n" +
            compose.onRoot(useUnmergedTree = true).printToString())
        compose.onNodeWithTag("icon-link-page-1").assertIsSelected()
        compose.onNodeWithTag("icon-link-entry-5").assertIsDisplayed()
        assertEquals(index, homeState.firstVisibleItemIndex)
        assertEquals(offset, homeState.firstVisibleItemScrollOffset)
        assertEquals(listOf("home", "topicDetail:入口六"), requests)
        capture("$name-back")
        } finally {
            releaseReturnParser.countDown()
            // Release before synchronizing Main, including on failed assertions or gate-entry timeouts.
            compose.waitForIdle()
            if (returnParserEntered.count == 0L) compose.waitUntil(5_000) { compose.onRoot().fetchSemanticsNode(); returnParserFinished.count == 0L }
            compose.waitForIdle()
        }
    }
    private fun awaitBodyReady() {
        // Observe the actual asynchronously parsed Text, not a clock delay or a corrective scroll.
        compose.waitUntil(5_000) { compose.onAllNodesWithText(longBody).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
    private fun render(scale: Float) {
        images = IconFixtureImages(compose.activity)
        val container = AppContainer(compose.activity.application).also { it.nativeUpdates.enabled = false }
        AppContainer::class.java.getDeclaredField("local").apply { isAccessible = true }.set(container, LocalRepository(MemoryLocal()))
        store = AccountStore(object : CredentialPersistence {
            override fun read(): ByteArray? = null
            override fun write(bytes: ByteArray) {}
            override fun reset() {}
        })
        client = OkHttpClient.Builder().addInterceptor { blocked += it.request().url.toString(); throw IOException("Offline fixture") }.build()
        val login = HttpLoginTransport(client)
        val social = SocialRepository(store) { snapshot -> CoolapkHttpTransport(client = client, requestSession = { store.requestSessionFor(snapshot) }) }
        val ctor = AccountRuntime::class.java.getDeclaredConstructor(AccountStore::class.java, HttpLoginTransport::class.java,
            LoginCoordinator::class.java, HttpCoolapkRepository::class.java, SocialRepository::class.java).apply { isAccessible = true }
        val accounts = ctor.newInstance(store, login, LoginCoordinator(store, login, "synthetic"), HttpCoolapkRepository(client = client), social)
        val repo = Proxy.newProxyInstance(CoolapkRepository::class.java.classLoader, arrayOf(CoolapkRepository::class.java)) { _, method, args ->
            when (method.name) {
                "home" -> { requests += "home"; ApiResult.Success(Page(listOf(ContentItem("intro", "text", title = "离线入口测试", message = longBody), iconFixture(10)), null)) }
                "topicDetail" -> { requests += "topicDetail:${args!![0]}"; ApiResult.Success(ContentItem("destination", "text", title = "已打开${args[0]}")) }
                "toString" -> "OfflineIconRepository"
                else -> throw AssertionError("Unexpected ${method.name}")
            }
        } as CoolapkRepository
        compose.setContent {
            val accountState by store.state.collectAsState()
            val model = remember(accountState.generation) { BrowseViewModel(repo) }
            CompositionLocalProvider(LocalMediaImageLoader provides images.loader, LocalPreviewPostParser provides parser,
                LocalDensity provides Density(LocalDensity.current.density, scale),
                LocalReadingStyles provides readingStyles(ReadingPreset.COMFORTABLE, FontChoice.NOTO_SANS)) {
                MaterialTheme(colorScheme = flutterReferenceColorScheme(), typography = appTypography(FontChoice.NOTO_SANS)) {
                    key(accountState.generation) { NativeApp(model, container, AppSettings(floatingNavigation = true, dynamicColor = false), accounts, accountState,
                        browseListStates = mapOf("home" to homeState)) }
                }
            }
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.runOnUiThread {
            val view = compose.activity.findViewById<View>(android.R.id.content)
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        File("build/verification/icon-grid-async/root-$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
