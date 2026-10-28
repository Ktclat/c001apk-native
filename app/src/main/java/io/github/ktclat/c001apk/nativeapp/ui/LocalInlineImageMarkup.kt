// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import io.github.ktclat.c001apk.nativeapp.domain.FullPostBodyBounds

/**
 * Adapt only the non-network part of Flutter's pure-legacy <img> vocabulary.
 * Call on bounded original HTML, AFTER Markdown selection and BEFORE markup normalization.
 * Unsupported images stay verbatim, so detail preflight still rejects them explicitly.
 * No entity decoding, filesystem lookup, network request, or new HTML capability occurs here.
 */
internal fun normalizeLocalInlineImages(raw: String): String {
    if (raw.length > FullPostBodyBounds.MAX_HTML_UNITS || '<' !in raw) return raw
    data class Replacement(val start: Int, val end: Int, val text: String)
    val replacements = ArrayList<Replacement>()
    val prose = StringBuilder(raw.length)
    val literalTags = ArrayList<String>()
    var images = 0
    var at = 0
    while (at < raw.length) {
        if (raw[at] != '<') {
            if (literalTags.isEmpty()) prose.append(raw[at])
            at++
            continue
        }
        // A Markdown-escaped '<' is source text, never a newly admitted local image tag.
        // Consume only this character; preserve every original byte in the returned source.
        var slash = at - 1
        while (slash >= 0 && raw[slash] == '\\') slash--
        if ((at - 1 - slash) % 2 == 1) {
            if (literalTags.isEmpty()) prose.append('<')
            at++
            continue
        }
        if (raw.startsWith("<!--", at)) {
            val end = raw.indexOf("-->", at + 4)
            if (end < 0) return raw
            at = end + 3
            continue
        }
        // Declarations, CDATA and processing instructions are not an image source grammar.
        if (raw.startsWith("<!", at) || raw.startsWith("<?", at)) return raw
        var cursor = at + 1
        val closing = cursor < raw.length && raw[cursor] == '/'
        if (closing) cursor++
        val nameStart = cursor
        while (cursor < raw.length && raw[cursor].isAsciiTagCharacter()) cursor++
        if (cursor == nameStart || cursor < raw.length && !raw[cursor].isHtmlSpace() && raw[cursor] !in "/>") {
            if (literalTags.isEmpty()) prose.append('<')
            at++
            continue
        }
        val name = raw.substring(nameStart, cursor).lowercase()
        val attributesStart = cursor
        var quote: Char? = null
        while (cursor < raw.length) {
            val char = raw[cursor]
            if (quote != null) {
                if (char == quote) quote = null
            } else if (char == '\'' || char == '"') quote = char
            else if (char == '>') break
            cursor++
            if (cursor - at > 8192) return raw
        }
        if (cursor >= raw.length || cursor - at + 1 > 8192) return raw
        val end = cursor + 1
        if (name in LOCAL_IMAGE_LITERAL_TAGS) {
            if (closing) {
                // Malformed nested raw/code markup never grants permission to leave a literal region.
                if (literalTags.lastOrNull() == name) literalTags.removeAt(literalTags.lastIndex)
            } else {
                if (literalTags.size >= FullPostBodyBounds.MAX_MARKUP_DEPTH) return raw
                literalTags.add(name)
            }
        } else if (literalTags.isEmpty()) {
            // A neutral non-whitespace marker preserves line boundaries for the conservative
            // Markdown guard without treating source filenames/quoted attributes as prose.
            prose.append('\uFFFC')
            if (name == "img" && !closing) {
                if (++images > InlineEmoticons.MAX_PER_TEXT) return raw
                localImageSource(raw.substring(attributesStart, cursor))?.let { source ->
                    val text = InlineEmoticons.sourceTokens[source] ?: inertImageLabel(source)
                    if (text != null) replacements.add(Replacement(at, end, escapeLocalImageText(text)))
                }
            }
        }
        at = end
    }
    if (replacements.isEmpty() || localImageMarkdownProse(prose.toString())) return raw
    return buildString(raw.length) {
        var copied = 0
        replacements.forEach { replacement ->
            append(raw, copied, replacement.start)
            append(replacement.text)
            copied = replacement.end
        }
        append(raw, copied, raw.length)
    }
}

private val LOCAL_IMAGE_LITERAL_TAGS = setOf("code", "tt", "pre", "script", "style", "textarea", "xmp")
private fun Char.isAsciiTagCharacter() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
private fun Char.isHtmlSpace() = this == ' ' || this == '\t' || this == '\r' || this == '\n' || this == '\u000c'

