// SPDX-License-Identifier: AGPL-3.0-only
// Source roles/order: Flutter feed_article_body.dart and html_text.dart at 52202fdf.
package io.github.ktclat.c001apk.nativeapp.ui

import android.graphics.Typeface
import android.text.Html
import android.text.style.TypefaceSpan
import android.text.style.StyleSpan
import android.text.style.StrikethroughSpan
import android.text.style.UnderlineSpan
import android.text.style.URLSpan
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import io.github.ktclat.c001apk.nativeapp.domain.*

internal enum class ArticleTextRole { BODY, TITLE }
internal sealed interface ArticleFragment {
    val sourceIndex: Int
    data class RichText(val value: AnnotatedString, val links: List<PostLink>, val role: ArticleTextRole,
        override val sourceIndex: Int, val markdownStyle: MarkdownStyle = MarkdownStyle()) : ArticleFragment
    data class Image(val url: String?, val caption: String, val imageIndex: Int,
        override val sourceIndex: Int) : ArticleFragment
    data class Share(val url: String?, val title: String, override val sourceIndex: Int) : ArticleFragment
}
internal data class ArticlePage(val fragments: List<ArticleFragment>, val textUnits: Int)
private class ArticleLimit(val reason: FullPostBodyLimit, val units: Int? = null) : RuntimeException()
private class ArticleBudget(val active: () -> Unit) {
    var tags = 0; var links = 0; var runs = 0; var decoded = 0; var markdown = false
    fun text(units: Int) {
        if (units > FullPostBodyBounds.MAX_HTML_UNITS - decoded) throw ArticleLimit(FullPostBodyLimit.BODY_TOO_LARGE, decoded + units)
        decoded += units
    }
    fun link() { if (++links > FullPostBodyBounds.MAX_LINKS) throw ArticleLimit(FullPostBodyLimit.LINK_COMPLEXITY) }
}

/** Strict small inline vocabulary. Admitted styles are preserved, never silently flattened. */
private fun articleMarkupPreflight(html: String, budget: ArticleBudget) {
    val allowed = setOf("a", "b", "strong", "i", "em", "u", "del", "s", "strike", "p", "div", "br", "tt")
    val stack = ArrayList<String>()
    val anchorAttributes = Regex("(?is)^\\s*(?:href\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s\"'=<>`]+)\\s*)?/?\\s*$")
    var at = 0
    while (at < html.length) {
        if (at % 1024 == 0) budget.active()
        if (html[at] != '<') { at++; continue }
        if (html.startsWith("<!--", at)) {
            val end = html.indexOf("-->", at + 4)
            if (end < 0) throw ArticleLimit(FullPostBodyLimit.UNSUPPORTED_MARKUP)
            at = end + 3; budget.active(); continue
        }
        var end = at + 1
        val closing = end < html.length && html[end] == '/'
        if (closing) end++
        val startName = end
        while (end < html.length && html[end].isLetterOrDigit()) end++
        if (end == startName) { at++; continue }
        val name = html.substring(startName, end).lowercase()
        if (name !in allowed) throw ArticleLimit(FullPostBodyLimit.UNSUPPORTED_MARKUP)
        val attributesStart = end
        var quote: Char? = null
        while (end < html.length) {
            if (end % 1024 == 0) budget.active()
            val c = html[end]
            if (quote != null) { if (c == quote) quote = null }
            else if (c == '\'' || c == '"') quote = c else if (c == '>') break
            end++
            if (end - at > 8192) throw ArticleLimit(FullPostBodyLimit.MARKUP_COMPLEXITY)
        }
        if (end >= html.length) throw ArticleLimit(FullPostBodyLimit.UNSUPPORTED_MARKUP)
        if (end - at + 1 > 8192) throw ArticleLimit(FullPostBodyLimit.MARKUP_COMPLEXITY)
        val attributes = html.substring(attributesStart, end)
        if (name == "a" && !closing) {
            if (!anchorAttributes.matches(attributes)) throw ArticleLimit(FullPostBodyLimit.UNSUPPORTED_MARKUP)
            budget.link()
        } else if (attributes.trim().removeSuffix("/").isNotBlank()) throw ArticleLimit(FullPostBodyLimit.UNSUPPORTED_MARKUP)
        if (++budget.tags > FullPostBodyBounds.MAX_MARKUP_TAGS) throw ArticleLimit(FullPostBodyLimit.MARKUP_COMPLEXITY)
        if (closing) {
            if (stack.lastOrNull() != name) throw ArticleLimit(FullPostBodyLimit.UNSUPPORTED_MARKUP)
            stack.removeAt(stack.lastIndex)
        } else if (name != "br" && html[end - 1] != '/') {
            stack.add(name)
            if (stack.size > FullPostBodyBounds.MAX_MARKUP_DEPTH) throw ArticleLimit(FullPostBodyLimit.MARKUP_COMPLEXITY)
        }
        at = end + 1; budget.active()
    }
    if (stack.isNotEmpty()) throw ArticleLimit(FullPostBodyLimit.UNSUPPORTED_MARKUP)
}

