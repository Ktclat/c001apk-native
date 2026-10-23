// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import io.github.ktclat.c001apk.nativeapp.domain.FailureKind
import io.github.ktclat.c001apk.nativeapp.settings.*
import io.github.ktclat.c001apk.nativeapp.ui.SystemAccessibility
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Real diagnostic composable/native Canvas; synthetic journal, no request or device acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h480dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeDiagnosticsReadabilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var density = 0f
    private val fontScale = 1.6f
    private val scroll get() = compose.onNode(hasScrollAction())
    private val directory = File("build/verification/home-diagnostics-readability")

    @Test fun populatedJournalTopScopeFirstLastAndTailRemainReachableAtLargeFont() {
        val journal = HomeRequestDiagnostics(Any()) { 0L }
        repeat(12) { index ->
            journal.append(0L, HomeRequestEvent(
                if (index == 0) HomeRequestTab.DYNAMIC else if (index == 11) HomeRequestTab.PICTURES else HomeRequestTab.HOT,
                if (index == 0) HomeRequestEndpoint.INDEX_V8 else HomeRequestEndpoint.PAGE_DATA_LIST,
                if (index == 0) HomeRequestHost.API2 else HomeRequestHost.API,
                HomeRequestMode.GUEST, false, HomeProtocolInfo(2604201, 35, true), 400 + index,
                HomeContentType.HTML, FailureKind.HTTP, 100L + index))
        }
        val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("fixture", "unchanged before explicit Copy"))
        var backs = 0
        compose.setContent {
            val measured = LocalDensity.current
            density = measured.density
            CompositionLocalProvider(LocalDensity provides Density(measured.density, fontScale)) {
                MaterialTheme(colorScheme = fixedColorScheme(0, false), typography = appTypography(FontChoice.NOTO_SANS)) {
                    NativeDiagnosticsScreen(false, SystemAccessibility(false, false), { backs++ }, journal)
                }
            }
        }
        compose.waitForIdle()
        assertEquals(12, journal.snapshot().size)
        compose.onNodeWithTag("copy-native-diagnostics").assertIsDisplayed()
        compose.onNodeWithText("返回").assertIsDisplayed()
        capture("01-top")
        val scope = compose.onNodeWithText("动态、热门、话题、数码、酷图的既有请求；", substring = true, useUnmergedTree = true)
        revealLine(scope, 0)
        capture("02-scope")
        val events = journal.snapshot().joinToString("\n") { it.text() }
        val eventNode = compose.onNodeWithText(events, useUnmergedTree = true)
        revealLine(eventNode, 0)
        capture("03-first-event")
        revealLine(eventNode, events.lastIndexOf('\n') + 1)
        capture("04-last-event")
        revealLine(eventNode, events.length - 1)
        capture("05-event-tail")
        assertEquals("unchanged before explicit Copy", clipboard.primaryClip!!.getItemAt(0).text.toString())
        compose.onNodeWithTag("copy-native-diagnostics").assertIsDisplayed().performClick()
        val copied = clipboard.primaryClip!!.getItemAt(0).text.toString()
        assertTrue(copied.contains(events)); assertTrue(copied.contains("HTTP=400")); assertTrue(copied.contains("HTTP=411"))
        assertTrue(copied.contains("无记录不代表成功")); assertFalse(copied.contains("https://"))
        compose.onNodeWithText("返回").assertIsDisplayed().performClick()
        assertEquals(1, backs)
    }

    /** Individual lines in the long existing Text must be visible, not merely present in semantics. */
    private fun revealLine(node: SemanticsNodeInteraction, offset: Int) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout = layouts.single()
        assertEquals(density, layout.layoutInput.density.density, .001f)
        assertEquals(fontScale, layout.layoutInput.density.fontScale, .001f)
        val line = layout.getLineForOffset(offset)
        val viewport = scroll.fetchSemanticsNode().boundsInRoot
        val before = node.getUnclippedBoundsInRoot()
        val delta = before.top.value * density + layout.getLineTop(line) - viewport.top - 8f * density
        scroll.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta) }
        compose.waitForIdle()
        val after = node.getUnclippedBoundsInRoot()
        val top = after.top.value * density + layout.getLineTop(line)
        val bottom = after.top.value * density + layout.getLineBottom(line)
        val visible = scroll.fetchSemanticsNode().boundsInRoot
        assertTrue("Target line top=$top outside $visible", top >= visible.top - 1f)
        assertTrue("Target line bottom=$bottom outside $visible", bottom <= visible.bottom + 1f)
        assertTrue("Target text is horizontally clipped", after.left.value * density >= visible.left - 1f && after.right.value * density <= visible.right + 1f)
        directory.mkdirs()
        File(directory, "line-reachability.txt").appendText("offset=$offset; line=$line; topPx=$top; bottomPx=$bottom; viewportPx=$visible; density=$density; fontScale=$fontScale\n")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val root = compose.onNodeWithTag("native-diagnostics").getUnclippedBoundsInRoot()
        val viewport = scroll.fetchSemanticsNode().boundsInRoot
        val range = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        val copy = compose.onNodeWithTag("copy-native-diagnostics").getUnclippedBoundsInRoot()
        val back = compose.onNodeWithText("返回").getUnclippedBoundsInRoot()
        val bitmap = compose.runOnUiThread {
            val view = compose.activity.findViewById<View>(android.R.id.content)
            check(view.width > 0 && view.height > 0)
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        directory.mkdirs()
        try {
            File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            File(directory, "$name.metrics.txt").writeText(
                "Actual native test view, not physical-device evidence.\nwidthPx=${bitmap.width}; heightPx=${bitmap.height}; density=$density; fontScale=$fontScale\n" +
                "rootDp=$root\nscrollViewportPx=$viewport\nscrollValue=${range.value()}; scrollMax=${range.maxValue()}\ncopyDp=$copy\nbackDp=$back\n")
        } finally { bitmap.recycle() }
    }
}
