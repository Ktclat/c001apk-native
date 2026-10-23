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
class SocialRepository internal constructor(private val accounts: AccountStore,
    private val transportFor: (SessionSnapshot) -> CoolapkHttpTransport,
    private val defaultImageUploader: OssImageUploader) {
    constructor(accounts: AccountStore, transportFor: (SessionSnapshot) -> CoolapkHttpTransport) :
        this(accounts, transportFor, OssImageUploader())
    private val notificationOwner = Any()
    /** UI intent guard; write entry still verifies the captured generation again on IO. */
    internal fun isCurrentGeneration(generation: Long) = accounts.state.value.generation == generation
    private suspend fun <T> authenticated(block: suspend (CoolapkHttpTransport) -> T): T =
        authenticatedScoped { transport, _ -> block(transport) }
    private suspend fun <T> authenticatedScoped(block: suspend (CoolapkHttpTransport, SessionSnapshot) -> T): T = withContext(Dispatchers.IO) {
        val snapshot = accounts.snapshot()
        if (snapshot.accountId == null) throw LoginRequired()
        val result = block(transportFor(snapshot), snapshot)
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
        return try { authenticatedScoped { transport, snapshot -> when (val result = transport.get(path, query)) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> {
                val array = result.value as? JsonArray ?: return@authenticatedScoped invalid()
                if (array.size > 500 || array.any { it !is JsonObject }) return@authenticatedScoped invalid()
                val ended = array.filterIsInstance<JsonObject>().any { it.text("entityType") == "noMoreDataCard" }
                val rows = array.filterIsInstance<JsonObject>().filter { it.text("entityType") != "noMoreDataCard" }.map { obj -> SocialRow(
                    obj.text("id").ifBlank { if (path == "v6/message/list") obj.text("ukey") else "" }, obj.text("message"),
                    obj.text("messageUsername").ifBlank { obj.text("username") }.ifBlank { obj.text("fromusername") }, obj.text("messageUid").ifBlank { obj.text("uid") }.ifBlank { obj.text("fromuid") },
                    obj.text("ukey").takeIf { it.isNotBlank() }, obj.text("is_top") == "1",
                    safeMessageImage(obj.text("message_pic")), obj.text("message_pic").isNotBlank(), obj.text("entityType"),
                    if (NoticeCategory.entries.any { it != NoticeCategory.MESSAGES && it.path == path } && obj.text("entityType") == "notification" && validId(obj.text("id")))
                        NotificationDeleteScope(notificationOwner, obj.text("id"), snapshot.generation) else null) }
                if (rows.any { it.id.isBlank() }) return@authenticatedScoped invalid()
                val last = rows.lastOrNull()?.id?.takeIf { it.isNotBlank() }
                ApiResult.Success(Page(rows, if (ended || last == null || last == cursor.lastItem) null else
                    PageCursor(cursor.page + 1, cursor.firstItem ?: rows.first().id, last)))
            }
        } } } catch (_: LoginRequired) { loginFailure() }
    }
    /** targetLiked is the desired final state, never the currently displayed state. */
    suspend fun like(id: String, reply: Boolean, targetLiked: Boolean, expectedGeneration: Long? = null): WriteOutcome {
        if (!validId(id)) return badArgument()
        val action = if (reply) { if (targetLiked) "likeReply" else "unLikeReply" } else if (targetLiked) "like" else "unlike"
        return write(expectedGeneration = expectedGeneration) { it.post("v6/feed/$action", mapOf("trace" to ""), query = mapOf("id" to id, "detail" to "0")) }
    }
    suspend fun follow(target: FollowTarget, id: String, followed: Boolean, expectedGeneration: Long? = null): WriteOutcome {
        if (id.isBlank() || (target != FollowTarget.TOPIC && !validId(id))) return badArgument()
        return write(expectedGeneration = expectedGeneration) { transport -> when (target) {
            FollowTarget.USER -> transport.post("v6/user/${if (followed) "follow" else "unfollow"}", emptyMap(), query = mapOf("uid" to id))
            FollowTarget.APP -> transport.get("v6/apk/${if (followed) "follow" else "unFollow"}", mapOf("id" to id), mutation = true)
            FollowTarget.TOPIC -> transport.get("v6/feed/${if (followed) "followTag" else "unFollowTag"}", mapOf("tag" to id), mutation = true)
            FollowTarget.PRODUCT -> transport.post("v6/product/changeFollowStatus", mapOf("id" to id, "status" to if (followed) "1" else "0"), multipart = true)
        } }
    }
    suspend fun publish(draft: ComposerDraft, images: List<SelectedImage> = emptyList(),
        uploader: OssImageUploader = defaultImageUploader, expectedGeneration: Long? = null): WriteOutcome = withContext(Dispatchers.IO) {
        val frozenImages = images.toList()
        if (!withinComposerTextLimit(draft.text) || draft.text.isBlank() || (draft.replyId != null && !validId(draft.replyId))) return@withContext badArgument()
        if (draft.targetType != null && draft.targetType !in setOf("apk", "product", "topic")) return@withContext badArgument()
        if (draft.targetType != null && draft.targetId.isNullOrBlank()) return@withContext badArgument()
        if (frozenImages.isEmpty()) return@withContext write(requireRecord = true, expectedGeneration = expectedGeneration, action = publicationAction(draft))
        if (!validImageBatch(frozenImages)) return@withContext WriteOutcome.Rejected(FailureKind.INVALID_ARGUMENT, "最多9张图片，单张20MB，总计60MB")
        val snapshot = accounts.snapshot()
        if (snapshot.accountId == null) return@withContext WriteOutcome.Rejected(FailureKind.AUTHENTICATION, "请先登录后操作")
        if (expectedGeneration != null && snapshot.generation != expectedGeneration)
            return@withContext WriteOutcome.Rejected(FailureKind.AUTHENTICATION, "账号状态已变化，图片尚未上传或发布")
        if (!verifyImageBatch(frozenImages)) return@withContext WriteOutcome.Rejected(FailureKind.INVALID_ARGUMENT, "附件已失效或内容已变化，尚未上传或发布；请移除后重新选择")
        val transport = transportFor(snapshot).forSingleOperation()
        val form = feedPrepareForm(frozenImages)
        when (val prepared = transport.post("v6/upload/ossUploadPrepare", form, multipart = true)) {
            is ApiResult.Success -> uploadFeedAndPublish(snapshot, transport, draft, frozenImages, uploader, prepared.value, true)
            is ApiResult.Failure -> challengeOutcome(snapshot, transport, prepared) retry@{ verifiedTransport ->
                if (!verifyImageBatch(frozenImages)) return@retry WriteOutcome.Rejected(FailureKind.INVALID_ARGUMENT, "附件已失效；尚未上传或发布，请重新选择")
                when (val retried = verifiedTransport.post("v6/upload/ossUploadPrepare", form, multipart = true)) {
                    is ApiResult.Failure -> retried.writeOutcome()
                    is ApiResult.Success -> uploadFeedAndPublish(snapshot, transport, draft, frozenImages, uploader, retried.value, false)
                }
            }
        }
    }
    private fun publicationAction(draft: ComposerDraft, pictures: List<String> = emptyList(), images: List<SelectedImage> = emptyList()): suspend (CoolapkHttpTransport) -> ApiResult<JsonElement> {
        // Freeze privacy and ordered attachment URLs together, before any challenge can be displayed.
        val form = buildMap {
            put("message", draft.text)
            if (pictures.isNotEmpty()) put("pic", pictures.joinToString(","))
            if (draft.replyId == null) {
                put("type", if (draft.targetType == "apk") "comment" else "feed")
                put("status", "1"); put("publish_status", if (draft.selfOnly) "1" else "0")
                draft.targetType?.let { put("targetType", it) }; draft.targetId?.let { put("targetId", it) }
            } else put("replyAndForward", if (draft.replyAndForward) "1" else "0")
        }
        return action@{ transport ->
            if (images.isNotEmpty() && !verifyImageBatch(images)) return@action ApiResult.Failure(FailureKind.INVALID_ARGUMENT, "附件已失效或内容已变化；未发布，不会降级为无图发送")
            if (draft.replyId == null) transport.post("v6/feed/createFeed", form)
            else transport.post("v6/feed/reply", form, query = mapOf("id" to draft.replyId, "type" to if (draft.replyToReply) "reply" else "feed")) }
    }
    private suspend fun uploadFeedAndPublish(snapshot: SessionSnapshot, transport: CoolapkHttpTransport, draft: ComposerDraft,
        images: List<SelectedImage>, uploader: OssImageUploader, data: JsonElement, allowChallenge: Boolean): WriteOutcome {
        fun current() = accounts.isCurrent(snapshot) && transport.isRequestScopeCurrent()
        if (!current()) throw CancellationException("Session changed")
        val grants = parseFeedImageGrants(data, images, System.currentTimeMillis())
            ?: return WriteOutcome.Rejected(FailureKind.INVALID_RESPONSE, "动态图片上传授权不符合安全限制；尚未发布")
        for ((index, grant) in grants.withIndex()) {
            if (!current()) throw CancellationException("Session changed")
            when (val uploaded = uploader.upload(grant.upload, images[index], ::current)) {
                is ApiResult.Success -> Unit
                is ApiResult.Failure -> return if (uploaded.kind == FailureKind.UNCERTAIN) WriteOutcome.Uncertain("第${index + 1}张图片上传结果未确认；动态尚未发布，不会自动重传")
                    else WriteOutcome.Rejected(uploaded.kind, "第${index + 1}张图片上传失败；动态尚未发布")
            }
        }
        return scopedWrite(snapshot, transport, publicationAction(draft, grants.map { it.publicationUrl }, images),
            requireRecord = true, allowChallenge = allowChallenge)
    }
    suspend fun sendText(uid: String, text: String, expectedGeneration: Long? = null): WriteOutcome {
        if (!validId(uid) || text.isBlank()) return badArgument()
        return write(requireRecord = true, messageRecord = true, expectedGeneration = expectedGeneration) {
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
    suspend fun sendImage(uid: String, image: SelectedImage, uploader: OssImageUploader = OssImageUploader(), expectedGeneration: Long? = null): WriteOutcome = withContext(Dispatchers.IO) {
        if (!validId(uid) || image.sizeBytes !in 1..MAX_IMAGE_BYTES) return@withContext badArgument()
        val snapshot = accounts.snapshot()
        if (snapshot.accountId == null) return@withContext WriteOutcome.Rejected(FailureKind.AUTHENTICATION, "请先登录")
        if (expectedGeneration != null && snapshot.generation != expectedGeneration)
            return@withContext WriteOutcome.Rejected(FailureKind.AUTHENTICATION, "账号状态已变化，图片尚未上传或发送")
        val transport = transportFor(snapshot).forSingleOperation()
        val fileList = buildJsonArray { add(buildJsonObject {
            put("name", image.name); put("resolution", "${image.width}x${image.height}"); put("md5", image.md5Hex)
        }) }.toString()
        val form = mapOf("uploadBucket" to "message", "uploadDir" to "message", "is_anonymous" to "0", "uploadFileList" to fileList, "toUid" to uid)
        val prepared = transport.post("v6/upload/ossUploadPrepare", form, multipart = true)
        if (!accounts.isCurrent(snapshot)) throw CancellationException("Session changed")
        when (prepared) {
            is ApiResult.Success -> uploadPreparedAndSend(snapshot, transport, uid, image, uploader, prepared.value, allowChallenge = true)
            is ApiResult.Failure -> challengeOutcome(snapshot, transport, prepared) { verifiedTransport ->
                when (val retried = verifiedTransport.post("v6/upload/ossUploadPrepare", form, multipart = true)) {
                    is ApiResult.Failure -> retried.writeOutcome()
                    is ApiResult.Success -> uploadPreparedAndSend(snapshot, transport, uid, image, uploader, retried.value, allowChallenge = false)
                }
            }
        }
    }
    private suspend fun uploadPreparedAndSend(snapshot: SessionSnapshot, transport: CoolapkHttpTransport, uid: String,
        image: SelectedImage, uploader: OssImageUploader, data: JsonElement, allowChallenge: Boolean): WriteOutcome {
        if (!accounts.isCurrent(snapshot) || !transport.isRequestScopeCurrent()) throw CancellationException("Session changed")
        val grant = parseOssGrant(data, image, System.currentTimeMillis())
            ?: return WriteOutcome.Rejected(FailureKind.INVALID_RESPONSE, "图片上传授权不符合安全限制；消息尚未发送")
        val uploaded = uploader.upload(grant, image) { accounts.isCurrent(snapshot) && transport.isRequestScopeCurrent() }
        if (!accounts.isCurrent(snapshot)) throw CancellationException("Session changed")
        if (uploaded is ApiResult.Failure) return uploaded.writeOutcome()
        // A send challenge retries only this exact message reference, never the already-completed upload.
        val messageForm = mapOf("message" to "", "message_pic" to (uploaded as ApiResult.Success).value)
        return scopedWrite(snapshot, transport, { it.post("v6/message/send", messageForm, query = mapOf("uid" to uid), multipart = true) },
            requireRecord = true, messageRecord = true, allowChallenge = allowChallenge)
    }
    suspend fun sendFeed(uid: String, idOrUrl: String, expectedGeneration: Long? = null): WriteOutcome =
        feedShareUrl(idOrUrl)?.let { sendText(uid, it, expectedGeneration = expectedGeneration) } ?: badArgument()
    suspend fun conversationPinned(key: String, pinned: Boolean, expectedGeneration: Long? = null): WriteOutcome {
        if (key.isBlank() || key.length > 256) return badArgument()
        return write(messageMutation = true, expectedGeneration = expectedGeneration) { it.get("v6/message/${if (pinned) "addTop" else "removeTop"}", mapOf("ukey" to key), mutation = true) }
    }
    suspend fun deleteNotification(row: SocialRow): WriteOutcome {
        val scope = row.notificationScope ?: return badArgument()
        if (!scope.belongsTo(notificationOwner, row)) return badArgument()
        return write(expectedGeneration = scope.generation) {
            it.post("v6/notification/delete", emptyMap(), query = mapOf("id" to row.id))
        }
    }
    suspend fun delete(target: DeleteTarget, id: String, conversationKey: String? = null, expectedGeneration: Long? = null): WriteOutcome {
        // Notification deletion must carry exact response provenance and account generation.
        if (target == DeleteTarget.NOTIFICATION) return badArgument()
        if (target != DeleteTarget.CONVERSATION && !validId(id)) return badArgument()
        if (target in setOf(DeleteTarget.MESSAGE, DeleteTarget.CONVERSATION) && conversationKey.isNullOrBlank()) return badArgument()
        return write(messageMutation = target in setOf(DeleteTarget.MESSAGE, DeleteTarget.CONVERSATION), expectedGeneration = expectedGeneration) { transport -> when (target) {
            DeleteTarget.MESSAGE -> transport.get("v6/message/delete", mapOf("id" to id, "ukey" to conversationKey!!), mutation = true)
            DeleteTarget.CONVERSATION -> transport.get("v6/message/deleteChat", mapOf("ukey" to conversationKey!!), mutation = true)
            DeleteTarget.FEED -> transport.post("v6/feed/deleteFeed", emptyMap(), query = mapOf("id" to id))
            DeleteTarget.REPLY -> transport.post("v6/feed/deleteReply", emptyMap(), query = mapOf("id" to id))
            DeleteTarget.NOTIFICATION -> invalid()
        } }
    }
    private suspend fun write(requireRecord: Boolean = false, messageRecord: Boolean = false, messageMutation: Boolean = false,
        expectedGeneration: Long? = null,
        action: suspend (CoolapkHttpTransport) -> ApiResult<JsonElement>): WriteOutcome = withContext(Dispatchers.IO) {
        val snapshot = accounts.snapshot()
        if (snapshot.accountId == null) return@withContext WriteOutcome.Rejected(FailureKind.AUTHENTICATION, "请先登录后操作")
        if (expectedGeneration != null && snapshot.generation != expectedGeneration)
            return@withContext WriteOutcome.Rejected(FailureKind.AUTHENTICATION, "账号状态已变化，请刷新后重新操作")
        val transport = transportFor(snapshot).forSingleOperation()
        scopedWrite(snapshot, transport, action, requireRecord, messageRecord, messageMutation)
    }
    private suspend fun scopedWrite(snapshot: SessionSnapshot, transport: CoolapkHttpTransport,
        action: suspend (CoolapkHttpTransport) -> ApiResult<JsonElement>, requireRecord: Boolean = false,
        messageRecord: Boolean = false, messageMutation: Boolean = false, allowChallenge: Boolean = true): WriteOutcome {
        if (!accounts.isCurrent(snapshot)) throw CancellationException("Session changed")
        val response = action(transport)
        if (!accounts.isCurrent(snapshot)) throw CancellationException("Session changed")
        if (response is ApiResult.Success) return confirmWrite(response.value, requireRecord, messageRecord, messageMutation)
        val failure = response as ApiResult.Failure
        if (!allowChallenge) return failure.writeOutcome()
        return challengeOutcome(snapshot, transport, failure) { verifiedTransport ->
            scopedWrite(snapshot, verifiedTransport, action, requireRecord, messageRecord, messageMutation, allowChallenge = false)
        }
    }
    private fun challengeOutcome(snapshot: SessionSnapshot, transport: CoolapkHttpTransport, failure: ApiResult.Failure,
        verifiedAction: suspend (CoolapkHttpTransport) -> WriteOutcome): WriteOutcome {
        val challenge = failure.challenge
        if (failure.kind != FailureKind.CAPTCHA || challenge == null) return failure.writeOutcome()
        val prompt = when (challenge) {
            is ApiChallenge.Graphic -> CaptchaPrompt.Graphic
            is ApiChallenge.Netease -> CaptchaPrompt.Netease(io.github.ktclat.c001apk.nativeapp.account.HumanChallengeSpec(challenge.captchaId, challenge.field))
        }
        val pending = PendingCaptcha(prompt,
            isCurrent = { accounts.isCurrent(snapshot) && transport.isCaptchaContinuationCurrent(challenge) },
            loadGraphic = { transport.getCaptchaImage() },
            validateGraphic = { code -> transport.validateGraphicCaptcha(code) },
            replay = retry@{ proof ->
                if (!accounts.isCurrent(snapshot) || !transport.isCaptchaContinuationCurrent(challenge))
                    WriteOutcome.Rejected(FailureKind.AUTHENTICATION, "账号或会话已变化，原操作未重发")
                else {
                    val verifiedTransport = try { when (challenge) {
                        is ApiChallenge.Graphic -> transport.withGraphicVerification(challenge)
                        is ApiChallenge.Netease -> transport.withCaptchaProof(challenge, proof.orEmpty())
                    } } catch (_: IllegalArgumentException) { return@retry WriteOutcome.Rejected(FailureKind.CAPTCHA, "验证已失效，原操作未重发") }
                    verifiedAction(verifiedTransport)
                }
            })
        return WriteOutcome.Rejected(failure.kind, failure.message, pending)
    }
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
