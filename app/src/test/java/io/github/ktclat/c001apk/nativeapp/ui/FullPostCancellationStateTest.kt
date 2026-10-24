// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import io.github.ktclat.c001apk.nativeapp.domain.*
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FullPostCancellationStateTest {
    @Test fun cancelledDetailClearsPendingOffersRetryAndDoesNotFabricateAvailability() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val calls = mutableListOf<Continuation<ApiResult<ContentItem>>>()
            val repository = Proxy.newProxyInstance(CoolapkRepository::class.java.classLoader,
                arrayOf(CoolapkRepository::class.java)) { _, method, args ->
                check(method.name == "feedDetail")
                @Suppress("UNCHECKED_CAST")
                calls += args.last() as Continuation<ApiResult<ContentItem>>
                COROUTINE_SUSPENDED
            } as CoolapkRepository
            val model = BrowseViewModel(repository)
            model.load("feed:42"); runCurrent()
            assertTrue(model.states.value.getValue("feed:42").loading)
            calls.single().resumeWithException(CancellationException("Synthetic session change")); runCurrent()
            val cancelled = model.states.value.getValue("feed:42")
            assertFalse(cancelled.loading); assertFalse(cancelled.loaded); assertTrue(cancelled.items.isEmpty())
            assertEquals("正文加载已取消，请重试", cancelled.error)
            model.load("feed:42", refresh = true); runCurrent()
            assertEquals(2, calls.size)
            val actual = "x".repeat(12_000) + "retry tail"
            calls.last().resume(ApiResult.Success(ContentItem("42", "feed", message = actual.take(12_000),
                bodyTruncated = true, fullPostBody = FullPostBody.Available(actual)))); runCurrent()
            assertEquals(actual, (model.states.value.getValue("feed:42").items.single().fullPostBody as FullPostBody.Available).html)
            assertNull(model.states.value.getValue("feed:42").error)
        } finally { Dispatchers.resetMain() }
    }
}
