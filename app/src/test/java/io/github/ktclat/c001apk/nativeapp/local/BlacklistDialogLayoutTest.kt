// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.local

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.ktclat.c001apk.nativeapp.diagnostics.NativeBuildInfo
import io.github.ktclat.c001apk.nativeapp.settings.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowDisplay
import java.io.File

/** Conservative safe-window geometry, not a substitute for actual rotated compositor review. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], qualifiers = "w592dp-h336dp-land-320dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BlacklistDialogLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var oldScale = 1f
    private val state = object : JsonStateStore<LocalState> {
        override val data = MutableStateFlow(LocalState(blacklist = Blacklist(userBlackList = listOf("42"), topicBlackList = listOf("topic"))))
        override suspend fun update(transform: (LocalState) -> LocalState) { data.value = transform(data.value) }
    }
    private val initial = state.data.value
    private val output = File("build/verification/blacklist-dialog-geometry")
    private var layoutEvidence = buildJsonObject { }

    @After fun resetScale() { compose.runOnUiThread { RuntimeEnvironment.setFontScale(oldScale) } }

    @Test fun trueShortLandscapeDarkKeepsInitialWarningAndScrolledTailReadable() = verify(dark = true, landscape = true)
    @Test fun trueShortLandscapeLightKeepsInitialWarningAndScrolledTailReadable() = verify(dark = false, landscape = true)
    @Test @Config(qualifiers = "w320dp-h568dp-port-320dpi")
    fun narrowPortraitDarkKeepsInitialWarningAndScrolledTailReadable() = verify(dark = true, landscape = false)
    @Test @Config(qualifiers = "w320dp-h568dp-port-320dpi")
    fun narrowPortraitLightKeepsInitialWarningAndScrolledTailReadable() = verify(dark = false, landscape = false)

    private fun verify(dark: Boolean, landscape: Boolean) {
        // Physical landscape is 1280x720 at320dpi, not the earlier822x369dp fixture.
        // Remove the real26 emulator's right48dp nav and top24dp status insets from
        // the dialog budget. Portrait is deliberately narrower than that emulator.
        val physicalWidth = if (landscape) 1280 else 640
        val physicalHeight = if (landscape) 720 else 1280
        val safeWidth = if (landscape) 1184 else 640
        val safeHeight = if (landscape) 672 else 1136
        compose.runOnUiThread {
            oldScale = RuntimeEnvironment.getFontScale(); RuntimeEnvironment.setFontScale(2f)
            shadowOf(ShadowDisplay.getDefaultDisplay()).apply {
                setWidth(safeWidth); setHeight(safeHeight)
                setRealWidth(physicalWidth); setRealHeight(physicalHeight)
            }
            compose.activity.window.setLayout(safeWidth, safeHeight)
        }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, 2.4f)) {
                MaterialTheme(colorScheme = fixedColorScheme(0, dark), typography = appTypography(FontChoice.NOTO_SANS)) {
                    BlacklistScreen(LocalRepository(state), {}, {})
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("blacklist-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("blacklist-list").performScrollToNode(hasText("清空用户列表"))
        compose.onNodeWithText("清空用户列表").performClick()
        compose.onNodeWithTag("blacklist-clear-title").assertExists()
        compose.waitForIdle()
        // Dialogs have their own Window; constrain that window too, rather than
        // accepting an activity-only resize which can leave a taller dialog.
        compose.runOnUiThread {
            requireNotNull(ShadowDialog.getLatestDialog().window).let { window ->
                // Keep production width policy; forcing width here would mask the old bug.
                window.setLayout(window.attributes.width, safeHeight)
            }
        }
        compose.waitForIdle()
        val prefix = "${if (landscape) "landscape640x360" else "portrait320x640"}-${if (dark) "dark" else "light"}-font240"
        capture("$prefix-initial", physicalWidth, physicalHeight, safeWidth, safeHeight)
        val body = textLayout("blacklist-clear-body")
        val title = textLayout("blacklist-clear-title")
        assertEquals(2.4f, title.layoutInput.density.fontScale, .001f)
        assertEquals(2.4f, body.layoutInput.density.fontScale, .001f)
        if (landscape) assertEquals("The wide short dialog must not waste a second title line", 1, title.lineCount)
        assertActions()
        assertTextRangeVisible(body, 0 until body.layoutInput.text.text.indexOf('。') + 1, "initial warning")
        capture("$prefix-initial", physicalWidth, physicalHeight, safeWidth, safeHeight)
        compose.onNodeWithTag("blacklist-clear-viewport").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        compose.waitForIdle()
        capture("$prefix-tail", physicalWidth, physicalHeight, safeWidth, safeHeight)
        val tail = body.layoutInput.text.text.indexOf("可先导出备份。")
        assertTrue(tail >= 0)
        assertTextRangeVisible(body, tail until body.layoutInput.text.length, "backup tail")
        assertActions()
        capture("$prefix-tail", physicalWidth, physicalHeight, safeWidth, safeHeight)
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(initial, state.data.value) }
    }

    private fun textLayout(tag: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }
    private fun assertActions() {
        for (text in listOf("取消", "确认清空")) {
            val button = compose.onNodeWithText(text).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            val bounds = button.getUnclippedBoundsInRoot()
            val clipped = button.fetchSemanticsNode().boundsInRoot
            assertEquals((bounds.bottom - bounds.top).value * 2f, clipped.height, 1f)
            assertEquals((bounds.right - bounds.left).value * 2f, clipped.width, 1f)
        }
    }
    private fun assertTextRangeVisible(layout: TextLayoutResult, offsets: IntRange, label: String) {
        val body = compose.onNodeWithTag("blacklist-clear-body").getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag("blacklist-clear-viewport").fetchSemanticsNode().boundsInRoot
        val boxes = offsets.map { offset -> layout.getBoundingBox(offset).translate(body.left.value * 2f, body.top.value * 2f) }
        val line = layout.getLineForOffset(offsets.first)
        val lineHeight = layout.getLineBottom(line) - layout.getLineTop(line)
        // A nonzero ScrollAxisRange and visible node can still be only glyph slivers.
        // Require complete actual glyph boxes, including the sentence's final line.
        assertTrue("$label viewport ${viewport.height}px must fit a complete ${lineHeight}px line", viewport.height + 1f >= lineHeight)
        boxes.forEachIndexed { index, box ->
            assertTrue("$label char${offsets.first + index} $box must be inside clipped $viewport",
                box.left >= viewport.left - 1f && box.right <= viewport.right + 1f &&
                    box.top >= viewport.top - 1f && box.bottom <= viewport.bottom + 1f)
        }
        layoutEvidence = buildJsonObject {
            put("range", label); put("effectiveFontScale", layout.layoutInput.density.fontScale)
            put("viewport", viewport.toString()); put("lineHeightPx", lineHeight)
            put("visibleCharacterBoxes", JsonArray(boxes.map { JsonPrimitive(it.toString()) }))
        }
    }
    private fun capture(name: String, width: Int, height: Int, safeWidth: Int, safeHeight: Int) {
        output.mkdirs()
        val view = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
        val bitmap = compose.runOnUiThread { Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) } }
        try { File(output, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        File(output, "$name.json").writeText(buildJsonObject {
            put("sourceFingerprint", NativeBuildInfo.SOURCE_FINGERPRINT); put("syntheticOnly", true)
            put("physicalWidthPx", width); put("physicalHeightPx", height)
            put("safeWidthPx", safeWidth); put("safeHeightPx", safeHeight)
            put("dialogDecorWidthPx", view.width); put("dialogDecorHeightPx", view.height)
            put("bodyGeometry", layoutEvidence)
        }.toString())
    }
}
