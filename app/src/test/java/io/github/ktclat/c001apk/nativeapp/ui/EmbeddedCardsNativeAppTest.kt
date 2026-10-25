// SPDX-License-Identifier: AGPL-3.0-only
// Actual NativeApp and production decoder/rendering. Matched offline Flutter fixtures; no sockets.
package io.github.ktclat.c001apk.nativeapp.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import io.github.ktclat.c001apk.nativeapp.AppContainer
import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.data.*
import io.github.ktclat.c001apk.nativeapp.feature.media.LocalMediaImageLoader
import io.github.ktclat.c001apk.nativeapp.settings.*
import io.github.ktclat.c001apk.nativeapp.social.SocialRepository
import okhttp3.*
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EmbeddedCardsNativeAppTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var images: IconFixtureImages
    private lateinit var client: OkHttpClient
    private val forbiddenDns = CopyOnWriteArrayList<String>()
    private val requests = CopyOnWriteArrayList<String>()
    private val destinations = CopyOnWriteArrayList<String>()
    private var oldAnimationScale: Float? = null
    private var oldAccessibility: Pair<Boolean, Boolean>? = null
    @After fun close() {
        oldAnimationScale?.let { android.provider.Settings.Global.putFloat(compose.activity.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, it) }
        oldAccessibility?.let { previous ->
            val manager = compose.activity.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
            org.robolectric.Shadows.shadowOf(manager).apply { setEnabled(previous.first); setTouchExplorationEnabled(previous.second) }
        }
        if (::images.isInitialized) images.close()
        assertTrue("No external DNS attempts: $forbiddenDns", forbiddenDns.isEmpty())
        if (::client.isInitialized) { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
    }
    @Test fun listLight() = show("list-light", "supported-list-card", false)
    @Test fun listDark() = show("list-dark", "supported-list-card", true)
    @Test @Config(qualifiers = "w320dp-h600dp-320dpi") fun listNarrowLargeFont() = show("list-narrow-font2", "supported-list-card", false, 2f)
    @Test fun entityLight() = show("entity-light", "supported-entity-icons", false)
    @Test fun entityDark() = show("entity-dark", "supported-entity-icons", true)
    @Test @Config(qualifiers = "w320dp-h600dp-320dpi") fun entityNarrowLargeFont() = show("entity-narrow-font2", "supported-entity-icons", false, 2f)
    @Test @Config(qualifiers = "w320dp-h600dp-320dpi") fun entityFooterWithReducedMotionAndTouchExploration() = show("entity-footer-accessible", "supported-entity-icons", false, 2f, accessible = true)
    @Test fun publicCollectionEmpty() = show("collection-empty", "supported-entity-icons", false,
        collectionResponse = """{"data":[]}""", collectionExpected = "暂无内容")
    @Test fun publicCollectionPrivateRejection() = show("collection-private", "supported-entity-icons", false,
        collectionResponse = """{"status":-1,"messageStatus":"err_request_login","message":"private collection"}""", collectionExpected = "此内容需要登录，请登录后重试")
    @Test fun publicCollectionBusinessError() = show("collection-error", "supported-entity-icons", false,
        collectionResponse = """{"status":-2,"message":"unavailable"}""", collectionExpected = "服务器未能完成请求，请稍后重试")
    private fun show(name: String, fixture: String, dark: Boolean, scale: Float = 1f,
        collectionResponse: String = """{"data":[{"id":"9105","entityType":"feed","username":"合成作者","message":"合成收藏条目"}]}""",
        collectionExpected: String = "合成收藏条目", accessible: Boolean = false) {
        if (accessible) compose.runOnUiThread {
            oldAnimationScale = android.provider.Settings.Global.getFloat(compose.activity.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            android.provider.Settings.Global.putFloat(compose.activity.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
            val manager = compose.activity.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
            oldAccessibility = manager.isEnabled to manager.isTouchExplorationEnabled
            org.robolectric.Shadows.shadowOf(manager).apply { setEnabled(true); setTouchExplorationEnabled(true) }
        }
        images = IconFixtureImages(compose.activity)
        val store = AccountStore(object : CredentialPersistence {
            override fun read(): ByteArray? = null
            override fun write(bytes: ByteArray) = Unit
            override fun reset() = Unit
        }, guestDeviceId = "audit-synthetic-device")
        val source = requireNotNull(javaClass.getResource("/embedded-cards/$fixture.json")).readText()
        client = OkHttpClient.Builder().dns(object : Dns {
            override fun lookup(host: String): List<InetAddress> =
                if (host in setOf("localhost", "127.0.0.1", "::1")) listOf(InetAddress.getLoopbackAddress())
                else { forbiddenDns += host; throw UnknownHostException("Audit forbids external DNS") }
        }).addInterceptor { chain ->
            val request = chain.request()
            check(request.method == "GET") { "Audit forbids writes" }
            check(request.url.host in setOf("api.coolapk.com", "api2.coolapk.com", "www.coolapk.com"))
            val path = request.url.encodedPath
            requests += "GET ${request.url.host}$path"
            val body = when (path) {
                "/v6/main/indexV8" -> {
                    val page = request.url.queryParameter("page")
                    assertTrue("Only initial and one explicit next page are expected", page in setOf("1", "2"))
                    if (page == "2") {
                        assertEquals(if (fixture == "supported-list-card") "audit-icons" else "1001", request.url.queryParameter("firstItem"))
                        assertEquals(if (fixture == "supported-list-card") "3001" else "2001", request.url.queryParameter("lastItem"))
                    }
                    source
                }
                "/v6/user/feedList" -> {
                    assertEquals("1002", request.url.queryParameter("uid"))
                    destinations += "user:1002"
                    """{"data":[{"id":"9301","entityType":"feed","username":"合成作者","message":"已打开目标内容"}]}"""
                }
                "/v6/apk/detail" -> {
                    assertEquals("com.example.maps", request.url.queryParameter("id"))
                    assertEquals("1", request.url.queryParameter("installed"))
                    destinations += "app:com.example.maps"
                    """{"data":{"id":"3001","entityType":"apk","title":"已打开目标内容"}}"""
                }
                "/v6/topic/newTagDetail" -> {
                    assertEquals("数码精选", request.url.queryParameter("tag"))
                    destinations += "topic:数码精选"
                    """{"data":{"id":"4001","entityType":"topic","title":"已打开目标内容","url":"/t/数码精选"}}"""
                }
                "/v6/collection/itemList" -> {
                    assertEquals("2001", request.url.queryParameter("id"))
                    assertNull(request.url.queryParameter("uid"))
                    destinations += "collection:2001"
                    collectionResponse
                }
                else -> throw AssertionError("Unexpected fixture endpoint: $path")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
            // NEVER chain.proceed: no sockets, external API, or production account.
        }.build()
        val repository = HttpCoolapkRepository(client = client, requestSession = { store.captureRequestSession() })
        val login = HttpLoginTransport(client)
        val social = SocialRepository(store) { snapshot -> CoolapkHttpTransport(client = client, requestSession = { store.requestSessionFor(snapshot) }) }
        val constructor = AccountRuntime::class.java.getDeclaredConstructor(AccountStore::class.java, HttpLoginTransport::class.java,
            LoginCoordinator::class.java, HttpCoolapkRepository::class.java, SocialRepository::class.java).apply { isAccessible = true }
        val accounts = constructor.newInstance(store, login, LoginCoordinator(store, login, "audit-synthetic-device"), repository, social)
        val container = AppContainer(compose.activity.application).also { it.nativeUpdates.enabled = false }
        val model = BrowseViewModel(repository)
        fun awaitDestination(key: String, text: String) {
            try {
                compose.waitUntil(10_000) {
                    // Query rendered semantics first so Android/Compose pending work is drained,
                    // rather than polling StateFlow while Robolectric's main looper is idle.
                    val rendered = compose.onAllNodesWithText(text).fetchSemanticsNodes().any { it.boundsInRoot.width > 0 && it.boundsInRoot.height > 0 }
                    rendered && model.states.value[key]?.let { it.loaded && !it.loading } == true
                }
                compose.onNodeWithText(text).assertIsDisplayed()
            } catch (failure: Throwable) {
                runCatching { capture("$name-navigation-failure", verifyIcons = false) }
                File("build/verification/embedded-cards/$name-navigation-failure.txt").apply { parentFile!!.mkdirs() }.writeText(
                    "Expected key=$key; states=${model.states.value.mapValues { (_, state) -> Triple(state.loaded, state.loading, state.error) }}; paths=$requests; targets=$destinations\n" +
                        compose.onRoot(useUnmergedTree = true).printToString())
                throw failure
            }
        }
        compose.setContent {
            val state by store.state.collectAsState()
            CompositionLocalProvider(LocalMediaImageLoader provides images.loader,
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(androidx.compose.ui.platform.LocalDensity.current.density, scale),
                LocalReadingStyles provides readingStyles(ReadingPreset.COMFORTABLE, FontChoice.NOTO_SANS)) {
                MaterialTheme(colorScheme = fixedColorScheme(0, dark), typography = appTypography(FontChoice.NOTO_SANS)) {
                    NativeApp(model, container, AppSettings(floatingNavigation = true, dynamicColor = false), accounts, state, initialRoute = null)
                }
            }
        }
        val key = "home"
        try {
            compose.waitUntil(15_000) {
                // Drain the real Compose/main frame before observing async model completion.
                // Readiness does not depend on the content whose rendering is asserted below.
                compose.onRoot(useUnmergedTree = true).fetchSemanticsNode()
                model.states.value[key]?.let { it.loaded && !it.loading } == true
            }
            compose.waitForIdle()
        } catch (failure: Throwable) {
            val beforeCapture = "states=${model.states.value.mapValues { (_, state) -> Triple(state.loaded, state.loading, state.error) }}; paths=$requests"
            runCatching { capture("$name-initial-failure", verifyIcons = false) }
            File("build/verification/embedded-cards/$name-initial-failure.txt").apply { parentFile!!.mkdirs() }.writeText(
                "Expected key=$key; beforeCapture=$beforeCapture; afterCaptureStates=${model.states.value.mapValues { (_, state) -> Triple(state.loaded, state.loading, state.error) }}; paths=$requests\n" +
                    compose.onRoot(useUnmergedTree = true).printToString())
            throw failure
        }
        val expected = if (fixture == "supported-list-card") listOf(1, 2, 3, 7) else listOf(4, 5, 6)
        compose.waitUntil(5_000) { expected.all { "https://fixture.invalid/icon-$it.png" in images.loaded } }
        compose.onNodeWithText("此卡片的模板或嵌套内容暂不支持").assertDoesNotExist()
        if (fixture == "supported-list-card") {
            compose.onNodeWithText("离线地图").assertIsDisplayed()
            compose.onNodeWithText("128讨论").assertIsDisplayed()
            compose.onNodeWithText("1.2万下载").assertIsDisplayed()
        } else {
            compose.onNodeWithText("旅行摄影师").assertIsDisplayed()
            compose.onNodeWithText("开源观察员").assertIsDisplayed()
            compose.onNodeWithText("周末摄影精选").assertIsDisplayed()
            compose.onNodeWithText("128人关注").assertIsDisplayed()
            compose.onNodeWithText("24个内容").assertIsDisplayed()
        }
        capture(name)
        File("build/verification/embedded-cards/$name.txt").writeText(
            "Synthetic matched input=$fixture; dark=$dark; scale=$scale\nLoaded image identifiers=${images.loaded.sorted()}\n" + compose.onRoot(useUnmergedTree = true).printToString())
        exposeAndCheckFooter(name, accessible, model)
        val tag = if (fixture == "supported-list-card") "entity-card:apk:3001" else "entity-card:contacts:1002"
        compose.onNodeWithTag(tag).performScrollTo().performTouchInput { click(center) }
        val destinationKey = if (fixture == "supported-list-card") "app:com.example.maps" else "user:1002"
        awaitDestination(destinationKey, "已打开目标内容")
        assertEquals(listOf(if (fixture == "supported-list-card") "app:com.example.maps" else "user:1002"), destinations)
        val requestCount = requests.size
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag(tag).assertIsDisplayed()
        assertEquals("Back must not refetch the containing list", requestCount, requests.size)
        if (fixture == "supported-list-card") {
            compose.onNodeWithTag("icon-link-entry-0").performScrollTo().performTouchInput { click(center) }
            awaitDestination("topic:数码精选", "已打开目标内容")
            assertEquals(listOf("app:com.example.maps", "topic:数码精选"), destinations)
        } else {
            compose.onNodeWithTag("entity-card:collection:2001").performScrollTo().performTouchInput { click(center) }
            awaitDestination("collection:2001", collectionExpected)
            compose.onNodeWithText("登录后查看收藏内容").assertDoesNotExist()
            assertEquals(listOf("user:1002", "collection:2001"), destinations)
            capture("$name-public-collection", verifyIcons = false)
        }
    }
    private fun exposeAndCheckFooter(name: String, accessible: Boolean, model: BrowseViewModel) {
        val list = compose.onNodeWithTag("content-list")
        list.performScrollToNode(hasTestTag("content-more"))
        fun revealDock() {
            val config = list.fetchSemanticsNode().config
            if (config.contains(SemanticsActions.CustomActions)) {
                config[SemanticsActions.CustomActions].firstOrNull { it.label == SHOW_FLOATING_DOCK_ACTION }?.action?.let { reveal ->
                    compose.runOnIdle { assertTrue(reveal()) }
                }
            }
            compose.waitForIdle()
        }
        fun fullyExposed(): Boolean {
            val footer = compose.onNodeWithTag("content-more").fetchSemanticsNode().boundsInRoot
            val viewport = list.fetchSemanticsNode().boundsInRoot
            val dock = compose.onNodeWithTag("floating-navigation").fetchSemanticsNode().boundsInRoot
            return footer.top >= viewport.top && footer.bottom <= viewport.bottom && footer.left >= viewport.left &&
                footer.right <= viewport.right && footer.bottom <= dock.top
        }
        revealDock()
        repeat(6) {
            if (!fullyExposed()) {
                list.performTouchInput { swipe(androidx.compose.ui.geometry.Offset(centerX, height * .65f), androidx.compose.ui.geometry.Offset(centerX, height * .35f), durationMillis = 600) }
                revealDock()
            }
        }
        assertTrue("Footer must be fully above the visible dock, not merely intersect the viewport", fullyExposed())
        val footer = compose.onNodeWithTag("content-more").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("floating-navigation").assertIsDisplayed()
        if (accessible) {
            val manager = compose.activity.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
            assertTrue(manager.isEnabled); assertTrue(manager.isTouchExplorationEnabled)
            assertEquals(0f, android.provider.Settings.Global.getFloat(compose.activity.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f), 0f)
        }
        capture("$name-footer-reachable", verifyIcons = false)
        val before = requests.size
        footer.performTouchInput { click(center) }
        compose.waitUntil(10_000) {
            val absent = compose.onAllNodesWithTag("content-more").fetchSemanticsNodes().isEmpty()
            val home = model.states.value["home"]
            absent && home?.loaded == true && !home.loading && home.next == null && requests.size == before + 1
        }
        assertEquals("GET api2.coolapk.com/v6/main/indexV8", requests.last())
    }
    private fun capture(name: String, verifyIcons: Boolean = true) {
        compose.waitForIdle()
        val bitmap = compose.runOnUiThread {
            val view = compose.activity.findViewById<View>(android.R.id.content)
            check(view.width > 0 && view.height > 0)
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val expected = if (!verifyIcons) emptyList() else if (name.startsWith("list")) listOf("entity-icon:apk:3001" to 7) else
            listOf("entity-icon:user:1001" to 4, "entity-icon:contacts:1002" to 5, "entity-icon:collection:2001" to 6)
        expected.forEach { (tag, number) ->
            val rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val original = requireNotNull(requireNotNull(javaClass.getResourceAsStream("/icon-grid/icon-$number.png")).use { android.graphics.BitmapFactory.decodeStream(it) })
            val colors = mutableMapOf<Int, Int>()
            for (y in 0 until original.height) for (x in 0 until original.width) {
                val pixel = original.getPixel(x, y)
                if (android.graphics.Color.alpha(pixel) > 200) colors[pixel] = (colors[pixel] ?: 0) + 1
            }
            val color = colors.maxBy { it.value }.key
            original.recycle()
            var matched = 0
            val left = rect.left.toInt().coerceIn(0, bitmap.width)
            val right = rect.right.toInt().coerceIn(0, bitmap.width)
            val top = rect.top.toInt().coerceIn(0, bitmap.height)
            val bottom = rect.bottom.toInt().coerceIn(0, bitmap.height)
            for (y in top until bottom) for (x in left until right) {
                val pixel = bitmap.getPixel(x, y)
                if (kotlin.math.abs(android.graphics.Color.red(pixel) - android.graphics.Color.red(color)) < 6 &&
                    kotlin.math.abs(android.graphics.Color.green(pixel) - android.graphics.Color.green(color)) < 6 &&
                    kotlin.math.abs(android.graphics.Color.blue(pixel) - android.graphics.Color.blue(color)) < 6) matched++
            }
            assertTrue("Decoded source-colored icon ink required for $tag, got $matched pixels", matched > (right - left) * (bottom - top) / 10)
        }
        File("build/verification/embedded-cards/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }
}
