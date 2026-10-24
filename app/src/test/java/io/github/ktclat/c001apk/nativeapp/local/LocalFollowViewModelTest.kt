// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.local

import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class LocalFollowViewModelTest {
    private val dispatcher=StandardTestDispatcher()
    @Before fun prepare() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    @Test fun repeatedRootConfigurationDoesNotReloadAndBlacklistRebuilds() = runTest(dispatcher) {
        var calls=0
        val model=LocalFollowViewModel { uid, _ -> calls++; ApiResult.Success(Page(listOf(ContentItem("1","feed",userId=uid,topicTitle="Android")),null)) }
        model.configure(listOf("42"),Blacklist()); advanceUntilIdle()
        assertEquals(1,calls); assertEquals(1,model.state.value.feeds.size)
        repeat(30) { model.configure(listOf("42"),Blacklist()) }; advanceUntilIdle()
        assertEquals(1,calls)
        model.configure(listOf("42"),Blacklist(topicBlackList=listOf("Android"))); advanceUntilIdle()
        assertEquals(2,calls); assertTrue(model.state.value.feeds.isEmpty()); assertTrue(model.state.value.end)
    }
    @Test fun failedLoadIsNotFalseEmptyEndAndCanRetry() = runTest(dispatcher) {
        var fail=true
        val model=LocalFollowViewModel { uid, _ -> if(fail) ApiResult.Failure(FailureKind.NETWORK,"失败") else ApiResult.Success(Page(listOf(ContentItem("1","feed",userId=uid)),null)) }
        model.configure(listOf("42"),Blacklist()); advanceUntilIdle()
        assertFalse(model.state.value.end); assertNotNull(model.state.value.warning)
        fail=false; model.loadMore(); advanceUntilIdle()
        assertEquals(1,model.state.value.feeds.size); assertTrue(model.state.value.end); assertNull(model.state.value.warning)
    }
}
