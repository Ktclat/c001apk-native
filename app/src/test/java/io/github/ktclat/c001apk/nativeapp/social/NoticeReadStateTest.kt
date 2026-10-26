// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

import io.github.ktclat.c001apk.nativeapp.domain.*
import androidx.compose.runtime.snapshots.Snapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Capabilities are never part of retained route history, even when safe rows/scroll are. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NoticeReadStateTest {
    // Pure cache fixtures must not notify a previous Robolectric UI's snapshot observer.
    private fun isolated(block: () -> Unit) {
        val snapshot = Snapshot.takeMutableSnapshot()
        try { snapshot.enter(block) } finally { snapshot.dispose() }
    }
    private val row = SocialRow("1", "private notice", "Synthetic", "2")
    private fun pending(): PendingNoticeRead = PendingRead(ApiChallenge.Netease("abcdefghijklmnop", ""),
        { true }, { error("No graphic request") }, { error("No graphic answer") }, { error("Disposed capability replayed") })
    private fun attach(page: NotificationPageState): PendingNoticeRead = pending().also {
        page.rows = listOf(row); page.next = PageCursor(2, "1", "1"); page.loaded = true; page.alive = true
        page.failure = ApiResult.Failure(FailureKind.CAPTCHA, "Synthetic", noticeRead = it); page.failedMore = true
    }
    @Test fun routeLeaveClosesProofButRetainsSameOwnerRowsCursorAndFailureMode() = isolated {
        val page = NotificationPageState(); val cap = attach(page)
        assertTrue(cap.acceptHumanProof("NEC:abcdefgh:synthetic-state-proof"))
        val epoch = page.epoch; page.leave()
        assertEquals(DetailVerificationState.CLOSED, cap.state.value); assertFalse(cap.canOpen())
        assertFalse(page.alive); assertFalse(page.loading); assertTrue(page.epoch > epoch)
        assertEquals(listOf(row), page.rows); assertEquals(PageCursor(2, "1", "1"), page.next); assertTrue(page.failedMore)
    }
    @Test fun explicitClearDropsPrivateRowsAndPendingReferences() = isolated {
        val page = NotificationPageState(); val cap = attach(page); page.clear()
        assertEquals(DetailVerificationState.CLOSED, cap.state.value)
        assertTrue(page.rows.isEmpty()); assertFalse(page.loaded); assertNull(page.failure)
    }
    @Test fun categoryCacheEvictionClosesCapabilityRatherThanRetainingItForReturn() = isolated {
        val cache = NotificationPageCache(); val page = cache.page(NoticeCategory.MENTIONS); val cap = attach(page)
        cache.page(NoticeCategory.REPLIES); cache.page(NoticeCategory.LIKES); cache.page(NoticeCategory.FOLLOWERS)
        assertEquals(DetailVerificationState.CLOSED, cap.state.value); assertTrue(page.rows.isEmpty())
        assertNotSame(page, cache.page(NoticeCategory.MENTIONS))
    }
    @Test fun leavingLastNotificationRouteRevokesEveryCachedCapability() = isolated {
        val owner = NotificationNavigationState(); val screen = owner.screen(NoticeCategory.MENTIONS)
        val first = attach(screen.page(NoticeCategory.MENTIONS)); val second = attach(screen.page(NoticeCategory.LIKES))
        owner.retainRoutes(emptyList())
        assertEquals(DetailVerificationState.CLOSED, first.state.value); assertEquals(DetailVerificationState.CLOSED, second.state.value)
    }
}
