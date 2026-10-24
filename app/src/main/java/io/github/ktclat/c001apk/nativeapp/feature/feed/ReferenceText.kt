// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.feature.feed

internal sealed interface ReferenceText {
    data class Immediate(val text: String) : ReferenceText
    data object Html : ReferenceText
}

/**
 * Only classify the same UTF-16 prefix the HTML path consumes. This is intentionally
 * not a general HTML detector: whitespace, controls, entities, tags and unfamiliar
 * characters keep the existing parser. The allowlist contains only literal HTML
 * character data, with nothing Html.fromHtml or trim must normalize/remove.
 */
internal fun classifyReferenceText(body: String, hotReply: Boolean): ReferenceText {
    val prefix = body.take(if (hotReply) 1200 else 4000)
    var index = 0
    while (index < prefix.length) {
        val char = prefix[index]
        when {
            char in '!'..'~' && char != '<' && char != '&' -> Unit
            char in '\u4e00'..'\u9fff' -> Unit // Basic CJK ideographs.
            char in '\u3001'..'\u303f' -> Unit // CJK punctuation, excluding ideographic space.
            char in '\uff01'..'\uff60' -> Unit // Full-width graphics, not whitespace.
            char in '\u2600'..'\u27bf' || char == '\ufe0f' -> Unit // Emoji symbols/variation.
            char.isHighSurrogate() -> {
                val low = prefix.getOrNull(index + 1) ?: return ReferenceText.Html
                if (!low.isLowSurrogate()) return ReferenceText.Html
                val point = Character.toCodePoint(char, low)
                if (point !in 0x1f000..0x1faff) return ReferenceText.Html
                index++
            }
            else -> return ReferenceText.Html
        }
        index++
    }
    return ReferenceText.Immediate(prefix)
}
