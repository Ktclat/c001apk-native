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
import java.util.concurrent.atomic.AtomicInteger

/** Actual NativeApp routes with native Canvas and offline transports. Not a device/real-network test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w369dp-h822dp-520dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeAboutRootTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val directory = File("build/verification/native-about")
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
    private val rawInsets = mutableListOf<JsonObject>()

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

    private fun render(fontScale: Float = 1f, landscape: Boolean = false, dark: Boolean = false) {
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
            arrayOf(CoolapkRepository::class.java)) { _, method, _ ->
            if (method.name == "home") { browseCalls.incrementAndGet(); ApiResult.Success(Page(emptyList<ContentItem>(), null)) }
            else throw AssertionError("Unexpected synthetic browse call ${method.name}")
        } as CoolapkRepository
        model = BrowseViewModel(repository)
        val container = AppContainer(compose.activity.application).also { it.nativeUpdates.enabled = false }
        runBlocking { withTimeout(5_000) { container.local.loadStates().first { it is LocalLoadState.Ready } } }
        restorer = StateRestorationTester(compose)
        restorer.setContent {
            val accountState by store.state.collectAsState()
            val density = LocalDensity.current
            val drawing = WindowInsets.safeDrawing.getTop(density)
            val gesture = WindowInsets.systemGestures.getTop(density)
            val bottom = WindowInsets.safeDrawing.getBottom(density)
            SideEffect { insets = Triple(drawing, gesture, bottom) }
            MaterialTheme(colorScheme = fixedColorScheme(0, dark), typography = appTypography(FontChoice.NOTO_SANS)) {
                NativeApp(model, container, AppSettings(dynamicColor = false, font = FontChoice.NOTO_SANS),
                    accounts, accountState, initialRoute = "settings")
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
    private fun openAbout() {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("关于") and hasClickAction())
        compose.onNode(hasText("关于") and hasClickAction()).performClick()
        compose.onNodeWithTag("native-about").assertExists()
        compose.onNodeWithTag("native-diagnostics").assertDoesNotExist()
        compose.onNodeWithTag("floating-navigation").assertDoesNotExist()
        assertEquals(0, browseCalls.get())
    }
    private fun reveal(tag: String, list: String = "native-about-list") {
        compose.onNodeWithTag(list).performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }
    private fun hardwareBack() { compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle() }

    @Test fun actualSettingsAboutDiagnosticsBackAndProtocolRemainSeparateWithoutDuplicateStack() {
        render()
        capture("01-settings-current")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("关于") and hasClickAction())
        val entry = compose.onNode(hasText("关于") and hasClickAction())
        val action = requireNotNull(entry.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        compose.runOnIdle { action(); action() }
        compose.onNodeWithTag("native-about").assertExists()
        capture("02-about-current")
        reveal("about-diagnostics"); compose.onNodeWithTag("about-diagnostics").performClick()
        compose.onNodeWithTag("native-diagnostics").assertExists()
        capture("03-about-to-diagnostics-current")
        hardwareBack()
        compose.onNodeWithTag("native-about").assertExists()
        compose.onNodeWithTag("native-about-back").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("协议与设备") and hasClickAction())
        compose.onNode(hasText("协议与设备") and hasClickAction()).performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("本地诊断") and hasClickAction())
        compose.onNode(hasText("本地诊断") and hasClickAction()).performClick()
        compose.onNodeWithTag("native-diagnostics").assertExists()
        hardwareBack()
        compose.onNodeWithText("外观与字体").assertExists()
        assertEquals(0, browseCalls.get())
    }

    @Test fun actualAboutAndNoticeReaderRestoreThenPoppedAboutStartsFresh() {
        render(); openAbout()
        reveal("about-bundled"); compose.onNodeWithTag("about-bundled").performClick()
        reveal("bundled-PiliPlus-GPL-3.0.txt", "bundled-notice-list")
        compose.onNodeWithTag("bundled-PiliPlus-GPL-3.0.txt").performClick()
        reveal("bundled-notice-end", "bundled-notice-reader")
        verifyRealBodyBoundaries("PiliPlus-GPL-3.0.txt", "gpl-100")
        reveal("bundled-notice-end", "bundled-notice-reader")
        capture("04-bundled-license-tail")
        restorer.emulateSavedInstanceStateRestore(); injectInsets()
        compose.onNodeWithTag("bundled-notice-end").assertIsDisplayed()
        capture("05-bundled-restored-tail")
        hardwareBack()
        compose.onNodeWithTag("bundled-notice-list").assertExists()
        hardwareBack()
        compose.onNodeWithTag("about-bundled").assertIsDisplayed()
        reveal("about-notices")
        restorer.emulateSavedInstanceStateRestore(); injectInsets()
        compose.onNodeWithTag("about-notices").assertIsDisplayed()
        hardwareBack()
        openAbout()
        compose.onNodeWithTag("about-identity").assertIsDisplayed()
        assertEquals(0, browseCalls.get())
    }

    @Test fun portraitExactReportedInsetsAllTargetsAndLongUrlsAreReadable() {
        render(); openAbout(); verifyRows("portrait-100-light")
    }
    @Test fun portraitDarkTwoTimesSystemFontAllTargetsAndTailAreReadable() {
        render(fontScale = 2f, dark = true); openAbout(); verifyRows("portrait-200-dark")
    }
    @Test @Config(qualifiers = "w822dp-h369dp-land-520dpi")
    fun landscapeTwoTimesSystemFontAllTargetsAndTailAreReadable() {
        render(fontScale = 2f, landscape = true); openAbout(); verifyRows("landscape-200-light")
    }
    @Test fun bundledNoticeListAndFullTextAreReachableAtTwoTimesSystemFont() {
        render(fontScale = 2f); openAbout()
        reveal("about-bundled"); compose.onNodeWithTag("about-bundled").performClick()
        bundledNativeNotices.forEach { notice ->
            val tag = "bundled-${notice.file}"
            reveal(tag, "bundled-notice-list"); checkTarget(tag, "bundled-notice-list")
            verifyText("$tag-title"); verifyText("$tag-subtitle")
        }
        capture("bundled-200-list-tail")
        listOf("PiliPlus-GPL-3.0.txt", "NotoSansCJK-OFL.txt", "TextEmoticons-NOTICE.txt").forEach { file ->
            reveal("bundled-$file", "bundled-notice-list")
            compose.onNodeWithTag("bundled-$file").performClick()
            verifyText("bundled-notice-title")
            verifyRealBodyBoundaries(file, "${file.removeSuffix(".txt")}-200")
            reveal("bundled-notice-end", "bundled-notice-reader")
            verifyText("bundled-notice-end")
            hardwareBack()
            compose.onNodeWithTag("bundled-notice-list").assertExists()
        }
    }

    private fun verifyRows(name: String) {
        capture("$name-top")
        checkBack()
        listOf("identity", "version", "diagnostics", "project", "updates", "license", "bundled", "issues", "flutter", "notices").forEach { row ->
            val tag = "about-$row"
            reveal(tag)
            if (row !in listOf("identity", "version")) checkTarget(tag, "native-about-list")
            verifyText("$tag-title"); verifyText("$tag-subtitle")
        }
        capture("$name-tail")
        checkBack()
        assertEquals(0, accountTransportCalls.get()); assertEquals(0, browseCalls.get())
    }

    private fun checkBack() {
        val node = compose.onNodeWithTag("native-about-back")
        node.assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        val box = node.getUnclippedBoundsInRoot()
        assertTrue("Back target must be below max(140,179), not merely below status bar", box.top.value * 3.25f >= 179 - 1f)
        assertTrue("Insets must not be added twice", box.top.value * 3.25f < 179 + 12 * 3.25f)
        checkUnclipped(node)
    }
    private fun checkTarget(tag: String, list: String) {
        val node = compose.onNodeWithTag(tag)
        node.assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        val full = node.getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag(list).fetchSemanticsNode().boundsInRoot
        assertTrue("$tag complete touch area must be in viewport", full.top.value * 3.25f >= viewport.top - 1 && full.bottom.value * 3.25f <= viewport.bottom + 1)
        assertTrue("$tag complete touch area must be in horizontal viewport", full.left.value * 3.25f >= viewport.left - 1 && full.right.value * 3.25f <= viewport.right + 1)
        checkUnclipped(node)
    }
    private fun checkUnclipped(node: SemanticsNodeInteraction) {
        val full = node.getUnclippedBoundsInRoot(); val clipped = node.fetchSemanticsNode().boundsInRoot
        assertEquals((full.right - full.left).value * 3.25f, clipped.width, 1f)
        assertEquals((full.bottom - full.top).value * 3.25f, clipped.height, 1f)
    }
    private fun verifyText(tag: String, expectedText: String? = null, fullyVisible: Boolean = true): TextLayoutResult {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true)
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
        val layout = results.single(); val text = layout.layoutInput.text.text
        expectedText?.let { assertEquals("Actual rendered text must exactly equal the packaged paragraph", it, text) }
        directory.mkdirs()
        File(directory, "text-layouts.jsonl").appendText(buildJsonObject {
            put("tag", tag); put("text", text); put("density", layout.layoutInput.density.density)
            put("effectiveFontScale", layout.layoutInput.density.fontScale)
            put("systemFontScale", compose.activity.resources.configuration.fontScale)
            put("width", layout.size.width); put("height", layout.size.height)
            put("rawHasVisualOverflow", layout.hasVisualOverflow); put("rawDidOverflowWidth", layout.didOverflowWidth)
            put("lines", buildJsonArray { repeat(layout.lineCount) { line -> add(buildJsonObject {
                put("left", layout.getLineLeft(line)); put("right", layout.getLineRight(line))
                put("top", layout.getLineTop(line)); put("bottom", layout.getLineBottom(line))
                put("start", layout.getLineStart(line)); put("end", layout.getLineEnd(line))
                put("visibleEnd", layout.getLineEnd(line, visibleEnd = true))
                put("ellipsized", layout.isLineEllipsized(line))
            }) } })
            put("glyphs", buildJsonArray { text.indices.forEach { offset ->
                val glyph = layout.getBoundingBox(offset)
                val line = layout.getLineForOffset(offset)
                add(buildJsonObject {
                    put("offset", offset); put("codepoint", text[offset].code); put("isWhitespace", text[offset].isWhitespace())
                    put("line", line); put("lineVisibleEnd", layout.getLineEnd(line, visibleEnd = true))
                    put("left", glyph.left); put("right", glyph.right); put("top", glyph.top); put("bottom", glyph.bottom)
                })
            } })
        }.toString() + "\n")
        assertEquals(3.25f, layout.layoutInput.density.density, .001f)
        assertEquals(scale, layout.layoutInput.density.fontScale, .001f)
        assertFalse(layout.didOverflowHeight); assertFalse(layout.multiParagraph.didExceedMaxLines)
        assertTrue(layout.lineCount > 0); assertEquals(0, layout.getLineStart(0))
        repeat(layout.lineCount) { line ->
            assertFalse(layout.isLineEllipsized(line))
            assertTrue("$tag line $line width", layout.getLineLeft(line) >= -1 && layout.getLineRight(line) <= layout.size.width + 1)
            assertTrue("$tag line $line height", layout.getLineTop(line) >= -1 && layout.getLineBottom(line) <= layout.size.height + 1)
            if (line > 0) assertEquals(layout.getLineEnd(line - 1), layout.getLineStart(line))
        }
        assertEquals("All original text, including terminal whitespace, must be laid out", text.length, layout.getLineEnd(layout.lineCount - 1))
        text.indices.forEach { offset ->
            val glyph = layout.getBoundingBox(offset)
            val line = layout.getLineForOffset(offset)
            // Native raw evidence and the pinned Noto outline confirm a wrapped trailing U+0020
            // has advance but no ink. Restrict this exception to exact source license paragraphs,
            // ASCII space, and the engine's non-visible line tail. All visible glyphs stay strict.
            val trailingInklessSpace = expectedText != null && text[offset] == ' ' &&
                offset >= layout.getLineEnd(line, visibleEnd = true) && offset < layout.getLineEnd(line)
            assertTrue("$tag glyph $offset must fit vertically", glyph.top >= -1 && glyph.bottom <= layout.size.height + 1)
            assertTrue("$tag glyph $offset must fit horizontally", trailingInklessSpace ||
                (glyph.left >= -1 && glyph.right <= layout.size.width + 1))
        }
        if (fullyVisible) checkUnclipped(node)
        return layout
    }

    /** Real source paragraph boundaries, not the extra end marker. Large paragraphs may exceed a viewport. */
    private fun verifyRealBodyBoundaries(file: String, name: String) {
        val original = compose.activity.assets.open("licenses/$file").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val paragraphs = original.split(Regex("\\n\\s*\\n"))
        val first = paragraphs.indexOfFirst { it.isNotBlank() }
        val last = paragraphs.indexOfLast { it.isNotBlank() }
        listOf("first" to first, "last" to last).forEach { (edge, index) ->
            val tag = "bundled-notice-paragraph-$index"
            reveal(tag, "bundled-notice-reader")
            val text = paragraphs[index]
            // Preserve original pixels before the unchanged glyph oracle can fail.
            capture("$name-real-$edge-paragraph-before-glyph-check")
            val layout = verifyText(tag, expectedText = text, fullyVisible = false)
            val offset = if (edge == "first") text.indexOfFirst { !it.isWhitespace() } else text.indexOfLast { !it.isWhitespace() }
            val line = layout.getLineForOffset(offset)
            val node = compose.onNodeWithTag(tag, useUnmergedTree = true)
            val scroll = compose.onNodeWithTag("bundled-notice-reader")
            val viewport = scroll.fetchSemanticsNode().boundsInRoot
            val before = node.getUnclippedBoundsInRoot()
            val delta = if (edge == "first") before.top.value * 3.25f + layout.getLineTop(line) - viewport.top - 8f * 3.25f
                else before.top.value * 3.25f + layout.getLineBottom(line) - viewport.bottom + 8f * 3.25f
            scroll.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta) }
            compose.waitForIdle()
            val origin = node.getUnclippedBoundsInRoot()
            val visible = scroll.fetchSemanticsNode().boundsInRoot
            val top = origin.top.value * 3.25f + layout.getLineTop(line)
            val bottom = origin.top.value * 3.25f + layout.getLineBottom(line)
            val glyph = layout.getBoundingBox(offset)
            val glyphLeft = origin.left.value * 3.25f + glyph.left
            val glyphRight = origin.left.value * 3.25f + glyph.right
            val glyphTop = origin.top.value * 3.25f + glyph.top
            val glyphBottom = origin.top.value * 3.25f + glyph.bottom
            capture("$name-real-$edge-paragraph")
            File(directory, "$name-real-$edge-paragraph-body.json").writeText(buildJsonObject {
                put("sourceFile", file); put("paragraphIndex", index); put("expectedParagraph", text)
                put("actualText", layout.layoutInput.text.text); put("line", line); put("offset", offset)
                put("glyph", text[offset].toString()); put("lineTopPx", top); put("lineBottomPx", bottom)
                put("glyphLeftPx", glyphLeft); put("glyphRightPx", glyphRight); put("glyphTopPx", glyphTop); put("glyphBottomPx", glyphBottom)
                put("viewportLeftPx", visible.left); put("viewportRightPx", visible.right)
                put("viewportTopPx", visible.top); put("viewportBottomPx", visible.bottom)
                put("effectiveFontScale", layout.layoutInput.density.fontScale)
                put("systemFontScale", compose.activity.resources.configuration.fontScale)
            }.toString())
            assertTrue("Actual $edge source line must be vertically reachable", top >= visible.top - 1f && bottom <= visible.bottom + 1f)
            assertTrue("Actual $edge source glyph must be wholly in the visible viewport", glyphLeft >= visible.left - 1f && glyphRight <= visible.right + 1f && glyphTop >= visible.top - 1f && glyphBottom <= visible.bottom + 1f)
            assertTrue("Original paragraph must fit horizontal viewport", origin.left.value * 3.25f >= visible.left - 1f && origin.right.value * 3.25f <= visible.right + 1f)
        }
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
