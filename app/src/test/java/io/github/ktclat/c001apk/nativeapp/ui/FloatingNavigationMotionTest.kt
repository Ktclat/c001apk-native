package io.github.ktclat.c001apk.nativeapp.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableIntStateOf
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
}
