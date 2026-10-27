// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.ktclat.c001apk.nativeapp.AppContainer
import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.data.*
import io.github.ktclat.c001apk.nativeapp.diagnostics.NativeBuildInfo
import io.github.ktclat.c001apk.nativeapp.domain.*
import io.github.ktclat.c001apk.nativeapp.local.*
import io.github.ktclat.c001apk.nativeapp.settings.*
import io.github.ktclat.c001apk.nativeapp.social.SocialRepository
import kotlinx.coroutines.flow.MutableStateFlow
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

/** Real root routing; all account transport is fail-closed and all public data is synthetic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w369dp-h822dp-520dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeBlacklistRootTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val output = File("build/verification/blacklist-parity")
    private var oldScale: Float? = null
    private lateinit var client: OkHttpClient
    private val calls = AtomicInteger()
    private var titleEvidence: JsonObject? = null
    private val topicRequests = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val userRequests = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val specialTopic = "a/b #?% 中"
    private val initial = LocalState(blacklist = Blacklist(userBlackList = (1..45).map(Int::toString),
        topicBlackList = (1..40).map { "话题$it" } + specialTopic))
    private val local = object : JsonStateStore<LocalState> {
        override val data = MutableStateFlow(initial)
        override suspend fun update(transform: (LocalState) -> LocalState) { data.value = LocalRepository.validateState(transform(data.value)) }
    }
    @After fun cleanup() {
        compose.runOnUiThread { compose.activity.viewModelStore.clear(); oldScale?.let(RuntimeEnvironment::setFontScale) }
        if (::client.isInitialized) { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
        assertEquals("No real account, login, HTTP repository or social transport", 0, calls.get())
    }
    private fun render(systemScale: Float = 1f, appScale: Float = 1f, landscape: Boolean = false) {
        compose.runOnUiThread {
            oldScale = RuntimeEnvironment.getFontScale(); RuntimeEnvironment.setFontScale(systemScale)
            val width = if (landscape) 2670 else 1200; val height = if (landscape) 1200 else 2670
            shadowOf(ShadowDisplay.getDefaultDisplay()).apply { setWidth(width); setHeight(height); setRealWidth(width); setRealHeight(height) }
            compose.activity.window.setLayout(width, height)
        }
        val store = AccountStore(object : CredentialPersistence {
            override fun read(): ByteArray? = null
            override fun write(bytes: ByteArray) = Unit
            override fun reset() = Unit
        })
        client = OkHttpClient.Builder().addInterceptor { calls.incrementAndGet(); throw IOException("No networking in fixture") }.build()
        val login = HttpLoginTransport(client)
        val constructor = AccountRuntime::class.java.getDeclaredConstructor(AccountStore::class.java,
            HttpLoginTransport::class.java, LoginCoordinator::class.java, HttpCoolapkRepository::class.java, SocialRepository::class.java).apply { isAccessible = true }
        val accounts = constructor.newInstance(store, login, LoginCoordinator(store, login, "synthetic"),
            HttpCoolapkRepository(client), SocialRepository(store) { CoolapkHttpTransport(client) })
        val repository = Proxy.newProxyInstance(CoolapkRepository::class.java.classLoader, arrayOf(CoolapkRepository::class.java)) { _, method, args ->
            when (method.name) {
                "userProfile" -> { val uid = args!![0] as String; userRequests += uid; ApiResult.Success(ContentItem(uid, "user", userId = uid, userName = "合成用户 $uid", userProfile = UserSpaceProfile())) }
                "topicDetail" -> { val tag = args!![0] as String; topicRequests += tag; ApiResult.Success(ContentItem("synthetic-topic", "topic", title = tag)) }
                "home", "userFeed" -> ApiResult.Success(Page(emptyList<ContentItem>(), null))
                else -> throw AssertionError("Unexpected synthetic read ${method.name}")
            }
        } as CoolapkRepository
        val model = BrowseViewModel(repository)
        val container = AppContainer(compose.activity.application).also { it.nativeUpdates.enabled = false }
        AppContainer::class.java.getDeclaredField("local").apply { isAccessible = true }.set(container, LocalRepository(local))
        compose.setContent {
            val accountState by store.state.collectAsState()
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * appScale)) {
                MaterialTheme(colorScheme = fixedColorScheme(0, systemScale > 1f), typography = appTypography(FontChoice.NOTO_SANS)) {
                    NativeApp(model, container, AppSettings(dynamicColor = false, fontScale = appScale), accounts, accountState, initialRoute = "settings")
                }
            }
        }
        compose.onNodeWithTag("settings-category-list").performScrollToNode(hasText("浏览与隐私"))
        compose.onNodeWithText("浏览与隐私").performClick()
        compose.onNodeWithText("用户与话题黑名单").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("blacklist-list").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun await(text: String) { compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    private fun scroll(tag: String) { compose.onNodeWithTag("blacklist-list").performScrollToNode(hasTestTag(tag)) }
    private fun action(text: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("blacklist-list").performScrollToNode(hasText(text))
        return compose.onNodeWithText(text)
    }
    private fun back() { compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle() }
    private fun scrollValue() = compose.onNodeWithTag("blacklist-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    @Test fun actualUserAndSpecialTopicChildrenReturnToSameScrolledListAndDraftWithoutUnblocking() {
        render()
        compose.onNodeWithTag("blacklist-input").performTextInput("876")
        scroll("blacklist-open:30")
        val beforeUser = compose.onNodeWithTag("blacklist-open:30").getUnclippedBoundsInRoot().top.value
        val beforeUserScroll = scrollValue()
        assertTrue(beforeUserScroll > 0f)
        capture("user-scrolled")
        compose.onNodeWithTag("blacklist-open:30").performClick()
        await("用户主页")
        compose.waitUntil(5_000) { userRequests.contains("30") }
        capture("blocked-user-child")
        back()
        compose.onNodeWithTag("blacklist-open:30").assertIsDisplayed()
        assertEquals(beforeUser, compose.onNodeWithTag("blacklist-open:30").getUnclippedBoundsInRoot().top.value, .1f)
        assertEquals(beforeUserScroll, scrollValue(), .001f)
        capture("user-return")
        compose.onNodeWithTag("blacklist-list").performScrollToIndex(0)
        compose.onNodeWithTag("blacklist-input").assertTextContains("876")
        action("话题关键词").performClick()
        compose.onNodeWithTag("blacklist-input").performTextInput("新话题草稿")
        scroll("blacklist-open:$specialTopic")
        val beforeTopic = compose.onNodeWithTag("blacklist-open:$specialTopic").getUnclippedBoundsInRoot().top.value
        val beforeTopicScroll = scrollValue()
        capture("topic-scrolled")
        compose.onNodeWithTag("blacklist-open:$specialTopic").performClick()
        compose.waitUntil(5_000) { topicRequests.contains(specialTopic) }
        capture("special-topic-child")
        back()
        compose.onNodeWithTag("blacklist-open:$specialTopic").assertIsDisplayed()
        assertEquals(beforeTopic, compose.onNodeWithTag("blacklist-open:$specialTopic").getUnclippedBoundsInRoot().top.value, .1f)
        assertEquals(beforeTopicScroll, scrollValue(), .001f)
        compose.onNodeWithTag("blacklist-list").performScrollToIndex(0)
        compose.onNodeWithTag("blacklist-input").assertTextContains("新话题草稿")
        assertEquals(initial, local.data.value)
        back(); compose.onNodeWithText("浏览与隐私").assertExists()
    }

    @Test fun largeSystemAndAppFontActionsAndClearDialogKeepEffectiveDensity() {
        render(systemScale = 2f, appScale = 1.2f)
        assertTitleFits()
        action("导出用户列表").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        capture("portrait-dark-font240-actions")
        action("完整备份").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        action("清空用户列表").performClick()
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("清空用户黑名单？").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals(2.4f, results.single().layoutInput.density.fontScale, .001f)
        compose.onNodeWithText("取消").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("确认清空").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        captureDialog("portrait-dark-font240-clear")
        compose.onNodeWithText("取消").performClick()
        assertEquals(initial, local.data.value)
    }

    @Test @Config(qualifiers = "w822dp-h369dp-land-520dpi")
    fun shortLandscapeLargeFontActionsAndClearDialogRemainReachable() {
        render(systemScale = 2f, appScale = 1.2f, landscape = true)
        assertTitleFits()
        action("导出用户列表").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        capture("landscape-dark-font240-actions")
        action("清空用户列表").performClick()
        compose.onNodeWithText("取消").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("确认清空").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        captureDialog("landscape-dark-font240-clear")
        val description = compose.onNodeWithText("仅清空用户名单", substring = true)
        val viewport = compose.onNodeWithTag("blacklist-clear-viewport")
        val range = viewport.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        // Shorter warning-first copy may fit without scrolling. If it does overflow the
        // viewport, its actual tail must remain reachable rather than being dropped.
        if (range.maxValue() > 0f) {
            viewport.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 10_000f) }
            compose.waitForIdle()
            assertTrue(range.value() > 0f)
        }
        val bodyResults = mutableListOf<TextLayoutResult>()
        description.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(bodyResults) }
        assertFalse(bodyResults.single().hasVisualOverflow)
        captureDialog("landscape-dark-font240-clear-tail")
        compose.onNodeWithText("取消").performClick()
        assertEquals(initial, local.data.value)
    }

    private fun assertTitleFits() {
        val title = compose.onNodeWithTag("blacklist-title")
        val results = mutableListOf<TextLayoutResult>()
        title.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val result = results.single()
        val bounds = title.getUnclippedBoundsInRoot()
        val bar = compose.onNodeWithTag("blacklist-topbar").getUnclippedBoundsInRoot()
        // Compose may retain the paragraph's allocated max width while shrink-wrapping
        // this Text node. didOverflowWidth then compares allocation, not rendered glyphs.
        // Validate actual line/character bounds and ellipsis, not that aggregate flag.
        assertFalse("The title must not overflow vertically", result.didOverflowHeight)
        assertTrue(result.multiParagraph.height <= result.size.height + 1f)
        for (line in 0 until result.lineCount) {
            assertFalse(result.isLineEllipsized(line))
            assertTrue(result.getLineLeft(line) >= -1f)
            assertTrue(result.getLineRight(line) <= result.size.width + 1f)
            assertTrue(result.getLineTop(line) >= -1f)
            assertTrue(result.getLineBottom(line) <= result.size.height + 1f)
        }
        val boxes = (0 until result.layoutInput.text.length).map(result::getBoundingBox)
        boxes.forEach { box ->
            assertTrue(box.left >= -1f && box.right <= result.size.width + 1f)
            assertTrue(box.top >= -1f && box.bottom <= result.size.height + 1f)
        }
        assertTrue("Title must remain below the top app-bar edge", bounds.top >= bar.top)
        assertTrue("Title must remain above the app-bar/list clip", bounds.bottom <= bar.bottom)
        titleEvidence = buildJsonObject {
            put("textWidthPx", result.size.width); put("textHeightPx", result.size.height)
            put("allocatedParagraphWidthPx", result.multiParagraph.width)
            put("reportedWidthOverflow", result.didOverflowWidth); put("heightOverflow", result.didOverflowHeight)
            put("characterBoxes", JsonArray(boxes.map { box -> buildJsonObject {
                put("left", box.left); put("top", box.top); put("right", box.right); put("bottom", box.bottom)
            } }))
            put("titleTopDp", bounds.top.value); put("titleBottomDp", bounds.bottom.value)
            put("appBarTopDp", bar.top.value); put("appBarBottomDp", bar.bottom.value)
            put("effectiveFontScale", result.layoutInput.density.fontScale)
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle(); output.mkdirs()
        val view = compose.activity.findViewById<View>(android.R.id.content)
        val bitmap = compose.runOnUiThread { Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) } }
        save(name, bitmap)
    }
    private fun captureDialog(name: String) {
        compose.waitForIdle(); output.mkdirs()
        val view = requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog().window).decorView
        val bitmap = compose.runOnUiThread { Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) } }
        save(name, bitmap)
    }
    private fun save(name: String, bitmap: Bitmap) {
        try { File(output, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        File(output, "$name.json").writeText(buildJsonObject {
            put("syntheticOnly", true); put("sourceFingerprint", NativeBuildInfo.SOURCE_FINGERPRINT)
            put("accountTransportCalls", calls.get()); put("topicRequests", JsonArray(topicRequests.map(::JsonPrimitive)))
            titleEvidence?.let { put("titleLayout", it) }
        }.toString())
    }
}
