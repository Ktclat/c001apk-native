// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.util.regex.Pattern

private val insertionGrapheme = Pattern.compile("\\X")

/**
 * Replace a forward/reverse selection without splitting a grapheme (including UTF-16 pairs).
 * A collapsed caret inside a grapheme moves to its end and never deletes that grapheme.
 * Invalid offsets append. Clear IME composition because this is an explicit external edit.
 * Null means the whole insertion exceeds the existing resource bound; never truncate the draft.
 * Raw offsets are intentional: TextFieldValue otherwise silently clamps out-of-range selections.
 */
internal fun insertComposerToken(text: String, start: Int, end: Int, token: String): TextFieldValue? {
    if (text.length > MAX_COMPOSER_CODE_UNITS || token.length > MAX_COMPOSER_CODE_UNITS) return null
    var from = text.length
    var to = text.length
    if (start in 0..text.length && end in 0..text.length) {
        from = minOf(start, end)
        to = maxOf(start, end)
        val collapsed = from == to
        val matcher = insertionGrapheme.matcher(text)
        while (matcher.find()) {
            val left = matcher.start()
            val right = matcher.end()
            if (from > left && from < right) from = if (collapsed) right else left
            if (to > left && to < right) to = right
            if (right >= to) break
        }
    }
    if (text.length - (to - from) + token.length > MAX_COMPOSER_CODE_UNITS) return null
    val updated = text.replaceRange(from, to, token)
    return TextFieldValue(updated, TextRange(from + token.length), composition = null)
}
