// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

import io.github.ktclat.c001apk.nativeapp.domain.*
import io.github.ktclat.c001apk.nativeapp.platform.AppLink
import io.github.ktclat.c001apk.nativeapp.platform.parseAppLink
import kotlinx.serialization.json.*
import java.net.URI

/** Flutter EventPage's fields, never a feed preview or a recursive generic card. */
internal fun decodeEventDetail(value: JsonElement, requestedId: String): ContentItem {
    if (value == JsonNull) throw ApiProblem(failure(FailureKind.BUSINESS).copy(message = "未找到活动，可能已下架"))
    val obj = value as? JsonObject ?: ApiDecoder.invalid()
    fun field(name: String): String = (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim().orEmpty()
    if ((obj["title"] as? JsonPrimitive)?.isString != true) ApiDecoder.invalid()
    val responseId = (obj["id"] as? JsonPrimitive)?.contentOrNull
    if (responseId != null && (responseId.isEmpty() || responseId.any { it !in '0'..'9' } ||
            responseId.trimStart('0').ifEmpty { "0" } != requestedId.trimStart('0').ifEmpty { "0" })) ApiDecoder.invalid()
    val title = field("title")
    val sources = listOf("description" to "活动说明", "notice_rule" to "参与规则", "content" to "活动正文")
    val total = sources.sumOf { field(it.first).length.toLong() } + title.length
    if (total > FullPostBodyBounds.MAX_HTML_UNITS || title.length > ArticleBodyBounds.MAX_TITLE_UNITS) throw ApiProblem(failure(FailureKind.INVALID_RESPONSE).copy(
        message = "活动内容超过安全读取上限，未展示不完整详情"))
    val sections = sources.mapNotNull { (key, label) ->
        val source = field(key)
        if (source.isEmpty()) null else EventSection(label, FullPostBody.Available(source), literal = key != "content")
    }
    val action = field("action_url").takeIf { it.length <= FullPostBodyBounds.MAX_LINK_UNITS }?.takeIf {
        runCatching { URI(it).let { uri -> uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null } }.getOrDefault(false) &&
            parseAppLink(it) != AppLink.Unsupported
    }
    val deadline = (obj["time_reg_end"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()?.takeIf { it in 1..253_402_300_799L }
    return ContentItem(requestedId, "event", title = title,
        imageUrls = listOfNotNull(field("pic").takeIf { it.startsWith("https://") && it.length <= FullPostBodyBounds.MAX_LINK_UNITS }),
        event = EventContent(sections, deadline, action))
}
