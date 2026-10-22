// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.domain

/** Read-only projection. Account-specific action state is deliberately not inferred. */
data class ContentItem(
    val id: String,
    val kind: String,
    val title: String = "",
    val message: String = "",
    val userName: String = "",
    val userId: String = "",
    val avatarUrl: String? = null,
    val imageUrls: List<String> = emptyList(),
    val replyCount: Int = 0,
    val likeCount: Int = 0,
    val targetUrl: String? = null,
    val children: List<ContentItem> = emptyList(),
    val tabs: List<ContentTab> = emptyList(),
    val packageName: String? = null,
    val publishedAtSeconds: Long = 0,
    val topicTags: List<String> = emptyList(),
    val topicTitle: String = "",
    val firstRelationTitle: String = "",
    /** Container entities only. Replies and forward quotes never become sibling posts. */
    val entityTemplate: String? = null,
    val replies: List<ContentItem> = emptyList(),
    val feed: FeedMetadata? = null,
    val bodyTruncated: Boolean = false,
    val replyInteraction: ReplyInteractionMetadata? = null,

)
/** Reply-specific server state. Missing action/counts remain unknown rather than false/zero. */
data class ReplyInteractionMetadata(
    val likeState: ExplicitLikeState = ExplicitLikeState.UNKNOWN,
    val likeCount: Int? = null,
    val replyCount: Int? = null,
)
enum class ExplicitLikeState { UNKNOWN, NOT_LIKED, LIKED }
/** Values absent from the response remain unknown, including zero versus missing counts. */
data class FeedMetadata(
    val deviceTitle: String? = null,
    val infoHtml: String? = null,
    val ipLocation: String? = null,
    val pinned: Boolean? = null,
    val likeState: ExplicitLikeState = ExplicitLikeState.UNKNOWN,
    val replyCount: Int? = null,
    val likeCount: Int? = null,
    val forwardSourceType: String? = null,
    val forward: FeedReference? = null,
    val hotReply: FeedReference? = null,
    val link: FeedLinkMetadata? = null,
    val target: FeedRelationMetadata? = null,
    val relations: List<FeedRelationMetadata> = emptyList(),
)
enum class FeedReferenceKind { FORWARD, HOT_REPLY }
/** Flat, bounded quote projection. No JSON or recursively expanding quote trees reach UI. */
data class FeedReference(
    val kind: FeedReferenceKind,
    val id: String = "",
    val parentFeedId: String = "",
    val authorId: String = "",
    val author: String = "",
    val title: String = "",
    val body: String = "",
    val images: List<String> = emptyList(),
    val deleted: Boolean = false,
)
data class FeedLinkMetadata(val title: String, val url: String, val image: String? = null)
data class FeedRelationMetadata(
    val id: String = "", val entityType: String? = null, val targetType: String? = null,
    val title: String = "", val url: String? = null, val image: String? = null,
)
data class ContentTab(val title: String, val url: String)
data class PageCursor(val page: Int = 1, val firstItem: String? = null, val lastItem: String? = null) {
    init { require(page > 0) }
}
data class Page<T>(val items: List<T>, val nextCursor: PageCursor?)
enum class FailureKind { UNCERTAIN, NETWORK, HTTP, AUTHENTICATION, CAPTCHA, RATE_LIMIT, RISK_CONTROL, CLIENT_UPGRADE, BUSINESS, INVALID_RESPONSE, INVALID_ARGUMENT }
sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    /** Only locally authored, safe messages; never raw server data, URLs or exceptions. */
    data class Failure(val kind: FailureKind, val message: String, val httpStatus: Int? = null, val challenge: ApiChallenge? = null) : ApiResult<Nothing>
}

data class SearchOptions(val type: String = "feed", val feedType: String = "all", val sort: String = "default", val strict: Boolean = false, val pageType: String? = null, val pageParam: String? = null)
enum class ReplyOrder(val wireValue: String) { RECENT_REPLY("lastupdate_desc"), NEWEST("dateline_desc"), POPULAR("popular"), OLDEST("") }

enum class FollowType(val title: String, val route: String) {
    ALL("全部关注", "/page?url=V9_HOME_TAB_FOLLOW"),
    USERS("用户关注", "/page?url=V9_HOME_TAB_FOLLOW&type=circle"),
    TOPICS("话题关注", "/page?url=V9_HOME_TAB_FOLLOW&type=topic"),
    PRODUCTS("数码关注", "/page?url=V9_HOME_TAB_FOLLOW&type=product"),
    APPS("应用关注", "/page?url=V9_HOME_TAB_FOLLOW&type=apk"),
}

interface CoolapkRepository {
    suspend fun following(type: FollowType = FollowType.ALL, cursor: PageCursor = PageCursor()): ApiResult<Page<ContentItem>>
    suspend fun home(cursor: PageCursor = PageCursor()): ApiResult<Page<ContentItem>>
    suspend fun feedDetail(id: String): ApiResult<ContentItem>
    suspend fun replies(id: String, cursor: PageCursor = PageCursor()): ApiResult<Page<ContentItem>>
    suspend fun search(query: String, cursor: PageCursor = PageCursor(), type: String = "feed"): ApiResult<Page<ContentItem>>
    suspend fun searchAdvanced(query: String, options: SearchOptions, cursor: PageCursor = PageCursor()): ApiResult<Page<ContentItem>>
    suspend fun orderedReplies(id: String, order: ReplyOrder, authorOnly: Boolean = false, cursor: PageCursor = PageCursor()): ApiResult<Page<ContentItem>>
    suspend fun replyThread(replyId: String, cursor: PageCursor = PageCursor()): ApiResult<Page<ContentItem>>
    suspend fun pictures(tag: String, type: String, cursor: PageCursor = PageCursor()): ApiResult<Page<ContentItem>>
    suspend fun dyhArticles(id: String, type: String, cursor: PageCursor = PageCursor()): ApiResult<Page<ContentItem>>
    suspend fun userProfile(uid: String): ApiResult<ContentItem>
    suspend fun userFeed(uid: String, cursor: PageCursor = PageCursor()): ApiResult<Page<ContentItem>>
    suspend fun chronologicalUserFeed(uid: String, cursor: PageCursor = PageCursor()): ApiResult<Page<ContentItem>>
    suspend fun topicDetail(tag: String): ApiResult<ContentItem>
    suspend fun appDetail(id: String): ApiResult<ContentItem>
    suspend fun productDetail(id: String): ApiResult<ContentItem>
    suspend fun productCategories(): ApiResult<Page<ContentItem>>
    suspend fun page(url: String, title: String = "", cursor: PageCursor = PageCursor()): ApiResult<Page<ContentItem>>
}
