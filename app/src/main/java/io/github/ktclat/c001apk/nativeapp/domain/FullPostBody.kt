// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.domain

/** Only feed/detail may populate this. Never inferred from a list preview or persisted locally. */
sealed interface FullPostBody {
    /** Entire server message, not a substring. This is text-body parity, not article/media parity. */
    data class Available(val html: String) : FullPostBody
    data class Unavailable(val reason: FullPostBodyLimit, val receivedUnits: Int? = null) : FullPostBody
}

enum class FullPostBodyLimit {
    PARSING_ERROR, BODY_TOO_LARGE, MISSING_MESSAGE, STRUCTURED_ARTICLE, UNSUPPORTED_MARKUP, MARKUP_COMPLEXITY, LINK_COMPLEXITY,
}

object FullPostBodyBounds {
    const val MAX_HTML_UNITS = 262_144
    const val MAX_MARKUP_TAGS = 8_192
    const val MAX_MARKUP_DEPTH = 64
    const val MAX_LINKS = 1_024
    const val MAX_LINK_UNITS = 4_096
    const val PAGE_UNITS = 4_000
}
