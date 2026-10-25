// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.Assert.*
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume

@OptIn(ExperimentalCoroutinesApi::class)
class SourceListRoutesTest {
    @Test fun typedRoutesMatchSourceTabsAndPreserveColonsInPictureTags() {
        val pic = requireNotNull(sourceListRoute("coolpic:星空:2026"))
        assertEquals(listOf("recommend", "hot", "newest"), pic.tabs.map { it.first })
        assertEquals(pic to "newest", sourceListRequest("coolpic-list:newest:星空:2026"))
        val dyh = requireNotNull(sourceListRoute("dyh:55"))
        assertEquals(listOf("all", "square"), dyh.tabs.map { it.first })
        assertEquals(dyh to "square", sourceListRequest("dyh-list:square:55"))
    }
    @Test fun malformedRouteIdsKindsTypesAndControlsStayUnsupported() {
        for (value in listOf(null, "dyh:", "dyh:../1", "coolpic:", "coolpic:a\nb", "coolpic:" + "a".repeat(4097), "feed:1")) assertNull(sourceListRoute(value))
        for (value in listOf("coolpic:newest:tag", "coolpic-list:bad:tag", "dyh-list:hot:55", "dyh-list:square:nope")) assertNull(sourceListRequest(value))
    }
    @Test fun actualBrowseModelDispatchesDistinctMethodsAndPaginationWithoutFeedIdGuessing() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val calls = mutableListOf<Pair<String, List<Any?>>>()
            val repository = Proxy.newProxyInstance(CoolapkRepository::class.java.classLoader, arrayOf(CoolapkRepository::class.java)) { _, method, args ->
                calls += method.name to args.dropLast(1)
                if (method.name == "eventDetail") ApiResult.Success(ContentItem("1086", "event", title = "活动"))
                else ApiResult.Success(Page(listOf(ContentItem("44", "feed")), PageCursor(2, "44", "44")))
            } as CoolapkRepository
            val model = BrowseViewModel(repository)
            model.load("event:1086"); runCurrent()
            model.load("dyh-list:square:55"); runCurrent()
            model.load("coolpic-list:newest:星空:2026"); runCurrent()
            model.load("coolpic-list:newest:星空:2026", more = true); runCurrent()
            assertEquals(listOf("eventDetail", "dyhArticles", "pictures", "pictures"), calls.map { it.first })
            assertEquals(listOf("55", "square", PageCursor()), calls[1].second)
            assertEquals(listOf("星空:2026", "newest", PageCursor(2, "44", "44")), calls.last().second)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun departedLinkedReadCannotPublishEvenIfTransportIgnoresCancellation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var continuation: Continuation<ApiResult<Page<ContentItem>>>? = null
            val repository = Proxy.newProxyInstance(CoolapkRepository::class.java.classLoader, arrayOf(CoolapkRepository::class.java)) { _, method, args ->
                check(method.name == "pictures")
                @Suppress("UNCHECKED_CAST")
                val captured = args.last() as Continuation<ApiResult<Page<ContentItem>>>
                continuation = captured; COROUTINE_SUSPENDED
            } as CoolapkRepository
            val model = BrowseViewModel(repository); val key = "coolpic-list:hot:摄影"
            model.load(key); runCurrent(); model.cancelLinkedRead(key)
            continuation!!.resume(ApiResult.Success(Page(listOf(ContentItem("stale", "feed")), null))); runCurrent()
            assertFalse(model.states.value.getValue(key).loading)
            assertTrue(model.states.value.getValue(key).items.isEmpty())
        } finally { Dispatchers.resetMain() }
    }
    @Test fun failedThenCancelledRetryIsNotMistakenForSuccessfulEmptyContent() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var calls = 0
            var continuation: Continuation<ApiResult<Page<ContentItem>>>? = null
            val repository = Proxy.newProxyInstance(CoolapkRepository::class.java.classLoader, arrayOf(CoolapkRepository::class.java)) { _, _, args ->
                calls++
                when (calls) {
                    1 -> ApiResult.Failure(FailureKind.NETWORK, "连接失败，请重试")
                    2 -> {
                        @Suppress("UNCHECKED_CAST")
                        val captured = args.last() as Continuation<ApiResult<Page<ContentItem>>>
                        continuation = captured; COROUTINE_SUSPENDED
                    }
                    else -> ApiResult.Success(Page(listOf(ContentItem("fresh", "feed")), null))
                }
            } as CoolapkRepository
            val model = BrowseViewModel(repository); val key = "coolpic-list:hot:摄影"
            model.load(key); runCurrent(); assertNotNull(model.states.value.getValue(key).error)
            model.load(key, refresh = true); runCurrent(); model.cancelLinkedRead(key)
            assertFalse(model.states.value.getValue(key).loaded)
            model.load(key); runCurrent()
            assertEquals(3, calls)
            assertEquals("fresh", model.states.value.getValue(key).items.single().id)
            continuation!!.resume(ApiResult.Success(Page(emptyList(), null))); runCurrent()
            assertEquals("fresh", model.states.value.getValue(key).items.single().id)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun literalRulesKeepMarkdownSymbolsWhitespaceAndNoninteractiveUrlText() {
        val raw = "**literal  rule**\n[text](https://example.com/) <not markup>"
        val parsed = prepareEventLiteral(FullPostBody.Available(raw)) {} as PreparedFullPost.Ready
        assertEquals(raw, parsed.pages.joinToString("") { it.text })
        assertTrue(parsed.pages.all { it.links.isEmpty() })
    }
    @Test fun failedMoreKeepsRowsAndExactCursorForRetry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val calls = mutableListOf<PageCursor>()
            val repository = Proxy.newProxyInstance(CoolapkRepository::class.java.classLoader, arrayOf(CoolapkRepository::class.java)) { _, _, args ->
                val cursor = args[2] as PageCursor; calls += cursor
                if (calls.size == 2) ApiResult.Failure(FailureKind.NETWORK, "连接失败")
                else ApiResult.Success(Page(listOf(ContentItem(if (calls.size == 1) "first" else "tail", "feed")), if (calls.size == 1) PageCursor(2, "first", "first") else null))
            } as CoolapkRepository
            val model = BrowseViewModel(repository); val key = "dyh-list:square:55"
            model.load(key); runCurrent(); model.load(key, more = true); runCurrent()
            val failed = model.states.value.getValue(key)
            assertTrue(failed.moreError); assertEquals("first", failed.items.single().id)
            model.load(key, refresh = !failed.moreError, more = failed.moreError); runCurrent()
            assertEquals(listOf(PageCursor(), PageCursor(2, "first", "first"), PageCursor(2, "first", "first")), calls)
            assertEquals(listOf("first", "tail"), model.states.value.getValue(key).items.map { it.id })
        } finally { Dispatchers.resetMain() }
    }
    @Test fun owningHistoryRetainsAllFiniteSourceTabBookmarksUntilRouteLeavesStack() {
        assertEquals(setOf("dyh-list:all:55", "dyh-list:square:55", "coolpic-list:recommend:星空:2026", "coolpic-list:hot:星空:2026", "coolpic-list:newest:星空:2026"),
            retainedSourceListKeys(listOf("dyh:55", "coolpic:星空:2026", "feed:42")))
        assertTrue(retainedSourceListKeys(listOf("feed:42", "home")).isEmpty())
    }

}