/** Exactly one src attribute. Extra/duplicate attributes remain unsupported rather than sanitized. */
private fun localImageSource(attributes: String): String? {
    var at = 0
    fun spaces() { while (at < attributes.length && attributes[at].isHtmlSpace()) at++ }
    spaces()
    if (at + 3 > attributes.length || !attributes.regionMatches(at, "src", 0, 3, ignoreCase = true)) return null
    at += 3
    spaces()
    if (at >= attributes.length || attributes[at++] != '=') return null
    spaces()
    if (at >= attributes.length) return null
    val quote = attributes[at].takeIf { it == '\'' || it == '"' }
    if (quote != null) at++
    val start = at
    if (quote != null) {
        while (at < attributes.length && attributes[at] != quote) at++
        if (at >= attributes.length) return null
    } else {
        while (at < attributes.length && !attributes[at].isHtmlSpace()) {
            if (attributes[at] in "\"'=<>`") return null
            at++
        }
    }
    val source = attributes.substring(start, at)
    if (quote != null) at++
    spaces()
    if (at < attributes.length && attributes[at] == '/') { at++; spaces() }
    return source.takeIf { at == attributes.length && it.isNotEmpty() && it.length <= 128 }
}

/** Unknown labels are literal text, never asset names, URLs, markup, or new emoji tokens. */
private fun inertImageLabel(source: String): String? = source.takeIf {
    it.isNotBlank() && it.none { char -> char < ' ' || char == '\u007f' || char in ":/\\[]*`_~" } &&
        it != "." && it != ".." && !it.startsWith('.') && !it.endsWith("icon", ignoreCase = true)
}

private fun escapeLocalImageText(text: String): String = buildString(text.length) {
    text.forEach { char -> append(when (char) {
        '&' -> "&amp;"
        '<' -> "&lt;"
        '>' -> "&gt;"
        '"' -> "&quot;"
        '\'' -> "&#39;"
        else -> char.toString()
    }) }
}

/**
 * Keep mixed Markdown in its existing safe path without mistaking ordinary Coolapk token runs
 * or device names for markup. This is a bounded lexical guard, not a second Markdown parser.
 * Deliberately conservative: unknown reference-looking pairs and unsupported punctuation still
 * block adaptation. Exact known-token pairs are plain only when no reference definition exists.
 */
private fun localImageMarkdownProse(prose: String): Boolean {
    for (at in prose.indices) {
        val char = prose[at]
        if (char in "`*~") return true
        // CommonMark does not allow single intraword underscores to open/close emphasis.
        // Do not relax standalone/doubled/escaped delimiters or change the actual Markdown parser.
        if (char == '_' && !(at > 0 && at + 1 < prose.length &&
                prose[at - 1].isLetterOrDigit() && prose[at + 1].isLetterOrDigit())) return true
    }
    if (Regex("(?m)^(?: {4}|\\t)\\S|^ {0,3}(?:#{1,6}(?:\\s|$)|>\\s|(?:[-+]|[0-9]{1,9}[.)])\\s|[-=]{2,}\\s*$)").containsMatchIn(prose)) return true
    // HTML tags became U+FFFC boundaries in the text-only scan. A definition after a paragraph
    // tag is also ambiguous; never exempt [笑哭][受虐滑稽] when it can be a real reference link.
    if (localReferenceDefinition(prose)) return true

    fun afterWhitespace(start: Int): Int {
        var next = start
        while (next < prose.length && prose[next].isWhitespace()) next++
        return next
    }
    fun known(open: Int, close: Int): Boolean = close >= open &&
        close - open + 1 <= InlineEmoticons.maxTokenLength &&
        InlineEmoticons.contains(prose.substring(open, close + 1))

    var at = 0
    while (at < prose.length) {
        if (prose[at] == '[') {
            // Consume the first closing bracket, just like the renderer. A nested unknown
            // candidate is never reclassified from an inner catalog-looking substring.
            val close = prose.indexOf(']', at + 1)
            if (close < 0) break
            val next = afterWhitespace(close + 1)
            if (next < prose.length && prose[next] == '(') return true
            if (next < prose.length && prose[next] == '[') {
                val nextClose = prose.indexOf(']', next + 1)
                if (!known(at, close) || !known(next, nextClose)) return true
            }
            at = close + 1
        } else {
            // Preserve the old conservative guard for unmatched closing-bracket syntax too.
            if (prose[at] == ']') {
                val next = afterWhitespace(at + 1)
                if (next < prose.length && prose[next] in "([") return true
            }
            at++
        }
    }
    return false
}


/** One bounded lookahead per text/tag boundary; never rescan across a later boundary. */
private fun localReferenceDefinition(prose: String): Boolean {
    fun boundary(char: Char) = char == '\r' || char == '\n' || char == '\uFFFC'
    for (at in prose.indices) {
        if (at != 0 && !boundary(prose[at - 1])) continue
        var open = at
        while (open < prose.length && open - at < 3 && prose[open] == ' ') open++
        if (open >= prose.length || prose[open] != '[') continue
        var close = open + 1
        while (close < prose.length && close - open <= 1000 &&
            prose[close] != ']' && !boundary(prose[close])) close++
        if (close >= prose.length || close - open > 1000 || prose[close] != ']' || close == open + 1) continue
        var next = close + 1
        while (next < prose.length && prose[next] in " \t") next++
        if (next < prose.length && prose[next] == ':') return true
    }
    return false
}
