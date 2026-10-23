// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.domain

/** Only feed/detail may populate this. Never inferred from a list preview or persisted locally. */
sealed interface FullPostBody {
    /** Entire server message, not a substring. This is text-body parity, not article/media parity. */
    data class Available(val html: String) : FullPostBody
    /** Exact source identity plus bounded known blocks; never populated by list projections. */
    data class Structured(val rawOutput: String, val title: String?, val cover: String?, val blocks: List<ArticleBlock>) : FullPostBody
    data class Unavailable(val reason: FullPostBodyLimit, val receivedUnits: Int? = null) : FullPostBody
}

enum class FullPostBodyLimit {
    MALFORMED_ARTICLE, UNSUPPORTED_ARTICLE, ARTICLE_COMPLEXITY, PARSING_ERROR, BODY_TOO_LARGE, MISSING_MESSAGE, STRUCTURED_ARTICLE, UNSUPPORTED_MARKUP, MARKUP_COMPLEXITY, LINK_COMPLEXITY,
}

object FullPostBodyBounds {
    const val MAX_HTML_UNITS = 262_144
    const val MAX_MARKUP_TAGS = 8_192
    const val MAX_MARKUP_DEPTH = 64
    const val MAX_LINKS = 1_024
    const val MAX_LINK_UNITS = 4_096
    const val PAGE_UNITS = 4_000
}

/** Flutter feed_article.dart fields, without guessing a recursive or alternate text schema. */
sealed interface ArticleBlock {
    data class Text(val message: String) : ArticleBlock
    data class Image(val url: String?, val description: String) : ArticleBlock
    data class ShareUrl(val url: String?, val title: String) : ArticleBlock
}

object ArticleBodyBounds {
    const val MAX_SOURCE_BLOCKS = 128
    const val MAX_IMAGES = 18
    const val MAX_SEGMENTS = 200
    const val MAX_FRAGMENTS_PER_SEGMENT = 16
    const val MAX_IMAGES_PER_SEGMENT = 4
    const val MAX_TITLE_UNITS = 4_000
    const val MAX_LABEL_UNITS = 2_000
}
