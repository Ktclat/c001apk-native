// SPDX-License-Identifier: AGPL-3.0-only
// Endpoint and query contracts adapted from Ktclat/c001apk network_repo.dart.
package io.github.ktclat.c001apk.nativeapp.data

import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Separate API hosts; injectable loopback addresses are for MockWebServer tests only. */
data class ApiEndpoints(val api: HttpUrl = "https://api.coolapk.com/".toHttpUrl(), val api2: HttpUrl = "https://api2.coolapk.com/".toHttpUrl(),
    /** Override only for loopback contract tests; custom API fixtures never contact the public service. */
    val publicWeb: HttpUrl? = null)

/** Immutable request scope; implementations must verify cookie domain/path/expiry. */
data class RequestSession(
    val cookieFor: (HttpUrl) -> String?,
    val isCurrent: () -> Boolean,
    val acceptCookies: (HttpUrl, List<String>) -> Unit,
    val deviceCode: String? = null,
    val isSessionCurrent: (() -> Boolean)? = null,
    val acceptChallengeCookies: ((HttpUrl, List<String>) -> RequestSession?)? = null,
    /** Explicit anonymous identity marker; never infer identity from the presence of SESSID. */
    val isGuest: Boolean = false,
    /** Exact owner/generation/device/cookie revision, including anonymous read identities. */
    val isReadSessionCurrent: (() -> Boolean)? = null,
    /** Opaque local AccountStore identity for memory-only read-session ownership; never serialized. */
    val readOwner: Any? = null,
    val readSessionCookies: (() -> List<Cookie>)? = null,
    /** Accepted memory-only read validation; never supplied to social/login transports. */
    val readValidationCookie: (() -> String?)? = null,
    val diagnosticGeneration: Long? = null,
    val diagnosticMode: io.github.ktclat.c001apk.nativeapp.diagnostics.HomeRequestMode = io.github.ktclat.c001apk.nativeapp.diagnostics.HomeRequestMode.UNKNOWN,
)

