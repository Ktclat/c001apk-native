// SPDX-License-Identifier: AGPL-3.0-only
// Endpoint and query contracts adapted from Ktclat/c001apk network_repo.dart.
package io.github.ktclat.c001apk.nativeapp.data

import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Separate API hosts; injectable loopback addresses are for MockWebServer tests only. */
data class ApiEndpoints(val api: HttpUrl = "https://api.coolapk.com/".toHttpUrl(), val api2: HttpUrl = "https://api2.coolapk.com/".toHttpUrl())

/** Immutable request scope; implementations must verify cookie domain/path/expiry. */
data class RequestSession(
    val cookieFor: (HttpUrl) -> String?,
    val isCurrent: () -> Boolean,
    val acceptCookies: (HttpUrl, List<String>) -> Unit,
    val deviceCode: String? = null,
)

class HttpCoolapkRepository(
    client: OkHttpClient = OkHttpClient(),
    private val endpoints: ApiEndpoints = ApiEndpoints(),
    private val protocol: ClientProtocol = ClientProtocol(),
    private val installTimeSeconds: Long = System.currentTimeMillis() / 1000,
    private val requestSession: (() -> RequestSession)? = null,
) : CoolapkRepository {
    private val transport = CoolapkHttpTransport(client, endpoints, protocol, requestSession)

    override suspend fun following(type: FollowType, cursor: PageCursor) = page(type.route, type.title, cursor)
    override suspend fun home(cursor: PageCursor) = list("v6/main/indexV8", cursor, mapOf("firstLaunch" to "1", "installTime" to "$installTimeSeconds", "ids" to ""), api2 = true)
    override suspend fun feedDetail(id: String) = objectRequest("v6/feed/detail", "id", id, numericOnly = true)
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
    override suspend fun pictures(tag: String, type: String, cursor: PageCursor) = list("v6/picture/list", cursor, mapOf("tag" to tag, "type" to type))
    override suspend fun dyhArticles(id: String, type: String, cursor: PageCursor): ApiResult<Page<ContentItem>> {
        if (!numeric(id)) return failure(FailureKind.INVALID_ARGUMENT)
        return list("v6/dyhArticle/list", cursor, mapOf("dyhId" to id, "type" to type))
    }
    override suspend fun userProfile(uid: String) = objectRequest("v6/user/profile", "uid", uid, numericOnly = true)
    override suspend fun userFeed(uid: String, cursor: PageCursor): ApiResult<Page<ContentItem>> {
        if (!numeric(uid)) return failure(FailureKind.INVALID_ARGUMENT)
        return list("v6/user/feedList", cursor, mapOf("uid" to uid, "showAnonymous" to "0", "isIncludeTop" to "1", "showDoing" to "0"))
    }
    override suspend fun chronologicalUserFeed(uid: String, cursor: PageCursor): ApiResult<Page<ContentItem>> {
        if (!numeric(uid)) return failure(FailureKind.INVALID_ARGUMENT)
        return list("v6/user/feedList", cursor, mapOf("uid" to uid, "showAnonymous" to "0", "isIncludeTop" to "0", "showDoing" to "0"))
    }
    override suspend fun productDetail(id: String) = objectRequest("v6/product/detail", "id", id, numericOnly = true)
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
    private suspend fun list(path: String, cursor: PageCursor, params: Map<String, String>, api2: Boolean = false): ApiResult<Page<ContentItem>> {
        val query = params.toMutableMap().apply {
            put("page", cursor.page.toString())
            cursor.firstItem?.takeIf { it.isNotBlank() }?.let { put("firstItem", it) }
            cursor.lastItem?.takeIf { it.isNotBlank() }?.let { put("lastItem", it) }
        }
        return request(path, query, api2) { data ->
            val array = data as? JsonArray ?: ApiDecoder.invalid()
            val items = ApiDecoder.items(array)
            // Preserve card rows; use raw top-level IDs, not IDs of flattened child cards.
            val last = items.lastOrNull()?.id?.takeIf { it.isNotBlank() }
            val next = if (items.isEmpty() || last == null || last == cursor.lastItem) null else PageCursor(cursor.page + 1, cursor.firstItem ?: items.firstOrNull()?.id?.takeIf { it.isNotBlank() }, last)
            Page(items, next)
        }
    }
    private suspend fun <T> request(path: String, params: Map<String, String>, api2: Boolean = false, decode: (JsonElement) -> T): ApiResult<T> {
        val captured = requestSession?.invoke()
        val result = when (val response = transport.get(path, params, api2)) {
            is ApiResult.Failure -> response
            is ApiResult.Success -> withContext(Dispatchers.Default) {
                try { ApiResult.Success(decode(response.value)) } catch (e: ApiProblem) { e.failure }
            }
        }
        // Account switches during CPU mapping must not publish the previous account's content.
        if (captured?.isCurrent?.invoke() == false) throw CancellationException("Session changed")
        return result
    }
    private fun numeric(value: String) = value.isNotEmpty() && value.all { it in '0'..'9' }
}
