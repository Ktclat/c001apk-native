package io.github.ktclat.c001apk.nativeapp.ui

import android.graphics.Bitmap
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import io.github.ktclat.c001apk.nativeapp.AppContainer
import io.github.ktclat.c001apk.nativeapp.domain.*
import io.github.ktclat.c001apk.nativeapp.settings.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import io.github.ktclat.c001apk.nativeapp.local.loadStates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

/** Actual NativeApp rendering with synthetic data and injected insets; never a device or live-account test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeAppRenderingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val homeCalls = AtomicInteger()
    private var fixtureImages: io.github.ktclat.c001apk.nativeapp.feature.feed.SyntheticFeedImageLoader? = null
    @org.junit.After fun closeFixtureImages() { fixtureImages?.close() }
    private var diagnosticContainer: AppContainer? = null
    private var diagnosticModel: BrowseViewModel? = null
    private fun render(fontScale: Float = 1f, initialRoute: String? = null, sourceReference: Boolean = false, dark: Boolean = false, populated: Boolean = false, browseListStates: Map<String, LazyListState> = emptyMap()) {
        val container = AppContainer(compose.activity.application)
        diagnosticContainer = container
        container.nativeUpdates.enabled = false
        val accounts = runBlocking { container.accounts() }
        fun fixture(name: String) = io.github.ktclat.c001apk.nativeapp.data.ApiDecoder.items(kotlinx.serialization.json.Json.parseToJsonElement(requireNotNull(javaClass.getResource("/$name.json")).readText()) as kotlinx.serialization.json.JsonArray)
        val baseRows = if (sourceReference) fixture("flutter-reference-feeds") else (0..29).map { ContentItem((1000 + it).toString(), "feed", title = "合成动态 $it", userName = "合成测试用户", message = "这是离线测试内容，不代表真实网络或账号已验收。", replyCount = it) }
        val rows = if (populated) baseRows.mapIndexed { index, item ->
            item.copy(avatarUrl = io.github.ktclat.c001apk.nativeapp.feature.feed.SyntheticFeedImageLoader.AVATAR,
                imageUrls = if (index == 0) listOf(io.github.ktclat.c001apk.nativeapp.feature.feed.SyntheticFeedImageLoader.LANDSCAPE) else item.imageUrls)
        } else baseRows
        val images = if (populated) io.github.ktclat.c001apk.nativeapp.feature.feed.SyntheticFeedImageLoader(compose.activity).also { fixtureImages = it } else null
        val repository = Proxy.newProxyInstance(CoolapkRepository::class.java.classLoader, arrayOf(CoolapkRepository::class.java)) { _, method, _ ->
            if (method.name == "home") { homeCalls.incrementAndGet(); ApiResult.Success(Page(rows, null)) }
            else if (method.name == "feedDetail") ApiResult.Success(rows.first())
            else if (method.name == "orderedReplies") ApiResult.Success(Page(if (sourceReference) fixture("flutter-reference-replies") else (0..4).map { ContentItem("${300 + it}", "feed_reply", userName = "合成评论用户", message = "合成评论，验证完整的动态与评论层次。") }, null))
            else throw AssertionError("Unexpected repository call in rendering fixture: ${method.name}")
        } as CoolapkRepository
        val model = BrowseViewModel(repository)
        diagnosticModel = model
        val accountSnapshot = accounts.store.state.value
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(io.github.ktclat.c001apk.nativeapp.feature.media.LocalMediaImageLoader provides images?.loader, LocalDensity provides Density(density, fontScale), LocalReadingStyles provides readingStyles(ReadingPreset.COMFORTABLE, FontChoice.NOTO_SANS)) {
                MaterialTheme(colorScheme = if (sourceReference && !dark) flutterReferenceColorScheme() else fixedColorScheme(0, dark), typography = appTypography(FontChoice.NOTO_SANS)) { NativeApp(model, container, AppSettings(floatingNavigation = true, dynamicColor = false), accounts, accountSnapshot, initialRoute, browseListStates = browseListStates) }
            }
        }
        insets(systemDp = if (sourceReference) 0 else 24)
        try {
            if (sourceReference) compose.waitUntil(15_000) { compose.onAllNodesWithText("今天的数码日常").fetchSemanticsNodes().isNotEmpty() }
            if (initialRoute == null && !sourceReference) compose.waitUntil(15_000) { compose.onAllNodesWithText("合成动态 0").fetchSemanticsNodes().isNotEmpty() }
            if (initialRoute == null && !sourceReference) compose.waitUntil(15_000) { compose.onAllNodesWithText("这是离线测试内容，不代表真实网络或账号已验收。").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
        } catch (failure: ComposeTimeoutException) {
            runCatching { diagnoseReadinessFailure("render") }
            throw failure
        }
    }
    private fun insets(imeDp: Int = 0, systemDp: Int = 24) {
        compose.runOnUiThread {
            val activity = compose.activity
            val density = activity.resources.displayMetrics.density
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            ViewCompat.dispatchApplyWindowInsets(activity.window.decorView, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, (systemDp * density).toInt(), 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, (systemDp * density).toInt()))
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, (imeDp * density).toInt()))
                .setVisible(WindowInsetsCompat.Type.ime(), imeDp > 0).build())
        }
        compose.waitForIdle()
    }
    private fun awaitBodyLayout() {
        try { compose.waitUntil(15_000) {
            val placeholders = compose.onAllNodesWithText("正在排版…")
            // Lazy prefetch may keep non-visible, cancelled items in the semantics tree.
            // Only visible content belongs to this capture/readiness assertion.
            (0 until placeholders.fetchSemanticsNodes().size).none { placeholders[it].isDisplayed() }
        } } catch (failure: ComposeTimeoutException) {
            runCatching { diagnoseReadinessFailure("body") }
            throw failure
        }
        compose.waitForIdle()
    }
    /** Failure-only observation. The original timeout remains authoritative even if rescue changes UI. */
    private fun diagnoseReadinessFailure(phase: String) {
        val directory = File("build/verification").apply { mkdirs() }
        val notes = StringBuilder()
        fun attempt(label: String, action: () -> Unit) {
            runCatching(action).onFailure { notes.append("$label diagnostic failed: ${it.javaClass.name}: ${it.message}\n") }
        }
        fun image(name: String) {
            val bitmap = compose.runOnUiThread {
                val view = compose.activity.findViewById<View>(android.R.id.content)
                Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
            }
            File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        // Preserve the unrecovered tree and pixels before any extra collection or snapshot notification.
        attempt("before tree") { File(directory, "readiness-$phase-before.txt").writeText(compose.onRoot(useUnmergedTree = true).printToString()) }
        attempt("before image") { image("readiness-$phase-before.png") }
        notes.append("homeCalls=${homeCalls.get()}\n")
        notes.append("model=" + diagnosticModel?.states?.value?.mapValues { (_, value) ->
            "items=${value.items.size}, loading=${value.loading}, loaded=${value.loaded}, error=${value.error}"
        } + "\n")
        attempt("lifecycle") { notes.append("lifecycle=" + compose.runOnUiThread { compose.activity.lifecycle.currentState } + "\n") }
        attempt("snapshot notification probe") {
            compose.runOnUiThread { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
            compose.waitForIdle()
            File(directory, "readiness-$phase-after-notification.txt").writeText(compose.onRoot(useUnmergedTree = true).printToString())
            image("readiness-$phase-after-notification.png")
        }
        // Collect cold app state only AFTER the rescue comparison; doing this earlier could warm the
        // DataStore/flow and make its progress look like a snapshot-notification effect.
        attempt("raw local state") {
            val container = diagnosticContainer
            val local = if (container == null) null else kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(2_000) {
                    container.local.loadStates().first()
                }
            }
            notes.append("rawLocalAfterProbe=" + when (local) {
                is io.github.ktclat.c001apk.nativeapp.local.LocalLoadState.Ready -> "Ready"
                io.github.ktclat.c001apk.nativeapp.local.LocalLoadState.Loading -> "Loading"
                io.github.ktclat.c001apk.nativeapp.local.LocalLoadState.Failed -> "Failed"
                null -> "timeout or unavailable"
            } + "\n")
        }
        attempt("notes") { File(directory, "readiness-$phase-state.txt").writeText(notes.toString()) }
    }
    private fun capture(name: String) {
        awaitBodyLayout()
        val image = compose.runOnUiThread {
            val view = compose.activity.findViewById<View>(android.R.id.content)
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val file = File("build/verification/$name").apply { parentFile?.mkdirs() }
        file.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    private fun targets() {
        listOf("首页", "关注", "我的").forEach { text ->
            compose.onNode(hasText(text) and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Selected))
                .assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
    }
    @Test fun actualRootSwitchTravelsAndJelliesWithoutMovingTouchTargets() {
        render(sourceReference = true)
        awaitBodyLayout()
        fun tab(label: String) = compose.onNode(hasText(label) and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Selected))
        fun position() = compose.onNodeWithTag("selected-pill", useUnmergedTree = true).fetchSemanticsNode().config[DecorativeJellyPosition]
        fun shape() = compose.onNodeWithTag("selected-pill", useUnmergedTree = true).fetchSemanticsNode().config[DecorativeJellyScale]
        val labels = listOf("首页", "关注", "我的")
        val targetsBefore = labels.map { tab(it).fetchSemanticsNode().boundsInRoot }
        assertEquals(0f, position(), .001f)
        assertEquals(1f, shape(), .001f)
        capture("native-root-jelly-000-synthetic.png")
        compose.mainClock.autoAdvance = false
        tab("我的").performClick()
        compose.mainClock.advanceTimeByFrame()
        tab("我的").assertIsSelected()
        compose.mainClock.advanceTimeBy(80)
        assertTrue("Actual root selection must travel rather than teleport", position() > 0f && position() < 2f)
        assertTrue("Actual root switch must have visible decorative stretch", shape() > 1f)
        capture("native-root-jelly-080-synthetic.png")
        compose.mainClock.advanceTimeBy(96)
        capture("native-root-jelly-176-synthetic.png")
        assertEquals(targetsBefore, labels.map { tab(it).fetchSemanticsNode().boundsInRoot })
        compose.mainClock.advanceTimeBy(240)
        assertEquals(2f, position(), .001f)
        assertEquals(1f, shape(), .001f)
        capture("native-root-jelly-settled-synthetic.png")
        tab("我的").performClick(); compose.mainClock.advanceTimeBy(80)
        assertEquals(2f, position(), .001f); assertEquals(1f, shape(), .001f)
        tab("首页").performClick(); compose.mainClock.advanceTimeBy(64)
        tab("关注").performClick(); compose.mainClock.advanceTimeBy(448)
        tab("关注").assertIsSelected()
        assertEquals(1f, position(), .001f); assertEquals(1f, shape(), .001f)
        assertEquals(targetsBefore, labels.map { tab(it).fetchSemanticsNode().boundsInRoot })
        compose.mainClock.autoAdvance = true
    }

    @Test fun actualRootHonorsAnimatorDurationOffWithoutUsingTouchExplorationAsProxy() {
        render(sourceReference = true)
        val resolver = compose.activity.contentResolver
        val previous = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        try {
            compose.runOnUiThread { Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f) }
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            compose.onNode(hasText("我的") and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Selected)).performClick()
            compose.mainClock.advanceTimeBy(32)
            val pill = compose.onNodeWithTag("selected-pill", useUnmergedTree = true).fetchSemanticsNode().config
            assertEquals(2f, pill[DecorativeJellyPosition], .001f)
            assertEquals(1f, pill[DecorativeJellyScale], .001f)
        } finally {
            compose.mainClock.autoAdvance = true
            compose.runOnUiThread { Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, previous) }
        }
    }

    @Test @Config(qualifiers = "w390dp-h850dp-320dpi")
    fun populatedHomeHasRealOfflineAvatarAndMediaPixels() {
        render(sourceReference = true, populated = true)
        val images = requireNotNull(fixtureImages)
        compose.waitUntil(15_000) { images.loadedUrls.containsAll(listOf(
            io.github.ktclat.c001apk.nativeapp.feature.feed.SyntheticFeedImageLoader.AVATAR,
            io.github.ktclat.c001apk.nativeapp.feature.feed.SyntheticFeedImageLoader.LANDSCAPE)) }
        capture("native-populated-home-portrait-synthetic.png")
        org.junit.Assert.assertTrue(images.unexpectedUrls.toString(), images.unexpectedUrls.isEmpty())
    }
    @Test @Config(qualifiers = "w390dp-h850dp-320dpi")
    fun sourceReferenceHomePortrait() { render(sourceReference = true); capture("native-source-home-portrait-synthetic.png") }
    @Test @Config(qualifiers = "w850dp-h390dp-land-320dpi")
    fun sourceReferenceHomeLandscape() { render(sourceReference = true); capture("native-source-home-landscape-synthetic.png") }
    @Test @Config(qualifiers = "w390dp-h850dp-320dpi")
    fun sourceReferenceHomeLargeFont() { render(1.6f, sourceReference = true); capture("native-source-home-large-font-synthetic.png") }
    @Test @Config(qualifiers = "w390dp-h850dp-320dpi")
    fun sourceReferenceDetailPortrait() { render(initialRoute = "feed:100", sourceReference = true); capture("native-source-detail-portrait-synthetic.png") }
    @Test @Config(qualifiers = "w850dp-h390dp-land-320dpi")
    fun sourceReferenceDetailLandscape() { render(initialRoute = "feed:100", sourceReference = true); capture("native-source-detail-landscape-synthetic.png") }
    @Test @Config(qualifiers = "w390dp-h850dp-320dpi")
    fun sourceReferenceDetailLargeFont() { render(1.6f, "feed:100", sourceReference = true); capture("native-source-detail-large-font-synthetic.png") }
    @Test @Config(qualifiers = "w390dp-h850dp-320dpi")
    fun sourceReferenceHomeDark() { render(sourceReference = true, dark = true); capture("native-source-home-dark-synthetic.png") }
    @Test fun portraitFullScreen() { render(); targets(); capture("native-home-portrait-synthetic.png") }
    @Test @Config(qualifiers = "w852dp-h393dp-land-420dpi")
    fun landscapeFullScreen() { render(); targets(); capture("native-home-landscape-synthetic.png") }
    @Test fun largeFontFullScreen() { render(1.6f); targets(); capture("native-home-large-font-synthetic.png") }
    private data class FeedViewportSnapshot(
        val visiblePostIds: List<String>,
        val visibleBodyText: List<Pair<String, String>>,
        val firstIndex: Int,
        val firstOffset: Int,
    )

    /** Preserve retained/offscreen nodes as evidence, but assert the visible restoration contract. */
    private fun feedViewportSnapshot(name: String, state: LazyListState): FeedViewportSnapshot {
        awaitBodyLayout()
        val directory = File("build/verification/feed-restoration").apply { mkdirs() }
        File(directory, "$name-tree.txt").writeText(compose.onRoot(useUnmergedTree = true).printToString())
        val props = androidx.compose.ui.semantics.SemanticsProperties
        fun tagged(pattern: Regex) = SemanticsMatcher("tag matching $pattern") {
            it.config.getOrElseNullable(props.TestTag) { null }?.matches(pattern) == true
        }
        val notes = StringBuilder()
        val visibleBodies = mutableListOf<Pair<String, String>>()
        compose.onAllNodes(tagged(Regex("feed-body:[0-9]+"))).fetchSemanticsNodes().forEach { node ->
            val tag = node.config[props.TestTag]
            val interaction = compose.onNodeWithTag(tag)
            val text = node.config.getOrElseNullable(props.Text) { null }.orEmpty().joinToString()
            val displayed = interaction.isDisplayed()
            notes.append("$tag displayed=$displayed clipped=${node.boundsInRoot} unclipped=${interaction.getUnclippedBoundsInRoot()} text=$text\n")
            // An unfinished visible body still fails. Hidden prefetch is explicitly recorded, not
            // silently removed from the evidence to make the equality assertion pass.
            if (text.contains("正在排版…")) {
                File(directory, "$name-bounds.txt").writeText(notes.toString())
                assertFalse("Pending body must be outside the visible viewport: $tag", displayed)
            }
            if (displayed) visibleBodies += tag to text
        }
        val visibleIds = compose.onAllNodes(tagged(Regex("feed:[0-9]+"))).fetchSemanticsNodes().mapNotNull { node ->
            val tag = node.config[props.TestTag]
            val interaction = compose.onNodeWithTag(tag)
            val displayed = interaction.isDisplayed()
            notes.append("$tag displayed=$displayed clipped=${node.boundsInRoot} unclipped=${interaction.getUnclippedBoundsInRoot()}\n")
            tag.takeIf { displayed }
        }
        val position = compose.runOnUiThread { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
        notes.append("position=$position visiblePosts=$visibleIds\n")
        File(directory, "$name-bounds.txt").writeText(notes.toString())
        val bitmap = compose.runOnUiThread {
            val view = compose.activity.findViewById<View>(android.R.id.content)
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue("Fixture must retain visible posts", visibleIds.isNotEmpty())
        assertTrue("Fixture must retain visible complete body text", visibleBodies.isNotEmpty())
        assertTrue("Every visible body must retain its complete merged text", visibleBodies.all { it.second.isNotBlank() })
        return FeedViewportSnapshot(visibleIds, visibleBodies, position.first, position.second)
    }

    @Test fun scrollHideAndReturnPreserveFeedWithoutReload() {
        val homeState = LazyListState()
        render(browseListStates = mapOf("home" to homeState))
        compose.onNodeWithTag("content-list").performTouchInput {
            swipe(Offset(centerX, height * .8f), Offset(centerX, height * .2f), 600)
        }
        compose.onNodeWithTag("floating-navigation").assertDoesNotExist()
        capture("native-home-dock-hidden-synthetic.png")
        compose.onNodeWithTag("content-list").performTouchInput {
            swipe(Offset(centerX, height * .25f), Offset(centerX, height * .5f), 600)
        }
        compose.onNodeWithTag("floating-navigation").assertIsDisplayed()
        val before = feedViewportSnapshot("before", homeState)
        assertTrue("Fixture must actually leave the initial scroll position", before.firstIndex > 0 || before.firstOffset > 0)
        compose.onNode(hasText("我的") and isNotSelected()).performClick()
        compose.onNode(hasText("首页") and isNotSelected()).performClick()
        compose.waitForIdle()
        assertEquals(1, homeCalls.get())
        val after = feedViewportSnapshot("after", homeState)
        assertEquals(before, after)
    }
    @Test fun searchHidesDockWithSyntheticImeInsetsAndRestoresAfterDismissal() {
        render()
        compose.onNodeWithContentDescription("搜索").performClick()
        compose.onNodeWithText("搜索动态").performTextInput("合成")
        compose.runOnUiThread {
            Settings.Global.putFloat(compose.activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
            compose.activity.contentResolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), null)
        }
        insets(280)
        compose.onNodeWithTag("floating-navigation").assertDoesNotExist()
        capture("native-search-ime-synthetic.png")
        insets(0)
        compose.onNodeWithTag("floating-navigation").assertIsDisplayed()
        targets()
    }

    @Test fun detailHidesDockAndBackRestoresRootWithoutReload() {
        render()
        compose.onNodeWithTag("feed-body:1000").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("回复动态").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("floating-navigation").assertDoesNotExist()
        compose.onNodeWithText("查看动态 · 0 评论").assertDoesNotExist()
        capture("native-detail-synthetic.png")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("floating-navigation").assertIsDisplayed()
        assertEquals(1, homeCalls.get())
    }
    @Test fun reselectCurrentRootScrollsToTopWithoutReload() {
        render()
        compose.onNodeWithTag("content-list").performScrollToIndex(6)
        compose.onNode(hasText("首页") and isSelected()).performClick()
        compose.onNodeWithText("合成动态 0").assertIsDisplayed()
        assertEquals(1, homeCalls.get())
    }
    @Test fun editorWithSyntheticKeyboardHasNoRootDock() {
        render(initialRoute = "compose")
        compose.onNodeWithText("内容").performTextInput("合成草稿，没有发送")
        insets(280)
        compose.onNodeWithTag("floating-navigation").assertDoesNotExist()
        compose.onNodeWithText("确认发送").assertIsDisplayed()
        capture("native-editor-ime-synthetic.png")
    }
    @Test @Config(qualifiers = "w852dp-h393dp-land-420dpi")
    fun landscapeEditorHasNoRootDock() {
        render(initialRoute = "compose")
        compose.onNodeWithTag("floating-navigation").assertDoesNotExist()
        compose.onNodeWithText("确认发送").performScrollTo().assertIsDisplayed()
        capture("native-editor-landscape-synthetic.png")
    }
    @Test @Config(qualifiers = "w852dp-h393dp-land-420dpi")
    fun landscapeKeyboardKeepsEditorControlsReachableByScrolling() {
        render(initialRoute = "compose")
        insets(180)
        compose.onNodeWithText("内容").performScrollTo().performTextInput("横屏合成草稿，不发送")
        compose.onNodeWithText("确认发送").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("floating-navigation").assertDoesNotExist()
        capture("native-editor-landscape-ime-synthetic.png")
    }
    @Test fun replyEntryRequiresLoginWithoutSendingAnything() {
        render()
        compose.onNodeWithTag("feed-body:1000").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("回复动态").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("回复动态").performClick()
        compose.onNodeWithText("登录后回复").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("回复动态").assertExists()
    }
    @Test fun lastContentActionStaysAboveFloatingOverlay() {
        render()
        compose.onNodeWithTag("content-list").performScrollToIndex(29)
        awaitBodyLayout()
        compose.onNodeWithTag("content-list").performScrollToIndex(29)
        val action = compose.onNodeWithTag("feed-comments:1029").fetchSemanticsNode().boundsInRoot
        val dock = compose.onNodeWithTag("floating-navigation").fetchSemanticsNode().boundsInRoot
        capture("native-home-tail-clearance-synthetic.png")
        org.junit.Assert.assertTrue("Last action $action must scroll clear of dock $dock", action.bottom <= dock.top)
    }
    @Test fun actualSettingsRootShowsCategoriesAndReturnsWithinEachSection() {
        render(initialRoute = "settings")
        val categories = listOf("外观与字体", "关注与内容", "浏览与隐私", "存储与更新", "协议与设备")
        capture("native-settings-category-index-synthetic.png")
        categories.forEachIndexed { index, label ->
            compose.onNode(hasText(label) and hasClickAction()).performScrollTo().performClick()
            compose.onNodeWithTag("floating-navigation").assertDoesNotExist()
            capture("native-settings-category-$index-synthetic.png")
            compose.onNodeWithContentDescription("返回").performClick()
            categories.forEach { compose.onNode(hasText(it) and hasClickAction()).assertExists() }
        }
    }
    @Test fun actualAppearanceLastChoiceRemainsReachableAtLargeFont() {
        render(fontScale = 1.6f, initialRoute = "settings")
        compose.onNode(hasText("外观与字体") and hasClickAction()).performClick()
        val lastMatcher = hasText("悬浮导航栏") and hasClickAction()
        val list = compose.onNode(hasScrollAction())
        list.performScrollToNode(lastMatcher)
        val last = compose.onNode(lastMatcher)
        last.assertIsDisplayed()
        val target = last.getUnclippedBoundsInRoot()
        val viewport = list.getUnclippedBoundsInRoot()
        assertTrue("The complete final choice must remain within the scrolling viewport", target.top >= viewport.top && target.bottom <= viewport.bottom)
        assertTrue("Do not shrink the final action below 48dp", (target.bottom - target.top) >= 48.dp)
        compose.onNodeWithTag("floating-navigation").assertDoesNotExist()
        capture("native-settings-appearance-large-font-tail-synthetic.png")
    }
    @Test @Config(qualifiers = "w900dp-h600dp-160dpi")
    fun actualWideSettingsKeepsCategoryAndDetailPanesReachable() {
        render(initialRoute = "settings")
        compose.onNode(hasText("浏览与隐私") and hasClickAction()).performClick()
        compose.onNodeWithText("记录浏览历史").assertIsDisplayed()
        compose.onNode(hasText("外观与字体") and hasClickAction()).assertIsDisplayed()
        capture("native-settings-wide-two-pane-synthetic.png")
    }
    @Test fun actualMyRootUsesSourceShortcutGroupsAndToolbarSettings() {
        render(sourceReference = true)
        compose.onNode(hasText("我的") and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Selected)).performClick()
        compose.onNodeWithText("点击登录").assertIsDisplayed()
        compose.onNodeWithText("本地收藏").assertExists()
        compose.onNodeWithText("我的收藏").assertExists()
        capture("native-my-guest-source-layout-synthetic.png")
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNode(hasText("外观与字体") and hasClickAction()).assertIsDisplayed()
        compose.onNodeWithTag("floating-navigation").assertDoesNotExist()
    }
    @Test fun settingsHasNoRootDock() {
        render(initialRoute = "settings")
        compose.onNodeWithTag("floating-navigation").assertDoesNotExist()
        capture("native-settings-synthetic.png")
    }
}
