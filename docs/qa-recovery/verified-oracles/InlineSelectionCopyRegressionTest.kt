// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.ktclat.c001apk.nativeapp.domain.FullPostBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Exact source and independent 97-UTF-16-unit expected clipboard from the rejected Android run.
private const val COPY_SOURCE = "合成正文[受虐滑稽][受虐滑稽][受虐滑稽]，换行测试[笑哭][笑哭][笑哭]🎁。\n" +
    "特殊标记[图片][楼主][层主][置顶]，未知[QA未知表情]。\n" +
    "**合成加粗**，代码`[笑哭]`保持文字，[合成链接](/feed/9802)。"
private const val COPY_EXPECTED = "合成正文[受虐滑稽][受虐滑稽][受虐滑稽]，换行测试[笑哭][笑哭][笑哭]🎁。\n" +
    "特殊标记[图片][楼主][层主][置顶]，未知[QA未知表情]。\n" +
    "合成加粗，代码[笑哭]保持文字，合成链接。"

/** Real Foundation registration, long press, Select all, Copy and Android clipboard readback.
 * Only the toolbar host and unsupported hardware magnifier are adapted for Robolectric; neither
 * selection nor clipboard content is synthesized. Actual Android toolbar/pixels are a separate gate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [PreviewMagnifierShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InlineSelectionCopyRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val toolbar = SelectionToolbar()

    @Test fun exactRejectedFormattedReaderCopies97UnitsAndRawSourceRemainsExact() {
        assertEquals(97, COPY_EXPECTED.length)
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalTextToolbar provides toolbar) {
            FullPostText(FullPostBody.Available(COPY_SOURCE), "", {}, null)
        } } }
        await("article-text-0")
        copyAll("article-text-0", COPY_EXPECTED)
        compose.onNodeWithTag("detail-source-toggle").performClick()
        await("detail-source-page")
        copyAll("detail-source-page", COPY_SOURCE)
    }

    @Test fun showEmojiOffOnRecompositionNeverRegistersDuplicateBadgeCopySources() {
        var show by mutableStateOf(false)
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalTextToolbar provides toolbar,
            LocalShowEmoticons provides show) {
            FullPostText(FullPostBody.Available(COPY_SOURCE), "", {}, null)
        } } }
        await("article-text-0")
        for (enabled in listOf(false, true, false, true)) {
            compose.runOnIdle { show = enabled }
            if (enabled) compose.onNodeWithTag("inline-emoticon:[楼主]", useUnmergedTree = true).assertExists()
            else compose.onNodeWithTag("inline-emoticon:[楼主]", useUnmergedTree = true).assertDoesNotExist()
            copyAll("article-text-0", COPY_EXPECTED)
        }
    }

    @Test fun linkedDecorativeBadgeRetainsPointerTargetAndMixedTextCopiesOnlyOnce() {
        val source = "前<a href='/feed/9802'>[楼主][笑哭]</a>[层主][置顶][图片]，<tt>[笑哭]</tt>[未知]后"
        val expected = "前[楼主][笑哭][层主][置顶][图片]，[笑哭][未知]后"
        val parsed = decodePostHtml(source)
        val opened = mutableListOf<String>()
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalTextToolbar provides toolbar) {
            SelectionContainer { NativeRichText(parsed.richText, Modifier.testTag("mixed"), MaterialTheme.typography.bodyLarge,
                parsed.links, { opened += it }) }
        } } }
        val layout = layout("mixed")
        val badgeIndex = layout.layoutInput.placeholders.indexOfFirst { it.start == 1 && it.end == 5 }
        assertTrue(badgeIndex >= 0)
        val badge = requireNotNull(layout.placeholderRects[badgeIndex])
        assertTrue(badge.width > 0 && badge.height > 0)
        compose.onNodeWithTag("mixed").performTouchInput { click(badge.center) }
        compose.runOnIdle { assertEquals(listOf("/feed/9802"), opened) }
        copyAll("mixed", expected)
    }

    private fun await(tag: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1
    }

    private fun layout(tag: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        return layouts.single()
    }

    private fun copyAll(tag: String, expected: String) {
        compose.onNodeWithTag(tag).assertTextEquals(expected)
        val point = layout(tag).getBoundingBox(0).center
        compose.onNodeWithTag(tag).performTouchInput { longClick(point) }
        compose.runOnIdle {
            assertEquals(TextToolbarStatus.Shown, toolbar.status)
            requireNotNull(toolbar.selectAll) { "Production selection must offer Select all" }.invoke()
        }
        compose.waitForIdle()
        val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        var writes = 0
        val listener = ClipboardManager.OnPrimaryClipChangedListener { writes++ }
        compose.runOnIdle {
            clipboard.addPrimaryClipChangedListener(listener)
            try {
                requireNotNull(toolbar.copy) { "Production selection must offer Copy" }.invoke()
                val clip = requireNotNull(clipboard.primaryClip)
                assertEquals(1, clip.itemCount)
                val copied = clip.getItemAt(0).coerceToText(compose.activity).toString()
                assertEquals("Exact clipboard, without trimming/replacing badge suffixes", expected, copied)
                assertEquals(expected.length, copied.length)
                assertTrue("Copy must write the Android clipboard, not reuse an earlier clip", writes > 0)
            } finally { clipboard.removePrimaryClipChangedListener(listener) }
        }
    }

    private class SelectionToolbar : TextToolbar {
        override var status = TextToolbarStatus.Hidden
        var copy: (() -> Unit)? = null
        var selectAll: (() -> Unit)? = null
        override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
            status = TextToolbarStatus.Shown; copy = onCopyRequested; selectAll = onSelectAllRequested
        }
        override fun hide() { status = TextToolbarStatus.Hidden; copy = null; selectAll = null }
    }
}
