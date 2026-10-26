// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

import org.junit.Assert.*
import androidx.compose.runtime.snapshots.Snapshot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationStateTest {
    // These are pure cache assertions, not mounted UI. Do not publish snapshot mutations into
    // another Robolectric Compose test's previously installed global snapshot observer.
    private fun isolated(block: () -> Unit) {
        val snapshot = Snapshot.takeMutableSnapshot()
        try { snapshot.enter(block) } finally { snapshot.dispose() }
    }
    @Test fun initialRoutesShareOnlyThreeLruPagesAndEvictionRevokesLatePublication() = isolated {
        val owner = NotificationNavigationState()
        try {
        val first = owner.screen(NoticeCategory.MENTIONS)
        val second = owner.screen(NoticeCategory.REPLIES)
        val old = first.page(NoticeCategory.MENTIONS)
        old.rows = listOf(SocialRow("1","private","","")); old.alive = true; old.loaded = true; old.loading = true
        val epoch = old.epoch
        assertSame(old,second.page(NoticeCategory.MENTIONS))
        first.page(NoticeCategory.REPLIES); second.page(NoticeCategory.LIKES); second.page(NoticeCategory.FOLLOWERS)
        assertFalse(old.alive); assertFalse(old.loading); assertFalse(old.loaded); assertTrue(old.rows.isEmpty()); assertTrue(old.epoch > epoch)
        assertNotSame(old,first.page(NoticeCategory.MENTIONS))
        } finally { owner.clear() }
    }
    @Test fun departedRoutesClearPrivateRowsButChildHistoryRetainsThem() = isolated {
        val owner = NotificationNavigationState()
        try {
        val screen = owner.screen(NoticeCategory.MENTIONS)
        screen.category = NoticeCategory.LIKES
        val page = screen.page(NoticeCategory.LIKES)
        page.rows = listOf(SocialRow("1","private","","")); page.loaded = true
        owner.retainRoutes(listOf("notifications:MENTIONS","feed:1"))
        assertSame(page,owner.screen(NoticeCategory.MENTIONS).page(NoticeCategory.LIKES))
        assertEquals(NoticeCategory.LIKES,owner.screen(NoticeCategory.MENTIONS).category)
        owner.retainRoutes(emptyList())
        assertTrue(page.rows.isEmpty()); assertFalse(page.loaded)
        assertNotSame(screen,owner.screen(NoticeCategory.MENTIONS))
        } finally { owner.clear() }
    }
}
