package io.github.ktclat.c001apk.nativeapp.ui

import io.github.ktclat.c001apk.nativeapp.domain.*
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrowseViewModelTest {
    private data class Call(val id: String, val order: ReplyOrder, val authorOnly: Boolean,
        val continuation: Continuation<ApiResult<Page<ContentItem>>>)
    private fun repository(calls: MutableList<Call>): CoolapkRepository = Proxy.newProxyInstance(
        CoolapkRepository::class.java.classLoader, arrayOf(CoolapkRepository::class.java)) { _, method, args ->
        check(method.name == "orderedReplies")
        @Suppress("UNCHECKED_CAST")
        calls += Call(args[0] as String, args[1] as ReplyOrder, args[2] as Boolean,
            args.last() as Continuation<ApiResult<Page<ContentItem>>>)
        COROUTINE_SUSPENDED
    } as CoolapkRepository

    @Test fun orderedRoutePreservesServerSortAndAuthorFilter() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val calls = mutableListOf<Call>(); val model = BrowseViewModel(repository(calls))
            val key = "ordered:100:POPULAR:true"
            model.load(key); runCurrent()
            assertEquals(1, calls.size); assertEquals("100", calls[0].id)
            assertEquals(ReplyOrder.POPULAR, calls[0].order); assertTrue(calls[0].authorOnly)
            calls[0].continuation.resume(ApiResult.Success(Page(emptyList(), null))); runCurrent()
            assertFalse(model.states.value.getValue(key).loading)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun invalidatedReplyRequestCannotRestoreStaleResults() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val calls = mutableListOf<Call>(); val model = BrowseViewModel(repository(calls))
            val key = "ordered:100:RECENT_REPLY:false"
            model.load(key); runCurrent(); model.invalidateReplies("100")
            model.load(key); runCurrent(); assertEquals(2, calls.size)
            calls[1].continuation.resume(ApiResult.Success(Page(listOf(ContentItem("fresh", "feed_reply")), null))); runCurrent()
            calls[0].continuation.resume(ApiResult.Success(Page(listOf(ContentItem("stale", "feed_reply")), null))); runCurrent()
            assertEquals(listOf("fresh"), model.states.value.getValue(key).items.map { it.id })
            assertFalse(model.states.value.getValue(key).loading)
        } finally { Dispatchers.resetMain() }
    }
}
