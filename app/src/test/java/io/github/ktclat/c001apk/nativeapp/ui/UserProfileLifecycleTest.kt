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
class UserProfileLifecycleTest {
    @Test fun separateProfileAndFeedReadsPreserveIdentityOnEmptyFailurePaginationAndBack() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val calls = mutableListOf<Pair<String, List<Any?>>>()
            val model = BrowseViewModel(repository { name, args ->
                calls += name to args.dropLast(1)
                when (name) {
                    "userProfile" -> ApiResult.Success(profile("7"))
                    "userFeed" -> if (calls.count { it.first == "userFeed" } == 1) ApiResult.Success(Page(emptyList<ContentItem>(), null))
                        else ApiResult.Failure(FailureKind.NETWORK, "加载失败，请重试")
                    else -> error(name)
                }
            })
            model.load("user-profile:7"); model.load("user:7"); runCurrent()
            assertEquals(listOf("userProfile", "userFeed"), calls.map { it.first })
            assertEquals("7", model.states.value.getValue("user-profile:7").items.single().userId)
            model.leaveRead("user-profile:7"); model.leaveRead("user:7")
            model.load("user-profile:7"); model.load("user:7"); runCurrent(); assertEquals(2, calls.size)
            model.load("user:7", refresh = true); runCurrent()
            assertNotNull(model.states.value.getValue("user:7").error)
            assertEquals("7", model.states.value.getValue("user-profile:7").items.single().userId)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun departedNonCooperativeProfileCannotOverwriteNewVisitOrAnotherUser() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val pending = mutableListOf<Continuation<ApiResult<ContentItem>>>()
            val model = BrowseViewModel(repository { method, args ->
                assertEquals("userProfile", method)
                @Suppress("UNCHECKED_CAST")
                pending += args.last() as Continuation<ApiResult<ContentItem>>
                COROUTINE_SUSPENDED
            })
            model.load("user-profile:7"); runCurrent(); model.leaveRead("user-profile:7")
            model.load("user-profile:8"); runCurrent()
            pending[1].resume(ApiResult.Success(profile("8"))); runCurrent()
            model.load("user-profile:7"); runCurrent()
            pending[2].resume(ApiResult.Success(profile("7").copy(userName = "fresh"))); runCurrent()
            pending[0].resume(ApiResult.Success(profile("7").copy(userName = "stale"))); runCurrent()
            assertEquals("fresh", model.states.value.getValue("user-profile:7").items.single().userName)
            assertEquals("8", model.states.value.getValue("user-profile:8").items.single().userId)
        } finally { Dispatchers.resetMain() }
    }
    private fun profile(uid: String) = ContentItem(uid, "user", userId = uid, userProfile = UserSpaceProfile())
    private fun repository(answer: (String, Array<out Any?>) -> Any?): CoolapkRepository = Proxy.newProxyInstance(
        CoolapkRepository::class.java.classLoader, arrayOf(CoolapkRepository::class.java)) { _, method, args -> answer(method.name, args.orEmpty()) } as CoolapkRepository
}
