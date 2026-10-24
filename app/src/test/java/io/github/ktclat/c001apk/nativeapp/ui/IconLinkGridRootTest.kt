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

/** Actual NativeApp navigation, state restoration and production image rendering. No live service. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconLinkGridRootTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var images: IconFixtureImages
    private lateinit var client: OkHttpClient
    private lateinit var store: AccountStore
    private var unknownTemplate = false
    private val requests = mutableListOf<String>()
    private val blocked = mutableListOf<String>()
    private val homeState = LazyListState()
    private class MemoryLocal : JsonStateStore<LocalState> {
        override val data = MutableStateFlow(LocalState())
        override suspend fun update(transform: (LocalState) -> LocalState) { data.value = transform(data.value) }
    }
    @After fun close() {
        if (::images.isInitialized) images.close()
        if (::client.isInitialized) { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
        assertTrue("No actual transport: $blocked", blocked.isEmpty())
    }
    @Test fun homeChildBackRetainsPageAndScrollWithoutRefetch() = exercise(1f, "normal")
    @Test @Config(qualifiers = "w320dp-h600dp-320dpi") fun compactHome() = exercise(1f, "compact")
    @Test @Config(qualifiers = "w700dp-h393dp-land-320dpi") fun landscapeHome() = exercise(1f, "landscape")
    @Test fun largeFontHome() = exercise(1.6f, "font1.6")
    @Test fun unknownTemplateHasExplicitFallbackEvenWithoutChildren() {
        unknownTemplate = true
        render(1f)
        compose.onNodeWithTag("content-list").performScrollToNode(hasText("此卡片的模板或嵌套内容暂不支持"))
        compose.onNodeWithText("此卡片的模板或嵌套内容暂不支持").assertIsDisplayed()
        compose.onNodeWithTag("icon-link-grid").assertDoesNotExist()
    }
    @Test fun accountGenerationRecreatesRootAndDoesNotRestoreOldGridPage() {
        render(1f)
        compose.waitUntil(5_000) { requests.contains("home") }
        compose.onNodeWithTag("content-list").performScrollToNode(hasTestTag("icon-link-grid"))
        compose.onNodeWithTag("icon-link-page-1").performClick().assertIsSelected()
        compose.runOnIdle {
            assertTrue(store.saveAuthenticated("1", "synthetic", "synthetic-device", listOf(Cookie.Builder().name("token")
                .value("synthetic-not-real").domain("coolapk.com").path("/").secure()
                .expiresAt(System.currentTimeMillis() + 3600000).build()), store.snapshot()))
        }
        compose.waitUntil(5_000) { requests.count { it == "home" } == 2 }
        compose.onNodeWithTag("content-list").performScrollToNode(hasTestTag("icon-link-grid"))
        compose.onNodeWithTag("icon-link-page-0").assertIsSelected()
        compose.onNodeWithText("新账号入口一").assertIsDisplayed()
        compose.onNodeWithText("入口一").assertDoesNotExist()
    }
    private fun exercise(scale: Float, name: String) {
        render(scale)
        compose.waitUntil(5_000) { requests.contains("home") }
        compose.onNodeWithTag("content-list").performScrollToNode(hasTestTag("icon-link-grid"))
        compose.onNodeWithTag("icon-link-page-1").performClick()
        compose.onNodeWithTag("icon-link-entry-5").performScrollTo().assertIsDisplayed()
        compose.waitUntil(5_000) { (6..10).all { images.loaded.contains("https://fixture.invalid/icon-$it.png") } }
        capture("$name-before")
        val index = homeState.firstVisibleItemIndex
        val offset = homeState.firstVisibleItemScrollOffset
        assertTrue("Exercise genuine positive scroll", index > 0 || offset > 0)
        compose.onNodeWithTag("icon-link-entry-5").performClick()
        compose.onNodeWithText("已打开入口六").assertIsDisplayed()
        assertEquals(listOf("home", "topicDetail:入口六"), requests)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        capture("$name-immediate-back")
        compose.onNodeWithTag("icon-link-page-1").assertIsSelected()
        compose.onNodeWithTag("icon-link-entry-5").assertIsDisplayed()
        assertEquals(index, homeState.firstVisibleItemIndex)
        assertEquals(offset, homeState.firstVisibleItemScrollOffset)
        assertEquals(listOf("home", "topicDetail:入口六"), requests)
        capture("$name-back")
        // Only after strict restoration checks: expose targets and use real pointer hit-testing.
        touchReachable("icon-link-page-0")
        compose.onNodeWithTag("icon-link-page-0").assertIsSelected()
        touchReachable("icon-link-page-1")
        compose.onNodeWithTag("icon-link-page-1").assertIsSelected()
        exposeTarget("icon-link-entry-9")
        capture("$name-touch-reachable")
        compose.onNodeWithTag("icon-link-entry-9").performTouchInput { click(center) }
        compose.onNodeWithText("已打开入口十").assertIsDisplayed()
        assertEquals(listOf("home", "topicDetail:入口六", "topicDetail:入口十"), requests)
    }
    private fun touchReachable(tag: String) {
        exposeTarget(tag)
        compose.onNodeWithTag(tag).performTouchInput { click(center) }
        compose.waitForIdle()
    }
    private fun exposeTarget(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo()
        fun exposed(): Boolean {
            val nodes = compose.onAllNodesWithTag(tag).fetchSemanticsNodes()
            if (nodes.size != 1) return false
            val target = nodes.single().boundsInRoot
            val viewport = compose.onNodeWithTag("content-list").fetchSemanticsNode().boundsInRoot
            val docks = compose.onAllNodesWithTag("floating-navigation").fetchSemanticsNodes()
            return target.width > 0 && target.height > 0 && target.top >= viewport.top && target.bottom <= viewport.bottom &&
                target.left >= viewport.left && target.right <= viewport.right && docks.none { it.boundsInRoot.overlaps(target) }
        }
        repeat(6) {
            if (exposed()) return
            compose.onNodeWithTag("content-list").performTouchInput {
                swipe(androidx.compose.ui.geometry.Offset(centerX, height * .6f),
                    androidx.compose.ui.geometry.Offset(centerX, height * .2f), durationMillis = 500)
            }
            compose.waitForIdle()
        }
        assertTrue("Target must be fully reachable outside the floating dock: $tag", exposed())
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
                "home" -> { requests += "home"; ApiResult.Success(Page((0 until 12).map { ContentItem("intro-$it", "text", title = "离线入口测试 ${it + 1}") } + listOf((if (unknownTemplate) ContentItem("unknown", "card", entityTemplate = "futureTemplate") else iconFixture(10).let { grid ->
                    if (store.state.value.activeId == null) grid else grid.copy(children = grid.children.map { it.copy(title = "新账号${it.title}") })
                })), null)) }
                "topicDetail" -> { requests += "topicDetail:${args!![0]}"; ApiResult.Success(ContentItem("destination", "text", title = "已打开${args[0]}")) }
                "toString" -> "OfflineIconRepository"
                else -> throw AssertionError("Unexpected ${method.name}")
            }
        } as CoolapkRepository
        compose.setContent {
            val accountState by store.state.collectAsState()
            val model = remember(accountState.generation) { BrowseViewModel(repo) }
            CompositionLocalProvider(LocalMediaImageLoader provides images.loader,
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
        File("build/verification/icon-grid/root-$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
