// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.local

import io.github.ktclat.c001apk.nativeapp.domain.*
import io.github.ktclat.c001apk.nativeapp.settings.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import org.junit.Assert.*
import org.junit.Test

private class MemoryStore<T>(initial: T): JsonStateStore<T> {
    override val data = MutableStateFlow(initial)
    override suspend fun update(transform: (T) -> T) { data.value = transform(data.value) }
}
class LocalRepositoryTest {
    private fun feed(id: String, uid: String = "1", time: Long = 0) = ContentItem(id, "feed", message = "测试".repeat(200), userId = uid, publishedAtSeconds = time)
    @Test fun favoritesHistoryAndFollowAreIsolated() = runTest {
        val store = MemoryStore(LocalState()); val repo = LocalRepository(store) { 100 }
        repo.favorite(feed("1")); repo.record(feed("2"), true); repo.follow(LocalFollowUser("3"))
        repo.clear(LibraryKind.HISTORY)
        val restored = LocalRepository(store).state.first()
        assertEquals("1", restored.favorites.single().id); assertEquals(150, restored.favorites.single().summary.length)
        assertEquals("3", restored.follows.single().uid); assertTrue(restored.history.isEmpty())
        repo.record(feed("3"), false); assertTrue(repo.state.first().history.isEmpty())
    }
    @Test fun historyDoesNotRewriteAlreadyVisitedAndUnicodeIsWhole() = runTest {
        val repo = LocalRepository(MemoryStore(LocalState()))
        repo.record(feed("1").copy(message = "😀".repeat(200)), true)
        repo.record(feed("1").copy(message = "other"), true)
        val text = repo.state.first().history.single().summary
        assertEquals(150, text.codePointCount(0,text.length)); assertEquals(300,text.length)
    }
    @Test fun blacklistImportIsAtomicMergedAndBounded() = runTest {
        val repo = LocalRepository(MemoryStore(LocalState()))
        repo.addBlockedUser("1")
        repo.importBlacklist("""{"userBlackList":["1","2","2"],"topicBlackList":["Android"]}""")
        assertEquals(listOf("1","2"), repo.state.first().blacklist.userBlackList)
        val before = repo.state.first()
        listOf("""{"version":2,"userBlackList":[]}""", """{"userBlackList":["token"]}""", "[".repeat(20), " ".repeat(BlacklistJson.MAX_BYTES+1)).forEach { text ->
            try { repo.importBlacklist(text); fail("invalid input accepted") } catch (_: IllegalArgumentException) { }
            assertEquals(before, repo.state.first())
        }
        assertEquals(before.blacklist, BlacklistJson.decode(BlacklistJson.encode(before.blacklist)))
    }
    @Test fun blacklistUsesTopicMetadata() {
        val list = Blacklist(userBlackList = listOf("1"), topicBlackList = listOf("Android"))
        assertTrue(list.blocks("2", listOf("Android开发"))); assertTrue(list.blocks("1", emptyList())); assertFalse(list.blocks("2", emptyList()))
    }
    @Test fun settingsMigrationPreservesChoiceAndRejectsFuture() = runTest {
        val repo = SettingsRepository(MemoryStore(AppSettings(schemaVersion=1, fontScale=1.4f, readingPreset=ReadingPreset.ORIGINAL)))
        repo.update { it.copy(recordHistory=false) }
        val settings = repo.settings.first(); assertEquals(2, settings.schemaVersion); assertEquals(1.4f, settings.fontScale); assertEquals(ReadingPreset.ORIGINAL, settings.readingPreset)
        try { AppSettings(schemaVersion=9).validated(); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun pagerLimitsConcurrencyAndRetainsFailedCursor() = runTest {
        var active=0; var peak=0; var fail=true; val pages=mutableListOf<Pair<String,Int>>()
        val pager=LocalFollowPager((1..9).map(Int::toString), fetch={ uid,cursor ->
            active++; peak=maxOf(peak,active); pages+=uid to cursor.page; delay(1); active--
            if(uid=="1" && fail) ApiResult.Failure(FailureKind.NETWORK,"失败") else ApiResult.Success(Page(listOf(feed(uid,uid,uid.toLong())),null))
        })
        val first=pager.next(); assertTrue(peak<=4); assertEquals(setOf("1"),first.failedUsers); assertFalse(first.endReached)
        assertEquals(listOf("9","8","7","6","5","4","3","2"),first.items.map { it.id })
        fail=false; val next=pager.next(); assertEquals("1",next.items.single().id); assertTrue(next.endReached); assertEquals(listOf(1,1),pages.filter { it.first=="1" }.map { it.second })
    }
    @Test fun pagerBoundsFilteredPagesAndDeduplicates() = runTest {
        var calls=0
        val pager=LocalFollowPager(listOf("1"),fetch={ _,cursor -> calls++; ApiResult.Success(Page(listOf(feed("same")),cursor.copy(page=cursor.page+1))) }, accepts={false})
        val batch=pager.next(); assertEquals(3,calls); assertEquals(setOf("1"),batch.deferredUsers); assertFalse(batch.endReached)
        val dedupe=LocalFollowPager(listOf("1"),fetch={_,cursor-> ApiResult.Success(Page(listOf(feed("same"),feed("same")),if(cursor.page==1) PageCursor(2) else null))})
        assertEquals(1,dedupe.next().items.size)
    }
    @Test fun cancelledBatchDoesNotConsumeUndeliveredItems() = runTest {
        var hold = true
        val blocked = CompletableDeferred<Unit>()
        val pager = LocalFollowPager(listOf("1"), fetch = { _, cursor ->
            if (cursor.page == 1) ApiResult.Success(Page(listOf(feed("first")), PageCursor(2)))
            else { if (hold) { blocked.complete(Unit); CompletableDeferred<Unit>().await() }; ApiResult.Success(Page(listOf(feed("second")), null)) }
        })
        val request = launch { pager.next() }
        blocked.await(); request.cancelAndJoin(); hold = false
        assertEquals(listOf("first", "second"), pager.next().items.map { it.id })
    }
    @Test fun sourceBlacklistArrayRequiresExplicitKind() = runTest {
        val repo = LocalRepository(MemoryStore(LocalState()))
        repo.importBlacklist("[\"1\",2,\"1\"]", legacyUserList = true)
        repo.importBlacklist("[\"Android\"]", legacyUserList = false)
        assertEquals(listOf("1", "2"), repo.state.first().blacklist.userBlackList)
        assertEquals(listOf("Android"), repo.state.first().blacklist.topicBlackList)
        try { repo.importBlacklist("[\"3\"]"); fail("ambiguous legacy import accepted") } catch (_: IllegalArgumentException) { }
    }
}
