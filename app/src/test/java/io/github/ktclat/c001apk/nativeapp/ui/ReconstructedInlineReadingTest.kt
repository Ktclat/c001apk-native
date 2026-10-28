// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import io.github.ktclat.c001apk.nativeapp.domain.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Newly reconstructed tests; historical logs/approval are not represented by this file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReconstructedInlineReadingTest {
    private fun occurrences(text: AnnotatedString): List<Pair<Int, String>> = inlineRichContent(text, true).text
        .getStringAnnotations(0, text.length).filter { InlineEmoticons.contains(it.item) }.map { it.start to it.item }

    @Test fun nestedUnknownGrammarSurvivesEveryPreviewAndDetailSplit() {
        for (size in listOf(1200, 4000)) for (padding in listOf(size - 2, size - 1, size)) {
            for (suffix in listOf("[[笑哭]后[受虐滑稽]", "[未知[笑哭]后[笑哭]", "[" + "x".repeat(5000) + "[笑哭]后[笑哭]")) {
                val raw = "前".repeat(padding) + suffix
                val rich = withEmoticonLiteralContext(AnnotatedString(raw))
                val ranges = richTextPageRanges(rich, size)
                assertEquals(raw, ranges.joinToString("") { rich.subSequence(it.first, it.last + 1).text })
                assertTrue(ranges.all { it.count() <= size })
                val split = ranges.flatMap { range -> occurrences(rich.subSequence(range.first, range.last + 1)).map { (offset, token) -> offset + range.first to token } }
                assertEquals("$size/$padding must not expose a nested literal token", occurrences(rich), split)
                assertEquals(1, split.size)
            }
        }
    }

    @Test fun recognizedTokensAndSurrogatesRemainWholeWithinFixedPageBudgets() {
        val raw = "前".repeat(1198) + "[受虐滑稽]🎁" + "后".repeat(4000) + "[笑哭]"
        val rich = withEmoticonLiteralContext(AnnotatedString(raw))
        for (size in listOf(1200, 4000)) {
            val slices = richTextPageRanges(rich, size).map { rich.subSequence(it.first, it.last + 1) }
            assertEquals(raw, slices.joinToString("") { it.text })
            assertTrue(slices.all { it.length <= size && it.text.firstOrNull()?.isLowSurrogate() != true && it.text.lastOrNull()?.isHighSurrogate() != true })
            assertEquals(2, slices.sumOf { occurrences(it).size })
        }
    }

    @Test fun unknownMarkersDoNotConsumeBudgetOrRescanStyleSpansAndCodeIsLiteral() {
        val raw = "[unknown]".repeat(400) + "[笑哭]".repeat(140)
        val styled = AnnotatedString.Builder(raw).apply {
            repeat(400) { addStyle(SpanStyle(fontFamily = FontFamily.Serif), it * 9, it * 9 + 8) }
        }.toAnnotatedString()
        assertEquals(128, occurrences(styled).size)
        assertEquals(raw, inlineRichContent(styled, true).text.text)
        assertTrue(occurrences(AnnotatedString("[笑哭]", SpanStyle(fontFamily = FontFamily.Monospace))).isEmpty())
        assertTrue(inlineRichContent(styled, false).tokens.isEmpty())
    }

    @Test fun fullReaderKeepsAllLinkOccurrencesWithLocalOffsetsAndStyles() {
        val source = "前".repeat(3998) + "<a href='/feed/42'><b>[笑哭]后</b></a><a href='/feed/42'>[受虐滑稽]</a>"
        val ready = prepareFullPost(FullPostBody.Available(source)) as PreparedFullPost.Ready
        assertEquals("前".repeat(3998) + "[笑哭]后[受虐滑稽]", ready.pages.joinToString("") { it.text })
        val last = ready.pages.last()
        assertEquals(2, last.links.size)
        assertEquals(0, last.links.first().start)
        assertTrue(last.richText.spanStyles.isNotEmpty())
        assertEquals(2, linkedRichText(inlineRichContent(last.richText, true).text, last.links, Color.Blue) {}.getLinkAnnotations(0, last.text.length).size)
    }

    @Test fun legacyLocalImagesAreAdmittedWithoutWeakeningRemoteOrCodeBoundaries() {
        val img = "<img src='coolapk_emotion_31_xiaoku.png'>"
        val ready = prepareFullPost(FullPostBody.Available("[笑哭][受虐滑稽]前${img}后<tt>[笑哭]</tt>")) as PreparedFullPost.Ready
        assertEquals(3, occurrences(ready.pages.single().richText).size)
        val structured = FullPostBody.Structured(rawOutput = "[]", title = "前${img}后", cover = null, blocks = listOf(ArticleBlock.Text("正文${img}")))
        val article = prepareArticle(structured) as PreparedFullPost.Article
        assertEquals(2, article.pages.flatMap { it.fragments }.filterIsInstance<ArticleFragment.RichText>().sumOf { occurrences(it.value).size })
        val remote = prepareFullPost(FullPostBody.Available("<img src='https://example.test/p.png'>"))
        assertEquals(PreparedFullPost.Unavailable(FullPostBodyLimit.UNSUPPORTED_MARKUP), remote)
        assertEquals("<tt>$img</tt>", normalizeLocalInlineImages("<tt>$img</tt>"))
        assertEquals("`[笑哭]` $img", normalizeLocalInlineImages("`[笑哭]` $img"))
    }

    @Test fun markdownCodeRawAndEventLiteralAreNotDecorated() {
        val raw = "**正文[笑哭]** 与 `[笑哭]` [未知]"
        val prepared = prepareFullPost(FullPostBody.Available(raw)) as PreparedFullPost.Article
        val text = prepared.pages.flatMap { it.fragments }.filterIsInstance<ArticleFragment.RichText>().single()
        assertEquals(1, occurrences(text.value).size)
        assertEquals(raw, prepared.sourcePages!!.joinToString("") { it.text })
        assertTrue(prepared.sourcePages!!.all { !it.renderInline })
        val literal = prepareEventLiteral(FullPostBody.Available("[笑哭]<b>原文</b>")) {} as PreparedFullPost.Ready
        assertEquals("[笑哭]<b>原文</b>", literal.pages.single().text)
        assertFalse(literal.pages.single().renderInline)
    }

    @Test fun safeLinksStayBoundedAndDoNotActivateForbiddenSchemes() {
        val raw = "a".repeat(150)
        val links = (0..149).map { PostLink(it, it + 1, "a", "/feed/${it + 1}") }
        assertEquals(128, linkedRichText(AnnotatedString(raw), links, Color.Blue) {}.getLinkAnnotations(0, raw.length).size)
        for (url in listOf("javascript:alert(1)", "file:///tmp/x", "content://private/x", "data:text/html,x"))
            assertTrue(linkedRichText(AnnotatedString("x"), listOf(PostLink(0, 1, "x", url)), Color.Blue) {}.getLinkAnnotations(0, 1).isEmpty())
    }
}
