// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import io.github.ktclat.c001apk.nativeapp.domain.*
import io.github.ktclat.c001apk.nativeapp.settings.*
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MarkdownParsingTest {
    private fun markdown(source: String) = requireNotNull(prepareMarkdownText(source, ArticleTextRole.BODY, 7))
    private fun text(value: MarkdownText) = value.fragments.joinToString("\n") { it.value.text }
    private fun article(source: String) = prepareFullPost(FullPostBody.Available(source)) as PreparedFullPost.Article

    @Test fun conservativeDetectionDoesNotReinterpretNormalPosts() {
        for (source in listOf("#一加13#", "POCO_X3_GT", "1. 一项", "- 一项", "https://example.test", "普通标点 * _ ~", "~ordinary~", "1~2", "普通文本")) {
            assertNull(source, prepareMarkdownText(source, ArticleTextRole.BODY, 0))
            assertTrue(prepareFullPost(FullPostBody.Available(source)) is PreparedFullPost.Ready)
        }
    }

    @Test fun commonBlocksAndInlineStylesAreActuallyRendered() {
        val parsed = markdown("# 一级\n\n## 二级\n\n**粗体** *斜体* ~~删除~~ `内联`\n\n> 引用\n\n- 第一\n- 第二\n\n3. 有序\n4. 下一\n\n---")
        assertEquals(listOf(1, 2), parsed.fragments.map { it.markdownStyle.heading }.filter { it != 0 })
        val spans = parsed.fragments.flatMap { it.value.spanStyles }.map { it.item }
        assertTrue(spans.any { it.fontWeight == FontWeight.Bold })
        assertTrue(spans.any { it.fontStyle == FontStyle.Italic })
        assertTrue(spans.any { it.textDecoration == TextDecoration.LineThrough })
        assertTrue(spans.any { it.fontFamily == FontFamily.Monospace })
        assertTrue(parsed.fragments.any { it.markdownStyle.quoteDepth == 1 })
        assertTrue(text(parsed).contains("• 第一")); assertTrue(text(parsed).contains("3. 有序"))
        assertFalse(text(parsed).contains("**"))
    }

    @Test fun setextHeadingAndIndentedCodeDoNotDependOnInlinePunctuation() {
        val heading = markdown("标题\n====")
        assertEquals(1, heading.fragments.single().markdownStyle.heading)
        assertEquals("标题", text(heading))
        for (raw in listOf("    plain code", "\tplain code")) {
            val code = markdown(raw).fragments.single()
            assertTrue(code.markdownStyle.code)
            assertEquals("plain code\n", code.value.text)
        }
        assertNull(prepareMarkdownText("a = b", ArticleTextRole.BODY, 0))
        assertNull(prepareMarkdownText("普通段落\n    仍然是段落", ArticleTextRole.BODY, 0))
    }

    @Test fun listOnlyAndNestedReadOnlyTasksAreDetected() {
        for (source in listOf("- 一\n- 二", "1. 一\n2. 二", "- [x] 完成\n  - [ ] 等待")) {
            assertNotNull(prepareMarkdownText(source, ArticleTextRole.BODY, 0))
        }
        val tasks = markdown("- [x] 完成\n  - [ ] 等待")
        assertTrue(text(tasks).contains("☑ 完成")); assertTrue(text(tasks).contains("☐ 等待"))
        assertTrue(tasks.fragments.any { it.markdownStyle.listDepth == 2 })
    }

    @Test fun tasksKeepFollowingParagraphsNestedContentAndInlineStylesWithoutMutatingSource() {
        val raw = "- [x] **完成**\n\n  继续段落\n\n  - [ ] 子项\n\n- [ ] 等待"
        val result = markdown(raw)
        val plain = text(result)
        assertTrue(plain.contains("☑ 完成")); assertTrue(plain.contains("☐ 子项")); assertTrue(plain.contains("☐ 等待"))
        assertTrue(plain.contains("继续段落"))
        assertEquals(1, Regex("☑").findAll(plain).count())
        assertEquals(2, Regex("☐").findAll(plain).count())
        assertTrue(result.fragments.flatMap { it.value.spanStyles }.any { it.item.fontWeight == FontWeight.Bold })
        assertEquals(raw, article(raw).sourcePages!!.joinToString("") { it.text })
    }

    @Test fun codePreservesHtmlEmojiWhitespaceAndOnlyExactTransportMarkerIsHidden() {
        val raw = "```html\r\n  <b>[表情]</b> <!--break-->\r\n<!--keep-->\r\n```"
        val parsed = article(raw)
        val code = parsed.pages.flatMap { it.fragments }.filterIsInstance<ArticleFragment.RichText>().single()
        assertTrue(code.markdownStyle.code)
        assertEquals("  <b>[表情]</b> \n<!--keep-->\n", code.value.text)
        assertTrue(code.links.isEmpty())
        assertEquals(raw, parsed.sourcePages!!.joinToString("") { it.text })
    }

    @Test fun mixedHtmlUsesTheExistingHtmlContractNotAMarkdownFlatteningPass() {
        for (source in listOf("前\n**粗体**<p>后段</p>", "# 标题\n<a href='/feed/42'>原生链接</a>", "<b>HTML</b>")) {
            assertNull(prepareMarkdownText(source, ArticleTextRole.BODY, 0))
            val parsed = prepareFullPost(FullPostBody.Available(source)) as PreparedFullPost.Ready
            assertTrue(parsed.pages.joinToString("") { it.text }.isNotEmpty())
        }
        val unsupported = prepareFullPost(FullPostBody.Available("# 标题\n<script>NOT_EXECUTED</script>"))
        assertEquals(FullPostBodyLimit.UNSUPPORTED_MARKUP, (unsupported as PreparedFullPost.Unavailable).limit)
    }

    @Test fun tablesHaveHonestWholeSourceFallbackWithoutDroppingCells() {
        val raw = "# 表格\n\n| A | B |\n|---|---|\n| 中文 | 最后 |"
        val parsed = markdown(raw)
        assertEquals(raw, parsed.fragments.single().value.text)
        assertTrue(parsed.fragments.single().markdownStyle.notice!!.contains("表格"))
        assertEquals(raw, article(raw).sourcePages!!.single().text)
    }

    @Test fun sparseTablesFallBackBeforeAnyCellAllocationIncludingPipeFreeBodyRows() {
        for (row in listOf("x|\n", "x\n")) {
            val raw = "|" + "a|".repeat(2000) + "\n|" + "-|".repeat(2000) + "\n" + row.repeat(10_000)
            assertTrue(looksLikeMarkdownTable(raw))
            assertEquals(raw, text(markdown(raw)))
        }
        val code = "```text\n| A | B |\n|---|---|\n```"
        assertFalse(looksLikeMarkdownTable(code))
        assertTrue(markdown(code).fragments.single().markdownStyle.code)
    }

    @Test fun markdownImagesAreVisibleTextOnlyAndNeverEnterTheArticleImageLoader() {
        val raw = "# 图片\n\n![说明](https://image.coolapk.com/a.jpg) ![阻止](file:///private/secret)"
        val parsed = article(raw)
        assertTrue(parsed.images.isEmpty())
        assertTrue(parsed.pages.flatMap { it.fragments }.all { it is ArticleFragment.RichText })
        val parts = parsed.pages.flatMap { it.fragments }.filterIsInstance<ArticleFragment.RichText>()
        assertTrue(parts.joinToString(" ") { it.value.text }.contains("图片未加载：说明"))
        assertTrue(parts.joinToString(" ") { it.value.text }.contains("file:///private/secret"))
        assertTrue(parts.flatMap { it.links }.isEmpty())
    }

    @Test fun nativeAndExternalLinksAreExplicitActionsWhileUnsafeSchemesStayInert() {
        val parsed = markdown("[动态](/feed/42) [外部](https://example.test/x) [恶意](javascript:evil) [本地](file:///tmp/a) [锚点](#title)")
        assertEquals(listOf("/feed/42", "https://example.test/x"), parsed.fragments.flatMap { it.links }.map { it.url })
        assertTrue(text(parsed).contains("恶意")); assertTrue(text(parsed).contains("本地")); assertTrue(text(parsed).contains("锚点"))
        for (url in listOf("javascript:evil", "file:///a", "data:image/a", "//example.test", "https://u:p@example.test", "https://example.test/a\\b", "https://example.test/\u0000")) {
            assertFalse(url, markdownLinkAllowed(url))
        }
        assertTrue(markdownLinkAllowed("coolmarket://feed/42"))
        assertTrue(markdownLinkAllowed("/u/用户"))
    }

    @Test fun segmentationPreservesAllTextAndCrossingLinkSpansAtSurrogateBoundaries() {
        val raw = "**" + "x".repeat(3998) + "😀TAIL" + "** [" + "l".repeat(100) + "](/feed/42)"
        val result = article(raw)
        val parts = result.pages.flatMap { it.fragments }.filterIsInstance<ArticleFragment.RichText>()
        assertEquals("x".repeat(3998) + "😀TAIL " + "l".repeat(100), parts.joinToString("") { it.value.text })
        assertTrue(result.pages.all { it.textUnits <= FullPostBodyBounds.PAGE_UNITS && it.fragments.size <= 16 })
        for (part in parts) {
            assertFalse(part.value.text.firstOrNull()?.isLowSurrogate() == true)
            assertFalse(part.value.text.lastOrNull()?.isHighSurrogate() == true)
            assertTrue(part.links.all { it.start >= 0 && it.end <= part.value.length && it.start < it.end })
        }
        assertEquals(raw, result.sourcePages!!.joinToString("") { it.text })
    }

    @Test fun adversarialLengthNestingDelimitersAndNodeCountsKeepReadableSource() {
        val sources = listOf("# 大文\n" + "a".repeat(MarkdownBounds.MAX_PARSE_UNITS),
            "*a\n".repeat(1000) + "z" + "\na*".repeat(1000),
            "# 嵌套\n" + "[".repeat(1000) + "x" + "]".repeat(1000),
            "# 深引用\n" + "> ".repeat(80) + "尾部",
            "# 节点\n\n" + "短段\n\n".repeat(1100),
            "# 超长行\n" + "x".repeat(MarkdownBounds.MAX_LINE_UNITS + 1))
        for (raw in sources) {
            val parsed = article(raw)
            assertEquals(raw, parsed.pages.flatMap { it.fragments }.filterIsInstance<ArticleFragment.RichText>().joinToString("") { it.value.text })
            assertEquals(raw, parsed.sourcePages!!.joinToString("") { it.text })
            assertTrue(parsed.pages.first().fragments.filterIsInstance<ArticleFragment.RichText>().first().markdownStyle.notice != null)
        }
    }

    @Test fun recursiveCoreInlineMergeIsBoundedBeforeParsingAcrossSoftNewlinesAndCrLf() {
        for (lineEnd in listOf("\n", "\r\n", "\r")) {
            val raw = ("*a" + lineEnd).repeat(1000) + "z" + (lineEnd + "a*").repeat(1000)
            assertFalse(markdownPreflight(raw) {})
            val parsed = markdown(raw)
            assertEquals(raw, parsed.fragments.single().value.text)
            assertNotNull(parsed.fragments.single().markdownStyle.notice)
        }
        assertTrue(markdownPreflight(("**safe**\r\n\r\n").repeat(50)) {})
    }

    @Test fun manyBlankLinesUseLinearQuoteDetectionAndCooperativePreflight() {
        val raw = "# heading\n" + "\n".repeat(60_000)
        assertTrue(markdownPreflight(raw) {})
        assertEquals("heading", text(markdown(raw)))
        val cancelled = CancellationException("blank-line preflight")
        var checks = 0
        try {
            prepareMarkdownText(raw, ArticleTextRole.BODY, 0) { if (++checks == 5) throw cancelled }
            fail("Long blank-line input must remain cancellable before library parse")
        } catch (actual: CancellationException) { assertSame(cancelled, actual) }
    }

    @Test fun nestedSameLineContainersCannotBypassLeadingIndentBound() {
        for (prefix in listOf("- ", "1. ", "> - ")) {
            val raw = "# bounded\n\n" + prefix.repeat(1000) + "tail"
            assertFalse(markdownPreflight(raw) {})
            assertEquals(raw, text(markdown(raw)))
        }
    }

    @Test fun sourceBoundRemainsExactAndOversizeDoesNotAllocateRawPages() {
        val raw = "# " + "a".repeat(FullPostBodyBounds.MAX_HTML_UNITS - 2)
        assertEquals(raw, article(raw).sourcePages!!.joinToString("") { it.text })
        assertEquals(FullPostBodyLimit.BODY_TOO_LARGE,
            (prepareFullPost(FullPostBody.Available(raw + "a")) as PreparedFullPost.Unavailable).limit)
    }

    @Test fun cancellationPropagatesDuringDetectionAstProjectionAndRawPagination() {
        val raw = "# 标题\n\n" + "**正文**\n\n".repeat(60)
        var count = 0
        prepareFullPost(FullPostBody.Available(raw)) { count++ }
        assertTrue(count > 100)
        for (at in listOf(1, count / 2, count)) {
            var checks = 0
            val cancelled = CancellationException("markdown $at")
            try {
                prepareFullPost(FullPostBody.Available(raw)) { if (++checks == at) throw cancelled }
                fail("Cancellation checkpoint must remain owned")
            } catch (actual: CancellationException) { assertSame(cancelled, actual) }
        }
    }

    @Test fun articleTextImageShareOrderAndTitleTypographySurviveMarkdownAdoption() {
        val raw = "[{\"type\":\"text\",\"message\":\"# 正文\"}]"
        val body = FullPostBody.Structured(raw, "<b>原有标题</b>", "https://image.coolapk.com/cover.jpg", listOf(
            ArticleBlock.Text("# 正文\n\n**强调**"), ArticleBlock.Image("https://image.coolapk.com/a.jpg", "说明"),
            ArticleBlock.ShareUrl("/feed/43", "分享"), ArticleBlock.Text("末尾")))
        val parsed = prepareFullPost(body) as PreparedFullPost.Article
        val parts = parsed.pages.flatMap { it.fragments }
        assertEquals(listOf(-2, -1, 0, 0, 1, 2, 3), parts.map { it.sourceIndex })
        assertEquals(ArticleTextRole.TITLE, (parts[1] as ArticleFragment.RichText).role)
        assertEquals("原有标题", (parts[1] as ArticleFragment.RichText).value.text)
        assertEquals(listOf("https://image.coolapk.com/cover.jpg", "https://image.coolapk.com/a.jpg"), parsed.images)
        assertFalse(parsed.ordinaryTypography)
        assertEquals(listOf(body.title, body.cover, body.rawOutput), parsed.sourcePages!!.map { it.text })
        assertTrue(parsed.sourcePages!!.all { it.links.isEmpty() })
        assertEquals(raw, body.rawOutput)
    }

    @Test fun headingsRespectEveryPresetAndFontWhileCodeIsExplicitlyMonospace() {
        for (preset in ReadingPreset.entries) for (font in FontChoice.entries) {
            val base = readingStyles(preset, font).articleBody
            val heading = markdownTextStyle(base, MarkdownStyle(heading = 1))
            assertEquals(base.fontFamily, heading.fontFamily)
            assertEquals(base.fontSize * 1.55f, heading.fontSize)
            assertEquals(base.lineHeight * 1.55f, heading.lineHeight)
            assertEquals(base, markdownTextStyle(base, MarkdownStyle()))
            assertEquals(FontFamily.Monospace, markdownTextStyle(base, MarkdownStyle(code = true)).fontFamily)
        }
    }
}
