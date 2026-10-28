// SPDX-License-Identifier: AGPL-3.0-only
// Detection/display contract: TG-Twilight/c001apk 98526cc, utils/markdown_content.dart.
// Parsing: commonmark-java 0.24.0; direct AST projection, never HTML execution.
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import io.github.ktclat.c001apk.nativeapp.domain.*
import io.github.ktclat.c001apk.nativeapp.platform.AppLink
import io.github.ktclat.c001apk.nativeapp.platform.parseAppLink
import org.commonmark.node.*
import org.commonmark.node.Text as MarkdownTextNode
import org.commonmark.parser.Parser
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension

internal data class MarkdownStyle(val heading: Int = 0, val code: Boolean = false,
    val quoteDepth: Int = 0, val listDepth: Int = 0, val notice: String? = null, val taskChecked: Boolean? = null)
internal data class MarkdownText(val fragments: List<ArticleFragment.RichText>)
internal object MarkdownBounds {
    const val MAX_PARSE_UNITS = 65_536
    const val MAX_LINE_UNITS = 8_192
    const val MAX_NODES = 8_192
    const val MAX_DEPTH = 32
    const val MAX_DELIMITERS = 4_096
    const val MAX_PARAGRAPH_DELIMITERS = 64
}
private class MarkdownLimit : RuntimeException()

/** Display destinations still go through the existing guarded native router when clicked. */
internal fun markdownLinkAllowed(url: String): Boolean = url.length <= FullPostBodyBounds.MAX_LINK_UNITS &&
    url.isNotEmpty() && url.none { it.code <= 32 || it.code == 127 || it == '\\' } &&
    parseAppLink(url) != AppLink.Unsupported

/** No source/AST/result cache: each parse belongs to its caller's detail Job. */
internal fun prepareMarkdownText(source: String, role: ArticleTextRole, sourceIndex: Int,
    active: () -> Unit = {}): MarkdownText? {
    active()
    if (source.none { it in "#*_~`[>-.+=" } && !Regex("(?m)^(?: {4}|\\t)").containsMatchIn(source)) return null
    fun raw(reason: String) = MarkdownText(listOf(ArticleFragment.RichText(AnnotatedString(source), emptyList(),
        role, sourceIndex, MarkdownStyle(notice = reason))))
    // No GFM table parser is installed: sparse tables can allocate columns × rows before an AST guard.
    if (looksLikeMarkdownTable(source, active)) return raw("此 Markdown 含表格，当前按完整原文显示；表格排版尚未支持。")
    // Prefer an explicit readable source fallback over partially rendering an over-budget document.
    if (!markdownPreflight(source, active)) {
        return if (hasDefiniteMarkdownMarker(source)) raw("Markdown 超过安全排版上限，以下保留完整原文。") else null
    }
    val input = source.replace("<!--break-->", "").replace("\r\n", "\n").replace('\r', '\n')
    // The task-list extension has a recursive postprocessor before callers can inspect depth.
    // Recognize its limited read-only prefix ourselves, only AFTER iterative AST validation.
    val parser = Parser.builder().extensions(listOf(StrikethroughExtension.builder().requireTwoTildes(true).build())).build()
    active()
    // Library parsing is non-cooperative, bounded by input/line/delimiter preflight, off Main.
    val document = parser.parse(input)
    active()
    val quoteMarker = Regex("(?m)^[ \\t]*>[ \\t]").containsMatchIn(input)
    var detected = false
    var listItems = 0
    var html = false
    var nodes = 0
    val pending = java.util.ArrayDeque<Pair<Node, Int>>()
    pending.add(document to 0)
    try {
        while (pending.isNotEmpty()) {
            active()
            val (node, depth) = pending.removeLast()
            if (++nodes > MarkdownBounds.MAX_NODES || depth > MarkdownBounds.MAX_DEPTH) throw MarkdownLimit()
            when (node) {
                is HtmlInline, is HtmlBlock -> html = true
                is Heading, is FencedCodeBlock, is IndentedCodeBlock, is StrongEmphasis, is Emphasis,
                is Strikethrough, is Code, is Image -> detected = true
                is Link -> if (input.contains('[')) detected = true
                is BlockQuote -> if (quoteMarker) detected = true
                is ListItem -> { listItems++; if (node.firstChild?.let(::taskPrefix) != null) detected = true }
            }
            var child = node.firstChild
            while (child != null) { pending.add(child to depth + 1); child = child.next }
        }
        // Do not re-interpret Coolapk HTML or flatten its existing structured renderer.
        if (html) return null
        if (!detected && listItems < 2) return null
        return MarkdownProjector(role, sourceIndex, active).project(document)
    } catch (_: MarkdownLimit) {
        return raw("Markdown 结构超过安全上限，以下保留完整原文。")
    }
}

