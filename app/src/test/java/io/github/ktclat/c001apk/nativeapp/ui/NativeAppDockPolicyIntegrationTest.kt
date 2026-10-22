// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.provider.Settings
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.size
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import io.github.ktclat.c001apk.nativeapp.AppContainer
import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.data.*
import io.github.ktclat.c001apk.nativeapp.domain.*
import io.github.ktclat.c001apk.nativeapp.local.*
import io.github.ktclat.c001apk.nativeapp.settings.*
import io.github.ktclat.c001apk.nativeapp.social.SocialRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.rules.TestName
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDisplay
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Actual NativeApp, native AccessibilityManager listener, pointer input, Scaffold and insets.
 * SYNTHETIC mapping from the user's Flutter report, NOT a native-device measurement or
 * reproduction: physical 1200 x 2670, DPR 3.25, logical 369.230769 x 821.538462, font 1.
 * Visual top padding 140px maps to statusBars; top systemGestures remains independently 179px.
 * All bottom insets are zero. The two top values are never added together.
 * Integer dp qualifiers alone are insufficient, so the shadow display/window use exact pixels.
 * Accounts/local data are memory-only and every HTTP client rejects all requests before proceed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w369dp-h822dp-520dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeAppDockPolicyIntegrationTest {
    @get:Rule(order = 0) val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule(order = 1) val testName = TestName()
    @get:Rule(order = 2) val diagnostics = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            // Failure observation only. Never warm state, notify snapshots, retry or repair.
            runCatching { recordEvidence("${description.methodName}-FAILED", error.toString()) }
        }
    }

    private val uiSettings = mutableStateOf(AppSettings(floatingNavigation = true, dynamicColor = false))
    private val homeState = LazyListState()
    private val homeCalls = AtomicInteger()
    private val unexpected = CopyOnWriteArrayList<String>()
    private val samples = CopyOnWriteArrayList<ScrollSample>()
    private val clients = mutableListOf<OkHttpClient>()
    private var recordScroll = false
    private var imePx = 0
    private var rendered = false
    private var measuredVisualTop = -1
    private var measuredGestureTop = -1
    private var measuredSafeBottom = -1
    private var originalAnimationScale = 1f
    private data class ScrollSample(val y: Float, val source: NestedScrollSource)
    private val scrollObserver = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (recordScroll) samples += ScrollSample(consumed.y, source)
            return Offset.Zero // Observation only: NativeApp must receive real list consumption.
        }
    }
    private val dock get() = compose.onNodeWithTag("floating-navigation")
    private val list get() = compose.onNodeWithTag("content-list")
    private val pixelDensity get() = compose.activity.resources.displayMetrics.density
    private val accessibilityManager get() = compose.activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager

    @After fun closeClientsAndAssertIsolation() {
        if (rendered) compose.runOnUiThread {
            Settings.Global.putFloat(compose.activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, originalAnimationScale)
            compose.activity.contentResolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), null)
        }
        clients.forEach {
            it.dispatcher.cancelAll()
            it.connectionPool.evictAll()
            it.dispatcher.executorService.shutdownNow()
        }
        assertTrue("Unexpected repository/network request: $unexpected", unexpected.isEmpty())
    }

    @Test fun defaultHideIsIndependentOfRealTouchExplorationListenerChanges() {
        render(exploration = true)
        consumedDrag(forward = true)
        assertHiddenDockAndRecovery()
        setTouchExploration(false)
        assertHiddenDockAndRecovery() // The system flag cannot silently turn scroll hiding off.
        consumedDrag(forward = false)
        assertDockGeometry()
        consumedDrag(forward = true)
        setTouchExploration(true)
        assertHiddenDockAndRecovery()
        revealThroughAccessibility()
        consumedDrag(forward = true)
        assertHiddenDockAndRecovery()
        revealThroughAccessibility()
        rootTab("我的").performClick()
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNode(hasText("关于") and hasClickAction()).performScrollTo().performClick()
        for (exploration in listOf(false, true)) {
            setTouchExploration(exploration)
            compose.onNodeWithTag("copy-native-diagnostics").performClick()
            val copied = compose.runOnIdle {
                val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.primaryClip!!.getItemAt(0).coerceToText(compose.activity).toString()
            }
            assertTrue("Actual NativeApp listener snapshot must update diagnostics", copied.contains("触摸探索（系统信号）: ${if (exploration) "开启" else "关闭"}"))
            assertTrue(copied.contains("悬浮根页默认随滚动隐藏: 开启"))
            assertFalse(copied.contains("滚动隐藏模式"))
        }
        assertSystemState(exploration = true)
    }

    @Test fun defaultPointerHideAndReverseRevealPreserveViewportWithExplorationOnAndOff() {
        render(exploration = false)
        val viewport = list.getUnclippedBoundsInRoot()
        for (exploration in listOf(false, true)) {
            setTouchExploration(exploration)
            consumedDrag(forward = true)
            assertHiddenDockAndRecovery()
            assertRectEquals("Hide must not resize the content viewport", viewport, list.getUnclippedBoundsInRoot())
            consumedDrag(forward = false)
            assertDockGeometry()
            assertNoRecoveryActions()
            assertRectEquals("Reveal must not resize the content viewport", viewport, list.getUnclippedBoundsInRoot())
        }
        assertSystemState(exploration = true)
    }

    @Test fun realMotionOffSignalDisablesDecorationNotDefaultScrollOrUserFling() {
        render(exploration = true)
        setAnimationScale(0f)
        consumedDrag(forward = true)
        assertHiddenDockAndRecovery()
        revealThroughAccessibility()
        compose.mainClock.autoAdvance = false
        try {
            rootTab("我的").performClick()
            compose.mainClock.advanceTimeBy(80)
            val pill = compose.onNodeWithTag("selected-pill", useUnmergedTree = true).fetchSemanticsNode().config
            assertEquals("True motion-off must snap the decorative destination", 2f, pill[DecorativeJellyPosition], .001f)
            assertEquals("True motion-off must suppress the decorative pulse", 1f, pill[DecorativeJellyScale], .001f)
        } finally { compose.mainClock.autoAdvance = true }
        rootTab("首页").performClick()
        scrollToTop()
        genuineUserFlingHidesDock()
        assertHiddenDockAndRecovery()
        revealThroughAccessibility()
        setAnimationScale(originalAnimationScale)
        assertSystemState(exploration = true)
    }

    @Test fun accessibleRecoveryIsOnExistingReachableControlsAndResetsThresholdHistory() {
        render(exploration = true)
        consumedDrag(forward = true)
        assertHiddenDockAndRecovery()
        val oldRootAction = compose.onNodeWithContentDescription("搜索").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].single { it.label == SHOW_FLOATING_DOCK_ACTION }.action
        revealThroughAccessibility()
        rootTab("我的").performClick()
        rootTab("首页").performClick()
        consumedDrag(forward = true)
        assertHiddenDockAndRecovery()
        compose.runOnIdle { assertFalse("An action from an old root must not reset the current owner's state", oldRootAction()) }
        assertHiddenDockAndRecovery()
        revealThroughAccessibility()
        shortConsumedDrag()
        assertDockGeometry() // Recovery must discard the previous hidden 28dp distance.
        shortConsumedDrag()
        assertHiddenDockAndRecovery() // Two fresh 20dp consumed drags still cross the threshold.
        val listRecoveryAction = list.fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .single { it.label == SHOW_FLOATING_DOCK_ACTION }.action
        compose.runOnIdle { assertTrue(listRecoveryAction()) }
        assertDockGeometry()
        assertNoRecoveryActions()
        shortConsumedDrag()
        assertDockGeometry()
        assertSystemState(exploration = true)
    }

    @Test fun defaultAndRecoveryHonorImeInnerRouteAndRootGatesWithNoSettingsPicker() {
        render(exploration = true)
        consumedDrag(forward = true)
        val staleAction = compose.onNodeWithContentDescription("搜索").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].single { it.label == SHOW_FLOATING_DOCK_ACTION }.action
        injectInsets(ime = (280f * pixelDensity).toInt())
        dock.assertDoesNotExist(); assertNoRecoveryActions()
        compose.runOnIdle { assertFalse("Stale service action must not bypass the keyboard gate", staleAction()) }
        dock.assertDoesNotExist()
        injectInsets(ime = 0)
        assertDockGeometry()
        rootTab("我的").performClick()
        assertRootSelection("我的")
        compose.onNodeWithContentDescription("设置").performClick()
        awaitText("设置")
        dock.assertDoesNotExist(); assertNoRecoveryActions()
        compose.onNode(hasText("外观与字体") and hasClickAction()).performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("悬浮导航栏"))
        compose.onNodeWithText("悬浮导航栏").assertIsOn()
        listOf("悬浮导航栏的滚动显示", "自动（尊重无障碍）", "滚动时隐藏", "滚动时始终可见").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        dock.assertDoesNotExist(); assertNoRecoveryActions()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        assertDockGeometry()
        rootTab("首页").performClick()
        compose.runOnIdle { uiSettings.value = uiSettings.value.copy(floatingNavigation = false) }
        compose.waitForIdle()
        dock.assertDoesNotExist(); assertNoRecoveryActions()
        compose.onNode(hasText("首页") and isSelected()).assertIsDisplayed()
        compose.runOnIdle { uiSettings.value = uiSettings.value.copy(floatingNavigation = true) }
        compose.waitForIdle()
        assertDockGeometry()
        assertEquals("Root and inner navigation may not reload Home", 1, homeCalls.get())
        assertSystemState(exploration = true)
    }

    @Test fun activeSearchRestoresHiddenDockButNeverOverridesKeyboardGate() {
        render(exploration = true)
        consumedDrag(forward = true)
        assertHiddenDockAndRecovery()
        compose.onNodeWithContentDescription("搜索").performClick()
        compose.onNodeWithText("搜索动态").assertIsDisplayed()
        assertDockGeometry(); assertNoRecoveryActions()
        consumedDrag(forward = true)
        assertDockGeometry(); assertNoRecoveryActions()
        injectInsets(ime = (280f * pixelDensity).toInt())
        dock.assertDoesNotExist(); assertNoRecoveryActions()
        injectInsets(ime = 0)
        assertDockGeometry()
        compose.onNodeWithContentDescription("搜索").performClick()
        shortConsumedDrag()
        assertDockGeometry()
        shortConsumedDrag()
        assertHiddenDockAndRecovery()
        assertSystemState(exploration = true)
    }

    @Test fun reportedFlutterAspectHasExactMeasuredViewportSafeTargetsAndStableLastActionTail() {
        render(exploration = true)
        assertReportedViewport()
        val firstTab = compose.onNodeWithTag("home-category:0")
        val firstBounds = firstTab.getUnclippedBoundsInRoot()
        assertEquals("Visual top is 140px, not 140px + 179px", TOP_VISUAL_PX / DPR, firstBounds.top.value, 1f)
        assertTouchTarget(firstTab)
        assertTouchTarget(compose.onNodeWithContentDescription("搜索"))
        val viewport = list.getUnclippedBoundsInRoot()
        scrollToAbsoluteEnd()
        val last = compose.onNodeWithTag("feed-comments:${BASE_ID + ROW_COUNT - 1}")
        assertLastActionClearsDock(last)
        val tail = last.getUnclippedBoundsInRoot().bottom.value
        recordEvidence("reported-flutter-mapping-visible-end")
        scrollToTop()
        consumedDrag(forward = true)
        assertHiddenDockAndRecovery()
        assertRectEquals("Hiding overlay must preserve viewport", viewport, list.getUnclippedBoundsInRoot())
        scrollToAbsoluteEnd()
        dock.assertDoesNotExist()
        assertEquals("Hidden dock must preserve the exact tail, not collapse it", tail,
            last.getUnclippedBoundsInRoot().bottom.value, 1f)
        revealThroughAccessibility()
        assertLastActionClearsDock(last)
        assertEquals("Accessibility reveal must preserve the reserved tail", tail,
            last.getUnclippedBoundsInRoot().bottom.value, 1f)
        assertReportedViewport()
        recordEvidence("reported-flutter-mapping-restored-end")
    }

    private fun recoveryMatcher() = SemanticsMatcher("Has 显示底部导航 accessibility action") { node ->
        node.config.contains(SemanticsActions.CustomActions) &&
            node.config[SemanticsActions.CustomActions].any { it.label == SHOW_FLOATING_DOCK_ACTION }
    }
    private fun assertNoRecoveryActions() {
        compose.onAllNodes(recoveryMatcher(), useUnmergedTree = true).assertCountEquals(0)
    }
    private fun assertHiddenDockAndRecovery() {
        dock.assertDoesNotExist()
        compose.onAllNodesWithTag("floating-navigation", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodes(hasAnyAncestor(hasTestTag("floating-navigation")), useUnmergedTree = true).assertCountEquals(0)
        list.assert(recoveryMatcher()).assert(hasScrollAction())
        val control = compose.onNodeWithContentDescription("搜索")
        control.assertIsDisplayed().assertHasClickAction().assert(recoveryMatcher())
        assertTouchTarget(control)
    }
    private fun revealThroughAccessibility() {
        val recoveryAction = compose.onNodeWithContentDescription("搜索").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].single { it.label == SHOW_FLOATING_DOCK_ACTION }.action
        compose.runOnIdle { assertTrue(recoveryAction()) }
        assertDockGeometry()
        assertNoRecoveryActions()
    }
    private fun setAnimationScale(scale: Float) {
        compose.runOnUiThread {
            Settings.Global.putFloat(compose.activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
            compose.activity.contentResolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), null)
        }
        compose.waitForIdle()
    }
    private fun genuineUserFlingHidesDock() {
        val slop = ViewConfiguration.get(compose.activity).scaledTouchSlop.toFloat()
        compose.runOnIdle { samples.clear(); recordScroll = true }
        compose.mainClock.autoAdvance = false
        try {
            list.performTouchInput {
                down(Offset(centerX, height * .5f))
                moveBy(Offset(0f, -slop), delayMillis = 16)
                repeat(3) { moveBy(Offset(0f, -8f * pixelDensity), delayMillis = 8) }
            }
            compose.mainClock.advanceTimeByFrame()
            val finger = samples.sumOf { max(0f, -it.y).toDouble() }.toFloat() / pixelDensity
            assertTrue("Real pre-fling movement remains below 28dp: $finger", finger in 12f..26f)
            dock.assertIsDisplayed()
            val before = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            list.performTouchInput { up() }
            compose.mainClock.advanceTimeBy(300)
            compose.waitForIdle()
            val after = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            val fling = samples.filter { it.source == NestedScrollSource.SideEffect }.sumOf { max(0f, -it.y).toDouble() }.toFloat() / pixelDensity
            val total = samples.sumOf { max(0f, -it.y).toDouble() }.toFloat() / pixelDensity
            assertTrue("A real fling continues content after release", after > before)
            assertTrue("Post-release consumption must be nonzero: $fling", fling > 0f)
            assertTrue("Actual consumed movement crosses 28dp: $total", total > 28f)
            dock.assertDoesNotExist()
        } finally {
            runCatching { list.performTouchInput { cancel() } }
            recordScroll = false
            compose.mainClock.autoAdvance = true
        }
    }

    private fun setTouchExploration(value: Boolean) {
        compose.runOnUiThread { shadowOf(accessibilityManager).setTouchExplorationEnabled(value) }
        compose.waitForIdle()
        assertEquals(value, accessibilityManager.isTouchExplorationEnabled)
    }

    private fun assertSystemState(exploration: Boolean) {
        assertTrue("Default dock behavior must not disable native accessibility", accessibilityManager.isEnabled)
        assertEquals("Default dock behavior must not change native touch exploration", exploration, accessibilityManager.isTouchExplorationEnabled)
        assertEquals("Default dock behavior must not change native reduced-motion setting", originalAnimationScale,
            Settings.Global.getFloat(compose.activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f), 0f)
    }

    private fun consumedDrag(forward: Boolean) {
        compose.runOnIdle { samples.clear(); recordScroll = true }
        try {
            list.performTouchInput {
                swipe(Offset(centerX, height * if (forward) .60f else .20f),
                    Offset(centerX, height * if (forward) .20f else .60f), durationMillis = 600)
            }
            compose.waitForIdle()
        } finally { recordScroll = false }
        val consumed = samples.filter { it.source == NestedScrollSource.UserInput }
            .sumOf { max(0f, if (forward) -it.y else it.y).toDouble() }.toFloat() / pixelDensity
        assertTrue("Real finger consumption must cross the ${if (forward) 28 else 20}dp threshold: $consumed dp; $samples",
            consumed > if (forward) 28f else 20f)
    }

    private fun shortConsumedDrag() {
        val slop = ViewConfiguration.get(compose.activity).scaledTouchSlop.toFloat()
        compose.runOnIdle { samples.clear(); recordScroll = true }
        try {
            list.performTouchInput {
                down(Offset(centerX, height * .40f))
                moveBy(Offset(0f, -(slop + 20f * pixelDensity)), delayMillis = 80)
                advanceEventTime(250) // Stationary release: no velocity-driven hide after the probe.
                moveBy(Offset.Zero, delayMillis = 16)
                up()
            }
            compose.waitForIdle()
        } finally { recordScroll = false }
        val consumed = samples.filter { it.source == NestedScrollSource.UserInput }
            .sumOf { max(0f, -it.y).toDouble() }.toFloat() / pixelDensity
        assertTrue("Partial drag must really consume 14–26dp, below the 28dp hide threshold: $consumed", consumed in 14f..26f)
        val fling = samples.filter { it.source == NestedScrollSource.SideEffect }.sumOf { abs(it.y).toDouble() } / pixelDensity
        assertTrue("Stationary release must not add meaningful fling consumption: $fling", fling <= .5)
    }

    private fun scrollToAbsoluteEnd() {
        list.performScrollToIndex(ROW_COUNT - 1)
        compose.runOnIdle { runBlocking {
            homeState.scrollBy((homeState.layoutInfo.viewportSize.height + homeState.layoutInfo.afterContentPadding).toFloat())
        } }
        compose.waitForIdle()
        val range = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals("Must reach actual end including trailing padding", range.maxValue(), range.value(), .01f)
        assertFalse("Real lazy state must be at absolute end", compose.runOnIdle { homeState.canScrollForward })
    }

    private fun scrollToTop() {
        list.performScrollToIndex(0)
        compose.waitForIdle()
        val range = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals("Must start at actual top", 0f, range.value(), .01f)
    }

    private fun rootTab(label: String) = compose.onNode(hasText(label) and
        SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected) and hasAnyAncestor(hasTestTag("floating-navigation")))

    private fun assertRootSelection(selected: String) {
        compose.waitForIdle()
        listOf("首页", "关注", "我的").forEach { label ->
            rootTab(label).assertIsDisplayed().assertHasClickAction()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, selected == label))
        }
        compose.onAllNodes(isSelected() and hasAnyAncestor(hasTestTag("floating-navigation"))).assertCountEquals(1)
    }

    private fun assertDockGeometry() {
        dock.assertIsDisplayed()
        compose.onNodeWithTag("navigation-rail").assertDoesNotExist()
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val rect = dock.getUnclippedBoundsInRoot()
        assertEquals("Zero bottom inset leaves exactly the 12dp dock gap", root.bottom.value - 12f, rect.bottom.value, 1f)
        assertEquals("Font 1 retains the native 62dp dock", 62f, rect.size.height.value, 1f)
        assertTrue("Bottom dock is horizontal, not a rail", rect.size.width > rect.size.height * 2)
        val targets = listOf("首页", "关注", "我的").map { label ->
            rootTab(label).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            assertTouchTarget(rootTab(label))
        }
        targets.forEachIndexed { index, a -> targets.drop(index + 1).forEach { b ->
            assertTrue("Real neighboring touch targets must not overlap: $a vs $b",
                min(a.right, b.right) - max(a.left, b.left) <= .5f * pixelDensity ||
                    min(a.bottom, b.bottom) - max(a.top, b.top) <= .5f * pixelDensity)
        } }
    }

    private fun assertTouchTarget(node: SemanticsNodeInteraction): androidx.compose.ui.geometry.Rect {
        node.assertIsDisplayed().assertHasClickAction()
        val touch = node.fetchSemanticsNode().touchBoundsInRoot
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        assertTrue("Actual touch width must be >=48dp: $touch", touch.width / pixelDensity >= 48f - .5f)
        assertTrue("Actual touch height must be >=48dp: $touch", touch.height / pixelDensity >= 48f - .5f)
        assertTrue("Touch left must stay on screen", touch.left / pixelDensity >= root.left.value - .5f)
        assertTrue("Touch right must stay on screen", touch.right / pixelDensity <= root.right.value + .5f)
        assertTrue("Touch target must clear visual status inset", touch.top >= TOP_VISUAL_PX - 1f)
        assertTrue("Touch bottom must stay on screen", touch.bottom / pixelDensity <= root.bottom.value + .5f)
        val visual = node.getUnclippedBoundsInRoot()
        val clipped = node.fetchSemanticsNode().boundsInRoot
        assertEquals("Visual width must not be clipped", visual.size.width.value, clipped.width / pixelDensity, 1f)
        assertEquals("Visual height must not be clipped", visual.size.height.value, clipped.height / pixelDensity, 1f)
        return touch
    }

    private fun assertLastActionClearsDock(node: SemanticsNodeInteraction) {
        val touch = assertTouchTarget(node)
        val visual = node.getUnclippedBoundsInRoot()
        val top = dock.getUnclippedBoundsInRoot().top.value
        assertTrue("Entire last visual action must clear dock by >=8dp: $visual, dockTop=$top", visual.bottom.value <= top - 8f + 1f)
        assertTrue("Entire >=48dp touch target must clear dock by >=8dp: $touch, dockTop=$top", touch.bottom / pixelDensity <= top - 8f + 1f)
    }

    private fun assertRectEquals(message: String, a: DpRect, b: DpRect) {
        assertEquals("$message left", a.left.value, b.left.value, .5f)
        assertEquals("$message top", a.top.value, b.top.value, .5f)
        assertEquals("$message right", a.right.value, b.right.value, .5f)
        assertEquals("$message bottom", a.bottom.value, b.bottom.value, .5f)
    }

    private fun assertReportedViewport() {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val logical = compose.onRoot().getUnclippedBoundsInRoot()
        val content = compose.activity.findViewById<View>(android.R.id.content)
        assertEquals("Actual Android content width, not only a qualifier", WIDTH_PX, content.width)
        assertEquals("Actual Android content height, not only a qualifier", HEIGHT_PX, content.height)
        assertEquals("Actual Compose root width", WIDTH_PX.toFloat(), root.width, .01f)
        assertEquals("Actual Compose root height", HEIGHT_PX.toFloat(), root.height, .01f)
        assertEquals("Flutter-reported density used only as a synthetic native mapping", DPR, pixelDensity, .001f)
        assertEquals(369.230769f, logical.size.width.value, .001f)
        assertEquals(821.538462f, logical.size.height.value, .001f)
        assertEquals("Native Compose sees the separate visual inset", TOP_VISUAL_PX, measuredVisualTop)
        assertEquals("Native Compose sees the separate gesture inset", TOP_GESTURE_PX, measuredGestureTop)
        assertEquals("Native Compose sees zero safe bottom", 0, measuredSafeBottom)
    }

    private fun render(exploration: Boolean) {
        check(!rendered); rendered = true
        compose.runOnUiThread {
            // Both logical display frames and window size must carry the exact physical report.
            shadowOf(ShadowDisplay.getDefaultDisplay()).apply {
                setWidth(WIDTH_PX); setHeight(HEIGHT_PX)
                setRealWidth(WIDTH_PX); setRealHeight(HEIGHT_PX)
            }
            WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
            compose.activity.window.setLayout(WIDTH_PX, HEIGHT_PX)
            shadowOf(accessibilityManager).apply {
                setEnabled(true)
                setTouchExplorationEnabled(exploration)
            }
        }
        originalAnimationScale = Settings.Global.getFloat(compose.activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val container = AppContainer(compose.activity.application)
        container.nativeUpdates.enabled = false
        val local = LocalRepository(object : JsonStateStore<LocalState> {
            override val data = MutableStateFlow(LocalState())
            override suspend fun update(transform: (LocalState) -> LocalState) { data.value = LocalRepository.validateState(transform(data.value)) }
        })
        // Same narrow injection as AdaptiveDockRenderingTest. No credential store is initialized.
        AppContainer::class.java.getDeclaredField("local").apply { isAccessible = true }.set(container, local)
        check(container.local === local)
        val rows = (0 until ROW_COUNT).map { index ->
            ContentItem((BASE_ID + index).toString(), "feed", title = "策略合成首页 $index", userName = "合成作者", userId = "77",
                message = "", replyCount = index, feed = FeedMetadata(likeState = ExplicitLikeState.UNKNOWN, replyCount = index))
        }
        val repository = Proxy.newProxyInstance(CoolapkRepository::class.java.classLoader, arrayOf(CoolapkRepository::class.java)) { _, method, _ ->
            when (method.name) {
                "home" -> { homeCalls.incrementAndGet(); ApiResult.Success(Page(rows, null)) }
                "toString" -> "SyntheticNativeDockPolicyRepository"
                else -> { unexpected += "browse:${method.name}"; throw AssertionError("Unexpected method ${method.name}") }
            }
        } as CoolapkRepository
        val accounts = syntheticAccounts()
        val model = BrowseViewModel(repository)
        val snapshot = accounts.store.state.value
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1f),
                LocalReadingStyles provides readingStyles(ReadingPreset.COMFORTABLE, FontChoice.NOTO_SANS)) {
                MaterialTheme(colorScheme = flutterReferenceColorScheme(), typography = appTypography(FontChoice.NOTO_SANS)) {
                    val density = LocalDensity.current
                    val visualTop = WindowInsets.statusBars.getTop(density)
                    val gestureTop = WindowInsets.systemGestures.getTop(density)
                    val safeBottom = WindowInsets.safeDrawing.getBottom(density)
                    SideEffect { measuredVisualTop = visualTop; measuredGestureTop = gestureTop; measuredSafeBottom = safeBottom }
                    Box(Modifier.fillMaxSize().nestedScroll(scrollObserver)) {
                        NativeApp(model, container, uiSettings.value, accounts, snapshot, browseListStates = mapOf("home" to homeState))
                    }
                }
            }
        }
        injectInsets(ime = 0)
        awaitText("策略合成首页 0") // Same bounded readiness as the adaptive fixture, with no rescue.
        assertReportedViewport()
        assertDockGeometry()
        assertRootSelection("首页")
    }

    private fun syntheticAccounts(): AccountRuntime {
        val store = AccountStore(object : CredentialPersistence {
            private var bytes: ByteArray? = null
            override fun read() = bytes?.copyOf()
            override fun write(bytes: ByteArray) { this.bytes = bytes.copyOf() }
            override fun reset() { bytes = null }
        })
        val blocked = OkHttpClient.Builder().addInterceptor { chain ->
            unexpected += "blocked:${chain.request().method} ${chain.request().url}"
            throw IOException("All HTTP is disabled in this synthetic fixture")
        }.build().also { clients += it }
        val endpoints = ApiEndpoints("http://127.0.0.1:9/".toHttpUrl(), "http://127.0.0.1:9/".toHttpUrl())
        val login = HttpLoginTransport(blocked)
        val social = SocialRepository(store) { snapshot ->
            CoolapkHttpTransport(client = blocked, endpoints = endpoints, requestSession = { store.requestSessionFor(snapshot) })
        }
        return AccountRuntime::class.java.getDeclaredConstructor(AccountStore::class.java, HttpLoginTransport::class.java,
            LoginCoordinator::class.java, HttpCoolapkRepository::class.java, SocialRepository::class.java)
            .apply { isAccessible = true }.newInstance(store, login, LoginCoordinator(store, login, "synthetic-device"),
                HttpCoolapkRepository(client = blocked, endpoints = endpoints), social)
    }

    private fun injectInsets(ime: Int) {
        imePx = ime
        compose.runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(compose.activity.window.decorView, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, TOP_VISUAL_PX, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.NONE)
                .setInsets(WindowInsetsCompat.Type.systemGestures(), Insets.of(0, TOP_GESTURE_PX, 0, 0))
                .setInsets(WindowInsetsCompat.Type.mandatorySystemGestures(), Insets.NONE)
                .setInsets(WindowInsetsCompat.Type.tappableElement(), Insets.NONE)
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
                .setVisible(WindowInsetsCompat.Type.statusBars(), true)
                .setVisible(WindowInsetsCompat.Type.navigationBars(), false)
                .setVisible(WindowInsetsCompat.Type.ime(), ime > 0).build())
        }
        compose.waitForIdle()
    }

    private fun awaitText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun recordEvidence(name: String, failure: String = "") {
        val directory = File("build/verification/native-dock-policy").apply { mkdirs() }
        File(directory, "$name.txt").writeText(
            "SYNTHETIC mapping from a Flutter report; NOT a native-device measurement/reproduction.\n" +
                "Reported physical=1200x2670; density=3.25; logical=369.230769x821.538462; font=1.\n" +
                "Raw insets: visual top=140px; systemGestures top=179px; bottom=0px. Top values are separate, not summed.\n" +
                "Actual root=${compose.onRoot().fetchSemanticsNode().boundsInRoot}; logical=${compose.onRoot().getUnclippedBoundsInRoot()}; density=$pixelDensity\n" +
                "scrollHide=default; exploration=${accessibilityManager.isTouchExplorationEnabled}; imePx=$imePx; homeCalls=${homeCalls.get()}\n" +
                "Measured insets: visualTop=$measuredVisualTop; gestureTop=$measuredGestureTop; safeBottom=$measuredSafeBottom\n" +
                "pointerSamples=$samples\nunexpected=$unexpected\nfailure=$failure\n" +
                compose.onRoot(useUnmergedTree = true).printToString())
        val bitmap = compose.runOnUiThread {
            val content = compose.activity.findViewById<View>(android.R.id.content)
            check(content.width > 0 && content.height > 0)
            Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888).also { content.draw(Canvas(it)) }
        }
        File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    companion object {
        private const val WIDTH_PX = 1200
        private const val HEIGHT_PX = 2670
        private const val DPR = 3.25f
        private const val TOP_VISUAL_PX = 140
        private const val TOP_GESTURE_PX = 179
        private const val BASE_ID = 9000
        private const val ROW_COUNT = 12
    }
}
