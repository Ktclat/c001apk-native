// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

import io.github.ktclat.c001apk.nativeapp.account.AccountStore
import io.github.ktclat.c001apk.nativeapp.account.SessionSnapshot
import io.github.ktclat.c001apk.nativeapp.data.CoolapkHttpTransport
import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** A transport is bound to one immutable account snapshot, never a mutable global cookie jar. */
class SocialRepository(private val accounts: AccountStore,
    private val transportFor: (SessionSnapshot) -> CoolapkHttpTransport) {
    private suspend fun <T> authenticated(block: suspend (CoolapkHttpTransport) -> T): T = withContext(Dispatchers.IO) {
        val snapshot = accounts.snapshot()
        if (snapshot.accountId == null) throw LoginRequired()
        val result = block(transportFor(snapshot))
        if (!accounts.isCurrent(snapshot)) throw CancellationException("Session changed")
        result
    }
    suspend fun notices(category: NoticeCategory, cursor: PageCursor = PageCursor()): ApiResult<Page<SocialRow>> =
        rows(category.path, cursor)
    suspend fun chat(conversationKey: String, cursor: PageCursor = PageCursor()): ApiResult<Page<SocialRow>> {
        if (conversationKey.isBlank() || conversationKey.length > 256) return invalid()
        return rows("v6/message/chat", cursor, mapOf("ukey" to conversationKey))
    }
    private suspend fun rows(path: String, cursor: PageCursor, extra: Map<String,String> = emptyMap()): ApiResult<Page<SocialRow>> {
        val query = extra + buildMap {
            put("page", cursor.page.toString()); cursor.firstItem?.let { put("firstItem", it) }; cursor.lastItem?.let { put("lastItem", it) }
        }
        return try { authenticated { transport -> when (val result = transport.get(path, query)) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> {
                val array = result.value as? JsonArray ?: return@authenticated invalid()
                if (array.size > 500 || array.any { it !is JsonObject }) return@authenticated invalid()
                val ended = array.filterIsInstance<JsonObject>().any { it.text("entityType") == "noMoreDataCard" }
                val rows = array.filterIsInstance<JsonObject>().filter { it.text("entityType") != "noMoreDataCard" }.map { obj -> SocialRow(
                    obj.text("id").ifBlank { if (path == "v6/message/list") obj.text("ukey") else "" }, obj.text("message"),
                    obj.text("messageUsername").ifBlank { obj.text("username") }.ifBlank { obj.text("fromusername") }, obj.text("messageUid").ifBlank { obj.text("uid") }.ifBlank { obj.text("fromuid") },
                    obj.text("ukey").takeIf { it.isNotBlank() }, obj.text("is_top") == "1",
                    safeMessageImage(obj.text("message_pic")), obj.text("message_pic").isNotBlank()) }
                if (rows.any { it.id.isBlank() }) return@authenticated invalid()
                val last = rows.lastOrNull()?.id?.takeIf { it.isNotBlank() }
                ApiResult.Success(Page(rows, if (ended || last == null || last == cursor.lastItem) null else
                    PageCursor(cursor.page + 1, cursor.firstItem ?: rows.first().id, last)))
            }
        } } } catch (_: LoginRequired) { loginFailure() }
    }
    suspend fun like(id: String, reply: Boolean, liked: Boolean): WriteOutcome {
        if (!validId(id)) return badArgument()
        val action = if (reply) { if (liked) "likeReply" else "unLikeReply" } else if (liked) "like" else "unlike"
        return write { it.post("v6/feed/$action", mapOf("trace" to ""), query = mapOf("id" to id, "detail" to "0")) }
    }
    suspend fun follow(target: FollowTarget, id: String, followed: Boolean): WriteOutcome {
        if (id.isBlank() || (target != FollowTarget.TOPIC && !validId(id))) return badArgument()
        return write { transport -> when (target) {
            FollowTarget.USER -> transport.post("v6/user/${if (followed) "follow" else "unfollow"}", emptyMap(), query = mapOf("uid" to id))
            FollowTarget.APP -> transport.get("v6/apk/${if (followed) "follow" else "unFollow"}", mapOf("id" to id), mutation = true)
            FollowTarget.TOPIC -> transport.get("v6/feed/${if (followed) "followTag" else "unFollowTag"}", mapOf("tag" to id), mutation = true)
            FollowTarget.PRODUCT -> transport.post("v6/product/changeFollowStatus", mapOf("id" to id, "status" to if (followed) "1" else "0"), multipart = true)
        } }
    }
    suspend fun publish(draft: ComposerDraft): WriteOutcome {
        if (draft.text.isBlank() || (draft.replyId != null && !validId(draft.replyId))) return badArgument()
        if (draft.targetType != null && draft.targetType !in setOf("apk", "product", "topic")) return badArgument()
        if (draft.targetType != null && draft.targetId.isNullOrBlank()) return badArgument()
        val form = buildMap {
            put("message", draft.text)
            if (draft.replyId == null) {
                put("type", if (draft.targetType == "apk") "comment" else "feed")
                put("status", "1"); put("publish_status", if (draft.selfOnly) "1" else "0")
                draft.targetType?.let { put("targetType", it) }; draft.targetId?.let { put("targetId", it) }
            } else put("replyAndForward", if (draft.replyAndForward) "1" else "0")
        }
        return write(requireRecord = true) { if (draft.replyId == null) it.post("v6/feed/createFeed", form)
            else it.post("v6/feed/reply", form, query = mapOf("id" to draft.replyId, "type" to if (draft.replyToReply) "reply" else "feed")) }
    }
    suspend fun sendText(uid: String, text: String): WriteOutcome {
        if (!validId(uid) || text.isBlank()) return badArgument()
        return write(requireRecord = true, messageRecord = true) {
            it.post("v6/message/send", mapOf("message" to text, "message_pic" to ""), query = mapOf("uid" to uid), multipart = true)
        }
    }
    suspend fun ownFeeds(cursor: PageCursor): ApiResult<Page<FeedShareItem>> = withContext(Dispatchers.IO) {
        val snapshot = accounts.snapshot()
        val uid = snapshot.accountId ?: return@withContext loginFailure()
        val params = buildMap { put("uid", uid); put("page", cursor.page.toString()); put("showAnonymous", "0"); put("isIncludeTop", "0")
            cursor.firstItem?.let { put("firstItem", it) }; cursor.lastItem?.let { put("lastItem", it) } }
        val response = transportFor(snapshot).get("v6/user/feedList", params)
        if (!accounts.isCurrent(snapshot)) throw CancellationException("Session changed")
        return@withContext when (response) {
            is ApiResult.Failure -> response
            is ApiResult.Success -> {
                val array = response.value as? JsonArray ?: return@withContext invalid()
                if (array.size > 500 || array.any { it !is JsonObject }) return@withContext invalid()
                val objects = array.filterIsInstance<JsonObject>()
                val feeds = objects.filter { it.text("entityType") == "feed" && validId(it.text("id")) }
                    .map { FeedShareItem(it.text("id"), it.text("message_title").ifBlank { it.text("message") }) }
                val last = objects.lastOrNull()?.text("id")?.takeIf { it.isNotBlank() }
                val ended = objects.any { it.text("entityType") == "noMoreDataCard" }
                ApiResult.Success(Page(feeds, if (ended || last == null || last == cursor.lastItem) null else
                    PageCursor(cursor.page + 1, cursor.firstItem ?: objects.firstOrNull()?.text("id"), last)))
            }
        }
    }
    suspend fun imageLocation(messageId: String): ApiResult<String> {
        if (!validId(messageId)) return ApiResult.Failure(FailureKind.INVALID_ARGUMENT, "消息编号无效")
        return try { authenticated { it.getImageLocation(messageId) } } catch (_: LoginRequired) { loginFailure() }
    }
    suspend fun sendImage(uid: String, image: SelectedImage, uploader: OssImageUploader = OssImageUploader()): WriteOutcome = withContext(Dispatchers.IO) {
        if (!validId(uid) || image.sizeBytes !in 1..MAX_IMAGE_BYTES) return@withContext badArgument()
        val snapshot = accounts.snapshot()
        if (snapshot.accountId == null) return@withContext WriteOutcome.Rejected(FailureKind.AUTHENTICATION, "请先登录")
        val transport = transportFor(snapshot)
        val fileList = buildJsonArray { add(buildJsonObject {
            put("name", image.name); put("resolution", "${image.width}x${image.height}"); put("md5", image.md5Hex)
        }) }.toString()
        val prepared = transport.post("v6/upload/ossUploadPrepare", mapOf("uploadBucket" to "message", "uploadDir" to "message",
            "is_anonymous" to "0", "uploadFileList" to fileList, "toUid" to uid), multipart = true)
        if (!accounts.isCurrent(snapshot)) throw CancellationException("Session changed")
        if (prepared is ApiResult.Failure) return@withContext prepared.writeOutcome()
        val grant = parseOssGrant((prepared as ApiResult.Success).value, image, System.currentTimeMillis())
            ?: return@withContext WriteOutcome.Rejected(FailureKind.INVALID_RESPONSE, "图片上传授权不符合安全限制；消息尚未发送")
        val uploaded = uploader.upload(grant, image) { accounts.isCurrent(snapshot) }
        if (!accounts.isCurrent(snapshot)) throw CancellationException("Session changed")
        if (uploaded is ApiResult.Failure) return@withContext uploaded.writeOutcome()
        val result = transport.post("v6/message/send", mapOf("message" to "", "message_pic" to (uploaded as ApiResult.Success).value),
            query = mapOf("uid" to uid), multipart = true)
        if (!accounts.isCurrent(snapshot)) throw CancellationException("Session changed")
        return@withContext when (result) {
            is ApiResult.Failure -> result.writeOutcome()
            is ApiResult.Success -> confirmWrite(result.value, requireRecord = true, messageRecord = true)
        }
    }
    suspend fun sendFeed(uid: String, idOrUrl: String): WriteOutcome =
        feedShareUrl(idOrUrl)?.let { sendText(uid, it) } ?: badArgument()
    suspend fun conversationPinned(key: String, pinned: Boolean): WriteOutcome {
        if (key.isBlank() || key.length > 256) return badArgument()
        return write(messageMutation = true) { it.get("v6/message/${if (pinned) "addTop" else "removeTop"}", mapOf("ukey" to key), mutation = true) }
    }
    suspend fun delete(target: DeleteTarget, id: String, conversationKey: String? = null): WriteOutcome {
        if (target != DeleteTarget.CONVERSATION && !validId(id)) return badArgument()
        if (target in setOf(DeleteTarget.MESSAGE, DeleteTarget.CONVERSATION) && conversationKey.isNullOrBlank()) return badArgument()
        return write(messageMutation = target in setOf(DeleteTarget.MESSAGE, DeleteTarget.CONVERSATION)) { transport -> when (target) {
            DeleteTarget.MESSAGE -> transport.get("v6/message/delete", mapOf("id" to id, "ukey" to conversationKey!!), mutation = true)
            DeleteTarget.CONVERSATION -> transport.get("v6/message/deleteChat", mapOf("ukey" to conversationKey!!), mutation = true)
            else -> transport.post(when (target) { DeleteTarget.FEED -> "v6/feed/deleteFeed"; DeleteTarget.REPLY -> "v6/feed/deleteReply"; else -> "v6/notification/delete" }, emptyMap(), query = mapOf("id" to id))
        } }
    }
    private suspend fun write(requireRecord: Boolean = false, messageRecord: Boolean = false, messageMutation: Boolean = false,
        action: suspend (CoolapkHttpTransport) -> ApiResult<JsonElement>): WriteOutcome = try {
        authenticated { transport -> when (val response = action(transport)) {
            is ApiResult.Failure -> response.writeOutcome()
            is ApiResult.Success -> confirmWrite(response.value, requireRecord, messageRecord, messageMutation)
        } }
    } catch (_: LoginRequired) { WriteOutcome.Rejected(FailureKind.AUTHENTICATION, "请先登录后操作") }
    private class LoginRequired : IllegalStateException()
    private fun loginFailure() = ApiResult.Failure(FailureKind.AUTHENTICATION, "请先登录后查看")
    private fun invalid() = ApiResult.Failure(FailureKind.INVALID_RESPONSE, "响应格式异常")
    private fun badArgument() = WriteOutcome.Rejected(FailureKind.INVALID_ARGUMENT, "请检查操作内容")
}

internal fun confirmWrite(data: JsonElement, requireRecord: Boolean = false, messageRecord: Boolean = false, messageMutation: Boolean = false): WriteOutcome {
    if (requireRecord) {
        val rows = if (data is JsonArray) data.filterIsInstance<JsonObject>() else listOfNotNull(data as? JsonObject)
        val record = rows.firstOrNull { validId(it.text("id")) && (!messageRecord || it.text("entityType") == "message") }
        return record?.let { WriteOutcome.Confirmed(it.text("id")) } ?: WriteOutcome.Uncertain()
    }
    val confirmation = if (data is JsonObject) {
        if (messageMutation) data["count"] else data["count"] ?: data["id"] ?: data["likenum"] ?: data["status"]
    } else data
    return if (confirmation != null && confirmation != JsonNull && (confirmation as? JsonPrimitive)?.contentOrNull !in listOf(null, "", "false"))
        WriteOutcome.Confirmed() else WriteOutcome.Uncertain()
}
