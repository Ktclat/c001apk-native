// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.domain

enum class PersonalListKind(val title: String, val path: String) {
 FOLLOW("我的关注", "v6/user/followList"), FAN("关注我的人", "v6/user/fansList"),
 RECENT("我的常去", "v6/user/recentHistoryList"), COLLECTION("我的收藏", "v6/collection/list"),
 LIKE("我的赞", "v6/user/likeList"), REPLY("我的回复", "v6/user/replyList"),
 REPLY_ME("我收到的回复", "v6/user/replyToMeList"), APPS("应用", "v6/user/apkFollowList"),
 TOPICS("话题", "v6/page/dataList"), PRODUCTS("数码", "v6/page/dataList"),
 COLLECTION_ITEMS("收藏内容", "v6/collection/itemList")
}
data class PersonalListRequest(val kind: PersonalListKind, val collectionId: String? = null)
sealed interface PersonalDestination {
 data class Route(val value: String): PersonalDestination
 data class Link(val value: String): PersonalDestination
 data object Unsupported: PersonalDestination
}
/** Raw record identity is kept separate from the target entity identity. Missing counts stay absent. */
data class PersonalEntry(val recordId: String, val content: ContentItem, val label: String,
 val detail: String, val destination: PersonalDestination, val likeAuthor: String? = null)
fun interface PersonalListRepository {
 suspend fun load(request: PersonalListRequest, uid: String, cursor: PageCursor): ApiResult<Page<PersonalEntry>>
}
