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
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import io.github.ktclat.c001apk.nativeapp.AppContainer
import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.data.*
import io.github.ktclat.c001apk.nativeapp.diagnostics.NativeBuildInfo
import io.github.ktclat.c001apk.nativeapp.domain.*
import io.github.ktclat.c001apk.nativeapp.local.LocalLoadState
import io.github.ktclat.c001apk.nativeapp.local.loadStates
import io.github.ktclat.c001apk.nativeapp.settings.*
import io.github.ktclat.c001apk.nativeapp.social.SocialRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
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
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/** Actual NativeApp settings. Synthetic native-Canvas evidence, not a physical device or live service. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w900dp-h600dp-land-520dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsCategoryScrollRootTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val directory = File("build/verification/settings-category-scroll")
    private var oldFontScale: Float? = null
    private var scale = 1f
    private var width = 2925
    private var height = 1950
    private var wide = true
    private var fixture = ""
    private var insets = Triple(0, 0, 0)
    private val accountCalls = AtomicInteger()
    private val browseCalls = AtomicInteger()
    private lateinit var client: OkHttpClient
    private val observations = mutableListOf<JsonObject>()

    @After fun cleanup() {
        compose.runOnUiThread {
            ViewCompat.setOnApplyWindowInsetsListener(compose.activity.window.decorView, null)
            compose.activity.viewModelStore.clear()
            oldFontScale?.let(RuntimeEnvironment::setFontScale)
        }
        if (::client.isInitialized) {
            client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow()
        }
        assertEquals("No account, login, social, or HTTP repository calls", 0, accountCalls.get())
    }

    private fun render(name: String, widthPx: Int, heightPx: Int, fontScale: Float) {
        fixture = name; width = widthPx; height = heightPx; scale = fontScale; wide = width / 3.25f >= 840f
        compose.runOnUiThread {
            oldFontScale = RuntimeEnvironment.getFontScale(); RuntimeEnvironment.setFontScale(scale)
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
        client = OkHttpClient.Builder().addInterceptor {
            accountCalls.incrementAndGet(); throw IOException("Synthetic fail-closed: networking is forbidden")
        }.build()
        val login = HttpLoginTransport(client)
        val constructor = AccountRuntime::class.java.getDeclaredConstructor(AccountStore::class.java,
            HttpLoginTransport::class.java, LoginCoordinator::class.java, HttpCoolapkRepository::class.java,
            SocialRepository::class.java).apply { isAccessible = true }
        val accounts = constructor.newInstance(store, login, LoginCoordinator(store, login, "synthetic"),
            HttpCoolapkRepository(client), SocialRepository(store) { CoolapkHttpTransport(client) })
        val repository = Proxy.newProxyInstance(CoolapkRepository::class.java.classLoader,
            arrayOf(CoolapkRepository::class.java)) { _, method, _ ->
            if (method.name == "home") { browseCalls.incrementAndGet(); ApiResult.Success(Page(emptyList<ContentItem>(), null)) }
            else throw AssertionError("Unexpected synthetic browse call ${method.name}")
        } as CoolapkRepository
        val model = BrowseViewModel(repository)
        val container = AppContainer(compose.activity.application).also { it.nativeUpdates.enabled = false }
        runBlocking { withTimeout(5_000) { container.local.loadStates().first { it is LocalLoadState.Ready } } }
        compose.setContent {
            val accountState by store.state.collectAsState()
            val density = LocalDensity.current
            val drawing = WindowInsets.safeDrawing.getTop(density)
            val gesture = WindowInsets.systemGestures.getTop(density)
            val bottom = WindowInsets.safeDrawing.getBottom(density)
            SideEffect { insets = Triple(drawing, gesture, bottom) }
            MaterialTheme(colorScheme = fixedColorScheme(0, false), typography = appTypography(FontChoice.NOTO_SANS)) {
                NativeApp(model, container, AppSettings(dynamicColor = false, font = FontChoice.NOTO_SANS),
                    accounts, accountState, initialRoute = "settings")
            }
        }
        injectInsets()
        assertEquals(scale, compose.activity.resources.configuration.fontScale, .001f)
        assertEquals(scale, compose.activity.application.resources.configuration.fontScale, .001f)
        assertViewport()
        assertEquals(if (wide) 2 else 1, compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().size)
        compose.onNodeWithTag("floating-navigation").assertDoesNotExist()
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
            ViewCompat.setOnApplyWindowInsetsListener(decor) { view, _ -> ViewCompat.onApplyWindowInsets(view, fixtureInsets()) }
            ViewCompat.dispatchApplyWindowInsets(decor, fixtureInsets())
        }
        compose.waitForIdle(); assertEquals(Triple(140, 179, 0), insets)
    }
    private fun assertViewport() {
        val view = compose.activity.findViewById<View>(android.R.id.content)
        assertEquals(width, view.width); assertEquals(height, view.height)
        assertEquals(3.25f, compose.activity.resources.displayMetrics.density, .001f)
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals(width.toFloat(), root.width, .1f); assertEquals(height.toFloat(), root.height, .1f)
        assertEquals(wide, root.width / 3.25f >= 840f)
    }
    private fun detailMatcher() = hasScrollToIndexAction() and SemanticsMatcher("settings detail viewport") {
        !wide || it.boundsInRoot.left >= 320f * 3.25f
    }
    private fun categoriesMatcher() = hasScrollToIndexAction() and SemanticsMatcher("settings category viewport") {
        it.boundsInRoot.left < 1f
    }
    private fun detail() = compose.onNode(detailMatcher())
    private fun category(title: String) = hasText(title) and hasClickAction()
    private fun choose(title: String) {
        // Scroll only the category rail, never the detail. Inspect the new detail before any reveal action.
        compose.onNode(categoriesMatcher()).performScrollToNode(category(title))
        compose.onNode(category(title)).performClick(); compose.waitForIdle()
    }
    private fun hardwareBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle()
    }
    private fun tail(name: String) {
        detail().performScrollToNode(hasText("悬浮导航栏"))
        detail().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        compose.waitForIdle()
        val axis = detail().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("Appearance must actually be scrolled", axis.value() > 0f)
        assertEquals("Appearance tail must actually be reached", axis.maxValue(), axis.value(), .01f)
        compose.onNodeWithText("悬浮导航栏").assertIsDisplayed().assertIsOff()
        capture(name)
    }

    @Test fun wideRegularFontSwitchesPrivacyAndProtocolBackToAppearanceAtTop() {
        render("wide-900x600-font100", 2925, 1950, 1f); wideFlow()
    }
    @Test @Config(qualifiers = "w900dp-h369dp-land-520dpi")
    fun wideShortTwoTimesSystemFontSwitchesPrivacyAndProtocolBackToAppearanceAtTop() {
        render("wide-900x369-font200", 2925, 1200, 2f); wideFlow()
    }
    @Test @Config(qualifiers = "w369dp-h822dp-520dpi")
    fun narrowReportedWindowBackAndReenterStartsEachCategoryAtTop() {
        render("narrow-reported-1200x2670-font200", 1200, 2670, 2f)
        choose("外观与字体"); observe("01-appearance-initial", "外观与字体", "外观")
        tail("02-appearance-tail"); hardwareBack()
        compose.onNodeWithText("外观与字体").assertExists()
        choose("浏览与隐私"); observe("03-privacy-after-tail", "浏览与隐私", "用户与话题黑名单")
        hardwareBack(); choose("外观与字体"); observe("04-appearance-return-privacy", "外观与字体", "外观")
        tail("05-appearance-tail-again"); hardwareBack(); choose("协议与设备")
        observe("06-protocol-after-tail", "协议与设备", "设备标识与协议参数")
        hardwareBack(); choose("外观与字体"); observe("07-appearance-return-protocol", "外观与字体", "外观")
        hardwareBack(); capture("08-back-to-categories")
        compose.onNodeWithText("外观与字体").assertExists(); assertEquals(0, browseCalls.get())
        assertObservations()
    }
    private fun wideFlow() {
        observe("01-appearance-initial", "外观与字体", "外观")
        tail("02-appearance-tail")
        choose("浏览与隐私"); observe("03-privacy-after-tail", "浏览与隐私", "用户与话题黑名单")
        choose("外观与字体"); observe("04-appearance-return-privacy", "外观与字体", "外观")
        tail("05-appearance-tail-again")
        choose("协议与设备"); observe("06-protocol-after-tail", "协议与设备", "设备标识与协议参数")
        choose("外观与字体"); observe("07-appearance-return-protocol", "外观与字体", "外观")
        assertEquals(0, browseCalls.get()); assertEquals(0, accountCalls.get())
        hardwareBack()
        compose.onNodeWithText("设置", substring = false).assertDoesNotExist()
        compose.onNodeWithTag("floating-navigation").assertDoesNotExist()
        compose.waitUntil(5_000) { browseCalls.get() == 1 }
        capture("08-back-to-home")
        assertEquals("Only explicit Back to the home route invokes the offline home fake", 1, browseCalls.get())
        assertObservations()
    }
    private fun observe(name: String, title: String, first: String) {
        compose.waitForIdle(); capture(name)
        val axis = detail().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        val viewport = detail().fetchSemanticsNode().boundsInRoot
        fun textState(text: String, inDetail: Boolean): JsonObject {
            val scope = if (inDetail) hasAnyAncestor(detailMatcher()) else !hasAnyAncestor(detailMatcher())
            val matcher = hasText(text, substring = false) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and scope
            val nodes = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()
            assertTrue("Expected at most one exact text node for $text", nodes.size <= 1)
            if (nodes.isEmpty()) return buildJsonObject { put("text", text); put("present", false); put("fullyVisible", false) }
            val node = compose.onNode(matcher, useUnmergedTree = true)
            val bounds = node.getUnclippedBoundsInRoot()
            val clipped = nodes.single().boundsInRoot
            val fullLeft = bounds.left.value * 3.25f; val fullRight = bounds.right.value * 3.25f
            val fullTop = bounds.top.value * 3.25f; val fullBottom = bounds.bottom.value * 3.25f
            val fullyVisible = clipped.width > 0f && clipped.height > 0f &&
                kotlin.math.abs(clipped.width - (fullRight - fullLeft)) <= 1f &&
                kotlin.math.abs(clipped.height - (fullBottom - fullTop)) <= 1f &&
                (!inDetail || (fullTop >= viewport.top - 1f && fullBottom <= viewport.bottom + 1f && fullLeft >= viewport.left - 1f && fullRight <= viewport.right + 1f))
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            val layout = layouts.single(); assertEquals(text, layout.layoutInput.text.text)
            recordAndVerifyLayout(name, text, layout)
            return buildJsonObject {
                put("text", text); put("present", true); put("fullyVisible", fullyVisible)
                put("leftPx", fullLeft); put("rightPx", fullRight); put("topPx", fullTop); put("bottomPx", fullBottom)
            }
        }
        val heading = textState(title, wide); val firstItem = textState(first, true)
        val result = buildJsonObject {
            put("name", name); put("fixture", fixture); put("scrollValue", axis.value()); put("scrollMaximum", axis.maxValue())
            put("atTop", kotlin.math.abs(axis.value()) <= .01f); put("heading", heading); put("firstItem", firstItem)
            put("viewportTopPx", viewport.top); put("viewportBottomPx", viewport.bottom)
            put("matchesSwitchToFreshTop", kotlin.math.abs(axis.value()) <= .01f && heading["fullyVisible"]!!.jsonPrimitive.boolean && firstItem["fullyVisible"]!!.jsonPrimitive.boolean)
        }
        observations += result
        File(directory, "$fixture-$name-observation.json").writeText(result.toString())
    }
    private fun assertObservations() {
        File(directory, "$fixture-observations.json").writeText(JsonArray(observations).toString())
        assertTrue("Switching category should reset to top like Flutter ValueKey(section); raw observations: $observations",
            observations.all { it["matchesSwitchToFreshTop"]!!.jsonPrimitive.boolean })
    }
    private fun recordAndVerifyLayout(name: String, text: String, layout: TextLayoutResult) {
        File(directory, "$fixture-text-layouts.jsonl").appendText(buildJsonObject {
            put("state", name); put("text", text); put("density", layout.layoutInput.density.density)
            put("effectiveFontScale", layout.layoutInput.density.fontScale); put("systemFontScale", compose.activity.resources.configuration.fontScale)
            put("width", layout.size.width); put("height", layout.size.height); put("rawHasVisualOverflow", layout.hasVisualOverflow)
            put("rawDidOverflowWidth", layout.didOverflowWidth); put("rawDidOverflowHeight", layout.didOverflowHeight)
            put("lines", buildJsonArray { repeat(layout.lineCount) { line -> add(buildJsonObject {
                put("start", layout.getLineStart(line)); put("end", layout.getLineEnd(line)); put("visibleEnd", layout.getLineEnd(line, visibleEnd = true))
                put("left", layout.getLineLeft(line)); put("right", layout.getLineRight(line)); put("top", layout.getLineTop(line)); put("bottom", layout.getLineBottom(line))
                put("ellipsized", layout.isLineEllipsized(line))
            }) } })
            put("glyphs", buildJsonArray { text.indices.forEach { offset -> val box = layout.getBoundingBox(offset); add(buildJsonObject {
                put("offset", offset); put("codepoint", text[offset].code); put("left", box.left); put("right", box.right); put("top", box.top); put("bottom", box.bottom)
            }) } })
        }.toString() + "\n")
        assertEquals(3.25f, layout.layoutInput.density.density, .001f); assertEquals(scale, layout.layoutInput.density.fontScale, .001f)
        assertFalse(layout.didOverflowHeight); assertFalse(layout.multiParagraph.didExceedMaxLines)
        assertTrue(layout.lineCount > 0); assertEquals(0, layout.getLineStart(0))
        repeat(layout.lineCount) { line ->
            assertFalse(layout.isLineEllipsized(line))
            assertTrue(layout.getLineLeft(line) >= -1 && layout.getLineRight(line) <= layout.size.width + 1)
            assertTrue(layout.getLineTop(line) >= -1 && layout.getLineBottom(line) <= layout.size.height + 1)
            if (line > 0) assertEquals(layout.getLineEnd(line - 1), layout.getLineStart(line))
        }
        assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1))
        text.indices.forEach { val box = layout.getBoundingBox(it)
            assertTrue(box.left >= -1 && box.right <= layout.size.width + 1 && box.top >= -1 && box.bottom <= layout.size.height + 1)
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle(); assertViewport(); directory.mkdirs()
        val bitmap = compose.runOnUiThread {
            val view = compose.activity.findViewById<View>(android.R.id.content)
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        try { File(directory, "$fixture-$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        fun sha(file: String) = MessageDigest.getInstance("SHA-256").digest(File(file).readBytes()).joinToString("") { "%02x".format(it) }
        File(directory, "$fixture-$name.json").writeText(buildJsonObject {
            put("syntheticOnly", true); put("fixture", fixture); put("physicalDeviceClaim", false)
            put("sourceFingerprint", NativeBuildInfo.SOURCE_FINGERPRINT); put("fingerprintScope", NativeBuildInfo.FINGERPRINT_SCOPE)
            put("sourceCommit", NativeBuildInfo.COMMIT); put("sourceBranch", NativeBuildInfo.BRANCH); put("sourceWorktree", NativeBuildInfo.WORKTREE)
            put("baselineSourceTree", "eff3740f0b7c97b15eae27b001edd62cf68fb001")
            put("baselineIntegrationCommit", "ae47c57a23ec6ad6fa9ae1aa91a75936354154c0")
            put("testSourceSha256", sha("src/test/java/io/github/ktclat/c001apk/nativeapp/ui/SettingsCategoryScrollRootTest.kt"))
            put("settingsSourceSha256", sha("src/main/java/io/github/ktclat/c001apk/nativeapp/settings/SettingsScreen.kt"))
            put("widthPx", width); put("heightPx", height); put("density", 3.25f); put("wide", wide)
            put("systemFontScale", compose.activity.resources.configuration.fontScale)
            put("drawingTopPx", insets.first); put("gestureTopPx", insets.second); put("bottomPx", insets.third)
            put("accountTransportCalls", accountCalls.get()); put("browseCalls", browseCalls.get())
            put("networkGuard", "AccountRuntime fail-closed client; browse is an in-memory proxy; native updates disabled before composition; no external launcher clicked")
        }.toString())
        File(directory, "$fixture-$name-semantics.txt").writeText(compose.onRoot().printToString())
    }
}
