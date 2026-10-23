// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pure source-preserving preparation. The body constructor deliberately tests defensive bounds too. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StructuredArticleParsingTest {
    private fun body(vararg blocks: ArticleBlock, title: String? = null, cover: String? = null) =
        FullPostBody.Structured("synthetic source identity", title, cover, blocks.toList())
    private fun ready(vararg blocks: ArticleBlock, title: String? = null, cover: String? = null) =
        prepareFullPost(body(*blocks, title = title, cover = cover)) as PreparedFullPost.Article
    private fun fragments(result: PreparedFullPost.Article) = result.pages.flatMap { it.fragments }
    private fun rich(result: PreparedFullPost.Article) = fragments(result).filterIsInstance<ArticleFragment.RichText>()
    private fun text(result: PreparedFullPost.Article) = rich(result).joinToString("") { it.value.text }
    private fun assertLimit(expected: FullPostBodyLimit, input: FullPostBody.Structured) {
        assertEquals(expected, (prepareFullPost(input) as PreparedFullPost.Unavailable).limit)
    }

    @Test fun shortArticleIsOneOrderedSurfaceWithCoverThenTitleAndEverySourceBlock() {
        val repeated = "https://image.coolapk.com/repeated@640x480.jpg"
        val parsed = ready(ArticleBlock.Text("HEAD"), ArticleBlock.Image(repeated, "PLAIN <b>CAPTION</b>"),
            ArticleBlock.ShareUrl("/feed/43", "PLAIN <i>SHARE</i>"), ArticleBlock.Text("EXACT_TAIL"), title = "TITLE", cover = repeated)
        assertEquals(1, parsed.pages.size)
        val parts = fragments(parsed)
        assertEquals(6, parts.size)
        assertEquals(listOf(repeated, repeated), parsed.images)
        assertEquals(0, (parts[0] as ArticleFragment.Image).imageIndex)
        assertEquals("TITLE", (parts[1] as ArticleFragment.RichText).value.text)
        assertEquals(ArticleTextRole.TITLE, (parts[1] as ArticleFragment.RichText).role)
        assertEquals("HEAD", (parts[2] as ArticleFragment.RichText).value.text)
        assertEquals(ArticleTextRole.BODY, (parts[2] as ArticleFragment.RichText).role)
        assertEquals("PLAIN <b>CAPTION</b>", (parts[3] as ArticleFragment.Image).caption)
        assertEquals(1, (parts[3] as ArticleFragment.Image).imageIndex)
        assertEquals("PLAIN <i>SHARE</i>", (parts[4] as ArticleFragment.Share).title)
        assertEquals("EXACT_TAIL", (parts[5] as ArticleFragment.RichText).value.text)
        assertEquals(listOf(0, 1, 2, 3), parts.drop(2).map {
            when (it) { is ArticleFragment.RichText -> it.sourceIndex; is ArticleFragment.Image -> it.sourceIndex; is ArticleFragment.Share -> it.sourceIndex }
        })
    }

    @Test fun blankFieldsRemainVisibleTypedFragmentsAndNoTitleOrCoverIsInvented() {
        val parsed = ready(ArticleBlock.Text(""), ArticleBlock.Image(null, ""), ArticleBlock.ShareUrl(null, ""))
        val parts = fragments(parsed)
        assertEquals(3, parts.size)
        assertEquals("", (parts[0] as ArticleFragment.RichText).value.text)
        assertNull((parts[1] as ArticleFragment.Image).url)
        assertNull((parts[2] as ArticleFragment.Share).url)
        assertEquals(listOf<String?>(null), parsed.images)
        assertTrue(rich(parsed).none { it.role == ArticleTextRole.TITLE })
    }

    @Test fun textAcross3999SurrogateBoundaryPreservesTailOrderAndValidSpanRanges() {
        val source = "x".repeat(3999) + "😀" + "y".repeat(8000) + "EXACT_END"
        val parsed = ready(ArticleBlock.Text("<b>$source</b>"))
        assertEquals(source, text(parsed))
        assertTrue(parsed.pages.size >= 4)
        assertEquals("EXACT_END", text(parsed).takeLast(9))
        for (page in parsed.pages) {
            assertTrue(page.textUnits <= 4000)
            for (part in page.fragments.filterIsInstance<ArticleFragment.RichText>()) {
                val value = part.value
                assertTrue(value.text.firstOrNull()?.isLowSurrogate() != true)
                assertTrue(value.text.lastOrNull()?.isHighSurrogate() != true)
                assertTrue(value.spanStyles.isNotEmpty())
                assertTrue(value.spanStyles.all { it.start >= 0 && it.end <= value.length && it.end > it.start })
                assertTrue(value.spanStyles.any { it.start == 0 && it.end == value.length && it.item.fontWeight != null })
            }
        }
    }

    @Test fun supportedInlineFormattingIsPreservedRatherThanFlattened() {
        val parsed = ready(ArticleBlock.Text("<b>B</b><strong>S</strong><i>I</i><em>E</em><u>U</u><s>X</s><del>D</del><strike>K</strike>"))
        val value = rich(parsed).single().value
        assertEquals("BSIEUXDK", value.text)
        fun stylesAt(index: Int) = value.spanStyles.filter { it.start <= index && it.end > index }.map { it.item }
        for (index in 0..1) assertTrue(stylesAt(index).any { (it.fontWeight?.weight ?: 0) >= FontWeight.Medium.weight })
        for (index in 2..3) assertTrue(stylesAt(index).any { it.fontStyle == FontStyle.Italic })
        assertTrue(stylesAt(4).any { it.textDecoration?.contains(TextDecoration.Underline) == true })
        for (index in 5..7) assertTrue(stylesAt(index).any { it.textDecoration?.contains(TextDecoration.LineThrough) == true })
    }

    @Test fun boundaryCrossingLinkRemainsActionableOnBothFragmentsWithLocalRanges() {
        val parsed = ready(ArticleBlock.Text("x".repeat(3998) + "<a href='/feed/43'>ABCD</a>"))
        assertEquals("x".repeat(3998) + "ABCD", text(parsed))
        val parts = rich(parsed)
        assertEquals(2, parts.size)
        assertEquals(listOf(1, 1), parts.map { it.links.size })
        for (part in parts) {
            val link = part.links.single()
            assertEquals("/feed/43", link.url)
            assertTrue(link.start >= 0 && link.end <= part.value.length && link.end > link.start)
        }
    }

    @Test fun paragraphNormalizationAndSimpleMarkdownAreAppliedBeforeSegmentation() {
        val parsed = ready(ArticleBlock.Text("前\r\n\r\n**加粗**<p>后段</p>"))
        val combined = text(parsed)
        assertTrue(combined.contains("前")); assertTrue(combined.contains("加粗")); assertTrue(combined.contains("后段"))
        assertFalse(combined.contains("\r")); assertFalse(combined.contains("**"))
        val styles = rich(parsed).flatMap { it.value.spanStyles }
        assertTrue(styles.any { (it.item.fontWeight?.weight ?: 0) >= FontWeight.Medium.weight })
    }

    @Test fun unsupportedInlineImagesTablesCssAndEmbedsNeverBecomeCompleteFlattenedText() {
        for (markup in listOf("text<img src='https://image.coolapk.com/a.png'/>", "<table><tr><td>x</td></tr></table>",
            "<script>x</script>", "<iframe src='https://example.test'/>", "<span style='color:red'>x</span>",
            "<div class='unimplemented-css'>x</div>")) {
            assertLimit(FullPostBodyLimit.UNSUPPORTED_MARKUP, body(ArticleBlock.Text(markup)))
        }
    }

    @Test fun imageAndFragmentPackingAreAggregateNotOneArtificialPagePerBlock() {
        val images = ready(*Array(18) { ArticleBlock.Image("https://image.coolapk.com/$it.jpg", "caption:$it") })
        assertEquals(5, images.pages.size)
        assertEquals((0..17).toList(), fragments(images).filterIsInstance<ArticleFragment.Image>().map { it.imageIndex })
        assertTrue(images.pages.all { it.fragments.filterIsInstance<ArticleFragment.Image>().size <= 4 })
        val small = ready(*Array(128) { ArticleBlock.Text("$it:") })
        assertEquals(8, small.pages.size)
        assertTrue(small.pages.all { it.fragments.size <= 16 })
        assertEquals((0..127).joinToString("") { "$it:" }, text(small))
        assertTrue(small.pages.all { it.textUnits <= 4000 })
    }

    @Test fun atomicCaptionsAndShareLabelsCountTowardTheSegmentTextBudget() {
        val parsed = ready(ArticleBlock.Text("x".repeat(3999)), ArticleBlock.Image(null, "caption"),
            ArticleBlock.ShareUrl("/feed/43", "share"), ArticleBlock.Text("TAIL"))
        assertEquals(2, parsed.pages.size)
        assertEquals(3999, parsed.pages[0].textUnits)
        assertTrue(parsed.pages[0].fragments.none { it is ArticleFragment.Image })
        assertEquals("caption", (parsed.pages[1].fragments[0] as ArticleFragment.Image).caption)
        assertEquals("share", (parsed.pages[1].fragments[1] as ArticleFragment.Share).title)
        assertTrue(parsed.pages.all { it.textUnits <= 4000 })
        assertEquals("x".repeat(3999) + "TAIL", text(parsed))
    }

    @Test fun individualDecorationAndLabelLimitsAreExactWithoutEllipsizing() {
        val title = "T".repeat(4000)
        assertEquals(title, rich(ready(ArticleBlock.Text("x"), title = title)).first().value.text)
        assertLimit(FullPostBodyLimit.ARTICLE_COMPLEXITY, body(ArticleBlock.Text("x"), title = title + "T"))
        for (block in listOf(ArticleBlock.Image(null, "x".repeat(2000)), ArticleBlock.ShareUrl(null, "x".repeat(2000)))) {
            assertTrue(prepareFullPost(body(block)) is PreparedFullPost.Article)
        }
        assertLimit(FullPostBodyLimit.ARTICLE_COMPLEXITY, body(ArticleBlock.Image(null, "x".repeat(2001))))
        assertLimit(FullPostBodyLimit.ARTICLE_COMPLEXITY, body(ArticleBlock.ShareUrl(null, "x".repeat(2001))))
    }

    @Test fun aggregateDecodedBudgetCountsAllTextLabelsAndTitleWithoutPerBlockReset() {
        val blocks = List(66) { ArticleBlock.Text("x".repeat(if (it == 65) 2144 else 4000)) }
        val parsed = ready(*blocks.toTypedArray())
        assertEquals(262_144, text(parsed).length)
        assertEquals("x".repeat(262_144), text(parsed))
        assertTrue(parsed.pages.size <= 200)
        assertLimit(FullPostBodyLimit.BODY_TOO_LARGE, body(*(blocks + ArticleBlock.Text("x")).toTypedArray()))
        assertLimit(FullPostBodyLimit.BODY_TOO_LARGE, body(*blocks.toTypedArray(), title = "x"))
        assertLimit(FullPostBodyLimit.BODY_TOO_LARGE, body(*(blocks + ArticleBlock.Image(null, "x")).toTypedArray()))
        assertLimit(FullPostBodyLimit.BODY_TOO_LARGE, body(*(blocks + ArticleBlock.ShareUrl(null, "x")).toTypedArray()))
    }

    @Test fun linksAndTagsAreBoundedAcrossTheWholeArticleNotResetPerTextBlock() {
        val anchor = "<a href='/feed/43'>x</a>"
        assertTrue(prepareFullPost(body(ArticleBlock.Text(anchor.repeat(512)), ArticleBlock.Text(anchor.repeat(512)))) is PreparedFullPost.Article)
        assertLimit(FullPostBodyLimit.LINK_COMPLEXITY, body(ArticleBlock.Text(anchor.repeat(512)), ArticleBlock.Text(anchor.repeat(513))))
        assertLimit(FullPostBodyLimit.LINK_COMPLEXITY, body(ArticleBlock.Text(anchor.repeat(1024)), ArticleBlock.ShareUrl("/feed/44", "extra action")))
        val tags = "<b>x</b>".repeat(2048)
        assertTrue(prepareFullPost(body(ArticleBlock.Text(tags), ArticleBlock.Text(tags))) is PreparedFullPost.Article)
        assertLimit(FullPostBodyLimit.MARKUP_COMPLEXITY, body(ArticleBlock.Text(tags), ArticleBlock.Text(tags + "<br>")))
        assertTrue(prepareFullPost(body(ArticleBlock.Text("<b>".repeat(64) + "x" + "</b>".repeat(64)))) is PreparedFullPost.Article)
        assertLimit(FullPostBodyLimit.MARKUP_COMPLEXITY, body(ArticleBlock.Text("<b>".repeat(65) + "x" + "</b>".repeat(65))))
    }

    @Test fun articlePreparationCooperativelyCancelsDuringScanningAndPacking() {
        val source = body(*Array(100) { ArticleBlock.Text("x".repeat(2000)) })
        var totalChecks = 0
        prepareFullPost(source) { totalChecks++ }
        assertTrue(totalChecks > 100)
        for (stopAt in listOf(1, 3, totalChecks / 2, totalChecks)) {
            var checks = 0
            val cancellation = CancellationException("synthetic article prepare $stopAt")
            try {
                prepareFullPost(source) { if (++checks == stopAt) throw cancellation }
                fail("Preparation must not publish after cancellation checkpoint $stopAt")
            } catch (actual: CancellationException) { assertSame(cancellation, actual) }
        }
    }

    @Test fun ordinaryParserAndItsPageCountRemainUnchanged() {
        val source = "x".repeat(12_000) + "ORDINARY_TAIL"
        val ordinary = prepareFullPost(FullPostBody.Available(source)) as PreparedFullPost.Ready
        assertEquals(source, ordinary.pages.joinToString("") { it.text })
        assertEquals(4, ordinary.pages.size)
        assertEquals(FullPostBodyLimit.UNSUPPORTED_MARKUP,
            (prepareFullPost(FullPostBody.Available("<img src='https://image.coolapk.com/a.jpg'>")) as PreparedFullPost.Unavailable).limit)
        assertEquals("99564fcb207181dade8792c6d1ec8fa3c65ca3120ddc5aa8a18db8dc2f2c829b", readerBodyDigest("A\u0000中\uD83D\uDE00\uD800"))
    }
    @Test fun fullTagUtf16LengthIsBoundedAt8192IncludingItsClosingDelimiter() {
        fun opening(units: Int) = "<b" + " ".repeat(units - 3) + ">"
        assertTrue(prepareFullPost(body(ArticleBlock.Text(opening(8192) + "x</b>"))) is PreparedFullPost.Article)
        assertLimit(FullPostBodyLimit.MARKUP_COMPLEXITY, body(ArticleBlock.Text(opening(8193) + "x</b>")))
    }

}
