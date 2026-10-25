// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume

@OptIn(ExperimentalCoroutinesApi::class)
class PublicCollectionLifecycleTest {
    @Test fun collectionReadDispatchesExactIdCursorAndKeepsDistinctSourceEntityIds() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val calls = mutableListOf<List<Any?>>()
            val repo = repository { method, args ->
                assertEquals("collectionItems", method); calls += args.dropLast(1)
                ApiResult.Success(Page(listOf(ContentItem("0", "card", entityTemplate = "iconLinkGridCard", sourceEntityId = "card-${calls.size}")),
                    if (calls.size == 1) PageCursor(2, "first", "last") else null))
            }
            val model = BrowseViewModel(repo)
            model.load("collection:2001"); runCurrent(); model.load("collection:2001", more = true); runCurrent()
            assertEquals(listOf(listOf("2001", PageCursor()), listOf("2001", PageCursor(2, "first", "last"))), calls)
            assertEquals(listOf("card-1", "card-2"), model.states.value.getValue("collection:2001").items.map { it.sourceEntityId })
        } finally { Dispatchers.resetMain() }
    }
    @Test fun routeDepartureAndNewReadRejectOldNonCooperativeCompletion() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val continuations = mutableListOf<Continuation<ApiResult<Page<ContentItem>>>>()
            val repo = repository { method, args ->
                assertEquals("collectionItems", method)
                @Suppress("UNCHECKED_CAST")
                continuations += args.last() as Continuation<ApiResult<Page<ContentItem>>>
                COROUTINE_SUSPENDED
            }
            val model = BrowseViewModel(repo); val key = "collection:2001"
            model.load(key); runCurrent(); model.cancelLinkedRead(key)
            assertFalse(model.states.value.getValue(key).loading)
            model.load(key); runCurrent(); assertEquals(2, continuations.size)
            continuations[1].resume(ApiResult.Success(Page(listOf(ContentItem("fresh", "feed")), null))); runCurrent()
            continuations[0].resume(ApiResult.Success(Page(listOf(ContentItem("stale", "feed")), null))); runCurrent()
            assertEquals(listOf("fresh"), model.states.value.getValue(key).items.map { it.id })
        } finally { Dispatchers.resetMain() }
    }
    @Test fun repeatedListWrapperAcrossPagesDoesNotDiscardNewChildren() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var page = 0
            val model = BrowseViewModel(repository { _, _ ->
                page++
                ApiResult.Success(Page(listOf(ContentItem("group", "card", entityTemplate = "listCard", sourceEntityId = "group",
                    children = listOf(ContentItem("feed-$page", "feed")))), if (page == 1) PageCursor(2, "group", "group") else null))
            })
            val key = "collection:2001"
            model.load(key); runCurrent(); model.load(key, more = true); runCurrent()
            val raw = model.states.value.getValue(key).items
            assertEquals(listOf("group", "group"), raw.map { it.id })
            assertEquals(listOf("feed-1", "feed-2"), contentListItems(raw).map { it.id })
        } finally { Dispatchers.resetMain() }
    }
    @Test fun emptyAndPrivateFailureAreDistinctWithoutFakeContentOrLoginMutation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var response: ApiResult<Page<ContentItem>> = ApiResult.Success(Page(emptyList(), null))
            val model = BrowseViewModel(repository { method, _ -> assertEquals("collectionItems", method); response })
            val key = "collection:2001"
            model.load(key); runCurrent()
            assertTrue(model.states.value.getValue(key).loaded); assertNull(model.states.value.getValue(key).error)
            response = ApiResult.Failure(FailureKind.AUTHENTICATION, "此内容需要登录，请登录后重试")
            model.load(key, refresh = true); runCurrent()
            val failed = model.states.value.getValue(key)
            assertEquals(FailureKind.AUTHENTICATION, failed.failure?.kind)
            assertTrue(failed.items.isEmpty()); assertNotNull(failed.error)
        } finally { Dispatchers.resetMain() }
    }
    private fun repository(answer: (String, Array<out Any?>) -> Any?): CoolapkRepository = Proxy.newProxyInstance(
        CoolapkRepository::class.java.classLoader, arrayOf(CoolapkRepository::class.java)) { _, method, args -> answer(method.name, args.orEmpty()) } as CoolapkRepository
}
