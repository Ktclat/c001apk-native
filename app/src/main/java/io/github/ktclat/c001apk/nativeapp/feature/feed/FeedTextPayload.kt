// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.feature.feed

import android.icu.text.BreakIterator
import android.net.Uri
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import java.util.Locale

/** Flutter utils.dart getShareUrl/getShareText (0d9ed00): Copy is a link, not the body. */
internal data class FeedTextPayload(val copyLink: String, val shareText: String)
internal class FeedShareContentTooLarge : IllegalArgumentException()

// Flutter's DOM text walk inserts a newline for br only, not for paragraph/list boundaries.
// This linear scanner removes only Android's layout tags; Html still decodes entities/markup.
// A repeated-regex parser can exhaust the stack on a single long quoted attribute.
private fun withoutLayoutBlocks(source: String): String {
    val blocks = setOf("p", "div", "ul", "li", "blockquote", "h1", "h2", "h3", "h4", "h5", "h6")
    return buildString(source.length) {
        var index = 0
        while (index < source.length) {
            if (source.startsWith("<!--", index)) {
                val end = source.indexOf("-->", index + 4).let { if (it < 0) source.length else it + 3 }
                append(source, index, end); index = end; continue
            }
            if (source[index] == '<') {
                var nameStart = index + 1
                if (source.getOrNull(nameStart) == '/') nameStart++
                var nameEnd = nameStart
                while (nameEnd < source.length && source[nameEnd].isLetterOrDigit()) nameEnd++
                val name = if (nameEnd - nameStart <= 10) source.substring(nameStart, nameEnd).lowercase(Locale.ROOT) else ""
                if (nameEnd > nameStart && (source.getOrNull(nameEnd)?.let { it.isWhitespace() || it == '/' || it == '>' } == true)) {
                    var end = nameEnd
                    var quote: Char? = null
                    while (end < source.length) {
                        val char = source[end++]
                        if (quote != null) { if (char == quote) quote = null }
                        else if (char == '\'' || char == '"') quote = char
                        else if (char == '>') break
                    }
                    if (source.getOrNull(end - 1) == '>' && quote == null) {
                        if (name !in blocks) append(source, index, end)
                        index = end; continue
                    }
                    append(source, index, source.length); break
                }
            }
            append(source[index++])
        }
    }
}

// IDs are native feed identities, never URLs or queries. A bounded path cannot become an Intent blob.
internal fun validFeedTextId(id: String): Boolean = id.length in 1..4096 && id.all { it in '0'..'9' }
internal fun feedCopyLink(id: String): String? = if (validFeedTextId(id))
    "https://www.coolapk1s.com/feed/${Uri.encode(id)}" else null

/** No title/summary is parsed until the explicit Share gesture, and callers run this off Main. */
internal fun feedTextPayload(id: String, title: String, message: String): FeedTextPayload? {
    val link = feedCopyLink(id) ?: return null
    fun plain(value: String): String {
        // Already-decoded native previews are <=12,000 units. Fail visibly on unexpected huge input,
        // rather than cutting markup or a grapheme cluster halfway through, or sharing raw source.
        if (value.length > 262_144 || value.count { it == '<' } > 8_192) throw FeedShareContentTooLarge()
        val parsed = SpannableStringBuilder(Html.fromHtml(withoutLayoutBlocks(value), Html.FROM_HTML_MODE_COMPACT))
        parsed.getSpans(0, parsed.length, ImageSpan::class.java).sortedByDescending(parsed::getSpanStart).forEach { image ->
            parsed.delete(parsed.getSpanStart(image), parsed.getSpanEnd(image))
        }
        return parsed.toString()
            .replace(Regex("[\\s\\p{Z}\\uFEFF]+"), " ").trim()
    }
    var summary = plain(title).ifEmpty { plain(message) }.ifEmpty { "动态" }
    val breaks = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(summary) }
    var boundary = breaks.first()
    var count = 0
    while (count < 100) {
        val next = breaks.next()
        if (next == BreakIterator.DONE) break
        boundary = next
        count++
    }
    if (boundary < summary.length) summary = summary.substring(0, boundary) + "…"
    // One pathological grapheme may itself be enormous; never export an unbounded Intent extra.
    if (summary.length > 16_384) throw FeedShareContentTooLarge()
    return FeedTextPayload(link, "$summary\n$link\nhttps://www.coolapk.com/feed/${Uri.encode(id)}\n分享自c001apk")
}
