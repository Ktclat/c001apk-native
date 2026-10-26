// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import io.github.ktclat.c001apk.nativeapp.AppContainer
import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.data.*
import io.github.ktclat.c001apk.nativeapp.diagnostics.NativeBuildInfo
import io.github.ktclat.c001apk.nativeapp.domain.*
import io.github.ktclat.c001apk.nativeapp.local.*
import io.github.ktclat.c001apk.nativeapp.settings.*
import io.github.ktclat.c001apk.nativeapp.social.SocialRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Cookie
import androidx.compose.ui.unit.Density
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDisplay
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

/** Actual NativeApp routes with native Canvas and offline transports. Not a device/real-network test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w369dp-h822dp-520dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeSettingsParityRootTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val directory = File("build/verification/settings-parity")
    private var oldFontScale: Float? = null
    private var scale = 1f
    private var width = 1200
    private var height = 2670
    private var insets = Triple(0, 0, 0)
    private val browseCalls = AtomicInteger()
    private val accountTransportCalls = AtomicInteger()
    private lateinit var client: OkHttpClient
    private lateinit var model: BrowseViewModel
    private lateinit var restorer: StateRestorationTester
    private lateinit var accountStore: AccountStore
    private lateinit var container: AppContainer
    private val followRequests = java.util.concurrent.CopyOnWriteArrayList<FollowType>()
    private val rawInsets = mutableListOf<JsonObject>()

    private class MemoryLocalStore : JsonStateStore<LocalState> {
        override val data = MutableStateFlow(LocalState())
        override suspend fun update(transform: (LocalState) -> LocalState) {
            data.value = LocalRepository.validateState(transform(data.value))
        }
    }

    @After fun cleanup() {
        compose.runOnUiThread {
            ViewCompat.setOnApplyWindowInsetsListener(compose.activity.window.decorView, null)
            compose.activity.viewModelStore.clear()
            oldFontScale?.let(RuntimeEnvironment::setFontScale)
        }
        if (::client.isInitialized) {
            client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow()
        }
        assertEquals("The fixture accounts client must not attempt any login, social or repository request", 0, accountTransportCalls.get())
    }

    private fun render(fontScale: Float = 1f, landscape: Boolean = false, dark: Boolean = false, signedIn: Boolean = false, initialRoute: String = "settings") {
        scale = fontScale
        width = if (landscape) 2670 else 1200; height = if (landscape) 1200 else 2670
        compose.runOnUiThread {
            oldFontScale = RuntimeEnvironment.getFontScale()
            RuntimeEnvironment.setFontScale(scale)
            shadowOf(ShadowDisplay.getDefaultDisplay()).apply {
                setWidth(width); setHeight(height); setRealWidth(width); setRealHeight(height)
            }
            WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
            compose.activity.window.setLayout(width, height)
        }
        val store = AccountStore(object : CredentialPersistence {
            override fun read(): ByteArray? = null
            override fun write(bytes: ByteArray) = Unit
            override fun reset() = Unit
        })
        accountStore = store
        if (signedIn) {
            for (id in listOf("98", "99")) store.saveAuthenticated(id, "合成账号 $id", "synthetic-device", listOf(Cookie.Builder()
                .name("token").value("synthetic-not-a-real-token").domain("coolapk.com").path("/").secure()
                .expiresAt(System.currentTimeMillis() + 3_600_000).build()), store.snapshot())
        }
        client = OkHttpClient.Builder().addInterceptor {
            accountTransportCalls.incrementAndGet(); throw IOException("Synthetic fail-closed: no networking")
        }.build()
        val login = HttpLoginTransport(client)
        val constructor = AccountRuntime::class.java.getDeclaredConstructor(AccountStore::class.java,
            HttpLoginTransport::class.java, LoginCoordinator::class.java, HttpCoolapkRepository::class.java,
            SocialRepository::class.java).apply { isAccessible = true }
        val accounts = constructor.newInstance(store, login, LoginCoordinator(store, login, "synthetic"),
            HttpCoolapkRepository(client), SocialRepository(store) { CoolapkHttpTransport(client) })
        val repository = Proxy.newProxyInstance(CoolapkRepository::class.java.classLoader,
            arrayOf(CoolapkRepository::class.java)) { _, method, args ->
            if (method.name == "following") { followRequests += args!![0] as FollowType; ApiResult.Success(Page(emptyList<ContentItem>(), null)) }
            else if (method.name == "userProfile") ApiResult.Success(ContentItem("42", "user", userId = "42", userProfile = io.github.ktclat.c001apk.nativeapp.domain.UserSpaceProfile()))
            else if (method.name == "home" || method.name == "userFeed") { browseCalls.incrementAndGet(); ApiResult.Success(Page(emptyList<ContentItem>(), null)) }
            else throw AssertionError("Unexpected synthetic browse call ${method.name}")
        } as CoolapkRepository
        model = BrowseViewModel(repository)
        container = AppContainer(compose.activity.application).also { it.nativeUpdates.enabled = false }
        // Full Robolectric suites can reuse the underlying local-data singleton. These route
        // fixtures require their own authored empty history/follows, not earlier tests' rows.
        // Replace only the local store; real settings persistence and the actual child UI remain.
        val local = LocalRepository(MemoryLocalStore())
        AppContainer::class.java.getDeclaredField("local").apply { isAccessible = true }.set(container, local)
        check(container.local === local)
        assertEquals(LocalState(), runBlocking { local.state.first() })
        runBlocking { container.settings.update { AppSettings(dynamicColor = false, theme = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) } }
        runBlocking { withTimeout(5_000) { container.local.loadStates().first { it is LocalLoadState.Ready } } }
        restorer = StateRestorationTester(compose)
        restorer.setContent {
            val accountState by store.state.collectAsState()
            val settings by container.settings.settings.collectAsState(initial = AppSettings(dynamicColor = false))
            val density = LocalDensity.current
            val drawing = WindowInsets.safeDrawing.getTop(density)
            val gesture = WindowInsets.systemGestures.getTop(density)
            val bottom = WindowInsets.safeDrawing.getBottom(density)
            SideEffect { insets = Triple(drawing, gesture, bottom) }
            CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * settings.fontScale),
                LocalReadingStyles provides readingStyles(settings.readingPreset, settings.font)) {
                MaterialTheme(colorScheme = fixedColorScheme(settings.themeColor, settings.theme == ThemeMode.DARK), typography = appTypography(settings.font)) {
                    key(accountState.generation) { NativeApp(model, container, settings, accounts, accountState, initialRoute = initialRoute) }
                }
            }
        }
        injectInsets()
        assertEquals(scale, compose.activity.resources.configuration.fontScale, .001f)
        assertEquals(scale, compose.activity.application.resources.configuration.fontScale, .001f)
        assertViewport()
    }

    private fun fixtureInsets() = WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 140, 0, 0))
        .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.NONE)
        .setInsets(WindowInsetsCompat.Type.systemGestures(), Insets.of(0, 179, 0, 0))
        .setInsets(WindowInsetsCompat.Type.mandatorySystemGestures(), Insets.NONE)
        .setInsets(WindowInsetsCompat.Type.tappableElement(), Insets.NONE)
        .setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE)
        .setVisible(WindowInsetsCompat.Type.statusBars(), true)
        .setVisible(WindowInsetsCompat.Type.navigationBars(), false)
        .setVisible(WindowInsetsCompat.Type.ime(), false).build()

    private fun injectInsets() {
        compose.runOnUiThread {
            val decor = compose.activity.window.decorView
            ViewCompat.setOnApplyWindowInsetsListener(decor) { view, raw ->
                ViewCompat.onApplyWindowInsets(view, fixtureInsets()).also { returned ->
                    rawInsets += buildJsonObject {
                        put("rawDrawingTop", raw.getInsets(WindowInsetsCompat.Type.statusBars()).top)
                        put("rawGestureTop", raw.getInsets(WindowInsetsCompat.Type.systemGestures()).top)
                        put("suppliedDrawingTop", 140); put("suppliedGestureTop", 179)
                        put("returnedDrawingTop", returned.getInsets(WindowInsetsCompat.Type.statusBars()).top)
                        put("returnedGestureTop", returned.getInsets(WindowInsetsCompat.Type.systemGestures()).top)
                    }
                }
            }
            ViewCompat.dispatchApplyWindowInsets(decor, fixtureInsets())
        }
        compose.waitForIdle()
        assertEquals(Triple(140, 179, 0), insets)
    }

    private fun assertViewport() {
        val view = compose.activity.findViewById<View>(android.R.id.content)
        assertEquals(width, view.width); assertEquals(height, view.height)
        assertEquals(3.25f, compose.activity.resources.displayMetrics.density, .001f)
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals(width.toFloat(), root.width, .1f); assertEquals(height.toFloat(), root.height, .1f)
    }
    private fun back() { compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle() }
    private fun section(label: String) {
        compose.onNodeWithTag("settings-category-list").performScrollToNode(hasText(label) and hasClickAction())
        compose.onNode(hasText(label) and hasClickAction()).performClick()
    }
    private fun row(label: String): SemanticsNodeInteraction {
        val list = if (compose.onAllNodesWithTag("settings-detail-list").fetchSemanticsNodes().isNotEmpty()) "settings-detail-list" else "content-list"
        compose.onNodeWithTag(list).performScrollToNode(hasText(label))
        return compose.onNodeWithText(label).performScrollTo()
    }
    private fun awaitText(label: String) { compose.waitUntil(5_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() } }

    @Test fun actualSettingsChangesPersistAndReturnFromHistoryKeepsCategoryAndScroll() {
        render()
        section("存储与更新")
        row("显示已安装应用更新入口").assertIsOn().performClick()
        compose.waitUntil(5_000) { !runBlocking { container.settings.settings.first() }.checkAppUpdates }
        row("图片缓存").assertIsDisplayed()
        capture("storage-portrait")
        compose.onNodeWithTag("settings-detail-list").performScrollToIndex(5)
        compose.onNodeWithText("浏览历史").assertIsDisplayed()
        val anchorBefore = compose.onNodeWithText("浏览历史").getUnclippedBoundsInRoot()
        val scrollBefore = compose.onNodeWithTag("settings-detail-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("The child is opened from a genuinely scrolled category", scrollBefore > 0f)
        compose.onNodeWithText("浏览历史").performClick()
        // The real child starts its own local Flow in Loading; wait for its Ready state,
        // without scrolling or changing the saved parent anchor.
        try { awaitText("暂无记录") } catch (failure: Throwable) {
            capture("history-load-timeout"); throw failure
        }
        compose.onNodeWithText("暂无记录").assertExists()
        back()
        compose.onNodeWithTag("settings-detail-list").assertExists()
        compose.onNodeWithText("浏览历史").assertIsDisplayed() // No corrective scroll may hide a reset.
        val anchorAfter = compose.onNodeWithText("浏览历史").getUnclippedBoundsInRoot()
        assertEquals(anchorBefore.top.value, anchorAfter.top.value, .1f)
        assertEquals(scrollBefore, compose.onNodeWithTag("settings-detail-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), .001f)
        compose.onNodeWithText("存储与更新").assertExists()
        row("显示已安装应用更新入口").assertIsOff()
        restorer.emulateSavedInstanceStateRestore(); injectInsets()
        row("显示已安装应用更新入口").assertIsOff()
        back() // Category -> index.
        compose.onNodeWithText("外观与字体").assertExists()
        back() // Index -> root.
        compose.onNodeWithText("应用").performClick()
        compose.onNodeWithTag("installed-app-updates-entry").assertDoesNotExist()
        compose.runOnIdle { runBlocking { container.settings.update { it.copy(checkAppUpdates = true) } } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("installed-app-updates-entry").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("installed-app-updates-entry").performScrollTo().assertIsDisplayed()
        capture("apps-manual-update-entry")
    }

    @Test fun actualFollowMenuUsesSelectedTypeAndSettingSurvivesAccountAndGuestBoundary() {
        render(signedIn = true)
        section("关注与内容")
        row("话题关注").performClick()
        compose.waitUntil(5_000) { runBlocking { container.settings.settings.first() }.followType == 2 }
        capture("content-follow-choice")
        back(); back()
        compose.onNodeWithText("关注").performClick()
        compose.waitUntil(5_000) { followRequests.contains(FollowType.TOPICS) }
        compose.onNodeWithContentDescription("切换关注内容：话题关注").performClick()
        compose.onNodeWithText("应用关注").performClick()
        compose.waitUntil(5_000) { followRequests.contains(FollowType.APPS) }
        capture("follow-apps-consumer")
        compose.runOnIdle { accountStore.switchTo("98") }
        section("关注与内容")
        row("应用关注").assertIsSelected()
        compose.runOnIdle { accountStore.useGuest() }
        section("关注与内容")
        compose.onNodeWithText("应用关注").assertDoesNotExist()
        compose.runOnIdle { accountStore.switchTo("99") }
        section("关注与内容")
        row("应用关注").assertIsSelected()
        assertEquals(4, runBlocking { container.settings.settings.first() }.followType)
        assertEquals(0, accountTransportCalls.get())
    }

    @Test fun actualUserRouteAddsLocalFollowAndRemovalCancelKeepsItWithoutAccountTransport() {
        render(initialRoute = "user:42")
        awaitText("本地关注")
        row("本地关注").performClick()
        awaitText("取消本地关注")
        assertTrue(runBlocking { container.local.state.first() }.follows.any { it.uid == "42" })
        capture("guest-profile-local-follow")
        row("取消本地关注").performClick()
        compose.onNodeWithText("取消").performClick()
        row("取消本地关注").assertExists()
        row("取消本地关注").performClick()
        compose.onNodeWithText("移除").performClick()
        awaitText("本地关注")
        assertFalse(runBlocking { container.local.state.first() }.follows.any { it.uid == "42" })
        assertEquals(0, accountTransportCalls.get())
    }

    @Test fun darkTwoTimesSystemFontStorageAndReadingPreviewRemainReachable() {
        render(fontScale = 2f, dark = true, signedIn = true)
        section("存储与更新")
        row("显示已安装应用更新入口").assertHeightIsAtLeast(48.dp)
        capture("storage-dark-large-font-top")
        row("清理图片缓存").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        capture("storage-dark-large-font-tail")
        back(); section("外观与字体")
        compose.onNodeWithTag("settings-detail-list").performScrollToNode(hasTestTag("settings-reading-preview"))
        compose.onNodeWithTag("settings-reading-preview").assertExists()
        capture("appearance-dark-large-font-preview")
        back(); section("关注与内容")
        row("应用关注").assertHeightIsAtLeast(48.dp)
        capture("content-dark-large-font")
    }

    @Test @Config(qualifiers = "w822dp-h369dp-land-520dpi")
    fun shortLandscapeLargeFontSettingsActionsAndBackRemainReachable() {
        render(fontScale = 2f, landscape = true, dark = true, signedIn = true)
        section("存储与更新")
        row("清理图片缓存").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        capture("storage-short-landscape-large-font")
        back(); section("关注与内容")
        row("应用关注").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { runBlocking { container.settings.settings.first() }.followType == 4 }
        capture("content-short-landscape-large-font")
        back()
        compose.onNodeWithText("外观与字体").assertExists()
    }

    @Test fun actualAppearancePickersPersistAndRenderEachChoiceUsingItsOwnStyles() {
        render(); section("外观与字体")
        row("界面字体").performClick()
        captureAppearanceSheet("appearance-font-comparison")
        compose.onNodeWithTag("font-choice-SYSTEM").performScrollTo().performClick()
        awaitText("当前：跟随系统 · 比较字形与选择字体")
        assertEquals(FontChoice.SYSTEM, runBlocking { container.settings.settings.first() }.font)
        row("界面字体").performClick()
        compose.onNodeWithTag("font-choice-SYSTEM").assertIsSelected()
        compose.onNodeWithContentDescription("关闭界面字体").performClick()
        row("正文排版").performClick()
        captureAppearanceSheet("appearance-reading-comparison")
        compose.onNodeWithTag("reading-choice-ORIGINAL").performScrollTo().performClick()
        awaitText("当前：原版排版 · 对比正文、标题与评论")
        assertEquals(ReadingPreset.ORIGINAL, runBlocking { container.settings.settings.first() }.readingPreset)
        row("应用字号").performClick()
        compose.onNodeWithContentDescription("应用字号").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(1.6f) }
        captureAppearanceSheet("appearance-size-draft-160")
        compose.onNodeWithText("取消").performScrollTo().performClick()
        assertEquals(1f, runBlocking { container.settings.settings.first() }.fontScale)
        row("应用字号").performClick()
        compose.onNodeWithContentDescription("应用字号").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(1.15f) }
        compose.onNodeWithText("应用").performScrollTo().performClick()
        awaitText("当前：115% · 预览后应用")
        assertEquals(1.15f, runBlocking { container.settings.settings.first() }.fontScale)
        compose.onNodeWithTag("settings-detail-list").performScrollToNode(hasTestTag("settings-preview-body"))
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag("settings-preview-body").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals(appFontFamily(FontChoice.SYSTEM), results.single().layoutInput.style.fontFamily)
        assertEquals(readingStyles(ReadingPreset.ORIGINAL, FontChoice.SYSTEM).body.fontSize, results.single().layoutInput.style.fontSize)
        assertEquals(1.15f, results.single().layoutInput.density.fontScale, .001f)
        restorer.emulateSavedInstanceStateRestore(); injectInsets()
        row("应用字号").performClick()
        compose.onNodeWithText("预览字号：115%").assertExists()
        compose.onNodeWithContentDescription("关闭应用字号").performClick()
        assertEquals(0, browseCalls.get()); assertEquals(0, accountTransportCalls.get())
    }

    @Test @Config(qualifiers = "w822dp-h369dp-land-520dpi")
    fun actualShortDarkLargeFontPickersScrollAndDialogBackKeepsAppearanceCategory() {
        render(fontScale = 2f, landscape = true, dark = true); section("外观与字体")
        row("界面字体").performClick()
        compose.onNodeWithTag("font-choice-NOTO_SANS").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭界面字体").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        captureAppearanceSheet("appearance-font-dark-short-font200")
        compose.runOnUiThread { org.robolectric.shadows.ShadowDialog.getLatestDialog().onBackPressed() }
        compose.onNodeWithTag("appearance-detail").assertDoesNotExist()
        compose.onNodeWithTag("settings-detail-list").assertExists()
        row("正文排版").performClick()
        compose.onNodeWithTag("reading-choice-ORIGINAL").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭正文排版").assertIsDisplayed()
        captureAppearanceSheet("appearance-reading-dark-short-font200")
        compose.onNodeWithContentDescription("关闭正文排版").performClick()
        row("应用字号").performClick()
        compose.onNodeWithContentDescription("应用字号").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(1.6f) }
        compose.onNodeWithText("应用").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        captureAppearanceSheet("appearance-size-dark-short-font200-tail")
        compose.onNodeWithText("取消").performClick()
        assertEquals(1f, runBlocking { container.settings.settings.first() }.fontScale)
        compose.onNodeWithTag("settings-detail-list").assertExists()
        assertEquals(0, browseCalls.get()); assertEquals(0, accountTransportCalls.get())
    }

    private fun captureAppearanceSheet(name: String) {
        compose.waitForIdle(); directory.mkdirs()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        check(dialog.isShowing)
        val bitmap = compose.runOnUiThread {
            val decor = requireNotNull(dialog.window).decorView
            check(decor.width > 0 && decor.height > 0)
            Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also { decor.draw(Canvas(it)) }
        }
        try { File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        File(directory, "$name.json").writeText(buildJsonObject {
            put("syntheticOnly", true); put("actualDialogWindow", true)
            put("sourceFingerprint", NativeBuildInfo.SOURCE_FINGERPRINT)
            put("widthPx", requireNotNull(dialog.window).decorView.width)
            put("heightPx", requireNotNull(dialog.window).decorView.height)
            put("systemFontScale", compose.activity.resources.configuration.fontScale)
            put("accountTransportCalls", accountTransportCalls.get()); put("browseCalls", browseCalls.get())
        }.toString())
    }

    private fun capture(name: String) {
        compose.waitForIdle(); assertViewport(); directory.mkdirs()
        val bitmap = compose.runOnUiThread {
            val view = compose.activity.findViewById<View>(android.R.id.content)
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        try { File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        File(directory, "$name.json").writeText(buildJsonObject {
            put("syntheticOnly", true); put("sourceFingerprint", NativeBuildInfo.SOURCE_FINGERPRINT)
            put("widthPx", width); put("heightPx", height); put("density", 3.25f)
            put("systemFontScale", compose.activity.resources.configuration.fontScale)
            put("drawingTopPx", insets.first); put("gestureTopPx", insets.second); put("bottomPx", insets.third)
            put("rawInsetEvents", JsonArray(rawInsets.toList()))
            put("accountTransportCalls", accountTransportCalls.get()); put("browseCalls", browseCalls.get())
            put("transportCounterScope", "Only the fixture AccountRuntime OkHttp client; NativeUpdateService and external launcher do not share this counter")
            put("nativeUpdateGuard", "The existing native update service is configured enabled=false before composition; update actions are not clicked")
            put("externalGuard", "This root fixture never clicks external link rows; separate callback tests record explicit link intents without opening a browser")
        }.toString())
    }
}
