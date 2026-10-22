// SPDX-License-Identifier: AGPL-3.0-only
// Wire contracts adapted from Ktclat/c001apk Flutter network_repo.dart and chat_controller.dart.
package io.github.ktclat.c001apk.nativeapp.social

import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.serialization.json.*

enum class NoticeCategory(val title: String, val path: String) {
    MENTIONS("@我的动态", "v6/notification/atMeList"), REPLIES("@我的评论", "v6/notification/atCommentMeList"),
    LIKES("收到的赞", "v6/notification/feedLikeList"), FOLLOWERS("新关注", "v6/notification/contactsFollowList"),
    MESSAGES("私信", "v6/message/list"), SYSTEM("系统通知", "v6/notification/list")
}
data class SocialRow(val id: String, val message: String, val userName: String, val uid: String,
    val conversationKey: String? = null, val pinned: Boolean = false, val imageUrl: String? = null, val hasImage: Boolean = false, val entityType: String = "", val notificationScope: NotificationDeleteScope? = null)
data class ComposerDraft(val text: String, val selfOnly: Boolean = false, val targetType: String? = null,
    val targetId: String? = null, val replyId: String? = null, val replyToReply: Boolean = false,
    val replyAndForward: Boolean = false)
enum class FollowTarget { USER, APP, TOPIC, PRODUCT }
enum class DeleteTarget { FEED, REPLY, NOTIFICATION, MESSAGE, CONVERSATION }
sealed interface WriteOutcome {
    data class Confirmed(val serverId: String? = null) : WriteOutcome
    data class Rejected(val kind: FailureKind, val message: String) : WriteOutcome
    data class Uncertain(val message: String = "服务端结果未确认，请刷新核实；不要重复发送") : WriteOutcome
}
sealed interface DeliveryState {
    data object Ready : DeliveryState
    data object Sending : DeliveryState
    data class Finished(val outcome: WriteOutcome) : DeliveryState
}
/** Never classify transport, malformed data or generic HTTP failure as safe-to-retry. */
internal fun ApiResult.Failure.writeOutcome(): WriteOutcome = when (kind) {
    FailureKind.UNCERTAIN, FailureKind.NETWORK, FailureKind.HTTP, FailureKind.INVALID_RESPONSE -> WriteOutcome.Uncertain()
    else -> WriteOutcome.Rejected(kind, message)
}
internal fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun validId(value: String) = value.isNotEmpty() && value.all { it in '0'..'9' }
/** Feed sharing is a normal text URL, never an invented rich-card protocol. */
fun feedShareUrl(idOrUrl: String): String? {
    val value = idOrUrl.trim()
    if (validId(value)) return "https://www.coolapk.com/feed/$value"
    return Regex("https://(?:www\\.)?coolapk\\.com/feed/([0-9]+)/?").matchEntire(value)
        ?.groupValues?.get(1)?.let { "https://www.coolapk.com/feed/$it" }
}

/** A chat message's conversation key is not its identity. */
internal fun mergeSocialRows(existing: List<SocialRow>, incoming: List<SocialRow>, conversationList: Boolean): List<SocialRow> =
    (existing + incoming).distinctBy { if (conversationList) it.conversationKey ?: it.id else it.id }

/** Opaque provenance from one repository response, never inferred from the sender's uid. */
class NotificationDeleteScope internal constructor(private val owner: Any, private val id: String, val generation: Long) {
    internal fun matches(row: SocialRow): Boolean = row.entityType == "notification" && row.id == id && validId(id)
    internal fun belongsTo(expected: Any, row: SocialRow): Boolean = owner === expected && matches(row)
    override fun toString() = "NotificationDeleteScope(redacted)"
}
internal fun canDeleteNotification(row: SocialRow): Boolean = row.notificationScope?.matches(row) == true
