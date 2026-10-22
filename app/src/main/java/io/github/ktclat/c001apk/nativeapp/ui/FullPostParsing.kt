// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import android.text.Html
import android.text.style.URLSpan
import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class FullPostPage(val text: String, val links: List<PostLink>)
internal sealed interface PreparedFullPost {
    data class Ready(val pages: List<FullPostPage>) : PreparedFullPost
    data class Unavailable(val limit: FullPostBodyLimit, val receivedUnits: Int? = null) : PreparedFullPost
}

/** Linear bounded markup preflight. Unsupported structures are never flattened into "full" text. */
internal fun fullPostMarkupLimit(html: String, checkActive: () -> Unit = {}): FullPostBodyLimit? {
    if (html.length > FullPostBodyBounds.MAX_HTML_UNITS * 5) return FullPostBodyLimit.MARKUP_COMPLEXITY
    val allowed = setOf("a", "b", "strong", "i", "em", "u", "del", "s", "strike", "span", "font", "div", "p",
        "br", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "ul", "ol", "li", "sup", "sub", "small", "big", "tt", "cite", "dfn")
    val stack = ArrayList<String>()
    var tags = 0
    var links = 0
    var index = 0
    while (index < html.length) {
        if (index % 1024 == 0) checkActive()
        if (html[index] != '<') { index++; continue }
        if (html.startsWith("<!--", index)) {
            val end = html.indexOf("-->", index + 4)
            if (end < 0) return FullPostBodyLimit.UNSUPPORTED_MARKUP
            index = end + 3
            continue
        }
        var next = index + 1
        val closing = next < html.length && html[next] == '/'
        if (closing) next++
        val nameStart = next
        while (next < html.length && html[next].isLetterOrDigit()) next++
        if (next == nameStart) { index++; continue }
        val name = html.substring(nameStart, next).lowercase()
        if (name !in allowed) return FullPostBodyLimit.UNSUPPORTED_MARKUP
        var quote: Char? = null
        while (next < html.length) {
            if (next % 1024 == 0) checkActive()
            val c = html[next]
            if (quote != null) { if (c == quote) quote = null }
            else if (c == '\'' || c == '"') quote = c
            else if (c == '>') break
            next++
            if (next - index > 8192) return FullPostBodyLimit.MARKUP_COMPLEXITY
        }
        if (next >= html.length) return FullPostBodyLimit.UNSUPPORTED_MARKUP
        if (++tags > FullPostBodyBounds.MAX_MARKUP_TAGS) return FullPostBodyLimit.MARKUP_COMPLEXITY
        if (!closing && name == "a" && ++links > FullPostBodyBounds.MAX_LINKS) return FullPostBodyLimit.LINK_COMPLEXITY
        if (closing) {
            val match = stack.indexOfLast { it == name }
            if (match >= 0) while (stack.size > match) stack.removeAt(stack.lastIndex)
        } else if (name != "br" && html[next - 1] != '/') {
            stack.add(name)
            if (stack.size > FullPostBodyBounds.MAX_MARKUP_DEPTH) return FullPostBodyLimit.MARKUP_COMPLEXITY
        }
        index = next + 1
        checkActive()
    }
    return null
}

/** Work here is owned by the caller's Job. No networking or UI state is touched by the parser. */
internal fun prepareFullPost(body: FullPostBody, checkActive: () -> Unit = {}): PreparedFullPost {
    if (body is FullPostBody.Unavailable) return PreparedFullPost.Unavailable(body.reason, body.receivedUnits)
    val html = (body as FullPostBody.Available).html
    if (html.length > FullPostBodyBounds.MAX_HTML_UNITS) return PreparedFullPost.Unavailable(FullPostBodyLimit.BODY_TOO_LARGE, html.length)
    checkActive()
    val normalized = normalizePostMarkup(html)
    fullPostMarkupLimit(normalized, checkActive)?.let { return PreparedFullPost.Unavailable(it) }
    checkActive()
    // Framework parsing is non-cooperative but bounded by source length, tag count and depth.
    val spanned = Html.fromHtml(normalized, Html.FROM_HTML_MODE_LEGACY)
    checkActive()
    val text = spanned.toString()
    if (text.length > FullPostBodyBounds.MAX_HTML_UNITS) return PreparedFullPost.Unavailable(FullPostBodyLimit.BODY_TOO_LARGE, text.length)
    val spans = spanned.getSpans(0, spanned.length, URLSpan::class.java)
    if (spans.size > FullPostBodyBounds.MAX_LINKS) return PreparedFullPost.Unavailable(FullPostBodyLimit.LINK_COMPLEXITY)
    val links = ArrayList<PostLink>(spans.size)
    for (span in spans) {
        checkActive()
        val start = spanned.getSpanStart(span)
        val end = spanned.getSpanEnd(span)
        if (start < 0 || end <= start || end > text.length || span.url.length > FullPostBodyBounds.MAX_LINK_UNITS)
            return PreparedFullPost.Unavailable(FullPostBodyLimit.LINK_COMPLEXITY)
        links.add(PostLink(start, end, text.substring(start, end).take(80), span.url))
    }
    return PreparedFullPost.Ready(fullPostPages(text, links, checkActive))
}

/** Slice after HTML decoding, so links/tags spanning a page boundary remain intact and usable. */
internal fun fullPostPages(text: String, links: List<PostLink>, checkActive: () -> Unit = {}): List<FullPostPage> {
    require(text.length <= FullPostBodyBounds.MAX_HTML_UNITS)
    require(links.size <= FullPostBodyBounds.MAX_LINKS)
    val pages = ArrayList<FullPostPage>()
    var start = 0
    do {
        checkActive()
        var end = (start + FullPostBodyBounds.PAGE_UNITS).coerceAtMost(text.length)
        if (end > start && end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        pages.add(FullPostPage(text.substring(start, end), links.filter { it.start < end && it.end > start }.distinctBy { it.url }))
        start = end
    } while (start < text.length)
    return pages
}

internal suspend fun parseAndPublishFullPost(
    body: FullPostBody,
    parser: (FullPostBody, () -> Unit) -> PreparedFullPost = ::prepareFullPost,
    publish: (PreparedFullPost) -> Unit,
) {
    val result = withContext(Dispatchers.Default) {
        val context = currentCoroutineContext()
        val prepared = try { parser(body) { context.ensureActive() } }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        // Framework HTML/URLSpan decoding can throw RuntimeException for malformed input.
        // Do not catch Error (including OOM); cancellation still wins before publication.
        catch (_: RuntimeException) { PreparedFullPost.Unavailable(FullPostBodyLimit.PARSING_ERROR) }
        context.ensureActive()
        prepared
    }
    withContext(Dispatchers.Main.immediate) { publish(result) }
}
