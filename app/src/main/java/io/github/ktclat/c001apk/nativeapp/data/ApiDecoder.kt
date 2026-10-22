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
            throw ApiProblem(failure(kind))
        }
        return root["data"] ?: invalid()
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
        val user = obj["userInfo"] as? JsonObject
        val imageFields = listOf("pic", "message_cover", "logo", "avatar")
        val pictures = (obj["picArr"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val children = if (depth >= 3) emptyList() else listOf("entities", "replyRows", "rows").flatMap { key ->
            (obj[key] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().map { decodeItem(it, depth + 1, budget) }
        }
        return ContentItem(
            id = obj.text("id").ifBlank { obj.text("entityId") }.ifBlank { obj.text("uid") },
            kind = obj.text("entityType").ifBlank { "unknown" },
            title = obj.text("title").ifBlank { obj.text("message_title") }.ifBlank { obj.text("appName") },
            message = obj.text("message").ifBlank { obj.text("description") }.ifBlank { obj.text("intro") },
            userName = obj.text("username").ifBlank { user?.text("username").orEmpty() },
            userId = obj.text("uid").ifBlank { user?.text("uid").orEmpty() },
            avatarUrl = safeImage(user?.text("userAvatar").orEmpty().ifBlank { obj.text("userAvatar") }.ifBlank { obj.text("avatar") }),
            imageUrls = (pictures + imageFields.map { obj.text(it) }).mapNotNull(::safeImage).distinct(),
            replyCount = obj.text("replynum").toIntOrNull() ?: obj.text("commentnum").toIntOrNull() ?: 0,
            likeCount = obj.text("likenum").toIntOrNull() ?: 0,
            targetUrl = obj.text("url").takeIf { it.isNotBlank() },
            children = children,
            tabs = (obj["tabList"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull {
                val url = it.text("url")
                if (url.isEmpty()) null else ContentTab(it.text("title"), url)
            },
            packageName = obj.text("packageName").takeIf { it.isNotBlank() },
            publishedAtSeconds = obj.text("dateline").toLongOrNull() ?: 0,
            topicTags = obj.text("tags").split(',').map { it.trim() }.filter { it.isNotEmpty() },
            topicTitle = obj.text("ttitle"),
            firstRelationTitle = ((obj["relationRows"] as? JsonArray)?.firstOrNull() as? JsonObject)?.text("title").orEmpty(),
        )
    }
    private fun safeImage(value: String): String? = value.takeIf { it.startsWith("https://") }
    fun invalid(): Nothing = throw ApiProblem(failure(FailureKind.INVALID_RESPONSE))
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
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
