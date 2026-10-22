package io.github.ktclat.c001apk.nativeapp.ui
import org.junit.Assert.*
import org.junit.Test
class PostMarkupTest {
    @Test fun preservesSingleLineBreakAndCollapsesExcessBlankParagraphs() {
        assertEquals("第一段<br/>第二段<br/><br/>第三段", normalizePostMarkup("第一段\r\n第二段\n\n\n\n第三段"))
    }
    @Test fun convertsOnlyBoundedSimpleBoldWithoutBreakingLinks() {
        assertEquals("<strong>重点</strong> <a href=\"https://example.invalid\">链接</a>", normalizePostMarkup("**重点** <a href=\"https://example.invalid\">链接</a>"))
        assertEquals("**跨<br/>行**", normalizePostMarkup("**跨\n行**"))
    }
}
