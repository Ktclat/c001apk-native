// SPDX-License-Identifier: AGPL-3.0-only
// Protocol fields and error taxonomy adapted from Ktclat/c001apk (AGPL-3.0).
package io.github.ktclat.c001apk.nativeapp.data

import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.serialization.json.*

internal class ApiProblem(val failure: ApiResult.Failure) : Exception()
internal object ApiDecoder {
    private val json = Json { ignoreUnknownKeys = true }
    fun envelope(body: String, requestPath: String? = null): JsonElement {
        // Reject extreme nesting/node counts before the recursive JSON parser allocates a tree.
        if (!boundedJsonEnvelope(body)) throw ApiProblem(failure(FailureKind.INVALID_RESPONSE).copy(
            message = "响应结构超过安全读取上限，未展示不完整正文"))
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: invalid()
        val marker = root.text("messageStatus").lowercase()
        val clues = "$marker ${root.text("message")} ${root.text("error")}".lowercase()
        val code = root.text("code").toIntOrNull()
        val status = root.text("status").toIntOrNull()
        val failed = (code != null && code !in listOf(0, 1, 200)) ||
            (status != null && status !in listOf(0, 1)) || marker.startsWith("err_") ||
            root.text("error").let { it.isNotEmpty() && it !in listOf("0", "false") } ||
            ((root["data"] == null || root["data"] == JsonNull) && root.text("message").isNotEmpty() && code == null && status == null)
        if (failed) {
            val kind = when {
                status == -10 || "网络环境" in clues -> FailureKind.RISK_CONTROL
                marker == "err_request_need_upgrade_new_version" -> FailureKind.CLIENT_UPGRADE
                "captcha" in clues || "验证码" in clues -> FailureKind.CAPTCHA
                "login" in clues || "auth" in clues || "登录" in clues || code == 401 -> FailureKind.AUTHENTICATION
                "rate_limit" in clues || "频繁" in clues || code == 429 -> FailureKind.RATE_LIMIT
                else -> FailureKind.BUSINESS
            }
            // Upstream apk/detail -2 is endpoint-specific, not a global meaning for -2.
            // Keep access/security failures above authoritative and never echo server text.
            val unavailableApp = kind == FailureKind.BUSINESS && requestPath == "v6/apk/detail" &&
                status == -2 && root.text("message").let { "应用不存在" in it || "未上架" in it }
            // Flutter's guest_detail_test fixture uses this exact feed/detail code/message.
            // Do not assign a global meaning to 404 or echo arbitrary server content.
            val unavailableFeed = kind == FailureKind.BUSINESS && requestPath == "v6/feed/detail" &&
                code == 404 && status == null && marker.isEmpty() && root.text("message") == "动态不存在"
            throw ApiProblem(failure(kind).let {
                if (unavailableApp) it.copy(message = "该应用未在酷安上架")
                else if (unavailableFeed) it.copy(message = "该动态不存在或暂不可见") else it
            }.copy(challenge = if (kind == FailureKind.CAPTCHA) challenge(root) else null))
        }
        return root["data"] ?: invalid()
    }
    private fun challenge(root: JsonObject): ApiChallenge? = when (root.text("messageStatus")) {
        "err_request_captcha" -> ApiChallenge.Graphic()
        "err_request_captcha_v2" -> {
            val raw = root["messageExtra"] ?: root["extra"]
            val extra = (raw as? JsonObject) ?: (raw as? JsonPrimitive)?.contentOrNull?.let {
                // The outer lexical check cannot inspect JSON embedded inside a quoted string.
                if (boundedJsonEnvelope(it)) runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() else null
            } ?: root
            val id = extra.text("captchaId").takeIf { Regex("^[a-zA-Z0-9]{16,64}$").matches(it) } ?: "414e5c9b866a03db03f860a1a9101672"
            val candidate = (extra["captchaField"] as? JsonPrimitive)?.contentOrNull
            val field = candidate?.takeIf { it.trim().isEmpty() || Regex("^_[a-zA-Z0-9_]{1,63}$").matches(it) }?.trim() ?: "_v2_post_token"
            ApiChallenge.Netease(id, field)
        }
        else -> null
    }
    private class Budget(var remaining: Int = 500)
    fun item(value: JsonElement): ContentItem = decodeItem(value, 0, Budget())
    /** Same endpoint and ContentItem metadata contract; retain the actual detail message separately. */
    fun feedDetail(value: JsonElement, checkActive: () -> Unit = {}): ContentItem {
        val obj = value as? JsonObject ?: invalid()
        val preview = item(value)
        val highlighted = listOf("topReplyRows", "replyMeRows").flatMap { field ->
            (obj[field] as? JsonArray).orEmpty().take(20).filterIsInstance<JsonObject>().map(::item)
        }.filter { it.kind == "feed_reply" }.distinctBy { it.id }
        return preview.copy(fullPostBody = decodeDetailBody(obj, checkActive), highlightedReplies = highlighted)
    }
    fun items(values: JsonArray): List<ContentItem> {
        val budget = Budget()
        return values.map { decodeItem(it, 0, budget) }
    }
    private fun decodeItem(value: JsonElement, depth: Int, budget: Budget): ContentItem {
        if (--budget.remaining < 0) invalid()
        val obj = value as? JsonObject ?: invalid()
        val kind = obj.text("entityType").ifBlank { "unknown" }
        val user = (obj["userInfo"] as? JsonObject)
            ?: if (kind == "contacts") obj["fUserInfo"] as? JsonObject else null
        val id = obj.text("id").ifBlank { obj.text("entityId") }.ifBlank { obj.text("uid") }
        val children = if (depth >= 3) emptyList() else
            (obj["entities"] as? JsonArray).orEmpty().take(100).filterIsInstance<JsonObject>()
                .map { decodeItem(it, depth + 1, budget) }
        val replies = if (depth >= 3) emptyList() else
            (obj["replyRows"] as? JsonArray).orEmpty().take(100).filterIsInstance<JsonObject>()
                .map { decodeItem(it, depth + 1, budget) }
        val rawMessage = listOf("message", "description", "intro").asSequence()
            .mapNotNull { (obj[it] as? JsonPrimitive)?.contentOrNull }.firstOrNull { it.isNotBlank() }.orEmpty()
        val message = rawMessage.take(12_000).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
        return ContentItem(
            id = id,
            kind = kind,
            title = if (kind == "feed") obj.text("message_title") else obj.text("title").ifBlank { obj.text("message_title") }.ifBlank { obj.text("appName") },
            message = message, bodyTruncated = message.length < rawMessage.length,
            userName = if (kind == "contacts") obj.contactField("username") else user?.text("username").orEmpty().ifBlank { obj.text("username") },
            userId = if (kind == "contacts") obj.contactField("uid") else obj.text("uid").ifBlank { user?.text("uid").orEmpty() },
            avatarUrl = safeImage(if (kind == "contacts") obj.contactField("userAvatar") else obj.text("userAvatar").ifBlank { user?.text("userAvatar").orEmpty() }.ifBlank { obj.text("avatar") }),
            imageUrls = if (kind in setOf("feed", "feed_reply")) pictures(obj) else
                (pictures(obj) + listOf("pic", "message_cover", "logo").map { obj.text(it) }).mapNotNull(::safeImage).distinct().take(18),
            replyCount = obj.count("replynum") ?: obj.count("commentnum") ?: 0,
            likeCount = obj.count("likenum") ?: 0,
            targetUrl = obj.text("url").takeIf { it.isNotBlank() },
            children = children,
            childrenIncomplete = obj["entities"]?.let { raw ->
                raw !is JsonArray || raw.size > 100 || raw.any { it !is JsonObject } || (depth >= 3 && raw.isNotEmpty())
            } ?: false,
            tabs = (obj["tabList"] as? JsonArray).orEmpty().take(50).filterIsInstance<JsonObject>().mapNotNull {
                val url = it.text("url")
                if (url.isEmpty()) null else ContentTab(it.text("title"), url)
            },
            packageName = obj.text("packageName").takeIf { it.isNotBlank() },
            publishedAtSeconds = obj.text("dateline").toLongOrNull() ?: 0,
            topicTags = obj.text("tags").split(',').map { it.trim() }.filter { it.isNotEmpty() },
            topicTitle = obj.text("ttitle"),
            entityTemplate = obj.optionalText("entityTemplate"),
            replies = replies,
            feed = if (kind == "feed") feedMetadata(obj, id, replies.firstOrNull()) else null,
            replyInteraction = if (kind == "feed_reply") ReplyInteractionMetadata(
                likeState = when ((obj["userAction"] as? JsonObject)?.text("like")) {
                    "1" -> ExplicitLikeState.LIKED; "0" -> ExplicitLikeState.NOT_LIKED; else -> ExplicitLikeState.UNKNOWN
                }, likeCount = obj.count("likenum"), replyCount = obj.count("replynum"),
            ) else null,
            firstRelationTitle = ((obj["relationRows"] as? JsonArray)?.firstOrNull() as? JsonObject)?.text("title").orEmpty(),
            cardFields = if (kind == "card" || listOf("pic", "logo", "title", "username", "userAvatar").any { it in obj }) HomeCardFields(
                picture = safeImage(obj.text("pic")), logo = safeImage(obj.text("logo")), description = obj.text("description"), title = obj.text("title"),
                userName = obj.text("username"), avatar = safeImage(obj.text("userAvatar")),
            ) else null,
            entityCard = entityCardMetadata(obj, kind, user),
            sourceEntityId = obj.optionalText("entityId"),
        )
    }
    /** Exact Flutter AppCard/CollectionCard roles, including snake_case wire fields.
     * Missing values remain absent; no invented counts, image downloads or HTML interpretation.
     */
    private fun entityCardMetadata(obj: JsonObject, kind: String, user: JsonObject?): EntityCardMetadata? {
        if (kind !in setOf("apk", "product", "topic", "user", "contacts", "recentHistory", "collection")) return null
        fun field(key: String, alias: String? = null): String = obj.text(key).ifBlank { alias?.let { obj.text(it) }.orEmpty() }
            .take(80).takeUnless { it == "null" }.orEmpty()
        fun metric(value: String, suffix: String) = value.takeIf { it.isNotBlank() && it != "null" }?.let { "$it$suffix" }
        val metrics = when (kind) {
            "apk" -> listOfNotNull(metric(field("commentnum"), "讨论"), metric(field("downCount"), "下载"))
            "product" -> listOfNotNull(metric(field("hot_num_txt", "hotNumTxt"), "热度"), metric(field("feed_comment_num_txt", "feedCommentNumTxt"), "讨论"))
            "topic" -> listOfNotNull(metric(field("hot_num_txt", "hotNumTxt"), "热度"), metric(field("commentnum_txt", "commentnumTxt"), "讨论"))
            "user" -> listOfNotNull(metric(field("follow"), "关注"), metric(field("fans"), "粉丝"))
            "contacts" -> listOfNotNull(metric(obj.contactField("follow").take(80), "关注"), metric(obj.contactField("fans").take(80), "粉丝"))
            "recentHistory" -> listOfNotNull(metric(field("follow_num", "followNum"), "关注"),
                if (field("target_type", "targetType") == "user") metric(field("fans_num", "fansNum"), "粉丝") else metric(field("comment_num", "commentNum"), "讨论"))
            else -> listOfNotNull(field("is_open_title", "isOpenTitle").takeIf(String::isNotBlank),
                metric(field("follow_num", "followNum"), "人关注"), metric(field("item_num", "itemNum"), "个内容"))
        }
        val image = when (kind) {
            "collection" -> obj.text("cover_pic").ifBlank { obj.text("coverPic") }
            "contacts" -> obj.contactField("userAvatar")
            "user" -> obj.text("userAvatar").ifBlank { user?.text("userAvatar").orEmpty() }
            else -> obj.text("logo")
        }
        return EntityCardMetadata(safeImage(image), kind in setOf("user", "contacts") ||
            (kind == "recentHistory" && field("target_type", "targetType") == "user"), metrics,
            field("target_type_title", "targetTypeTitle"), obj.text("logintime").toLongOrNull()?.takeIf { kind == "user" && it > 0 })
    }
    /** Flutter contact fields fall back independently, not by choosing one whole user object. */
    private fun JsonObject.contactField(key: String): String =
        (((this["userInfo"] as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull
            ?: ((this["fUserInfo"] as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull).orEmpty().take(12_000)
    private fun feedMetadata(obj: JsonObject, id: String, hot: ContentItem?): FeedMetadata {
        val sourceType = obj.optionalText("forwardSourceType")
        val source = obj["forwardSourceFeed"] as? JsonObject
        val action = obj["userAction"] as? JsonObject
        return FeedMetadata(
            deviceTitle = obj.optionalText("device_title") ?: obj.optionalText("deviceTitle"), infoHtml = obj.optionalText("infoHtml"),
            ipLocation = obj.optionalText("ip_location") ?: obj.optionalText("ipLocation"),
            pinned = when (obj.text("isStickTop")) { "1" -> true; "0" -> false; else -> null },
            likeState = when (action?.text("like")) {
                "1" -> ExplicitLikeState.LIKED; "0" -> ExplicitLikeState.NOT_LIKED; else -> ExplicitLikeState.UNKNOWN
            },
            replyCount = obj.count("replynum"), likeCount = obj.count("likenum"),
            forwardSourceType = sourceType,
            forward = sourceType?.let {
                if (source == null) FeedReference(FeedReferenceKind.FORWARD, parentFeedId = id, deleted = true)
                else FeedReference(FeedReferenceKind.FORWARD,
                    id = source.text("id"), parentFeedId = id, authorId = source.text("uid"),
                    author = source.text("username"), title = source.text("message_title"),
                    body = source.text("message").take(4_000), images = pictures(source))
            },
            hotReply = hot?.let { FeedReference(FeedReferenceKind.HOT_REPLY, it.id, id,
                it.userId, it.userName, it.title, it.message.take(1_200), it.imageUrls) },
            link = obj.optionalText("extra_url")?.let { FeedLinkMetadata(obj.text("extra_title"), it, safeImage(obj.text("extra_pic"))) },
            target = (obj["targetRow"] as? JsonObject)?.let(::relation),
            relations = (obj["relationRows"] as? JsonArray).orEmpty().take(20).filterIsInstance<JsonObject>().map(::relation),
        )
    }
    private fun relation(obj: JsonObject) = FeedRelationMetadata(
        obj.text("id"), obj.optionalText("entityType"), obj.optionalText("targetType"),
        obj.text("title"), obj.optionalText("url"), safeImage(obj.text("logo")),
    )
    private fun pictures(obj: JsonObject): List<String> = (obj["picArr"] as? JsonArray).orEmpty()
        .take(18).mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.take(12_000)?.let(::safeImage) }.distinct()
    private fun JsonObject.count(key: String): Int? = text(key).toLongOrNull()
        ?.takeIf { it >= 0 }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
    private fun JsonObject.optionalText(key: String): String? = text(key).takeIf { it.isNotBlank() }
    private fun safeImage(value: String): String? = publicImageUrl(value)
    fun invalid(): Nothing = throw ApiProblem(failure(FailureKind.INVALID_RESPONSE))
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty().take(12_000)
}
internal fun failure(kind: FailureKind, status: Int? = null) = ApiResult.Failure(kind, when (kind) {
    FailureKind.UNCERTAIN -> "操作结果尚不确定，请先刷新核实，不要直接重复提交"
    FailureKind.NETWORK -> "连接失败，请检查网络后重试"
    FailureKind.AUTHENTICATION -> "此内容需要登录，请登录后重试"
    FailureKind.CAPTCHA -> "服务端要求验证码，当前请求未完成"
    FailureKind.RISK_CONTROL -> "服务端限制了当前网络环境，请稍后重试"
    FailureKind.CLIENT_UPGRADE -> "服务端暂不接受当前客户端协议"
    FailureKind.RATE_LIMIT -> "请求过于频繁，请稍后重试"
    FailureKind.INVALID_RESPONSE -> "服务器响应格式异常，请稍后重试"
    FailureKind.INVALID_ARGUMENT -> "请求参数无效"
    FailureKind.HTTP -> "请求失败（HTTP ${status ?: "未知"}）"
    FailureKind.BUSINESS -> "服务器未能完成请求，请稍后重试"
}, status)
