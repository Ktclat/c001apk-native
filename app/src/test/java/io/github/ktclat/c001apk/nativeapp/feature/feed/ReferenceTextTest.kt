// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.feature.feed

import android.text.Html
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class ReferenceTextTest {
    private fun assertImmediateParity(body: String, hotReply: Boolean) {
        val result = classifyReferenceText(body, hotReply)
        assertTrue("Expected immediate text", result is ReferenceText.Immediate)
        val expected = Html.fromHtml(body.take(if (hotReply) 1200 else 4000), Html.FROM_HTML_MODE_LEGACY).toString().trim()
        assertEquals(expected, (result as ReferenceText.Immediate).text)
    }

    @Test fun exactHotFixtureAndRepresentativeLiteralTextMatchAndroidHtml() {
        listOf("", "适中的字号和行距，连续阅读更舒服。", "Hello-world_123!", "中文：「标点」！（测试）", "😀👍🏽❤️", ">not-a-tag", "#标题**原文**").forEach { text ->
            assertImmediateParity(text, true)
            assertImmediateParity(text, false)
        }
    }

    @Test fun everyAllowlistedCodePointMatchesAndroidHtmlInBoundedBatches() {
        val points = (0x21..0x7e).filter { it != '<'.code && it != '&'.code } +
            (0x4e00..0x9fff) + (0x3001..0x303f) + (0xff01..0xff60) +
            (0x2600..0x27bf) + listOf(0xfe0f) + (0x1f000..0x1faff)
        // 400 code points is at most 800 UTF-16 units: no boundary is accidentally skipped.
        points.chunked(400).forEach { chunk ->
            val text = chunk.joinToString("") { String(Character.toChars(it)) }
            assertImmediateParity(text, true)
            assertImmediateParity(text, false)
        }
    }

    @Test fun generatedEligibleCombinationsMatchAndroidHtmlAtBothLimits() {
        val random = Random(42)
        val alphabet = listOf("中", "文", "。", "，", "！", "A", ">", "'", "\"", "=", ";", "#", "😀", "👍", "🏽", "❤", "️")
        repeat(80) {
            val text = buildString { repeat(random.nextInt(1, 2400)) { append(alphabet[random.nextInt(alphabet.size)]) } }
            for (hot in listOf(true, false)) {
                val bounded = text.take(if (hot) 1200 else 4000)
                if (bounded.last().isHighSurrogate()) assertEquals(ReferenceText.Html, classifyReferenceText(text, hot))
                else assertImmediateParity(text, hot)
            }
        }
    }

    @Test fun whitespaceControlMarkupAndUnfamiliarCharactersKeepHtmlPath() {
        val ambiguous = listOf("‘标点’", "“标点”", "<b>文字</b>", "&amp;", "&#128512;", "a < b", "&", "<", "é", "\u200d", "\ufeff", "\uffff", "\ud800", "\udc00", "\ud800A", "A\udc00", "\ud83d\ude00\ud800") +
            (0..0x20).map { "中${it.toChar()}文" } +
            (0x7f..0x9f).map { "中${it.toChar()}文" } +
            listOf('\u00a0', '\u1680', '\u2000', '\u2007', '\u200b', '\u2028', '\u2029', '\u202f', '\u205f', '\u3000').map { "中${it}文" }
        ambiguous.forEach { text ->
            assertEquals(ReferenceText.Html, classifyReferenceText(text, true))
            assertEquals(ReferenceText.Html, classifyReferenceText(text, false))
        }
    }

    @Test fun exactUtf16BoundsAndCutSurrogatesRetainExistingSemantics() {
        for (hot in listOf(true, false)) {
            val limit = if (hot) 1200 else 4000
            assertImmediateParity("中".repeat(limit + 20), hot)
            assertImmediateParity("中".repeat(limit) + "<b>&amp; \ud800", hot)
            assertImmediateParity("中".repeat(limit - 2) + "😀suffix", hot)
            assertEquals(ReferenceText.Html, classifyReferenceText("中".repeat(limit - 1) + "😀", hot))
            assertEquals(ReferenceText.Html, classifyReferenceText("中".repeat(limit - 1) + "<", hot))
            assertEquals(ReferenceText.Html, classifyReferenceText("中".repeat(limit - 1) + "&", hot))
        }
        // The same body can be immediately plain as a hot reply but require HTML as a forward.
        val body = "中".repeat(1200) + "<b>尾部</b>"
        assertTrue(classifyReferenceText(body, true) is ReferenceText.Immediate)
        assertEquals(ReferenceText.Html, classifyReferenceText(body, false))
    }
}
