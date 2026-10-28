// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import android.text.Html
import android.text.style.URLSpan
import io.github.ktclat.c001apk.nativeapp.domain.*

/** Description and rules are Flutter Text, not HTML/Markdown or link destinations. */
internal fun prepareEventLiteral(body: FullPostBody, checkActive: () -> Unit): PreparedFullPost {
    if (body !is FullPostBody.Available || body.html.length > FullPostBodyBounds.MAX_HTML_UNITS) return prepareFullPost(body, checkActive)
    return PreparedFullPost.Ready(fullPostPages(body.html, emptyList(), checkActive).map { it.copy(renderInline = false) })
}

/** EventPage uses HtmlWidget, not the feed Markdown adapter. Preserve that distinction. */
internal fun prepareEventHtml(body: FullPostBody, checkActive: () -> Unit): PreparedFullPost {
    if (body !is FullPostBody.Available || body.html.length > FullPostBodyBounds.MAX_HTML_UNITS) return prepareFullPost(body, checkActive)
    val normalized = normalizeLocalInlineImages(body.html)
    fullPostMarkupLimit(normalized, checkActive)?.let { return PreparedFullPost.Unavailable(it) }
    checkActive()
    val spanned = Html.fromHtml(normalized, Html.FROM_HTML_MODE_LEGACY)
    checkActive()
    val text = spanned.toString()
    if (text.length > FullPostBodyBounds.MAX_HTML_UNITS) return PreparedFullPost.Unavailable(FullPostBodyLimit.BODY_TOO_LARGE, text.length)
    val spans = spanned.getSpans(0, spanned.length, URLSpan::class.java)
    if (spans.size > FullPostBodyBounds.MAX_LINKS) return PreparedFullPost.Unavailable(FullPostBodyLimit.LINK_COMPLEXITY)
    val links = spans.map { span ->
        checkActive()
        val start = spanned.getSpanStart(span); val end = spanned.getSpanEnd(span)
        if (start < 0 || end <= start || end > text.length || span.url.length > FullPostBodyBounds.MAX_LINK_UNITS)
            return PreparedFullPost.Unavailable(FullPostBodyLimit.LINK_COMPLEXITY)
        PostLink(start, end, text.substring(start, end).take(80), span.url)
    }
    return PreparedFullPost.Ready(fullPostPages(text, links, checkActive, postAnnotatedText(spanned)))
}
