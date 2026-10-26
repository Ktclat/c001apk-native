package io.github.ktclat.c001apk.nativeapp.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FloatingNavigationMotionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun render(reduced: Boolean = false) {
        val selected = mutableIntStateOf(0)
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme { Surface { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            FloatingNavigation(selected.intValue, reduced, { selected.intValue = it })
        } } } }
        compose.mainClock.advanceTimeBy(32)
    }
    private fun tab(label: String) = compose.onNode(hasText(label) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
    private fun position() = compose.onNodeWithTag("selected-pill", useUnmergedTree = true).fetchSemanticsNode().config[DecorativeJellyPosition]
    private fun scale() = compose.onNodeWithTag("selected-pill", useUnmergedTree = true).fetchSemanticsNode().config[DecorativeJellyScale]
    private fun frame(name: String) {
        val bitmap = compose.runOnUiThread { val view = compose.activity.findViewById<View>(android.R.id.content)
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) } }
        File("build/verification/$name.png").apply { parentFile.mkdirs() }.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    @Test fun decorativeJellyMovesButHitBoxesStayStableAndReselectDoesNotBounce() {
        render()
        val bounds = listOf("首页", "关注", "我的").map { tab(it).fetchSemanticsNode().boundsInRoot }
        frame("jelly-before-synthetic")
        tab("关注").performClick()
        compose.mainClock.advanceTimeBy(80)
        assertTrue("Switch must pulse only the decoration", scale() > 1f)
        assertTrue("Pill must visibly travel, not teleport", position() > 0f && position() < 1f)
        frame("jelly-80ms-synthetic")
        compose.mainClock.advanceTimeBy(80)
        frame("jelly-160ms-synthetic")
        assertEquals(bounds, listOf("首页", "关注", "我的").map { tab(it).fetchSemanticsNode().boundsInRoot })
        compose.mainClock.advanceTimeBy(240)
        assertEquals(1f, scale(), 0.001f)
        assertEquals(1f, position(), 0.001f)
        frame("jelly-settled-synthetic")
        tab("关注").performClick()
        compose.mainClock.advanceTimeBy(80)
        assertEquals(1f, scale(), 0.001f)
    }
    @Test fun rapidSwitchCancelsPreviousPulseCleanly() {
        render()
        tab("关注").performClick(); compose.mainClock.advanceTimeBy(80)
        tab("我的").performClick(); compose.mainClock.advanceTimeBy(400)
        tab("我的").assertIsSelected()
        assertEquals(2f, position(), 0.001f)
        assertEquals(1f, scale(), 0.001f)
    }
    @Test fun reducedMotionNeverPulses() {
        render(reduced = true)
        assertEquals(0f, position(), 0.001f)
        tab("关注").performClick(); compose.mainClock.advanceTimeBy(80)
        assertEquals(1f, scale(), 0.001f)
        assertEquals(1f, position(), 0.001f)
    }
    @Test fun heldDragNavigatesOnlyAfterPauseAndCanReverseWithoutLosingThePointer() = scrub(LayoutDirection.Ltr)
    @Test fun settledDragUsesLogicalTabOrderInRtl() = scrub(LayoutDirection.Rtl)

    private fun scrub(direction: LayoutDirection) {
        val selected = mutableIntStateOf(0)
        val callbacks = mutableListOf<Int>()
        compose.mainClock.autoAdvance = false
        compose.setContent { CompositionLocalProvider(LocalLayoutDirection provides direction) {
            MaterialTheme { Surface { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                FloatingNavigation(selected.intValue, false, { callbacks += it; selected.intValue = it })
            } } }
        } }
        compose.mainClock.advanceTimeBy(32)
        val bounds = listOf("首页", "关注", "我的").map { tab(it).fetchSemanticsNode().boundsInRoot }
        val dock = compose.onNodeWithTag("floating-navigation")
        val dockBounds = dock.fetchSemanticsNode().boundsInRoot
        fun point(tab: Int) = bounds[tab].center - dockBounds.topLeft
        frame("dock-settle-${direction}-before-synthetic")
        dock.performTouchInput { down(point(0)); moveTo(point(1), delayMillis = 30); moveTo(point(2), delayMillis = 30) }
        tab("首页").assertIsSelected()
        compose.runOnIdle { assertTrue(callbacks.isEmpty()) }
        frame("dock-settle-${direction}-crossed-tabs-synthetic")
        compose.mainClock.advanceTimeBy(240)
        tab("我的").assertIsSelected()
        compose.runOnIdle { assertEquals(listOf(2), callbacks) }
        compose.mainClock.advanceTimeBy(400)
        frame("dock-settle-${direction}-paused-profile-synthetic")
        // Separate injections force recomposition with the SAME pointer still held.
        dock.performTouchInput { moveTo(point(1), delayMillis = 30) }
        tab("我的").assertIsSelected()
        dock.performTouchInput { moveTo(point(0), delayMillis = 30); up() }
        compose.mainClock.advanceTimeBy(32)
        tab("首页").assertIsSelected()
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle { assertEquals(listOf(2, 0), callbacks) }
        frame("dock-settle-${direction}-released-home-synthetic")
        assertEquals(bounds, listOf("首页", "关注", "我的").map { tab(it).fetchSemanticsNode().boundsInRoot })
        // A normal tap remains immediate, including the existing same-tab reselection.
        tab("首页").performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf(2, 0, 0), callbacks) }
        tab("关注").performTouchInput { click() }
        compose.mainClock.advanceTimeBy(32)
        tab("关注").assertIsSelected()
        compose.runOnIdle { assertEquals(listOf(2, 0, 0, 1), callbacks) }
    }

    @Test fun verticalDragsAndCanceledScrubsDoNotCreatePhantomSelections() {
        val callbacks = mutableListOf<Int>()
        val selected = mutableIntStateOf(0)
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            FloatingNavigation(selected.intValue, true, { callbacks += it; selected.intValue = it })
        } } }
        compose.mainClock.advanceTimeBy(32)
        tab("首页").performTouchInput { down(center); moveBy(Offset(0f, -120f)); up() }
        compose.runOnIdle { assertTrue(callbacks.isEmpty()) }
        compose.onNodeWithTag("floating-navigation").performTouchInput { down(Offset(width / 6f, centerY)); moveTo(Offset(width / 2f, centerY)); cancel() }
        compose.mainClock.advanceTimeBy(400)
        tab("首页").assertIsSelected()
        compose.runOnIdle { assertTrue(callbacks.isEmpty()) }
        tab("我的").performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf(2), callbacks) }
    }

    @Test fun motionInsideOneTabRestartsTimerAndReleasingOutsideCancels() {
        val callbacks = mutableListOf<Int>()
        val selected = mutableIntStateOf(0)
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            FloatingNavigation(selected.intValue, true, { callbacks += it; selected.intValue = it })
        } } }
        compose.mainClock.advanceTimeBy(32)
        val dock = compose.onNodeWithTag("floating-navigation")
        dock.performTouchInput { down(Offset(width / 6f, centerY)); moveTo(Offset(width / 2f - 30f, centerY)) }
        repeat(3) { step ->
            compose.mainClock.advanceTimeBy(100)
            dock.performTouchInput { moveTo(Offset(width / 2f - 10f + step * 20f, centerY)) }
            tab("首页").assertIsSelected()
        }
        compose.runOnIdle { assertTrue(callbacks.isEmpty()) }
        dock.performTouchInput { moveTo(Offset(width + 20f, centerY)); up() }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle { assertTrue(callbacks.isEmpty()) }
    }

    @Test fun backgroundAndRemovalCancelPendingNavigationWithoutRevivingItOnResume() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        val callbacks = mutableListOf<Int>()
        val shown = mutableStateOf(true)
        val selected = mutableIntStateOf(0)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.mainClock.autoAdvance = false
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) {
            MaterialTheme { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (shown.value) FloatingNavigation(selected.intValue, true, { callbacks += it; selected.intValue = it })
            } }
        } }
        compose.mainClock.advanceTimeBy(32)
        val dock = compose.onNodeWithTag("floating-navigation")
        dock.performTouchInput { down(Offset(width / 6f, centerY)); moveTo(Offset(width / 2f, centerY)) }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle { assertTrue(callbacks.isEmpty()); owner.registry.currentState = Lifecycle.State.RESUMED }
        dock.performTouchInput { cancel() }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle { assertTrue(callbacks.isEmpty()) }
        dock.performTouchInput { down(Offset(width / 6f, centerY)); moveTo(Offset(width / 2f, centerY)) }
        compose.runOnIdle { assertTrue("Before removal: $callbacks", callbacks.isEmpty()); shown.value = false }
        // With the manual clock, deliver the requested state change before advancing
        // time: elapsed virtual time alone does not establish node disposal.
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(32)
        dock.assertDoesNotExist()
        compose.runOnIdle { assertTrue("At removal: $callbacks", callbacks.isEmpty()) }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle { assertTrue("After removal: $callbacks", callbacks.isEmpty()) }
    }

    @Test fun disabledMovingDockRejectsTapsAndDrags() {
        val callbacks = mutableListOf<Int>()
        compose.setContent { MaterialTheme { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            FloatingNavigation(0, false, { callbacks += it }, enabled = false)
        } } }
        tab("我的").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithTag("floating-navigation").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertTrue(callbacks.isEmpty()) }
    }

}
