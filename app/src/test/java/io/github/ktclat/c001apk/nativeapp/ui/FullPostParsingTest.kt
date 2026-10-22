package io.github.ktclat.c001apk.nativeapp.ui

import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FullPostParsingTest {
    @Test fun beyondTwelveThousandTextAndLinksSurviveDecodedPageSegmentation() {
        val html = "前".repeat(12_000) + "<b>完整尾部</b><a href='https://example.test/tail'>尾部链接</a>"
        val ready = prepareFullPost(FullPostBody.Available(html)) as PreparedFullPost.Ready
        assertEquals("前".repeat(12_000) + "完整尾部尾部链接", ready.pages.joinToString("") { it.text })
        assertEquals(4, ready.pages.size)
        assertTrue(ready.pages.dropLast(1).all { it.links.isEmpty() })
        assertEquals("https://example.test/tail", ready.pages.last().links.single().url)
        assertTrue(ready.pages.all { it.text.length <= FullPostBodyBounds.PAGE_UNITS })
    }
    @Test fun linkAcrossBoundaryIsUsableOnBothPagesWithoutSplittingHtml() {
        val html = "x".repeat(3998) + "<a href='https://example.test/cross'>ABCD</a>"
        val ready = prepareFullPost(FullPostBody.Available(html)) as PreparedFullPost.Ready
        assertEquals("x".repeat(3998) + "ABCD", ready.pages.joinToString("") { it.text })
        assertEquals(listOf(1, 1), ready.pages.map { it.links.size })
    }
    @Test fun surrogatePairsAndAllTextSurviveBoundaries() {
        val text = "x".repeat(3999) + "😀" + "y".repeat(8000) + "末"
        val pages = fullPostPages(text, emptyList())
        assertEquals(text, pages.joinToString("") { it.text })
        assertTrue(pages.all { it.text.length <= 4000 && it.text.firstOrNull()?.isLowSurrogate() != true && it.text.lastOrNull()?.isHighSurrogate() != true })
    }
    @Test fun maximumAcceptedBodyIsPagedAndPlusOneIsExplicitlyUnavailable() {
        val text = "x".repeat(FullPostBodyBounds.MAX_HTML_UNITS)
        val ready = prepareFullPost(FullPostBody.Available(text)) as PreparedFullPost.Ready
        assertEquals(text, ready.pages.joinToString("") { it.text })
        assertTrue(ready.pages.all { it.text.length <= 4000 })
        assertEquals(PreparedFullPost.Unavailable(FullPostBodyLimit.BODY_TOO_LARGE, text.length + 1), prepareFullPost(FullPostBody.Available(text + "x")))
    }
    @Test fun unsupportedImagesAndStructuredArticleAreNeverCalledFullText() {
        assertEquals(PreparedFullPost.Unavailable(FullPostBodyLimit.UNSUPPORTED_MARKUP), prepareFullPost(FullPostBody.Available("text<img src='https://example.test/pic'/>")))
        assertEquals(PreparedFullPost.Unavailable(FullPostBodyLimit.UNSUPPORTED_MARKUP), prepareFullPost(FullPostBody.Available("<table><tr><td>cell</td></tr></table>")))
        assertEquals(PreparedFullPost.Unavailable(FullPostBodyLimit.STRUCTURED_ARTICLE), prepareFullPost(FullPostBody.Unavailable(FullPostBodyLimit.STRUCTURED_ARTICLE)))
    }
    @Test fun nestingTagAndLinkBoundariesAreFinite() {
        assertNull(fullPostMarkupLimit("<b>".repeat(64) + "body" + "</b>".repeat(64)))
        assertEquals(FullPostBodyLimit.MARKUP_COMPLEXITY, fullPostMarkupLimit("<b>".repeat(65) + "body" + "</b>".repeat(65)))
        assertNull(fullPostMarkupLimit("<br/>".repeat(8192)))
        assertEquals(FullPostBodyLimit.MARKUP_COMPLEXITY, fullPostMarkupLimit("<br/>".repeat(8193)))
        val link = "<a href='https://example.test'>x</a>"
        assertNull(fullPostMarkupLimit(link.repeat(1024)))
        assertEquals(FullPostBodyLimit.LINK_COMPLEXITY, fullPostMarkupLimit(link.repeat(1025)))
        val tooLong = "https://example.test/" + "x".repeat(4096)
        assertEquals(PreparedFullPost.Unavailable(FullPostBodyLimit.LINK_COMPLEXITY), prepareFullPost(FullPostBody.Available("<a href='$tooLong'>x</a>")))
    }
    @Test fun cancellationDuringBoundedScanPropagatesRatherThanBecomingErrorState() {
        var checks = 0
        try {
            prepareFullPost(FullPostBody.Available("x".repeat(20_000))) { if (++checks >= 3) throw CancellationException("synthetic cancellation") }
            fail("must cancel")
        } catch (_: CancellationException) { assertEquals(3, checks) }
    }
}
