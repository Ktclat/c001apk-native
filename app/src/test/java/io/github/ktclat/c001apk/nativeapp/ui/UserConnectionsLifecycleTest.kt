// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume

@OptIn(ExperimentalCoroutinesApi::class)
class UserConnectionsLifecycleTest {
    private val contact = ContentItem("raw-record", "contacts", userId = "8", userName = "same", sourceEntityId = "shared-entity")
    @Test fun appendPreservesDuplicateRecordsAndRawNextCursorAndBackReusesRows() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val calls = mutableListOf<List<Any?>>()
            val model = BrowseViewModel(repository { method, args ->
                assertEquals("userConnections", method); calls += args.dropLast(1)
                ApiResult.Success(Page(listOf(contact, contact), PageCursor(calls.size + 1, "raw-first", "raw-last-${calls.size}")))
            })
            model.load("user-following:7"); runCurrent(); model.load("user-following:7", more = true); runCurrent()
            assertEquals(listOf("7", UserConnectionKind.FOLLOWING, PageCursor(2, "raw-first", "raw-last-1")), calls[1])
            assertEquals(4, model.states.value.getValue("user-following:7").items.size)
            model.leaveRead("user-following:7"); model.load("user-following:7"); runCurrent()
            assertEquals(2, calls.size)
            model.load("user-fans:7"); runCurrent()
            assertEquals(UserConnectionKind.FANS, calls.last()[1])
        } finally { Dispatchers.resetMain() }
    }
    @Test fun failedPaginationRetriesSameCursorWithoutDiscardingRowsOrDeduplicating() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val cursors = mutableListOf<PageCursor>()
            val model = BrowseViewModel(repository { _, args ->
                cursors += args[2] as PageCursor
                if (cursors.size == 2) ApiResult.Failure(FailureKind.NETWORK, "error")
                else ApiResult.Success(Page(listOf(contact), if (cursors.size == 1) PageCursor(2, "raw-record", "raw-record") else null))
            })
            model.load("user-fans:7"); runCurrent(); model.load("user-fans:7", more = true); runCurrent()
            val failed = model.states.value.getValue("user-fans:7")
            assertTrue(failed.moreError); assertEquals(1, failed.items.size)
            model.load("user-fans:7", more = true); runCurrent()
            assertEquals(cursors[1], cursors[2]); assertEquals(2, model.states.value.getValue("user-fans:7").items.size)
            assertNull(model.states.value.getValue("user-fans:7").next)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun departedNonCooperativeReadCannotReplaceAnotherUserOrNewVisit() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val pending = mutableListOf<Continuation<ApiResult<Page<ContentItem>>>>()
            val model = BrowseViewModel(repository { _, args ->
                @Suppress("UNCHECKED_CAST")
                pending += args.last() as Continuation<ApiResult<Page<ContentItem>>>
                COROUTINE_SUSPENDED
            })
            model.load("user-following:7"); runCurrent(); model.leaveRead("user-following:7")
            model.load("user-fans:8"); runCurrent()
            pending[1].resume(ApiResult.Success(Page(listOf(contact.copy(userName = "other")), null))); runCurrent()
            model.load("user-following:7"); runCurrent()
            pending[2].resume(ApiResult.Success(Page(listOf(contact.copy(userName = "fresh")), null))); runCurrent()
            pending[0].resume(ApiResult.Success(Page(listOf(contact.copy(userName = "stale")), null))); runCurrent()
            assertEquals("fresh", model.states.value.getValue("user-following:7").items.single().userName)
            assertEquals("other", model.states.value.getValue("user-fans:8").items.single().userName)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun leavingClosesChallengeAndNewVisitGetsFreshCapability() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var closed = 0
            fun challenge() = PendingPageRead(ApiChallenge.Netease("abcdefghijklmnop", "_v2_post_token"),
                isCurrent = { true }, image = { ApiResult.Failure(FailureKind.NETWORK, "error") }, verify = { ApiResult.Failure(FailureKind.NETWORK, "error") },
                retry = { ApiResult.Success(Page(emptyList<ContentItem>(), null)) }, onClosed = { closed++ })
            val capabilities = mutableListOf<PendingPageRead>()
            val model = BrowseViewModel(repository { _, _ ->
                val pending = challenge().also(capabilities::add)
                ApiResult.Failure(FailureKind.CAPTCHA, "verify", pageRead = pending)
            })
            model.load("user-following:7"); runCurrent(); model.leaveRead("user-following:7")
            assertEquals(1, closed); assertFalse(capabilities.single().acceptHumanProof("NEC:abcdefgh:synthetic-proof"))
            model.load("user-following:7"); runCurrent(); assertEquals(2, capabilities.size)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun wrappersDoNotDeduplicateAndDisplayBoundOrEndSentinelStopsPagination() {
        val wrapper = ContentItem("wrapper", "card", entityTemplate = "listCard", children = listOf(contact, contact))
        val projected = userConnectionsPage(BrowseState(), Page(listOf(wrapper), PageCursor(2, "raw", "raw")), false)
        assertEquals(2, projected.items.size)
        val limited = userConnectionsPage(BrowseState(items = List(1999) { contact }), Page(listOf(wrapper), PageCursor(3)), true)
        assertEquals(2000, limited.items.size); assertEquals(PageCursor(3), limited.next); assertTrue(limited.displayLimitReached)
        for (marker in listOf(ContentItem("end", "noMoreDataCard"), ContentItem("end", "card", entityTemplate = "noMoreDataCard"))) {
            val end = userConnectionsPage(projected, Page(listOf(marker), PageCursor(3)), true)
            assertEquals(2, end.items.size); assertNull(end.next)
        }
    }
    @Test fun userFeedEndMarkerScopePreservesRawRowsAndUnknownNestedCards() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val end = ContentItem("end", "card", entityTemplate = "noMoreDataCard")
            val unknown = end.copy(entityTemplate = "unknownCard")
            var items = listOf(ContentItem("post", "feed"), end)
            val next = PageCursor(2, "post", "end")
            val model = BrowseViewModel(repository { _, _ -> ApiResult.Success(Page(items, next)) })
            for (key in listOf("home", "following:0", "page:source")) {
                model.load(key); runCurrent()
                assertEquals(next, model.states.value.getValue(key).next)
            }
            model.load("user:7"); runCurrent()
            assertNull(model.states.value.getValue("user:7").next)
            assertEquals(items, model.states.value.getValue("user:7").items)
            items = listOf(unknown.copy(children = listOf(end)))
            model.load("user:8"); runCurrent()
            assertEquals(next, model.states.value.getValue("user:8").next)
            // A known listCard may expose its explicit terminal child, but arbitrary cards may not.
            items = listOf(unknown.copy(entityTemplate = "listCard", children = listOf(end)))
            model.load("user:8", more = true); runCurrent()
            assertNull(model.states.value.getValue("user:8").next)
            assertEquals(2, model.states.value.getValue("user:8").items.size)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun verifiedUserFeedRetryUsesSameExplicitEndMarkerPublication() = runBlocking {
        // PendingRead retries use real IO. Keep this bounded wait on real time, not runTest's auto-advancing clock.
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val end = ContentItem("end", "card", entityTemplate = "noMoreDataCard")
            val pending = PendingPageRead(ApiChallenge.Netease("abcdefghijklmnop", "_v2_post_token"),
                isCurrent = { true }, image = { ApiResult.Failure(FailureKind.NETWORK, "unused") },
                verify = { ApiResult.Failure(FailureKind.NETWORK, "unused") },
                retry = { ApiResult.Success(Page(listOf(end), PageCursor(2, "end", "end"))) })
            var calls = 0
            val model = BrowseViewModel(repository { _, _ -> calls++; ApiResult.Failure(FailureKind.CAPTCHA, "verify", pageRead = pending) })
            model.load("user:7")
            assertTrue(pending.acceptHumanProof("NEC:abcdefgh:synthetic-proof"))
            model.load("user:7", refresh = true, pageRetry = pending)
            val state = withTimeout(15000) { model.states.first { it["user:7"]?.items == listOf(end) }.getValue("user:7") }
            assertNull(state.next); assertNull(state.failure); assertEquals(1, calls)
        } finally { Dispatchers.resetMain() }
    }
    private fun repository(answer: (String, Array<out Any?>) -> Any?): CoolapkRepository = Proxy.newProxyInstance(
        CoolapkRepository::class.java.classLoader, arrayOf(CoolapkRepository::class.java)) { _, method, args -> answer(method.name, args.orEmpty()) } as CoolapkRepository
}
