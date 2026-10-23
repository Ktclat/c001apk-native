// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

import java.util.regex.Pattern

// Flutter reply_dialog.dart uses TextField(maxLength: 500), counted as grapheme clusters.
internal const val MAX_COMPOSER_CHARACTERS = 500
// Additional native resource bound: pathological combining sequences cannot stall layout/counting.
internal const val MAX_COMPOSER_CODE_UNITS = 32_768
internal const val COMPOSER_TEXT_TOO_LONG = "内容最多500个字符，请精简后再发送"
internal const val COMPOSER_PASTE_TOO_LARGE = "输入内容过大，请分段粘贴；原草稿已保留"
private val grapheme = Pattern.compile("\\X")

/** Saturates at 501. Never scans an unbounded paste or splits a surrogate/ZWJ sequence. */
internal fun composerCharacterCount(text: String): Int {
    if (text.length > MAX_COMPOSER_CODE_UNITS) return MAX_COMPOSER_CHARACTERS + 1
    val matcher = grapheme.matcher(text)
    var count = 0
    while (count <= MAX_COMPOSER_CHARACTERS && matcher.find()) count++
    return count
}
internal fun withinComposerTextLimit(text: String): Boolean = composerCharacterCount(text) <= MAX_COMPOSER_CHARACTERS