class HttpCoolapkRepository(
    client: OkHttpClient = OkHttpClient(),
    private val endpoints: ApiEndpoints = ApiEndpoints(),
    private val protocol: ClientProtocol = ClientProtocol(),
    private val installTimeMillis: Long = System.currentTimeMillis(),
    private val requestSession: (() -> RequestSession)? = null,
    val homeDiagnostics: io.github.ktclat.c001apk.nativeapp.diagnostics.HomeRequestDiagnostics? = null,
) : CoolapkRepository {
    private val readSessions = ReadSessionMemory()
    private val transport = CoolapkHttpTransport(client, endpoints, protocol, requestSession, homeDiagnostics = homeDiagnostics)

    private val publicReader = (endpoints.publicWeb ?: "https://www.coolapk.com/".toHttpUrl().takeIf {
        endpoints.api.host == "api.coolapk.com" && endpoints.api2.host == "api2.coolapk.com"
    })?.let { PublicFeedReader(client, it, protocol) }

    /** Factory UID and root generation are immutable; a caller cannot substitute another list owner. */
    fun personalLists(expectedUid: String?, isCurrentAccount: () -> Boolean): PersonalListRepository = PersonalListRepository { request, uid, cursor ->
        if (expectedUid == null || uid != expectedUid) failure(FailureKind.AUTHENTICATION)
        else {
            val frozen = request.copy()
            accountRead(expectedUid, isCurrentAccount, attach = { failure, pending -> failure.copy(personalRead = pending) }) { operation ->
                withContext(Dispatchers.Default) {
                    HttpPersonalListRepository { path, params -> operation.get(path, params) }.load(frozen, expectedUid, cursor)
                }
            }
        }
    }

    /** Bind both the UID and root account generation before entering this read-only operation. */
    fun ownProfile(uid: String, isCurrentAccount: () -> Boolean): OwnProfileRepository = OwnProfileRepository {
        withContext(Dispatchers.IO) io@{
            if (!isCurrentAccount()) throw CancellationException("Account changed")
            if (!numeric(uid)) return@io failure(FailureKind.INVALID_ARGUMENT)
            val captured = requestSession?.invoke() ?: return@io failure(FailureKind.AUTHENTICATION)
            if (!isCurrentAccount() || !captured.isCurrent()) throw CancellationException("Session changed")
            val operation = transport.forRequestSession(captured)
            if (!isCurrentAccount()) throw CancellationException("Account changed")
            val result = when (val response = operation.get("v6/user/profile", mapOf("uid" to uid))) {
                is ApiResult.Failure -> response
                is ApiResult.Success -> withContext(Dispatchers.Default) {
                    try { ApiResult.Success(decodeOwnProfile(response.value, uid)) }
                    catch (e: ApiProblem) { e.failure }
                }
            }
            if (!isCurrentAccount() || !captured.isCurrent() || !operation.isRequestScopeCurrent())
                throw CancellationException("Session changed")
            result
        }
    }

    /** No query/body, no acknowledgement writes. Only an explicit retry may consume this read. */
    fun notificationCounts(uid: String, isCurrentAccount: () -> Boolean): NotificationCountsRepository = NotificationCountsRepository {
        accountRead(uid, isCurrentAccount, attach = { failure, pending -> failure.copy(countsRead = pending) }) { operation ->
            when (val response = operation.get("v6/notification/checkCount")) {
                is ApiResult.Failure -> response
                is ApiResult.Success -> withContext(Dispatchers.Default) {
                    try { ApiResult.Success(decodeNotificationCounts(response.value, uid)) }
                    catch (e: ApiProblem) { e.failure }
                }
            }
        }
    }

    /** Only admitted GET readers may use the shared memory-only account-read continuation. */
    private suspend fun <T> accountRead(uid: String, isCurrentAccount: () -> Boolean,
        attach: (ApiResult.Failure, PendingRead<T>) -> ApiResult.Failure,
        read: suspend (CoolapkHttpTransport) -> ApiResult<T>): ApiResult<T> =
        accountReadContinuation(uid, isCurrentAccount, requestSession, transport, readSessions, attach = attach, read = read)

    override suspend fun following(type: FollowType, cursor: PageCursor) = page(type.route, type.title, cursor)
    override suspend fun home(cursor: PageCursor) = list("v6/main/indexV8", cursor, mapOf("firstLaunch" to "1", "installTime" to "$installTimeMillis", "ids" to ""), api2 = true)
    override suspend fun feedDetail(id: String): ApiResult<ContentItem> = withContext(Dispatchers.IO) {
        if (!numeric(id)) return@withContext failure(FailureKind.INVALID_ARGUMENT)
        val readScope = requestSession?.invoke()?.let(readSessions::capture)
        val captured = readScope?.session
        if (captured?.isCurrent?.invoke() == false) throw CancellationException("Session changed")
        val operation = captured?.let(transport::forCapturedReadSession) ?: transport.forSingleOperation()
        val result = readDetail(id, operation)
        if (captured?.isCurrent?.invoke() == false) throw CancellationException("Session changed")
        if (result !is ApiResult.Failure) return@withContext result
        if (result.kind == FailureKind.CAPTCHA && readScope != null) readSessions.revokeOnChallenge(readScope)
        // No fallback on HTTP403, network, malformed data, risk-control or rate limiting.
        if (result.kind !in setOf(FailureKind.CAPTCHA, FailureKind.AUTHENTICATION)) return@withContext result
        val challenge = result.challenge?.takeIf { captured != null && operation.isCaptchaContinuationCurrent(it) }
        val pending = challenge?.let { spec -> PendingDetailRead(spec,
            isCurrent = { captured?.isCurrent?.invoke() != false && operation.isCaptchaContinuationCurrent(spec) },
            image = { operation.getCaptchaImage() }, verify = { operation.validateGraphicCaptcha(it) },
            retry = { proof ->
                val verified = when (spec) {
                    is ApiChallenge.Graphic -> operation.withGraphicVerification(spec)
                    is ApiChallenge.Netease -> operation.withCaptchaProof(spec, proof.orEmpty())
                }
                // Human-initiated one-shot read: no second fallback or verification loop.
                val verifiedResult = readDetail(id, verified)
                if (captured?.isCurrent?.invoke() == false || !operation.isReadVerificationResultCurrent(spec)) throw CancellationException("Session changed")
                if (verifiedResult is ApiResult.Success && verifiedResult.value.fullPostBody is FullPostBody.Unavailable)
                    failure(FailureKind.INVALID_RESPONSE).copy(message = "账号详情未提供可完整读取的正文，未替换已读取内容")
                else {
                    if (verifiedResult is ApiResult.Success && readScope != null &&
                        !readSessions.publish(readScope, operation.readVerificationCookies(spec),
                            proof?.takeIf { spec is ApiChallenge.Netease && spec.field.isEmpty() })) throw CancellationException("Read session changed")
                    verifiedResult
                }
            }, onClosed = { operation.closeReadVerification(spec) }) }
        val reader = publicReader ?: return@withContext result.copy(detailRead = pending)
        val public = reader.read(id, captured)
        if (captured?.isCurrent?.invoke() == false) { pending?.cancel(); throw CancellationException("Session changed") }
        if (public is ApiResult.Success) {
            val decoded = decodeDetail(id, public.value, publicRead = true)
            if (captured?.isCurrent?.invoke() == false) { pending?.cancel(); throw CancellationException("Session changed") }
            if (decoded is ApiResult.Success && decoded.value.fullPostBody !is FullPostBody.Unavailable) {
                return@withContext ApiResult.Success(decoded.value.copy(accountDetailRetry = pending))
            }
        }
        result.copy(message = when (result.kind) {
            FailureKind.CAPTCHA -> if (pending != null) "当前设备需要验证，公开分享页未提供可读取正文；请亲自验证后重试" else "当前设备需要验证，公开分享页未提供可读取正文；可重新加载或登录后重试"
            else -> "此内容需要登录，公开分享页未提供可读取正文；请登录后重试"
        }, detailRead = pending)
    }
    private suspend fun readDetail(id: String, operation: CoolapkHttpTransport): ApiResult<ContentItem> =
        when (val response = operation.get("v6/feed/detail", mapOf("id" to id))) {
            is ApiResult.Failure -> response
            is ApiResult.Success -> decodeDetail(id, response.value)
        }
    private suspend fun decodeDetail(id: String, value: JsonElement, publicRead: Boolean = false): ApiResult<ContentItem> = withContext(Dispatchers.Default) {
        val context = currentCoroutineContext()
        try {
            val obj = value as? JsonObject ?: ApiDecoder.invalid()
            // Never attach another feed's body to this route. Public summaries are not details.
            val responseId = (obj["id"] as? JsonPrimitive)?.contentOrNull
            if (responseId == null || responseId.isEmpty() || responseId.any { it !in '0'..'9' } ||
                responseId.trimStart('0').ifEmpty { "0" } != id.trimStart('0').ifEmpty { "0" }) ApiDecoder.invalid()
            val sanitized = if (publicRead) JsonObject(obj - "userAction") else obj
            val item = ApiDecoder.feedDetail(sanitized) { context.ensureActive() }
            if (item.kind != "feed") ApiDecoder.invalid()
            ApiResult.Success((if (publicRead) item.withoutAccountActions() else item).copy(publicRead = publicRead))
        } catch (e: ApiProblem) { e.failure }
    }
    override suspend fun replies(id: String, cursor: PageCursor) = orderedReplies(id, ReplyOrder.RECENT_REPLY, false, cursor)
    override suspend fun orderedReplies(id: String, order: ReplyOrder, authorOnly: Boolean, cursor: PageCursor): ApiResult<Page<ContentItem>> {
        if (!numeric(id)) return failure(FailureKind.INVALID_ARGUMENT)
        return list("v6/feed/replyList", cursor, mapOf("id" to id, "listType" to order.wireValue, "discussMode" to "1", "feedType" to "feed", "blockStatus" to "0", "fromFeedAuthor" to if (authorOnly) "1" else "0"), api2 = true)
    }
    override suspend fun replyThread(replyId: String, cursor: PageCursor): ApiResult<Page<ContentItem>> {
        if (!numeric(replyId)) return failure(FailureKind.INVALID_ARGUMENT)
        return list("v6/feed/replyList", cursor, mapOf("id" to replyId, "listType" to "", "discussMode" to "0", "feedType" to "feed_reply", "blockStatus" to "0", "fromFeedAuthor" to "0"))
    }
    override suspend fun search(query: String, cursor: PageCursor, type: String) = searchAdvanced(query, SearchOptions(type = type), cursor)
    override suspend fun searchAdvanced(query: String, options: SearchOptions, cursor: PageCursor): ApiResult<Page<ContentItem>> {
        if (query.isBlank() || options.type !in setOf("feed", "apk", "user", "topic", "product")) return failure(FailureKind.INVALID_ARGUMENT)
        val params = mutableMapOf("type" to options.type, "feedType" to options.feedType, "sort" to options.sort, "searchValue" to query, "isStrict" to if (options.strict) "1" else "0", "showAnonymous" to "-1")
        options.pageType?.takeIf { it.isNotBlank() }?.let { params["pageType"] = it }
        options.pageParam?.takeIf { it.isNotBlank() }?.let { params["pageParam"] = it }
        return list("v6/search", cursor, params)
    }
    override suspend fun pictures(tag: String, type: String, cursor: PageCursor): ApiResult<Page<ContentItem>> {
        if (tag.isBlank() || tag.length > 4096 || tag.any { it.isISOControl() } || type !in setOf("recommend", "hot", "newest"))
            return failure(FailureKind.INVALID_ARGUMENT)
        return list("v6/picture/list", cursor, mapOf("tag" to tag, "type" to type))
    }
    override suspend fun eventDetail(id: String): ApiResult<ContentItem> = withContext(Dispatchers.IO) {
        if (!numeric(id)) return@withContext failure(FailureKind.INVALID_ARGUMENT)
        val readScope = requestSession?.invoke()?.let(readSessions::capture)
        val captured = readScope?.session
        if (captured?.isCurrent?.invoke() == false) throw CancellationException("Session changed")
        val operation = captured?.let(transport::forCapturedReadSession) ?: transport
        val result = when (val response = operation.get("v6/event/detail", mapOf("id" to id))) {
            is ApiResult.Failure -> response
            is ApiResult.Success -> withContext(Dispatchers.Default) {
                try { ApiResult.Success(decodeEventDetail(response.value, id)) } catch (problem: ApiProblem) { problem.failure }
            }
        }
        if (captured?.isCurrent?.invoke() == false) throw CancellationException("Session changed")
        if (result is ApiResult.Failure && result.kind == FailureKind.CAPTCHA && readScope != null)
            readSessions.revokeOnChallenge(readScope)
        result
    }
    override suspend fun dyhArticles(id: String, type: String, cursor: PageCursor): ApiResult<Page<ContentItem>> {
        if (!numeric(id) || type !in setOf("all", "square")) return failure(FailureKind.INVALID_ARGUMENT)
        return list("v6/dyhArticle/list", cursor, mapOf("dyhId" to id, "type" to type))
    }
    override suspend fun userConnections(uid: String, kind: UserConnectionKind, cursor: PageCursor): ApiResult<Page<ContentItem>> {
        if (!(uid.length in 1..32 && numeric(uid) && uid.any { it != '0' })) return failure(FailureKind.INVALID_ARGUMENT)
        return list(kind.path, cursor, mapOf("uid" to uid), allowRepeatedCursor = true)
    }
    override suspend fun userProfile(uid: String): ApiResult<ContentItem> {
        if (!(uid.length in 1..32 && numeric(uid) && uid.any { it != '0' })) return failure(FailureKind.INVALID_ARGUMENT)
        return request("v6/user/space", mapOf("uid" to uid)) { decodeUserSpace(it, uid) }
    }
    override suspend fun userFeed(uid: String, cursor: PageCursor): ApiResult<Page<ContentItem>> {
        if (!numeric(uid)) return failure(FailureKind.INVALID_ARGUMENT)
        return list("v6/user/feedList", cursor, mapOf("uid" to uid, "showAnonymous" to "0", "isIncludeTop" to "1", "showDoing" to "0"))
    }
    override suspend fun chronologicalUserFeed(uid: String, cursor: PageCursor): ApiResult<Page<ContentItem>> {
        if (!numeric(uid)) return failure(FailureKind.INVALID_ARGUMENT)
        return list("v6/user/feedList", cursor, mapOf("uid" to uid, "showAnonymous" to "0", "isIncludeTop" to "0", "showDoing" to "0"))
    }
    override suspend fun productDetail(id: String) = objectRequest("v6/product/detail", "id", id, numericOnly = true)
    override suspend fun collectionItems(id: String, cursor: PageCursor): ApiResult<Page<ContentItem>> {
        if (!validCollectionId(id)) return failure(FailureKind.INVALID_ARGUMENT)
        return list("v6/collection/itemList", cursor, mapOf("id" to id))
    }
    override suspend fun productCategories() = list("v6/product/categoryList", PageCursor(), emptyMap())
    override suspend fun topicDetail(tag: String) = objectRequest("v6/topic/newTagDetail", "tag", tag)
    override suspend fun appDetail(id: String) = objectRequest("v6/apk/detail", "id", id, extra = mapOf("installed" to "1"))
    override suspend fun page(url: String, title: String, cursor: PageCursor): ApiResult<Page<ContentItem>> {
        // Server-defined route is a query value only; never resolved as a network destination.
        if (url.isBlank() || url.length > 4096) return failure(FailureKind.INVALID_ARGUMENT)
        return list("v6/page/dataList", cursor, mapOf("url" to url, "title" to title, "subTitle" to ""))
    }
    private suspend fun objectRequest(path: String, key: String, value: String, numericOnly: Boolean = false, extra: Map<String, String> = emptyMap()): ApiResult<ContentItem> {
        if (value.isBlank() || (numericOnly && !numeric(value))) return failure(FailureKind.INVALID_ARGUMENT)
        return request(path, extra + (key to value)) { ApiDecoder.item(it) }
    }
    private suspend fun list(path: String, cursor: PageCursor, params: Map<String, String>, api2: Boolean = false, allowRepeatedCursor: Boolean = false): ApiResult<Page<ContentItem>> {
        val query = params.toMutableMap().apply {
            put("page", cursor.page.toString())
            cursor.firstItem?.takeIf { it.isNotBlank() }?.let { put("firstItem", it) }
            cursor.lastItem?.takeIf { it.isNotBlank() }?.let { put("lastItem", it) }
        }
        val readScope = requestSession?.invoke()?.let(readSessions::capture)
        val captured = readScope?.session
        val operation = captured?.let(transport::forCapturedReadSession) ?: transport.forSingleOperation()
        suspend fun read(using: CoolapkHttpTransport): ApiResult<Page<ContentItem>> = when (val response = using.get(path, query, api2)) {
            is ApiResult.Failure -> response
            is ApiResult.Success -> withContext(Dispatchers.Default) {
                try {
                    val array = response.value as? JsonArray ?: ApiDecoder.invalid()
                    val items = ApiDecoder.items(array)
                    // Known listCard wrappers group source entities; all other templates keep top-level cursors.
                    val boundary = listCardCursorBoundary(items)
                    val last = boundary.lastId
                    // Flutter relationship lists advance by page even when raw relationship IDs repeat.
                    // Other readers retain their existing repeated-boundary termination policy.
                    val next = if (!boundary.complete || last == null ||
                        (!allowRepeatedCursor && last == cursor.lastItem) ||
                        (allowRepeatedCursor && cursor.page == Int.MAX_VALUE)) null
                    else PageCursor(cursor.page + 1, cursor.firstItem ?: boundary.firstId, last)
                    ApiResult.Success(Page(items, next))
                } catch (e: ApiProblem) { e.failure }
            }
        }
        val result = read(operation)
        if (captured?.isCurrent?.invoke() == false) throw CancellationException("Session changed")
        if (result !is ApiResult.Failure) return result
        if (result.kind == FailureKind.CAPTCHA && readScope != null) readSessions.revokeOnChallenge(readScope)
        val challenge = result.challenge?.takeIf { captured != null && operation.isCaptchaContinuationCurrent(it) } ?: return result
        val pending = PendingPageRead(challenge,
            isCurrent = { captured?.isCurrent?.invoke() != false && operation.isCaptchaContinuationCurrent(challenge) },
            image = { operation.getCaptchaImage() }, verify = { operation.validateGraphicCaptcha(it) },
            retry = { proof ->
                val verified = when (challenge) {
                    is ApiChallenge.Graphic -> operation.withGraphicVerification(challenge)
                    is ApiChallenge.Netease -> operation.withCaptchaProof(challenge, proof.orEmpty())
                }
                val retried = read(verified) // Exact endpoint/query/cursor; no automatic second verification.
                if (captured?.isCurrent?.invoke() == false || !operation.isReadVerificationResultCurrent(challenge)) throw CancellationException("Session changed")
                if (retried is ApiResult.Success && readScope != null &&
                    !readSessions.publish(readScope, operation.readVerificationCookies(challenge),
                        proof?.takeIf { challenge is ApiChallenge.Netease && challenge.field.isEmpty() })) throw CancellationException("Read session changed")
                retried
            }, onClosed = { operation.closeReadVerification(challenge) })
        return result.copy(pageRead = pending)
    }
    private suspend fun <T> request(path: String, params: Map<String, String>, api2: Boolean = false, decode: (JsonElement) -> T): ApiResult<T> {
        val readScope = requestSession?.invoke()?.let(readSessions::capture)
        val captured = readScope?.session
        val operation = captured?.let(transport::forCapturedReadSession) ?: transport
        val result = when (val response = operation.get(path, params, api2)) {
            is ApiResult.Failure -> response
            is ApiResult.Success -> withContext(Dispatchers.Default) {
                try { ApiResult.Success(decode(response.value)) } catch (e: ApiProblem) { e.failure }
            }
        }
        // Account switches during CPU mapping must not publish the previous account's content.
        if (captured?.isCurrent?.invoke() == false) throw CancellationException("Session changed")
        if (result is ApiResult.Failure && result.kind == FailureKind.CAPTCHA && readScope != null)
            readSessions.revokeOnChallenge(readScope)
        return result
    }
    private fun numeric(value: String) = value.isNotEmpty() && value.all { it in '0'..'9' }
}

/** Public representations cannot establish this account's state, including nested pinned replies. */
private fun ContentItem.withoutAccountActions(): ContentItem = copy(
    feed = feed?.copy(likeState = ExplicitLikeState.UNKNOWN),
    replyInteraction = replyInteraction?.copy(likeState = ExplicitLikeState.UNKNOWN),
    replies = replies.map { it.withoutAccountActions() },
    children = children.map { it.withoutAccountActions() },
    highlightedReplies = highlightedReplies.map { it.withoutAccountActions() },
)
