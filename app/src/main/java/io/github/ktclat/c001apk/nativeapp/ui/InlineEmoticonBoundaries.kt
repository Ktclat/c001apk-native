// SPDX-License-Identifier: AGPL-3.0-only
// Reconstructed against Flutter 0d9ed002; this is new recovery work, not a restored original file.
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.ui.text.AnnotatedString

internal const val EMOTICON_LITERAL_CONTEXT = "native-emoticon-literal-context"

/** Carry whole-source first-close grammar through later slicing. Never expand a nested inner token
 * that Flutter consumed as part of an unknown candidate. No substring is allocated for long tokens.
 * Only nested candidates need annotations: simple unknown markers cannot expose a new '[' on split.
 */
internal fun withEmoticonLiteralContext(source: AnnotatedString): AnnotatedString {
    if ('[' !in source.text || source.getStringAnnotations(EMOTICON_LITERAL_CONTEXT, 0, source.length).isNotEmpty()) return source
    var builder: AnnotatedString.Builder? = null
    var at = 0
    while (at < source.length) {
        val open = source.text.indexOf('[', at)
        if (open < 0) break
        val close = source.text.indexOf(']', open + 1)
        if (close < 0) break
        val nested = source.text.indexOf('[', open + 1)
        if (nested in (open + 1)..close) {
            if (builder == null) builder = AnnotatedString.Builder(source)
            builder.addStringAnnotation(EMOTICON_LITERAL_CONTEXT, "literal", open, close + 1)
        }
        at = close + 1
    }
    return builder?.toAnnotatedString() ?: source
}

/** Stay within the requested budget; move back only for a complete catalog token, never an arbitrary
 * long unknown bracket candidate. Whole-source literal annotations prevent split reclassification.
 */
internal fun richTextBoundary(source: AnnotatedString, start: Int, requested: Int): Int {
    var end = requested.coerceIn(start, source.length)
    if (end > start && end < source.length && source[end - 1].isHighSurrogate() && source[end].isLowSurrogate()) end--
    if (end == source.length || end == start) return end
    val floor = maxOf(start, end - InlineEmoticons.maxTokenLength + 1)
    for (open in end - 1 downTo floor) {
        if (source[open] == ']') break
        if (source[open] != '[') continue
        val close = source.text.indexOf(']', open + 1)
        if (close >= end && close - open + 1 <= InlineEmoticons.maxTokenLength &&
            InlineEmoticons.contains(source.text.substring(open, close + 1)) &&
            source.getStringAnnotations(EMOTICON_LITERAL_CONTEXT, open, close + 1).isEmpty()) return open
        break
    }
    return end
}

internal fun richTextPageRanges(source: AnnotatedString, pageUnits: Int): List<IntRange> {
    require(pageUnits >= InlineEmoticons.maxTokenLength)
    val result = ArrayList<IntRange>()
    var start = 0
    do {
        val end = richTextBoundary(source, start, (start + pageUnits).coerceAtMost(source.length))
        check(end > start || start == source.length)
        result.add(start until end)
        start = end
    } while (start < source.length)
    return result
}

internal fun List<PostLink>.within(start: Int, end: Int): List<PostLink> = filter { it.start < end && it.end > start }.map {
    it.copy(start = (it.start - start).coerceAtLeast(0), end = (it.end - start).coerceAtMost(end - start))
}
