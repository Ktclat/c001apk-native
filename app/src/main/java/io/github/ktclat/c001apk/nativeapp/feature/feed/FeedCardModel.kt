// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.feature.feed

import io.github.ktclat.c001apk.nativeapp.domain.ContentItem
import io.github.ktclat.c001apk.nativeapp.domain.ExplicitLikeState
import io.github.ktclat.c001apk.nativeapp.domain.FeedReferenceKind
import io.github.ktclat.c001apk.nativeapp.domain.FeedReference as DomainFeedReference

/** Account state is independent of aggregate counts. UNKNOWN must never toggle a network like. */
enum class FeedLikeState { UNKNOWN, LOGIN_REQUIRED, NOT_LIKED, LIKED, PENDING }
data class FeedReference(val id: String, val author: String, val title: String = "", val body: String = "", val images: List<String> = emptyList(), val deleted: Boolean = false, val kind: FeedReferenceKind = FeedReferenceKind.FORWARD, val parentFeedId: String = "", val authorId: String = "")
data class FeedRelation(val key: String, val title: String, val url: String?, val image: String? = null, val id: String = "", val entityType: String? = null, val targetType: String? = null)
data class FeedLink(val title: String, val url: String, val image: String? = null)
data class FeedCardModel(
    val id: String,
    val author: String,
    val authorId: String,
    val avatar: String? = null,
    val title: String = "",
    val body: String = "",
    val device: String = "",
    val info: String = "",
    val time: String = "",
    val pinned: Boolean = false,
    val images: List<String> = emptyList(),
    val replies: Int? = null,
    val likes: Int? = null,
    val likeState: FeedLikeState = FeedLikeState.UNKNOWN,
    val forward: FeedReference? = null,
    val hotReply: FeedReference? = null,
    val link: FeedLink? = null,
    val relations: List<FeedRelation> = emptyList(),
    /** Undispatched entities remain nested and explicitly unsupported, never peer posts. */
    val unsupportedChildren: Int = 0,
)

/** Exact typed projection. Missing metadata counts stay null, not the legacy default zero.
 * Metadata HTML is bounded and parsed off-main by the header, never synchronously here.
 */
fun ContentItem.toFeedCardModel(nowSeconds: Long, detail: Boolean = false): FeedCardModel {
    val metadata = feed
    return FeedCardModel(
        id = id, author = userName, authorId = userId, avatar = avatarUrl,
        title = title, body = message, images = imageUrls,
        device = metadata?.deviceTitle.orEmpty(), info = metadata?.infoHtml.orEmpty(),
        pinned = metadata?.pinned == true,
        replies = if (metadata != null) metadata.replyCount else replyCount,
        likes = if (metadata != null) metadata.likeCount else likeCount,
        likeState = when (metadata?.likeState) {
            ExplicitLikeState.LIKED -> FeedLikeState.LIKED
            ExplicitLikeState.NOT_LIKED -> FeedLikeState.NOT_LIKED
            else -> FeedLikeState.UNKNOWN
        },
        time = listOfNotNull(feedRelativeTime(publishedAtSeconds, nowSeconds).takeIf(String::isNotBlank),
            metadata?.ipLocation?.takeIf { detail && it.isNotBlank() }?.let { "发布于 $it" }).joinToString(" · "),
        forward = metadata?.forward?.toPresentation(),
        hotReply = metadata?.hotReply?.toPresentation(),
        link = metadata?.link?.let { FeedLink(it.title, it.url, it.image) },
        relations = (listOfNotNull(metadata?.target) + metadata?.relations.orEmpty()).distinct().mapIndexed { index, relation ->
            FeedRelation("${relation.entityType}:${relation.id}:${relation.url}:$index", relation.title, relation.url, relation.image,
                relation.id, relation.entityType, relation.targetType)
        },
        unsupportedChildren = children.size,
    )
}
private fun DomainFeedReference.toPresentation() = FeedReference(
    id = id, author = author, title = title, body = body, images = images, deleted = deleted,
    kind = kind, parentFeedId = parentFeedId, authorId = authorId,
)

/** Routes only explicit, existing ownership; no inferred parent or destructive fallback. */
fun FeedReference.destination(): String? = if (deleted) null else when (kind) {
    FeedReferenceKind.FORWARD -> id.takeIf(String::isNotBlank)?.let { "feed:$it" }
    FeedReferenceKind.HOT_REPLY -> parentFeedId.takeIf(String::isNotBlank)?.let { "replies:$it" }
}

fun feedRelativeTime(published: Long, now: Long): String {
    if (published <= 0L) return ""
    val elapsed = (now - published).coerceAtLeast(0)
    return when {
        elapsed < 60 -> "刚刚"
        elapsed < 3600 -> "${elapsed / 60}分钟前"
        elapsed < 86400 -> "${elapsed / 3600}小时前"
        else -> "${elapsed / 86400}天前"
    }
}
fun FeedLikeState.canToggle(): Boolean = this == FeedLikeState.LIKED || this == FeedLikeState.NOT_LIKED
fun feedCount(value: Int?): String {
    if (value == null) return ""
    val count = value.coerceAtLeast(0)
    val unit = if (count >= 100_000_000) 100_000_000 else if (count >= 10_000) 10_000 else return count.toString()
    val whole = count / unit
    val tenth = (count.toLong() * 10 / unit % 10).toInt()
    return "$whole${if (tenth == 0) "" else ".$tenth"}${if (unit == 10_000) "万" else "亿"}"
}