/** Linear table-shaped separator recognition. No cell allocation and no unbounded regular expression.
 * Conservatively falls back for ambiguous table-shaped source instead of dropping any source.
 */
internal fun looksLikeMarkdownTable(source: String, active: () -> Unit = {}): Boolean {
    var start = 0
    var fence: Char? = null; var fenceLength = 0
    var previousTextLine = false
    while (start < source.length) {
        active()
        var end = start
        while (end < source.length && source[end] != '\r' && source[end] != '\n') {
            if (end % 1024 == 0) active()
            end++
        }
        var content = start
        while (content < end && source[content] == ' ') content++
        val indentation = content - start
        if (indentation <= 3 && content < end && source[content] in "`~") {
            val marker = source[content]
            var runEnd = content
            while (runEnd < end && source[runEnd] == marker) runEnd++
            val run = runEnd - content
            if (run >= 3) {
                if (fence == null) { fence = marker; fenceLength = run }
                else if (fence == marker && run >= fenceLength && (runEnd until end).all { source[it] == ' ' || source[it] == '\t' }) fence = null
                previousTextLine = false
                start = end + if (end + 1 < source.length && source[end] == '\r' && source[end + 1] == '\n') 2 else 1
                continue
            }
        }
        if (fence == null && indentation <= 3 && content < end && source[content] != '\t') {
            var pipe = false; var dash = false; var separator = true
            for (at in content until end) {
                if (at % 1024 == 0) active()
                when (source[at]) {
                    '|' -> pipe = true
                    '-' -> dash = true
                    ':', ' ', '\t' -> Unit
                    else -> separator = false
                }
            }
            if (previousTextLine && separator && pipe && dash) return true
            previousTextLine = (content until end).any { source[it] != ' ' && source[it] != '\t' }
        } else previousTextLine = false
        start = end + if (end + 1 < source.length && source[end] == '\r' && source[end + 1] == '\n') 2 else 1
    }
    return false
}

private fun hasDefiniteMarkdownMarker(source: String): Boolean =
    Regex("(?m)^ {0,3}(?:#{1,6} |```|~~~|> |={2,}[ \\t]*$)|^(?: {4}|\\t)|\\*\\*|\\[[^\\n]{0,256}\\]\\(|(?:^|\\s)[*_~`\\[]\\S").containsMatchIn(source)

/** Conservative structural work bound, including markers inside code (safe readable fallback). */
internal fun markdownPreflight(source: String, active: () -> Unit): Boolean {
    if (source.length > MarkdownBounds.MAX_PARSE_UNITS) return false
    var line = 0; var delimiters = 0; var brackets = 0; var lineMarkers = 0
    var indentation = 0; var leading = true; var containerMarkers = 0
    var paragraphDelimiters = 0; var blankLine = true
    for (i in source.indices) {
        if (i % 1024 == 0) active()
        val c = source[i]
        // CRLF is one newline; a soft line break cannot reset the paragraph work bound.
        if (c == '\n' && i > 0 && source[i - 1] == '\r') continue
        if (c == '\n' || c == '\r') {
            if (blankLine) paragraphDelimiters = 0
            blankLine = true; line = 0; lineMarkers = 0; indentation = 0; leading = true; containerMarkers = 0
        }
        else if (++line > MarkdownBounds.MAX_LINE_UNITS) return false
        if (c != ' ' && c != '\t' && c != '\r' && c != '\n') blankLine = false
        // commonmark's core merges inline text recursively before returning the AST. A global
        // paragraph bound (not a per-line bound) limits its call depth before it can run.
        if (c in "*_~`[]" && ++paragraphDelimiters > MarkdownBounds.MAX_PARAGRAPH_DELIMITERS) return false
        if (leading && (c == ' ' || c == '\t')) {
            indentation += if (c == '\t') 4 else 1
            if (indentation > MarkdownBounds.MAX_DEPTH * 2) return false
        } else if (c != '\n' && c != '\r') leading = false
        if (c in ">-*+.)" && ++containerMarkers > MarkdownBounds.MAX_DEPTH) return false
        if (c in "*_~`[]>") {
            if (++delimiters > MarkdownBounds.MAX_DELIMITERS || ++lineMarkers > 128) return false
        }
        if (c == '[' && ++brackets > MarkdownBounds.MAX_DEPTH) return false
        if (c == ']') brackets = (brackets - 1).coerceAtLeast(0)
    }
    active()
    return true
}

