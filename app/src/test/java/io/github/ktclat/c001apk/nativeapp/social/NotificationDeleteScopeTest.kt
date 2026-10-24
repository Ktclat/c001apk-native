// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

import org.junit.Assert.*
import org.junit.Test

class NotificationDeleteScopeTest {
    @Test fun mixedFeedAndReplyIdsCannotEnterNotificationDeleteAction() {
        val row = SocialRow("123", "fixture", "sender", "7", notificationScope = NotificationDeleteScope(Any(), "123", 1))
        assertFalse(canDeleteNotification(row))
        assertFalse(canDeleteNotification(row.copy(entityType = "feed")))
        assertFalse(canDeleteNotification(row.copy(entityType = "feed_reply")))
        assertFalse(canDeleteNotification(row.copy(entityType = "message")))
        assertTrue(canDeleteNotification(row.copy(entityType = "notification")))
        assertFalse(canDeleteNotification(row.copy(id = "not-an-id", entityType = "notification")))
        assertFalse(canDeleteNotification(row.copy(id = "456", entityType = "notification")))
        assertFalse(canDeleteNotification(row.copy(entityType = "notification", notificationScope = null)))
    }
}
