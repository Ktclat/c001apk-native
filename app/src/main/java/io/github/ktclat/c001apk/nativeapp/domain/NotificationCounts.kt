// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.domain

/** Read-only server snapshot for one account. Unknown is never inferred to be zero. */
data class NotificationCounts(val uid: String, val atMe: Long?, val atCommentMe: Long?,
    val feedLike: Long?, val contactsFollow: Long?, val message: Long?, val notification: Long?) {
    val messagesAndNotifications: Long? get() {
        val messages = message ?: return null
        val notices = notification ?: return null
        return if (messages >= 0 && notices >= 0 && messages <= Long.MAX_VALUE - notices) messages + notices else null
    }
    val hasUnknown: Boolean get() = listOf(atMe, atCommentMe, feedLike, contactsFollow, messagesAndNotifications).any { it == null }
}
fun interface NotificationCountsRepository { suspend fun load(): ApiResult<NotificationCounts> }