/** Read-only task prefix inspection is constant-depth; projection follows iterative AST validation and never mutates the tree. */
private fun taskPrefix(paragraph: Node): Pair<Boolean, Int>? {
    if (paragraph !is Paragraph || paragraph.parent !is ListItem || paragraph !== paragraph.parent.firstChild) return null
    val literal = (paragraph.firstChild as? MarkdownTextNode)?.literal ?: return null
    val match = Regex("^\\[([ xX])\\][ \\t]+").find(literal) ?: return null
    return (match.groupValues[1] != " ") to match.value.length
}

private class MarkdownProjector(val role: ArticleTextRole, val sourceIndex: Int, val active: () -> Unit) {
    val fragments = ArrayList<ArticleFragment.RichText>()
    var linkCount = 0; var imageCount = 0; var units = 0
    fun project(document: Node): MarkdownText {
        blocks(document, 0, 0)
        if (fragments.isEmpty()) throw MarkdownLimit()
        return MarkdownText(fragments.toList())
    }
    fun add(text: AnnotatedString, links: List<PostLink>, style: MarkdownStyle) {
        active()
        units += text.length
        if (units > FullPostBodyBounds.MAX_HTML_UNITS || fragments.size >= 1_024) throw MarkdownLimit()
        fragments.add(ArticleFragment.RichText(withEmoticonLiteralContext(text), links, role, sourceIndex, style))
    }
    fun blocks(parent: Node, quote: Int, listDepth: Int) {
        var node = parent.firstChild
        while (node != null) {
            active()
            when (node) {
                is Paragraph, is Heading -> {
                    val builder = AnnotatedString.Builder()
                    val links = ArrayList<PostLink>()
                    val task = taskPrefix(node)
                    if (node.parent is ListItem && node === node.parent.firstChild) {
                        val list = node.parent.parent
                        if (task != null) builder.append(if (task.first) "☑ " else "☐ ")
                        else {
                            if (list is OrderedList) {
                                var ordinal = list.markerStartNumber ?: 1
                                var sibling = list.firstChild
                                while (sibling != null && sibling !== node.parent) { ordinal++; sibling = sibling.next }
                                builder.append("$ordinal. ")
                            } else builder.append("• ")
                        }
                    }
                    inlines(node, builder, links, skipFirstText = task?.second ?: 0)
                    add(builder.toAnnotatedString(), links, MarkdownStyle(heading = (node as? Heading)?.level ?: 0,
                        quoteDepth = quote, listDepth = listDepth, taskChecked = task?.first))
                }
                is BlockQuote -> blocks(node, quote + 1, listDepth)
                is BulletList, is OrderedList -> blocks(node, quote, listDepth + 1)
                is ListItem -> blocks(node, quote, listDepth)
                is FencedCodeBlock -> add(AnnotatedString(node.literal), emptyList(), MarkdownStyle(code = true, quoteDepth = quote, listDepth = listDepth))
                is IndentedCodeBlock -> add(AnnotatedString(node.literal), emptyList(), MarkdownStyle(code = true, quoteDepth = quote, listDepth = listDepth))
                is ThematicBreak -> add(AnnotatedString("────────"), emptyList(), MarkdownStyle(quoteDepth = quote, listDepth = listDepth))
                is LinkReferenceDefinition -> Unit // A definition is syntax; its uses carry visible text and links.
                else -> throw MarkdownLimit() // Never silently omit an unreviewed node.
            }
            node = node.next
        }
    }
    fun inlines(parent: Node, builder: AnnotatedString.Builder, links: MutableList<PostLink>, skipFirstText: Int = 0) {
        var node = parent.firstChild
        while (node != null) {
            active()
            val start = builder.length
            when (node) {
                is MarkdownTextNode -> builder.append(if (node === parent.firstChild) node.literal.drop(skipFirstText) else node.literal)
                is SoftLineBreak, is HardLineBreak -> builder.append("\n")
                is Code -> {
                    builder.append(node.literal)
                    if (builder.length > start) builder.addStyle(SpanStyle(fontFamily = FontFamily.Monospace), start, builder.length)
                }
                is StrongEmphasis, is Emphasis, is Strikethrough -> {
                    inlines(node, builder, links)
                    val style = when (node) {
                        is StrongEmphasis -> SpanStyle(fontWeight = FontWeight.Bold)
                        is Emphasis -> SpanStyle(fontStyle = FontStyle.Italic)
                        else -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                    }
                    if (builder.length > start) builder.addStyle(style, start, builder.length)
                }
                is Link -> {
                    if (++linkCount > FullPostBodyBounds.MAX_LINKS || node.destination.length > FullPostBodyBounds.MAX_LINK_UNITS) throw MarkdownLimit()
                    inlines(node, builder, links)
                    if (builder.length > start && markdownLinkAllowed(node.destination)) {
                        val end = builder.length
                        links.add(PostLink(start, end, builder.toAnnotatedString().text.substring(start, end).take(80), node.destination))
                        builder.addStringAnnotation("article-url", node.destination, start, end)
                    }
                }
                is Image -> {
                    if (++imageCount > ArticleBodyBounds.MAX_IMAGES || node.destination.length > FullPostBodyBounds.MAX_LINK_UNITS) throw MarkdownLimit()
                    builder.append("[图片未加载：")
                    imageAlt(node, builder)
                    builder.append("] ")
                    // Visible literal destination only. No loader, image-URL intent, or external fetch.
                    builder.append(node.destination)
                }
                else -> throw MarkdownLimit()
            }
            if (builder.length > FullPostBodyBounds.MAX_HTML_UNITS) throw MarkdownLimit()
            node = node.next
        }
    }
    fun imageAlt(parent: Node, builder: AnnotatedString.Builder) {
        var child = parent.firstChild
        while (child != null) {
            active()
            when (child) {
                is MarkdownTextNode -> builder.append(child.literal)
                is Code -> builder.append(child.literal)
                is SoftLineBreak, is HardLineBreak -> builder.append(" ")
                else -> if (child.firstChild != null) imageAlt(child, builder) else throw MarkdownLimit()
            }
            child = child.next
        }
    }
}