private fun richArticleText(raw: String, role: ArticleTextRole, sourceIndex: Int, budget: ArticleBudget): ArticleFragment.RichText {
    budget.active()
    if (raw.length > FullPostBodyBounds.MAX_HTML_UNITS) throw ArticleLimit(FullPostBodyLimit.BODY_TOO_LARGE, raw.length)
    val normalized = normalizePostMarkup(normalizeLocalInlineImages(raw))
    // Normalization can expand line breaks/Markdown, but input and tag count remain bounded.
    if (normalized.length > FullPostBodyBounds.MAX_HTML_UNITS * 5) throw ArticleLimit(FullPostBodyLimit.MARKUP_COMPLEXITY)
    articleMarkupPreflight(normalized, budget)
    budget.active()
    val spanned = Html.fromHtml(normalized, Html.FROM_HTML_MODE_LEGACY)
    budget.active()
    val text = spanned.toString()
    budget.text(text.length)
    if (role == ArticleTextRole.TITLE && text.length > ArticleBodyBounds.MAX_TITLE_UNITS) throw ArticleLimit(FullPostBodyLimit.ARTICLE_COMPLEXITY)
    val builder = AnnotatedString.Builder(text)
    val links = ArrayList<PostLink>()
    for (span in spanned.getSpans(0, spanned.length, Any::class.java)) {
        budget.active()
        if (++budget.runs > FullPostBodyBounds.MAX_MARKUP_TAGS) throw ArticleLimit(FullPostBodyLimit.MARKUP_COMPLEXITY)
        val start = spanned.getSpanStart(span); val end = spanned.getSpanEnd(span)
        if (start < 0 || end <= start || end > text.length) throw ArticleLimit(FullPostBodyLimit.UNSUPPORTED_MARKUP)
        when (span) {
            is TypefaceSpan -> if (span.family == "monospace") builder.addStyle(SpanStyle(fontFamily = FontFamily.Monospace), start, end)
                else throw ArticleLimit(FullPostBodyLimit.UNSUPPORTED_MARKUP)
            is StyleSpan -> builder.addStyle(SpanStyle(
                fontWeight = if (span.style and Typeface.BOLD != 0) FontWeight.Medium else null,
                fontStyle = if (span.style and Typeface.ITALIC != 0) FontStyle.Italic else null), start, end)
            is UnderlineSpan -> builder.addStyle(SpanStyle(textDecoration = TextDecoration.Underline), start, end)
            is StrikethroughSpan -> builder.addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, end)
            is URLSpan -> {
                if (span.url.length > FullPostBodyBounds.MAX_LINK_UNITS) throw ArticleLimit(FullPostBodyLimit.LINK_COMPLEXITY)
                links.add(PostLink(start, end, text.substring(start, end).take(80), span.url))
                builder.addStringAnnotation("article-url", span.url, start, end)
            }
            // Small admitted HTML vocabulary must not gain an unreviewed layout/style behavior.
            else -> throw ArticleLimit(FullPostBodyLimit.UNSUPPORTED_MARKUP)
        }
    }
    return ArticleFragment.RichText(withEmoticonLiteralContext(builder.toAnnotatedString()), links, role, sourceIndex)
}

