// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewConfiguration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.size
import androidx.core.graphics.Insets
import androidx.core.view.DisplayCutoutCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import io.github.ktclat.c001apk.nativeapp.AppContainer
import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.data.*
import io.github.ktclat.c001apk.nativeapp.domain.*
import io.github.ktclat.c001apk.nativeapp.local.*
import io.github.ktclat.c001apk.nativeapp.platform.installedLauncherApps
import io.github.ktclat.c001apk.nativeapp.settings.*
import io.github.ktclat.c001apk.nativeapp.social.SocialRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.Cookie
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.Assert.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Real NativeApp/Scaffold/Compose rendering, not a duplicate shell or HTML mock.
 * Everything it can read is synthetic: in-memory account/local stores, a repository proxy,
 * an intercepted (never proceeded) local-follow HTTP response, and Robolectric launcher packages.
 * 400 x 890 dp at density 3 is a hypothetical Xiaomi-15-like aspect scenario, NOT a device claim.
 *
 * Requires the production tags documented in README.md. Do not loosen assertions to make a
 * broken layout pass. Failure diagnostics draw the actual Android content View, before any repair.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h890dp-480dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdaptiveDockRenderingTest {
    @get:Rule(order = 0) val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule(order = 1) val testName = TestName()
    @get:Rule(order = 2) val diagnostics = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            // Observation only: no Snapshot.sendApplyNotifications, state warming, retries or rescue.
            runCatching { capture("${description.methodName}-FAILED") }
            runCatching {
                output("${description.methodName}-FAILED.txt").writeText(
                    "${error.javaClass.name}: ${error.message}\n" +
                        "homeCalls=${homeCalls.get()}, followingCalls=${followingCalls.get()}\n" +
                        "localRequests=$localRequests\nunexpected=$unexpected\n" +
                        compose.onRoot(useUnmergedTree = true).printToString()
                )
            }
        }
    }

    private data class InsetsCase(
        val navigation: Int = 24,
        val status: Int = 24,
        val cutoutLeft: Int = 0,
        val cutoutRight: Int = 0,
        val gestureLeft: Int = 0,
        val gestureRight: Int = 0,
        val mandatoryBottom: Int = navigation,
        val tappableBottom: Int = navigation,
        val ime: Int = 0,
    )
    private data class SurfaceCase(
        val name: String,
        val listTag: String,
        val lastIndex: Int?,
        val target: SemanticsMatcher,
        val mustScroll: Boolean = true,
        val authorTarget: SemanticsMatcher? = null,
        val browseKey: String? = null,
    )
    private class MemoryStore(initial: LocalState) : JsonStateStore<LocalState> {
        override val data = MutableStateFlow(initial)
        override suspend fun update(transform: (LocalState) -> LocalState) {
            data.value = LocalRepository.validateState(transform(data.value))
        }
    }

    private val clients = mutableListOf<OkHttpClient>()
    private val homeCalls = AtomicInteger()
    private val followingCalls = AtomicInteger()
    private val unexpected = CopyOnWriteArrayList<String>()
    private val localRequests = CopyOnWriteArrayList<String>()
    private val uiSettings = mutableStateOf(AppSettings(floatingNavigation = true, dynamicColor = false))
    private val myScrollState = ScrollState(0)
    private val browseListStates = listOf("home", "hot", "topics", "products", "pictures", "following:${uiSettings.value.followType}", "apps", "root-local-follow").associateWith { LazyListState() }
    private var recordScroll = false
    private data class ScrollSample(val consumedY: Float, val source: NestedScrollSource)
    private val scrollSamples = CopyOnWriteArrayList<ScrollSample>()
    private val scrollObserver = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (recordScroll) scrollSamples += ScrollSample(consumed.y, source)
            return Offset.Zero // Observe actual NativeApp scroll, never consume or synthesize it.
        }
    }
    private lateinit var accounts: AccountRuntime
    private lateinit var model: BrowseViewModel
    private var currentInsets = InsetsCase()
    private var currentFont = 1f
    private var rowCount = LONG_ROWS
    private var rendered = false
    private val pixelDensity get() = compose.activity.resources.displayMetrics.density
    private val dock get() = compose.onNodeWithTag("floating-navigation")

    @After fun closeClientsAndAssertIsolation() {
        clients.forEach {
            it.dispatcher.cancelAll()
            it.connectionPool.evictAll()
            it.dispatcher.executorService.shutdownNow()
        }
        assertTrue("Fixture attempted an unapproved request: $unexpected", unexpected.isEmpty())
    }

    // Full 4-window x 2-font x 2-navigation-inset matrix, each exercising all five root surfaces.
    @Test @Config(qualifiers = "w400dp-h890dp-480dpi")
    fun aspect400x890_font100_nav24() = rootMatrix(1f, 24)
    @Test @Config(qualifiers = "w400dp-h890dp-480dpi")
    fun aspect400x890_font100_nav48() = rootMatrix(1f, 48)
    @Test @Config(qualifiers = "w400dp-h890dp-480dpi")
    fun aspect400x890_font160_nav24() = rootMatrix(1.6f, 24)
    @Test @Config(qualifiers = "w400dp-h890dp-480dpi")
    fun aspect400x890_font160_nav48() = rootMatrix(1.6f, 48)
    @Test @Config(qualifiers = "w320dp-h640dp-320dpi")
    fun compact320x640_font100_nav24() = rootMatrix(1f, 24)
    @Test @Config(qualifiers = "w320dp-h640dp-320dpi")
    fun compact320x640_font100_nav48() = rootMatrix(1f, 48)
    @Test @Config(qualifiers = "w320dp-h640dp-320dpi")
    fun compact320x640_font160_nav24() = rootMatrix(1.6f, 24)
    @Test @Config(qualifiers = "w320dp-h640dp-320dpi")
    fun compact320x640_font160_nav48() = rootMatrix(1.6f, 48)
    @Test @Config(qualifiers = "w360dp-h800dp-320dpi")
    fun medium360x800_font100_nav24() = rootMatrix(1f, 24)
    @Test @Config(qualifiers = "w360dp-h800dp-320dpi")
    fun medium360x800_font100_nav48() = rootMatrix(1f, 48)
    @Test @Config(qualifiers = "w360dp-h800dp-320dpi")
    fun medium360x800_font160_nav24() = rootMatrix(1.6f, 24)
    @Test @Config(qualifiers = "w360dp-h800dp-320dpi")
    fun medium360x800_font160_nav48() = rootMatrix(1.6f, 48)
    @Test @Config(qualifiers = "w890dp-h400dp-land-480dpi")
    fun landscape890x400_font100_nav24() = rootMatrix(1f, 24)
    @Test @Config(qualifiers = "w890dp-h400dp-land-480dpi")
    fun landscape890x400_font100_nav48() = rootMatrix(1f, 48)
    @Test @Config(qualifiers = "w890dp-h400dp-land-480dpi")
    fun landscape890x400_font160_nav24() = rootMatrix(1.6f, 24)
    @Test @Config(qualifiers = "w890dp-h400dp-land-480dpi")
    fun landscape890x400_font160_nav48() = rootMatrix(1.6f, 48)

    @Test @Config(qualifiers = "w320dp-h640dp-320dpi")
    fun compact320x640_font200_nav48_keepsEveryRootActionReachable() = rootMatrix(2f, 48)

    private fun rootMatrix(font: Float, navigation: Int) {
        render(font, InsetsCase(navigation = navigation))
        assertConfiguredWindow()
        exerciseSurface(homeSurface())
        openApps()
        exerciseSurface(appsSurface())
        selectRoot("关注")
        awaitText("合成在线关注 0")
        exerciseSurface(onlineSurface())
        showLocalFollow()
        awaitText("合成本地关注 0")
        exerciseSurface(localSurface())
        selectRoot("我的")
        exerciseSurface(mySurface())
        assertEquals("Root navigation must not refresh Home", 1, homeCalls.get())
        assertEquals("Online follow fixture must be used once", 1, followingCalls.get())
        assertTrue("Local Follow must use its separate synthetic repository", localRequests.isNotEmpty())
        assertRootSelection("我的")
    }

    @Test @Config(qualifiers = "w890dp-h400dp-land-480dpi")
    fun cutoutAndGesturesKeepBottomDockAndHomeToolbarWithinSafeEdges() {
        render(1.6f, InsetsCase(navigation = 48, cutoutLeft = 36, cutoutRight = 12,
            gestureLeft = 52, gestureRight = 24, mandatoryBottom = 64, tappableBottom = 56))
        assertDockGeometry()
        assertHomeToolbarSafe()
        exerciseSurface(homeSurface())
        openApps(); exerciseSurface(appsSurface())
        selectRoot("关注"); awaitText("合成在线关注 0"); exerciseSurface(onlineSurface())
        showLocalFollow(); awaitText("合成本地关注 0"); exerciseSurface(localSurface())
        selectRoot("我的"); exerciseSurface(mySurface())
    }

    @Test fun insetChangesMoveDockByOnlyTheAdditionalInset() {
        render(1f, InsetsCase(navigation = 24))
        val at24 = dock.getUnclippedBoundsInRoot()
        injectInsets(InsetsCase(navigation = 48))
        assertDockGeometry()
        val at48 = dock.getUnclippedBoundsInRoot()
        assertEquals("24 dp extra navigation inset must move dock exactly 24 dp, not 48 dp",
            24f, (at24.bottom - at48.bottom).value, 1f)
        injectInsets(InsetsCase(navigation = 48, mandatoryBottom = 64, tappableBottom = 56))
        assertDockGeometry()
        val at64 = dock.getUnclippedBoundsInRoot()
        assertEquals("Only gesture delta beyond safeDrawing may be added", 16f,
            (at48.bottom - at64.bottom).value, 1f)
        injectInsets(InsetsCase(navigation = 24))
        assertDockGeometry()
        assertRectEquals("Insets must be reversible", at24, dock.getUnclippedBoundsInRoot())
    }

    @Test @Config(qualifiers = "w320dp-h640dp-320dpi")
    fun imeHidesAndRestoresDockOnEveryRootWithoutReloadOrSelectionLoss() {
        render(1.6f, InsetsCase(navigation = 48))
        checkImeCycle("首页", "home")
        openApps(); checkImeCycle("首页", "apps")
        selectRoot("关注"); awaitText("合成在线关注 0"); checkImeCycle("关注", "online-follow")
        showLocalFollow(); awaitText("合成本地关注 0"); checkImeCycle("关注", "local-follow")
        selectRoot("我的"); checkImeCycle("我的", "my")
        assertEquals(1, homeCalls.get())
        assertEquals(1, followingCalls.get())
    }

    private fun checkImeCycle(selected: String, surface: String) {
        val before = dock.getUnclippedBoundsInRoot()
        injectInsets(currentInsets.copy(ime = 280))
        dock.assertDoesNotExist()
        compose.onNodeWithTag("navigation-rail").assertDoesNotExist()
        capture("${testName.methodName}-$surface-ime")
        injectInsets(currentInsets.copy(ime = 0))
        assertDockGeometry()
        assertRootSelection(selected)
        assertRectEquals("Keyboard dismissal must not retain an extra bottom inset", before, dock.getUnclippedBoundsInRoot())
    }

    @Test @Config(qualifiers = "w400dp-h890dp-480dpi")
    fun emptyHomeAppsOnlineAndLocalFollowDoNotHideOnUnconsumedScroll() = emptyRoots(1f)
    @Test @Config(qualifiers = "w320dp-h640dp-320dpi")
    fun emptyRootsStaySafeAtLargeFontAndSmallWidth() = emptyRoots(1.6f)
    private fun emptyRoots(font: Float) {
        render(font, InsetsCase(navigation = 48), count = 0, localFollow = false)
        awaitText("暂无内容")
        assertUnscrollableDock("content-list")
        openApps(); awaitText("暂无系统允许查询的非系统应用")
        assertUnscrollableDock("installed-apps-list")
        assertAboveDock(compose.onNodeWithText("刷新"))
        selectRoot("关注"); awaitText("暂无内容")
        assertUnscrollableDock("content-list")
        showLocalFollow()
        awaitText("尚未关注用户。添加用户后，将合并他们的公开动态。")
        compose.onNodeWithText("管理本地关注（0）").assertIsEnabled()
        assertAboveDock(compose.onNodeWithText("管理本地关注（0）"))
        val note = compose.onNodeWithText("尚未关注用户。添加用户后，将合并他们的公开动态。")
        note.performTouchInput { swipeUp() }
        assertDockGeometry()
        assertAboveDock(note, requireClick = false)
        assertTrue("No stored follows must mean no local repository request", localRequests.isEmpty())
        selectRoot("我的"); exerciseSurface(mySurface())
    }

    @Test @Config(qualifiers = "w400dp-h890dp-480dpi")
    fun oneItemRootsKeepTheirFinalActionAboveVisibleDock() {
        render(1f, InsetsCase(navigation = 48), count = 1)
        assertUnscrollableDock("content-list")
        assertAboveDock(compose.onNodeWithTag("feed-comments:1000"))
        openApps(); assertUnscrollableDock("installed-apps-list")
        assertAboveDock(compose.onNodeWithTag("installed-app:synthetic.dock.app00"))
        selectRoot("关注"); awaitText("合成在线关注 0")
        assertUnscrollableDock("content-list")
        assertAboveDock(compose.onNodeWithTag("feed-comments:2000"))
        showLocalFollow(); awaitText("合成本地关注 0")
        assertUnscrollableDock("local-follow-list")
        assertAboveDock(compose.onNodeWithTag("local-follow-card:7000"))
        selectRoot("我的"); exerciseSurface(mySurface())
    }

    @Test fun selectedRootSemanticsAndHitboxesRemainStableDuringJellyPulse() {
        render()
        val before = rootBounds()
        val beforeTouch = listOf("首页", "关注", "我的").associateWith { rootTab(it).fetchSemanticsNode().touchBoundsInRoot }
        compose.mainClock.autoAdvance = false
        try {
            rootTab("我的").performClick()
            // With automatic time disabled, commit the new root in one normal composition frame
            // before sampling its selection and animation. No selection can change before a frame.
            compose.mainClock.advanceTimeByFrame()
            listOf(0L, 90L, 70L, 80L).forEach { elapsed ->
                if (elapsed > 0) compose.mainClock.advanceTimeBy(elapsed)
                compose.waitForIdle()
                assertRootSelection("我的")
                rootBounds().forEach { (label, rect) ->
                    assertRectEquals("$label visual hitbox must not scale with decorative pulse", before.getValue(label), rect)
                    assertEquals("$label actual touch bounds must not scale with decorative pulse",
                        beforeTouch.getValue(label), rootTab(label).fetchSemanticsNode().touchBoundsInRoot)
                }
            }
        } finally { compose.mainClock.autoAdvance = true }
        selectRoot("关注"); awaitText("合成在线关注 0"); assertRootSelection("关注")
        selectRoot("首页"); assertRootSelection("首页")
        assertEquals(1, homeCalls.get())
    }

    @Test fun rootAndHomeTabChangesDiscardPartialHideAccumulator() {
        render()
        assertPartialDragRemainsVisible("feed-body:1000")
        selectRoot("我的"); selectRoot("首页")
        assertPartialDragRemainsVisible("feed-body:1000")
        // Switching a Home subpage is a separate page identity even when root stays Home.
        compose.onNode(hasText("热门") and isNotSelected()).performClick()
        awaitText("合成热门 0")
        assertPartialDragRemainsVisible("feed-body:3000")
        compose.onNode(hasText("动态") and isNotSelected()).performClick()
        assertPartialDragRemainsVisible("feed-body:1000")
        assertEquals("Returning to an existing page must preserve its loaded state", 1, homeCalls.get())
    }

    @Test fun programmaticScrollAndReselectNeverStartAUserFling() {
        render()
        val originalViewport = compose.onNodeWithTag("content-list").getUnclippedBoundsInRoot()
        compose.onNodeWithTag("content-list").performScrollToIndex(6)
        assertDockGeometry()
        rootTab("首页").performClick()
        awaitText("合成首页 0")
        compose.onNodeWithTag("feed-body:1000").assertIsDisplayed()
        assertDockGeometry()
        assertEquals(1, homeCalls.get())
        drag("content-list", forward = true)
        dock.assertDoesNotExist()
        compose.onNodeWithTag("content-list").performScrollToIndex(0)
        dock.assertDoesNotExist() // programmatic upward movement may not reveal a hidden dock either.
        // A genuine top-edge pull explicitly recovers the dock; programmatic top alone did not.
        drag("content-list", forward = false)
        assertDockGeometry()
        drag("content-list", forward = true)
        dock.assertDoesNotExist()
        compose.onNodeWithTag("content-list").performScrollToIndex(6)
        dock.assertDoesNotExist()
        drag("content-list", forward = false)
        assertDockGeometry()
        assertRectEquals("Recovery must not alter the root viewport", originalViewport, compose.onNodeWithTag("content-list").getUnclippedBoundsInRoot())
        assertRootSelection("首页")
        assertEquals(1, homeCalls.get())
    }

    @Test fun genuineUserFlingContinuesTheGesturePastHideThreshold() {
        render()
        val list = compose.onNodeWithTag("content-list")
        val before = compose.onNodeWithTag("feed-body:1000").getUnclippedBoundsInRoot().top.value
        val slop = ViewConfiguration.get(compose.activity).scaledTouchSlop.toFloat()
        compose.runOnIdle { scrollSamples.clear(); recordScroll = true }
        compose.mainClock.autoAdvance = false
        try {
            // One fast user drag consumes about 24 dp, below the 28 dp threshold. Keep pointer down
            // while checking; no fling has begun yet. Release with a recent nonzero velocity.
            list.performTouchInput {
                down(Offset(centerX, height * .5f))
                moveBy(Offset(0f, -slop), delayMillis = 16)
                repeat(3) { moveBy(Offset(0f, -8f * pixelDensity), delayMillis = 8) }
            }
            compose.mainClock.advanceTimeByFrame()
            val afterDrag = compose.onNodeWithTag("feed-body:1000").getUnclippedBoundsInRoot().top.value
            assertTrue("Pre-fling consumed movement must be positive and below hide threshold: ${before - afterDrag}",
                before - afterDrag in 12f..26f)
            dock.assertIsDisplayed()
            val consumedBeforeRelease = scrollSamples.sumOf { max(0f, -it.consumedY).toDouble() }.toFloat() / pixelDensity
            assertTrue("Observed finger consumption must still be below 28dp: $consumedBeforeRelease", consumedBeforeRelease in 12f..26f)
            val beforeFlingRange = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            list.performTouchInput { up() }
            compose.mainClock.advanceTimeBy(300)
            compose.waitForIdle()
            val afterFlingRange = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            val totalConsumed = scrollSamples.sumOf { max(0f, -it.consumedY).toDouble() }.toFloat() / pixelDensity
            val flingConsumed = scrollSamples.filter { it.source == NestedScrollSource.SideEffect }
                .sumOf { max(0f, -it.consumedY).toDouble() }.toFloat() / pixelDensity
            assertTrue("A real fling must continue content beyond the finger's movement", afterFlingRange > beforeFlingRange)
            assertTrue("Post-release SideEffect consumption must be real: $flingConsumed dp", flingConsumed > 0f)
            assertTrue("Actual total consumption must cross 28dp before expecting hide: $totalConsumed dp", totalConsumed > 28f)
            dock.assertDoesNotExist()
            // The first feed may legitimately be recycled during the fling; do not require it in semantics.
        } finally {
            // Any assertion failure must not leave an active pointer or a paused test clock.
            runCatching { list.performTouchInput { cancel() } }
            recordScroll = false
            compose.mainClock.autoAdvance = true
        }
    }

    private fun exerciseSurface(surface: SurfaceCase) {
        val list = compose.onNodeWithTag(surface.listTag).assertExists()
        assertDockGeometry()
        val viewport = list.getUnclippedBoundsInRoot()
        scrollEnd(surface)
        val endTarget = compose.onNode(surface.target)
        assertFinalTarget(surface)
        val visibleTailPosition = endTarget.getUnclippedBoundsInRoot().bottom.value
        capture("${testName.methodName}-${surface.name}-end-visible")
        scrollTop(surface)
        assertDockGeometry() // True programmatic scrolling must not change visibility.
        val range = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        val scrollable = range.maxValue() > 0f
        if (surface.mustScroll) assertTrue("${surface.name}: fixture must actually scroll", scrollable)
        val belowHideThreshold = surface.lastIndex == null && range.maxValue() / pixelDensity < 28f
        if (!scrollable || belowHideThreshold) {
            // My may fit, or have less than 28 dp of total overflow. It cannot consume the hide
            // threshold, so keeping navigation visible is correct. End-scroll safety was checked above.
            drag(surface.listTag, forward = true)
            assertDockGeometry()
            scrollEnd(surface)
            assertFinalTarget(surface)
            assertAuthorReachable(surface)
            return
        }
        drag(surface.listTag, forward = true)
        dock.assertDoesNotExist()
        assertRectEquals("${surface.name}: hiding overlay must not resize content viewport", viewport, list.getUnclippedBoundsInRoot())
        scrollEnd(surface)
        dock.assertDoesNotExist()
        val hiddenTailPosition = compose.onNode(surface.target).getUnclippedBoundsInRoot().bottom.value
        assertEquals("${surface.name}: tail space must not collapse while dock is hidden", visibleTailPosition, hiddenTailPosition, 1f)
        drag(surface.listTag, forward = false)
        assertDockGeometry()
        assertRectEquals("${surface.name}: reveal must not resize content viewport", viewport, list.getUnclippedBoundsInRoot())
        scrollEnd(surface)
        assertFinalTarget(surface)
        assertEquals("${surface.name}: same end position after reveal", visibleTailPosition,
            compose.onNode(surface.target).getUnclippedBoundsInRoot().bottom.value, 1f)
        // Verify a separate real action only AFTER all raw end-position / tail-stability checks.
        assertAuthorReachable(surface)
    }

    private fun homeSurface() = SurfaceCase("home", "content-list", rowCount - 1, hasTestTag("feed-comments:${1000 + rowCount - 1}"), browseKey = "home")
    private fun onlineSurface() = SurfaceCase("online-follow", "content-list", rowCount - 1, hasTestTag("feed-comments:${2000 + rowCount - 1}"), browseKey = "following:${uiSettings.value.followType}")
    private fun appsSurface() = SurfaceCase("apps", "installed-apps-list", rowCount + 1,
        hasTestTag("installed-app:synthetic.dock.app${(rowCount - 1).toString().padStart(2, '0')}"), browseKey = "apps")
    private fun localSurface() = SurfaceCase("local-follow", "local-follow-list", rowCount + 1,
        hasTestTag("local-follow-card:${7000 + rowCount - 1}"),
        authorTarget = hasText("合成本地作者") and hasClickAction() and
            hasAnyAncestor(hasTestTag("local-follow-card:${7000 + rowCount - 1}")), browseKey = "root-local-follow") // controls + rows; rowCount + 1 is terminal status.

    private fun assertFinalTarget(surface: SurfaceCase) {
        val target = compose.onNode(surface.target)
        if (surface.authorTarget == null) { assertAboveDock(target); return }
        // A long Card can be taller than the viewport left above a floating dock. At absolute end,
        // require its bottom to clear the dock AND a genuine 48dp-high clickable region above it.
        // Separately check the complete author button; never substitute a center-point check.
        target.assertIsDisplayed().assertHasClickAction()
        val bounds = target.getUnclippedBoundsInRoot()
        val clipped = target.fetchSemanticsNode().boundsInRoot
        val dockBounds = dock.getUnclippedBoundsInRoot()
        assertTrue("Final local card bottom must clear the visible dock", bounds.bottom.value <= dockBounds.top.value - 8f + 1f)
        val visibleTop = max(bounds.top.value, clipped.top / pixelDensity)
        val visibleBottom = min(clipped.bottom / pixelDensity, dockBounds.top.value - 8f)
        assertTrue("Final local card must expose at least 48dp of its clickable region above dock: ${visibleBottom - visibleTop}",
            visibleBottom - visibleTop >= 48f - 1f)
        assertTrue("Final local card clickable region must be at least 48dp wide", clipped.width / pixelDensity >= 48f - 1f)
    }
    private fun assertAuthorReachable(surface: SurfaceCase) {
        surface.authorTarget?.let { matcher ->
            val author = compose.onNode(matcher)
            author.performScrollTo()
            compose.waitForIdle()
            assertDockGeometry()
            assertAboveDock(author)
            capture("${testName.methodName}-${surface.name}-author-reachable")
        }
    }
    private fun mySurface() = SurfaceCase("my", "my-root-list", null, hasText("私信与信息") and hasClickAction(), mustScroll = false)

    private fun scrollEnd(surface: SurfaceCase) {
        val list = compose.onNodeWithTag(surface.listTag)
        if (surface.lastIndex != null) {
            list.performScrollToIndex(surface.lastIndex)
            if (surface.browseKey != null) compose.runOnIdle {
                // ScrollToIndex positions the last item, not necessarily its trailing padding.
                // Move the real state programmatically; do not use accessibility UserInput.
                val browseListState = browseListStates.getValue(requireNotNull(surface.browseKey))
                runBlocking {
                    browseListState.scrollBy((browseListState.layoutInfo.viewportSize.height +
                        browseListState.layoutInfo.afterContentPadding).toFloat())
                }
            }
        }
        else compose.runOnIdle { runBlocking { myScrollState.scrollTo(myScrollState.maxValue) } }
        // SemanticsActions.ScrollBy is accessibility UserInput in this Compose version, not a
        // programmatic scroll. Use the actual hoisted ScrollState for the non-lazy My column.
        compose.waitForIdle()
        val range = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals("${surface.name}: must reach the actual end, not just show a clipped last row",
            range.maxValue(), range.value(), .01f)
    }
    private fun scrollTop(surface: SurfaceCase) {
        val list = compose.onNodeWithTag(surface.listTag)
        if (surface.lastIndex != null) list.performScrollToIndex(0)
        else compose.runOnIdle { runBlocking { myScrollState.scrollTo(0) } }
        compose.waitForIdle()
        val range = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals("${surface.name}: must start at top", 0f, range.value(), .01f)
    }
    private fun drag(tag: String, forward: Boolean) {
        compose.onNodeWithTag(tag).performTouchInput {
            // Stay above overlay even in the 400-dp-high landscape viewport.
            val from = if (forward) .60f else .20f
            val to = if (forward) .20f else .60f
            swipe(Offset(centerX, height * from), Offset(centerX, height * to), durationMillis = 600)
        }
        compose.waitForIdle()
    }
    private fun assertPartialDragRemainsVisible(firstBodyTag: String) {
        val before = compose.onNodeWithTag(firstBodyTag).getUnclippedBoundsInRoot().top.value
        val slop = ViewConfiguration.get(compose.activity).scaledTouchSlop.toFloat()
        compose.onNodeWithTag("content-list").performTouchInput {
            down(Offset(centerX, height * .40f))
            moveBy(Offset(0f, -(slop + 20f * pixelDensity)), delayMillis = 80)
            advanceEventTime(250) // intentional stationary pause: no released-gesture velocity.
            moveBy(Offset.Zero, delayMillis = 16)
            up()
        }
        compose.waitForIdle()
        val after = compose.onNodeWithTag(firstBodyTag).getUnclippedBoundsInRoot().top.value
        assertTrue("Synthetic partial gesture must consume 14–26 dp, not zero or the hide threshold: ${before - after}",
            before - after in 14f..26f)
        assertDockGeometry()
    }
    private fun assertUnscrollableDock(tag: String) {
        val range = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals("Short/empty fixture must really be unscrollable", 0f, range.maxValue(), .01f)
        drag(tag, true); assertDockGeometry()
        drag(tag, false); assertDockGeometry()
    }

    private fun rootTab(label: String) = compose.onNode(hasText(label) and
        SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected) and hasAnyAncestor(hasTestTag("floating-navigation")))
    private fun selectRoot(label: String) {
        rootTab(label).performClick()
        compose.waitForIdle()
        assertRootSelection(label)
    }
    private fun assertRootSelection(selected: String) {
        listOf("首页", "关注", "我的").forEach { label ->
            rootTab(label).assertIsDisplayed().assertHasClickAction()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, label == selected))
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
        compose.onAllNodes(isSelected() and hasAnyAncestor(hasTestTag("floating-navigation"))).assertCountEquals(1)
        assertNeighboringTargetsDoNotOverlap(listOf("首页", "关注", "我的").map(::rootTab))
    }
    private fun rootBounds() = listOf("首页", "关注", "我的").associateWith { rootTab(it).getUnclippedBoundsInRoot() }
    private fun assertDockGeometry() {
        dock.assertIsDisplayed()
        compose.onNodeWithTag("navigation-rail").assertDoesNotExist()
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val rect = dock.getUnclippedBoundsInRoot()
        val compactHeight = compose.activity.resources.configuration.screenHeightDp < 600
        val gap = if (compactHeight) 8f else 12f
        val effectiveBottom = max(currentInsets.navigation, max(currentInsets.mandatoryBottom, currentInsets.tappableBottom))
        assertEquals("Dock bottom must consume safe + extra gesture inset exactly once", root.bottom.value - effectiveBottom - gap,
            rect.bottom.value, 1f)
        assertTrue("Floating mode must be a horizontal bottom overlay", rect.size.width > rect.size.height * 2)
        assertTrue("Left cutout/gesture safe edge", rect.left.value >= root.left.value + max(currentInsets.cutoutLeft, currentInsets.gestureLeft) + 8f - 1f)
        assertTrue("Right cutout/gesture safe edge", rect.right.value <= root.right.value - max(currentInsets.cutoutRight, currentInsets.gestureRight) - 8f + 1f)
        val expectedHeight = when {
            currentFont >= 1.8f -> 72f
            currentFont >= 1.3f -> 66f
            else -> 62f
        }
        assertEquals("Dock adapts label height at $currentFont font scale", expectedHeight, rect.size.height.value, 1f)
        val rootTargets = listOf("首页", "关注", "我的").map(::rootTab)
        rootTargets.forEach {
            it.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            assertTouchTarget(it, respectGestureInsets = true)
        }
        assertNeighboringTargetsDoNotOverlap(rootTargets)
    }
    private fun assertHomeToolbarSafe() {
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val tab = compose.onNode(hasText("动态") and isSelected()).getUnclippedBoundsInRoot()
        // The first tab, not just the selected second tab, must start after the cutout.
        val applications = compose.onNode(hasText("应用") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
            .assertIsDisplayed().assertHasClickAction().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        val applicationsBounds = applications.getUnclippedBoundsInRoot()
        val applicationsClipped = applications.fetchSemanticsNode().boundsInRoot
        val search = compose.onNodeWithContentDescription("搜索").getUnclippedBoundsInRoot()
        assertTrue("First Applications tab must clear left display cutout", applicationsBounds.left.value >= root.left.value + currentInsets.cutoutLeft - 1f)
        assertTrue("First Applications tab must not be horizontally clipped", abs(applicationsClipped.width / pixelDensity - applicationsBounds.size.width.value) <= 1f)
        assertTrue("First Applications tab must clear status bar", applicationsBounds.top.value >= root.top.value + currentInsets.status - 1f)
        assertTrue("Selected Home tab must clear left display cutout", tab.left.value >= root.left.value + currentInsets.cutoutLeft - 1f)
        assertTrue("Home search must clear right display cutout", search.right.value <= root.right.value - currentInsets.cutoutRight + 1f)
        assertTrue("Home toolbar must clear status bar", tab.top.value >= root.top.value + currentInsets.status - 1f)
        val searchNode = compose.onNodeWithContentDescription("搜索")
        assertTouchTarget(searchNode)
        assertVisualUnclipped(searchNode)
        assertTouchTarget(applications)
        assertVisualUnclipped(applications)
        assertNeighboringTargetsDoNotOverlap(listOf(applications, compose.onNode(hasText("动态") and isSelected()), searchNode))
    }
    private fun assertAboveDock(node: SemanticsNodeInteraction, requireClick: Boolean = true) {
        node.assertIsDisplayed()
        val bounds = node.getUnclippedBoundsInRoot()
        val dockBounds = dock.getUnclippedBoundsInRoot()
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        if (requireClick) {
            val touch = assertTouchTarget(node)
            assertTrue("Entire 48dp-or-larger touch target must clear dock, not just its visual center: $touch vs $dockBounds",
                touch.bottom / pixelDensity <= dockBounds.top.value - 8f + 1f)
        }
        assertTrue("Entire final target must clear dock, not merely its center: $bounds vs $dockBounds",
            bounds.bottom.value <= dockBounds.top.value - 8f + 1f)
        assertTrue("Final target top must be on screen", bounds.top.value >= root.top.value - 1f)
        assertVisualUnclipped(node)
    }
    private fun assertTouchTarget(node: SemanticsNodeInteraction, respectGestureInsets: Boolean = false): androidx.compose.ui.geometry.Rect {
        node.assertIsDisplayed().assertHasClickAction()
        // Material buttons can draw at 40dp while their actual minimum interactive target is
        // 48dp. Test the actual touch bounds, AND independently keep visual unclipped checks.
        val touch = node.fetchSemanticsNode().touchBoundsInRoot
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        assertTrue("Actual touch width must be at least 48dp: $touch", touch.width / pixelDensity >= 48f - .5f)
        assertTrue("Actual touch height must be at least 48dp: $touch", touch.height / pixelDensity >= 48f - .5f)
        val leftInset = max(currentInsets.cutoutLeft, if (respectGestureInsets) currentInsets.gestureLeft else 0)
        val rightInset = max(currentInsets.cutoutRight, if (respectGestureInsets) currentInsets.gestureRight else 0)
        assertTrue("Entire touch target must clear left safe edge", touch.left / pixelDensity >= root.left.value + leftInset - .5f)
        assertTrue("Entire touch target must clear right safe edge", touch.right / pixelDensity <= root.right.value - rightInset + .5f)
        assertTrue("Entire touch target must clear status bar", touch.top / pixelDensity >= root.top.value + currentInsets.status - .5f)
        assertTrue("Entire touch target must clear bottom system inset", touch.bottom / pixelDensity <= root.bottom.value -
            max(currentInsets.navigation, max(currentInsets.mandatoryBottom, currentInsets.tappableBottom)) + .5f)
        return touch
    }
    private fun assertVisualUnclipped(node: SemanticsNodeInteraction) {
        val bounds = node.getUnclippedBoundsInRoot()
        val clipped = node.fetchSemanticsNode().boundsInRoot
        assertTrue("Target visual bounds must not be vertically clipped",
            abs(clipped.height / pixelDensity - bounds.size.height.value) <= 1f)
        assertTrue("Target visual bounds must not be horizontally clipped",
            abs(clipped.width / pixelDensity - bounds.size.width.value) <= 1f)
    }
    private fun assertNeighboringTargetsDoNotOverlap(nodes: List<SemanticsNodeInteraction>) {
        val targets = nodes.map { it.fetchSemanticsNode().touchBoundsInRoot }
        targets.forEachIndexed { index, a ->
            targets.drop(index + 1).forEach { b ->
                val intersectionWidth = min(a.right, b.right) - max(a.left, b.left)
                val intersectionHeight = min(a.bottom, b.bottom) - max(a.top, b.top)
                assertTrue("Neighboring real touch targets must not overlap: $a vs $b",
                    intersectionWidth <= .5f * pixelDensity || intersectionHeight <= .5f * pixelDensity)
            }
        }
    }
    private fun assertRectEquals(message: String, a: DpRect, b: DpRect) {
        assertEquals("$message left", a.left.value, b.left.value, .5f)
        assertEquals("$message top", a.top.value, b.top.value, .5f)
        assertEquals("$message right", a.right.value, b.right.value, .5f)
        assertEquals("$message bottom", a.bottom.value, b.bottom.value, .5f)
    }
    private fun assertConfiguredWindow() {
        val configuration = compose.activity.resources.configuration
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        assertEquals("Qualifier must size actual rendered root", configuration.screenWidthDp.toFloat(), root.size.width.value, 1f)
        assertEquals("Qualifier must size actual rendered root", configuration.screenHeightDp.toFloat(), root.size.height.value, 1f)
        if (configuration.screenWidthDp == 400 || configuration.screenWidthDp == 890) {
            assertEquals("Hypothetical 400x890 and reverse use DPR3", 3f, pixelDensity, .001f)
        }
    }
    private fun openApps() {
        selectRoot("首页")
        compose.onNode(hasText("应用") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).performClick()
        awaitText(if (rowCount == 0) "暂无系统允许查询的非系统应用" else "合成应用 00")
        assertRootSelection("首页")
    }
    private fun showLocalFollow() {
        compose.runOnIdle { uiSettings.value = uiSettings.value.copy(secretFollow = true) }
        compose.waitForIdle()
        assertRootSelection("关注")
    }
    private fun awaitText(text: String) {
        // Bounded async fixture readiness, not timeout inflation or a recovery/probe loop.
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun render(font: Float = 1f, insets: InsetsCase = InsetsCase(), count: Int = LONG_ROWS, localFollow: Boolean = true) {
        check(!rendered); rendered = true
        rowCount = count; currentFont = font; currentInsets = insets
        installSyntheticApps(count)
        val container = AppContainer(compose.activity.application)
        container.nativeUpdates.enabled = false
        val local = LocalRepository(MemoryStore(LocalState()))
        if (localFollow) runBlocking { local.follow(LocalFollowUser("77", "合成本地作者")) }
        // AppContainer has no injection constructor. Test-only reflection replaces only the local
        // repository with an in-memory store; never initialize/read real encrypted credentials.
        AppContainer::class.java.getDeclaredField("local").apply { isAccessible = true }.set(container, local)
        check(container.local === local)
        accounts = syntheticAccounts(count)
        val home = rows(1000, "合成首页", count)
        val online = rows(2000, "合成在线关注", count)
        val hot = rows(3000, "合成热门", count)
        val repository = Proxy.newProxyInstance(CoolapkRepository::class.java.classLoader,
            arrayOf(CoolapkRepository::class.java)) { _, method, _ ->
            when (method.name) {
                "home" -> { homeCalls.incrementAndGet(); ApiResult.Success(Page(home, null)) }
                "following" -> { followingCalls.incrementAndGet(); ApiResult.Success(Page(online, null)) }
                "page" -> ApiResult.Success(Page(hot, null))
                "toString" -> "SyntheticAdaptiveDockRepository"
                else -> { unexpected += "browse:${method.name}"; throw AssertionError("Unexpected repository method ${method.name}") }
            }
        } as CoolapkRepository
        model = BrowseViewModel(repository)
        val accountSnapshot = accounts.store.state.value
        compose.runOnUiThread { WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false) }
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, font),
                LocalReadingStyles provides readingStyles(ReadingPreset.COMFORTABLE, FontChoice.NOTO_SANS)) {
                MaterialTheme(colorScheme = flutterReferenceColorScheme(), typography = appTypography(FontChoice.NOTO_SANS)) {
                    Box(Modifier.fillMaxSize().nestedScroll(scrollObserver)) {
                        NativeApp(model, container, uiSettings.value, accounts, accountSnapshot, myScrollState = myScrollState, browseListStates = browseListStates)
                    }
                }
            }
        }
        injectInsets(insets)
        awaitText(if (count == 0) "暂无内容" else "合成首页 0")
        assertDockGeometry()
        assertRootSelection("首页")
    }

    private fun rows(base: Int, prefix: String, count: Int) = (0 until count).map { index ->
        ContentItem((base + index).toString(), "feed", title = "$prefix $index", userName = "合成作者", userId = "77",
            // Blank body intentionally excludes unrelated async text pagination from dock testing.
            message = "", replyCount = index, feed = FeedMetadata(likeState = ExplicitLikeState.UNKNOWN, replyCount = index))
    }
    private fun syntheticAccounts(count: Int): AccountRuntime {
        val store = AccountStore(object : CredentialPersistence {
            private var value: ByteArray? = null
            override fun read() = value?.copyOf()
            override fun write(bytes: ByteArray) { value = bytes.copyOf() }
            override fun reset() { value = null }
        })
        store.saveAuthenticated("99", "合成测试账号", "synthetic-device", listOf(Cookie.Builder()
            .name("token").value("synthetic-not-a-real-token").domain("coolapk.com").path("/").secure()
            .expiresAt(System.currentTimeMillis() + 3_600_000).build()), store.snapshot())
        val blocked = OkHttpClient.Builder().addInterceptor { chain ->
            unexpected += "blocked:${chain.request().method} ${chain.request().url}"
            throw IOException("Networking is disabled in the adaptive dock fixture")
        }.build()
        val localClient = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.host != "127.0.0.1" || request.method != "GET" ||
                request.url.encodedPath != "/v6/user/feedList" || request.url.queryParameter("uid") != "77" ||
                request.url.queryParameter("isIncludeTop") != "0") {
                unexpected += "local:${request.method} ${request.url}"
                throw IOException("Unexpected synthetic local-feed read")
            }
            localRequests += request.url.toString()
            val items = if (request.url.queryParameter("page") == "1") (0 until count).map { index ->
                buildJsonObject {
                    put("id", (7000 + index).toString()); put("entityType", "feed"); put("uid", "77")
                    put("username", "合成本地作者"); put("message_title", "合成本地关注 $index")
                    put("message", "合成本地动态，仅用于布局验证。")
                    put("dateline", 1_700_000_000L - index)
                }
            } else emptyList()
            val body = buildJsonObject { put("data", JsonArray(items)) }.toString()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
                .body(body.toResponseBody()).build()
            // Never call chain.proceed. Even this loopback URL cannot open a socket.
        }.build()
        clients += listOf(blocked, localClient)
        val endpoint = ApiEndpoints("http://127.0.0.1:9/".toHttpUrl(), "http://127.0.0.1:9/".toHttpUrl())
        val login = HttpLoginTransport(blocked)
        val social = SocialRepository(store) { snapshot ->
            CoolapkHttpTransport(client = blocked, endpoints = endpoint, requestSession = { store.requestSessionFor(snapshot) })
        }
        val constructor = AccountRuntime::class.java.getDeclaredConstructor(AccountStore::class.java,
            HttpLoginTransport::class.java, LoginCoordinator::class.java, HttpCoolapkRepository::class.java,
            SocialRepository::class.java).apply { isAccessible = true }
        return constructor.newInstance(store, login, LoginCoordinator(store, login, "synthetic-device"),
            HttpCoolapkRepository(client = localClient, endpoints = endpoint), social)
    }

    @Suppress("DEPRECATION")
    private fun installSyntheticApps(count: Int) {
        val shadow = shadowOf(compose.activity.packageManager)
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        // Robolectric merges manifest launchers with setResolveInfosForIntent. Exclude only the
        // known host-test application, never enumerate or modify an actual device's inventory.
        val hostPackage = shadow.getInternalMutablePackageInfo(compose.activity.packageName)
        val host = requireNotNull(hostPackage.applicationInfo)
        host.flags = host.flags or ApplicationInfo.FLAG_SYSTEM
        hostPackage.activities?.forEach { activity ->
            activity.applicationInfo.flags = activity.applicationInfo.flags or ApplicationInfo.FLAG_SYSTEM
        }
        val visible = (0 until count).map { index ->
            val packageId = "synthetic.dock.app${index.toString().padStart(2, '0')}"
            val app = ApplicationInfo().apply {
                packageName = packageId; nonLocalizedLabel = "合成应用 ${index.toString().padStart(2, '0')}"
                flags = 0; enabled = true
            }
            val activity = ActivityInfo().apply {
                packageName = packageId; name = "$packageId.MainActivity"; applicationInfo = app
                exported = true; enabled = true
            }
            shadow.installPackage(PackageInfo().apply {
                packageName = packageId; applicationInfo = app; activities = arrayOf(activity)
                versionName = "1.0-synthetic"; versionCode = 1; lastUpdateTime = 1_700_000_000_000L + index
            })
            shadow.setApplicationIcon(packageId, ColorDrawable(Color.rgb(40 + index, 80, 120)))
            ResolveInfo().apply { activityInfo = activity; isDefault = true }
        }
        shadow.setResolveInfosForIntent(launcher, visible)
        val expected = visible.map { it.activityInfo.packageName }.toSet()
        val actual = runBlocking { installedLauncherApps(compose.activity) }.map { it.packageName }.toSet()
        assertEquals("Robolectric launcher fixture must contain exactly the synthetic packages", expected, actual)
    }

    private fun injectInsets(value: InsetsCase) {
        currentInsets = value
        compose.runOnUiThread {
            val density = pixelDensity
            fun px(dp: Int) = (dp * density).toInt()
            val builder = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, px(value.status), 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, px(value.navigation)))
                .setInsets(WindowInsetsCompat.Type.systemGestures(), Insets.of(px(value.gestureLeft), 0, px(value.gestureRight), px(value.mandatoryBottom)))
                .setInsets(WindowInsetsCompat.Type.mandatorySystemGestures(), Insets.of(0, 0, 0, px(value.mandatoryBottom)))
                .setInsets(WindowInsetsCompat.Type.tappableElement(), Insets.of(0, 0, 0, px(value.tappableBottom)))
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, px(value.ime)))
                .setVisible(WindowInsetsCompat.Type.systemBars(), true)
                .setVisible(WindowInsetsCompat.Type.ime(), value.ime > 0)
            if (value.cutoutLeft > 0 || value.cutoutRight > 0) {
                val width = compose.activity.window.decorView.width
                val rects = buildList {
                    if (value.cutoutLeft > 0) add(Rect(0, 40, px(value.cutoutLeft), 120))
                    if (value.cutoutRight > 0) add(Rect(width - px(value.cutoutRight), 40, width, 120))
                }
                builder.setDisplayCutout(DisplayCutoutCompat(Rect(px(value.cutoutLeft), 0, px(value.cutoutRight), 0), rects))
            }
            ViewCompat.dispatchApplyWindowInsets(compose.activity.window.decorView, builder.build())
        }
        compose.waitForIdle()
    }
    private fun output(name: String) = File("build/verification/adaptive-dock/$name").apply { parentFile?.mkdirs() }
    private fun capture(name: String) {
        val bitmap = compose.runOnUiThread {
            val view = compose.activity.findViewById<View>(android.R.id.content)
            check(view.width > 0 && view.height > 0)
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        output("$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
    companion object { private const val LONG_ROWS = 12 }
}