/** The existing segment bound also applies to Markdown; spans and links get local offsets. */
internal fun packMarkdownFragments(fragments: List<ArticleFragment.RichText>, active: () -> Unit): List<ArticlePage> {
    val pages = ArrayList<ArticlePage>()
    var parts = ArrayList<ArticleFragment>(); var units = 0
    fun flush() {
        if (parts.isNotEmpty()) { pages.add(ArticlePage(parts.toList(), units)); parts = ArrayList(); units = 0 }
    }
    for (fragment in fragments) {
        var start = 0
        do {
            active()
            if (units == FullPostBodyBounds.PAGE_UNITS || parts.size == ArticleBodyBounds.MAX_FRAGMENTS_PER_SEGMENT) flush()
            val end = richTextBoundary(fragment.value, start, minOf(fragment.value.length, start + FullPostBodyBounds.PAGE_UNITS - units))
            if (end == start && start < fragment.value.length) { flush(); continue }
            val links = fragment.links.filter { it.start < end && it.end > start }.map {
                it.copy(start = (it.start - start).coerceAtLeast(0), end = (it.end - start).coerceAtMost(end - start))
            }
            parts.add(fragment.copy(value = fragment.value.subSequence(start, end), links = links))
            units += end - start; start = end
        } while (start < fragment.value.length)
    }
    flush()
    // Max 8192 nodes can exceed the article page-count limit even for tiny blocks. Keep exact source instead.
    if (pages.size > ArticleBodyBounds.MAX_SEGMENTS) throw IllegalArgumentException("Markdown segment bound")
    return pages
}

/** Exact server strings, no HTML/Markdown decoding and no links. Never persisted or globally cached. */
internal fun rawDetailPages(body: FullPostBody, active: () -> Unit): List<FullPostPage> {
    val fields = when (body) {
        is FullPostBody.Available -> listOf("原始正文" to body.html)
        is FullPostBody.Structured -> listOfNotNull(body.title?.let { "原始标题" to it },
            body.cover?.let { "原始封面地址（仅文本）" to it }, "原始结构化正文（JSON）" to body.rawOutput)
        is FullPostBody.Unavailable -> emptyList()
    }
    return fields.flatMap { (label, source) -> fullPostPages(source, emptyList(), active).map { it.copy(sourceLabel = label, renderInline = false) } }
}