/** Bounded source-order packing; only text is split, and only at surrogate-safe boundaries. */
internal fun prepareArticle(body: FullPostBody.Structured, checkActive: () -> Unit = {}): PreparedFullPost {
    try {
        val rawUnits = body.rawOutput.length.toLong() + (body.title?.length ?: 0) + (body.cover?.length ?: 0)
        if (rawUnits > FullPostBodyBounds.MAX_HTML_UNITS) return PreparedFullPost.Unavailable(FullPostBodyLimit.BODY_TOO_LARGE, rawUnits.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        if (body.blocks.isEmpty() || body.blocks.size > ArticleBodyBounds.MAX_SOURCE_BLOCKS) return PreparedFullPost.Unavailable(FullPostBodyLimit.ARTICLE_COMPLEXITY)
        val budget = ArticleBudget(checkActive)
        val images = ArrayList<String?>()
        val pages = ArrayList<ArticlePage>()
        var fragments = ArrayList<ArticleFragment>(); var textUnits = 0; var pageImages = 0
        fun flush() {
            if (fragments.isEmpty()) return
            if (pages.size >= ArticleBodyBounds.MAX_SEGMENTS) throw ArticleLimit(FullPostBodyLimit.ARTICLE_COMPLEXITY)
            pages.add(ArticlePage(fragments.toList(), textUnits))
            fragments = ArrayList(); textUnits = 0; pageImages = 0
        }
        fun append(fragment: ArticleFragment, units: Int) {
            checkActive()
            val image = fragment is ArticleFragment.Image
            if (units > FullPostBodyBounds.PAGE_UNITS) throw ArticleLimit(FullPostBodyLimit.ARTICLE_COMPLEXITY)
            if (fragments.size >= ArticleBodyBounds.MAX_FRAGMENTS_PER_SEGMENT || textUnits + units > FullPostBodyBounds.PAGE_UNITS ||
                image && pageImages >= ArticleBodyBounds.MAX_IMAGES_PER_SEGMENT) flush()
            fragments.add(fragment); textUnits += units; if (image) pageImages++
        }
        fun text(raw: String, role: ArticleTextRole, sourceIndex: Int) {
            val markdown = if (role == ArticleTextRole.BODY) prepareMarkdownText(raw, role, sourceIndex, checkActive) else null
            val richParts = markdown?.fragments ?: listOf(richArticleText(raw, role, sourceIndex, budget))
            if (markdown != null) {
                budget.markdown = true
                for (rich in richParts) {
                    budget.text(rich.value.length)
                    repeat(rich.links.size) { budget.link() }
                    budget.runs += rich.value.spanStyles.size + rich.value.getStringAnnotations(0, rich.value.length).size
                    if (budget.runs > FullPostBodyBounds.MAX_MARKUP_TAGS) throw ArticleLimit(FullPostBodyLimit.MARKUP_COMPLEXITY)
                }
            }
            for (rich in richParts) {
                var start = 0
                do {
                    checkActive()
                    // Prefer remaining space so a short article has one natural ordered surface.
                    if (fragments.size >= ArticleBodyBounds.MAX_FRAGMENTS_PER_SEGMENT || textUnits >= FullPostBodyBounds.PAGE_UNITS) flush()
                    val end = richTextBoundary(rich.value, start, (start + FullPostBodyBounds.PAGE_UNITS - textUnits).coerceAtMost(rich.value.length))
                    if (end == start && start < rich.value.length) { flush(); continue }
                    val links = rich.links.filter { it.start < end && it.end > start }.map {
                        it.copy(start = (it.start - start).coerceAtLeast(0), end = (it.end - start).coerceAtMost(end - start))
                    }
                    append(rich.copy(value = rich.value.subSequence(start, end), links = links), end - start)
                    start = end
                } while (start < rich.value.length)
            }
        }
        fun image(url: String?, caption: String, sourceIndex: Int) {
            if (images.size >= ArticleBodyBounds.MAX_IMAGES || caption.length > ArticleBodyBounds.MAX_LABEL_UNITS) throw ArticleLimit(FullPostBodyLimit.ARTICLE_COMPLEXITY)
            if ((url?.length ?: 0) > FullPostBodyBounds.MAX_LINK_UNITS) throw ArticleLimit(FullPostBodyLimit.LINK_COMPLEXITY)
            budget.text(caption.length)
            val index = images.size; images.add(url)
            append(ArticleFragment.Image(url, caption, index, sourceIndex), caption.length)
        }
        if (!body.cover.isNullOrEmpty()) image(body.cover, "", -2)
        if (!body.title.isNullOrEmpty()) text(body.title, ArticleTextRole.TITLE, -1)
        body.blocks.forEachIndexed { index, block ->
            checkActive()
            when (block) {
                is ArticleBlock.Text -> text(block.message, ArticleTextRole.BODY, index)
                is ArticleBlock.Image -> image(block.url, block.description, index)
                is ArticleBlock.ShareUrl -> {
                    if (block.title.length > ArticleBodyBounds.MAX_LABEL_UNITS) throw ArticleLimit(FullPostBodyLimit.ARTICLE_COMPLEXITY)
                    if ((block.url?.length ?: 0) > FullPostBodyBounds.MAX_LINK_UNITS) throw ArticleLimit(FullPostBodyLimit.LINK_COMPLEXITY)
                    budget.text(block.title.length); budget.link()
                    append(ArticleFragment.Share(block.url, block.title, index), block.title.length)
                }
            }
        }
        flush(); checkActive()
        return PreparedFullPost.Article(pages.toList(), images.toList(),
            sourcePages = if (budget.markdown) rawDetailPages(body, checkActive) else null)
    } catch (limit: ArticleLimit) {
        return PreparedFullPost.Unavailable(limit.reason, limit.units)
    }
}
