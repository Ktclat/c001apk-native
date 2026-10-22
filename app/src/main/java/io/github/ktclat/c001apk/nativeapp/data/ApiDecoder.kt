// SPDX-License-Identifier: AGPL-3.0-only
// Protocol fields and error taxonomy adapted from Ktclat/c001apk (AGPL-3.0).
package io.github.ktclat.c001apk.nativeapp.data

import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.serialization.json.*

internal class ApiProblem(val failure: ApiResult.Failure) : Exception()
internal object ApiDecoder {
    private val json = Json { ignoreUnknownKeys = true }
    fun envelope(body: String): JsonElement {
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
            throw ApiProblem(failure(kind).copy(challenge = if (kind == FailureKind.CAPTCHA) challenge(root) else null))
        }
        return root["data"] ?: invalid()
    }
    private fun challenge(root: JsonObject): ApiChallenge? = when (root.text("messageStatus")) {
        "err_request_captcha" -> ApiChallenge.Graphic()
        "err_request_captcha_v2" -> {
            val raw = root["messageExtra"] ?: root["extra"]
            val extra = (raw as? JsonObject) ?: (raw as? JsonPrimitive)?.contentOrNull?.let {
                runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull()
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
            userName = user?.text("username").orEmpty().ifBlank { obj.text("username") },
            userId = obj.text("uid").ifBlank { user?.text("uid").orEmpty() },
            avatarUrl = safeImage(obj.text("userAvatar").ifBlank { user?.text("userAvatar").orEmpty() }.ifBlank { obj.text("avatar") }),
            imageUrls = if (kind in setOf("feed", "feed_reply")) pictures(obj) else
                (pictures(obj) + listOf("pic", "message_cover", "logo").map { obj.text(it) }).mapNotNull(::safeImage).distinct().take(18),
            replyCount = obj.count("replynum") ?: obj.count("commentnum") ?: 0,
            likeCount = obj.count("likenum") ?: 0,
            targetUrl = obj.text("url").takeIf { it.isNotBlank() },
            children = children,
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
            firstRelationTitle = ((obj["relationRows"] as? JsonArray)?.firstOrNull() as? JsonObject)?.text("title").orEmpty(),
        )
    }
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
    private fun safeImage(value: String): String? = value.takeIf { it.startsWith("https://") }
    fun invalid(): Nothing = throw ApiProblem(failure(FailureKind.INVALID_RESPONSE))
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty().take(12_000)
}
internal fun failure(kind: FailureKind, status: Int? = null) = ApiResult.Failure(kind, when (kind) {
    FailureKind.UNCERTAIN -> "操作结果尚不确定，请先刷新核实，不要直接重复提交"
    FailureKind.NETWORK -> "连接失败，请检查网络后重试"
    FailureKind.AUTHENTICATION -> "此内容需要登录，原生版账号功能尚在迁移"
    FailureKind.CAPTCHA -> "服务端要求验证码，原生版暂不支持验证；未绕过验证"
    FailureKind.RISK_CONTROL -> "服务端限制了当前网络环境，请稍后重试"
    FailureKind.CLIENT_UPGRADE -> "服务端暂不接受当前客户端协议"
    FailureKind.RATE_LIMIT -> "请求过于频繁，请稍后重试"
    FailureKind.INVALID_RESPONSE -> "服务器响应格式异常，请稍后重试"
    FailureKind.INVALID_ARGUMENT -> "请求参数无效"
    FailureKind.HTTP -> "请求失败（HTTP ${status ?: "未知"}）"
    FailureKind.BUSINESS -> "服务器未能完成请求，请稍后重试"
}, status)
