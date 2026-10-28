package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.ktclat.c001apk.nativeapp.domain.FullPostBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exact unchanged body that produced OBJ in the independently reviewed cc4ea0e emulator PNG. */
private const val ACTUAL_UI_BODY = "<p>合成正文[受虐滑稽][受虐滑稽][受虐滑稽]，换行测试[笑哭][笑哭][笑哭]🎁。</p>" +
    "<p>特殊标记[图片][楼主][层主][置顶]，未知[QA未知表情]。</p>" +
    "<p><a href=\"/feed/9802\">合成链接[笑哭]</a>，代码<tt>[笑哭]</tt>保持文字。</p>" +
    "<p>本地图像<img src=\"coolapk_emotion_31_xiaoku.png\">。</p>"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalImageUiRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun exactUiBodyNormalizesThePreviouslyMissingLocalImageNotAnEarlierMarker() {
        val expected = ACTUAL_UI_BODY.replace("<img src=\"coolapk_emotion_31_xiaoku.png\">", "[笑哭]")
        assertEquals(expected, normalizeLocalInlineImages(ACTUAL_UI_BODY))
        val parsed = decodePostHtml(ACTUAL_UI_BODY)
        assertFalse("Known local img must never become Android ImageSpan's OBJ character", '\uFFFC' in parsed.text)
        val localStart = parsed.text.indexOf("本地图像") + "本地图像".length
        assertTrue(localStart >= "本地图像".length)
        assertEquals("[笑哭]", parsed.text.substring(localStart, localStart + "[笑哭]".length))
        val rendered = inlineRichContent(parsed.richText, true)
        assertEquals(1, rendered.text.getStringAnnotations(localStart, localStart + 4).count {
            it.item == "[笑哭]" && it.start == localStart && it.end == localStart + 4
        })
        val codeStart = parsed.text.indexOf("代码") + 2
        assertEquals("[笑哭]", parsed.text.substring(codeStart, codeStart + 4))
        assertFalse(rendered.text.getStringAnnotations(codeStart, codeStart + 4).any { it.item == "[笑哭]" })
        assertTrue("Unknown source token remains readable", "[QA未知表情]" in rendered.text.text)
        assertEquals("/feed/9802", parsed.links.single().url)
        assertEquals("合成链接[笑哭]", parsed.links.single().label)
        val full = prepareFullPost(FullPostBody.Available(ACTUAL_UI_BODY))
        assertTrue("Full reader must admit this exact safe legacy body", full is PreparedFullPost.Ready)
        assertEquals(parsed.text, (full as PreparedFullPost.Ready).pages.single().text)
        assertEquals(ACTUAL_UI_BODY, rawDetailPages(FullPostBody.Available(ACTUAL_UI_BODY)) {}.single().text)
    }

    @Test fun productionLayoutReservesAnImageAtTheExactLocalImgOccurrence() {
        val parsed = decodePostHtml(ACTUAL_UI_BODY)
        val localStart = parsed.text.indexOf("本地图像") + 4
        compose.setContent { MaterialTheme {
            NativeRichText(parsed.richText, Modifier.testTag("actual-ui-body"), MaterialTheme.typography.bodyMedium,
                parsed.links, {})
        } }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("actual-ui-body").performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            assertTrue(it(layouts))
        }
        val layout = layouts.single()
        val index = layout.layoutInput.placeholders.indexOfFirst { it.start == localStart && it.end == localStart + 4 }
        assertTrue("The local img after 本地图像 needs its own inline image, not an earlier smile", index >= 0)
        val box = layout.placeholderRects[index]
        assertNotNull(box)
        assertTrue("local=$box; all=${layout.placeholderRects}; style=${layout.layoutInput.style}; density=${layout.layoutInput.density}", box!!.width > 0 && box.height > 0)
        assertFalse('\uFFFC' in layout.layoutInput.text.text)
    }

    @Test fun oddBackslashEscapedLocalImageStartsRemainExactSourceWithAdjacentEmotes() {
        val image = "<img src='coolapk_emotion_31_xiaoku.png'>"
        for (slashes in 0..4) {
            val raw = "[笑哭][笑哭] " + "\\".repeat(slashes) + image
            val expected = if (slashes % 2 == 1) raw else raw.replace(image, "[笑哭]")
            assertEquals("Backslash count $slashes", expected, normalizeLocalInlineImages(raw))
        }
        val encoded = "[笑哭][笑哭] &lt;img src='coolapk_emotion_31_xiaoku.png'&gt;"
        assertEquals(encoded, normalizeLocalInlineImages(encoded))
    }

    @Test fun intrawordDeviceNamesDoNotDisableLocalImagesButMarkdownStillDoes() {
        for (plain in listOf("POCO_X3_GT", "Redmi_K100_Pro", "X3_2026", "型号_代号_国行")) {
            val raw = "<p>$plain [受虐滑稽][笑哭] <img src='coolapk_emotion_31_xiaoku.png'></p>"
            assertEquals(raw.replace("<img src='coolapk_emotion_31_xiaoku.png'>", "[笑哭]"), normalizeLocalInlineImages(raw))
        }
        for (markdown in listOf("_强调_", "**强调**", "~~删除~~", "`代码`", "POCO__X3__GT", "[链接](https://example.test)",
            "[label][ref]", "[[笑哭][笑哭]", "[笑哭][ref]", "[label][笑哭]", "[未知[笑哭][受虐滑稽]",
            "[笑哭][受虐滑稽]\n[受虐滑稽]: /feed/42", "[笑哭][受虐滑稽]<p>[受虐滑稽]: /feed/42</p>")) {
            val raw = "$markdown <img src='coolapk_emotion_31_xiaoku.png'>"
            assertEquals("Mixed Markdown must remain in its original safety path: $markdown", raw, normalizeLocalInlineImages(raw))
        }
    }
}
