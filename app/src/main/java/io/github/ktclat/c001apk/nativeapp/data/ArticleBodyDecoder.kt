// SPDX-License-Identifier: AGPL-3.0-only
// Source: Flutter feed_controller.dart:94-122 and feed_article.dart:20-29 at 52202fdf.
package io.github.ktclat.c001apk.nativeapp.data

import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Called on the captured detail read's Default dispatcher, never on Main. */
internal fun decodeDetailBody(obj: JsonObject, checkActive: () -> Unit = {}): FullPostBody {
    fun unavailable(reason: FullPostBodyLimit) = FullPostBody.Unavailable(reason)
    fun ordinary(): FullPostBody {
        val message = (obj["message"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: return unavailable(FullPostBodyLimit.MISSING_MESSAGE)
        return if (message.length > FullPostBodyBounds.MAX_HTML_UNITS)
            FullPostBody.Unavailable(FullPostBodyLimit.BODY_TOO_LARGE, message.length)
        else FullPostBody.Available(message)
    }
    val structured = obj["message_raw_output"]
    if (structured == null || structured == JsonNull) return ordinary()
    val raw = (structured as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        ?: return unavailable(FullPostBodyLimit.MALFORMED_ARTICLE)
    // Source uses exact empty/null, not blank: padded JSON null is not a list.
    if (raw.isEmpty() || raw == "null") return ordinary()
    if (raw.length > FullPostBodyBounds.MAX_HTML_UNITS)
        return FullPostBody.Unavailable(FullPostBodyLimit.BODY_TOO_LARGE, raw.length)
    checkActive()
    when (inspectJsonEnvelope(raw, checkActive = checkActive)) {
        JsonEnvelopeStatus.MALFORMED -> return unavailable(FullPostBodyLimit.MALFORMED_ARTICLE)
        JsonEnvelopeStatus.LIMIT_EXCEEDED -> return unavailable(FullPostBodyLimit.ARTICLE_COMPLEXITY)
        JsonEnvelopeStatus.WITHIN_LIMITS -> Unit
    }
    val decoded = try { Json.parseToJsonElement(raw) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: IllegalArgumentException) { return unavailable(FullPostBodyLimit.MALFORMED_ARTICLE) }
    checkActive()
    val values = decoded as? JsonArray ?: return unavailable(FullPostBodyLimit.MALFORMED_ARTICLE)
    if (values.isEmpty()) return ordinary()
    if (values.size > ArticleBodyBounds.MAX_SOURCE_BLOCKS) return unavailable(FullPostBodyLimit.ARTICLE_COMPLEXITY)
    // Flutter's nullable String casts reject numbers/arrays even in unused known fields.
    val fields = listOf("type", "message", "url", "description", "title", "subTitle", "logo")
    fun JsonObject.validString(key: String): Boolean = this[key].let { it == null || it == JsonNull || it is JsonPrimitive && it.isString }
    fun JsonObject.optionalString(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    if (!obj.validString("title") || !obj.validString("message_cover")) return unavailable(FullPostBodyLimit.MALFORMED_ARTICLE)
    val title = obj.optionalString("title")
    val cover = obj.optionalString("message_cover")
    val rawUnits = raw.length.toLong() + (title?.length ?: 0) + (cover?.length ?: 0)
    if (rawUnits > FullPostBodyBounds.MAX_HTML_UNITS) return FullPostBody.Unavailable(FullPostBodyLimit.BODY_TOO_LARGE, rawUnits.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    if ((cover?.length ?: 0) > FullPostBodyBounds.MAX_LINK_UNITS) return unavailable(FullPostBodyLimit.LINK_COMPLEXITY)
    var images = if (cover.isNullOrEmpty()) 0 else 1
    var shares = 0
    val blocks = ArrayList<ArticleBlock>(values.size)
    for (value in values) {
        checkActive()
        val block = value as? JsonObject ?: return unavailable(FullPostBodyLimit.MALFORMED_ARTICLE)
        if (fields.any { !block.validString(it) }) return unavailable(FullPostBodyLimit.MALFORMED_ARTICLE)
        val url = block.optionalString("url")
        if ((url?.length ?: 0) > FullPostBodyBounds.MAX_LINK_UNITS) return unavailable(FullPostBodyLimit.LINK_COMPLEXITY)
        val decodedBlock = when (block.optionalString("type")) {
            "text" -> ArticleBlock.Text(block.optionalString("message").orEmpty())
            "image" -> {
                if (++images > ArticleBodyBounds.MAX_IMAGES) return unavailable(FullPostBodyLimit.ARTICLE_COMPLEXITY)
                ArticleBlock.Image(url, block.optionalString("description").orEmpty())
            }
            "shareUrl" -> {
                if (++shares > FullPostBodyBounds.MAX_LINKS) return unavailable(FullPostBodyLimit.LINK_COMPLEXITY)
                ArticleBlock.ShareUrl(url, block.optionalString("title").orEmpty())
            }
            // Unlike Flutter's filter, never silently lose an unknown block and call the rest complete.
            else -> return unavailable(FullPostBodyLimit.UNSUPPORTED_ARTICLE)
        }
        blocks.add(decodedBlock)
    }
    checkActive()
    return FullPostBody.Structured(raw, title, cover, blocks.toList())
}
