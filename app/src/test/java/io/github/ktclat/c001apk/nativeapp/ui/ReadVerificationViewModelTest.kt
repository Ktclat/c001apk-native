package io.github.ktclat.c001apk.nativeapp.ui

import io.github.ktclat.c001apk.nativeapp.domain.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadVerificationViewModelTest {
    private fun pending() = PendingPageRead(ApiChallenge.Netease("abcdefghijklmnop", "", "synthetic"), { true },
        { error("No image") }, { error("No graphic validation") }, { error("No retry expected") })
    private fun repository(result: () -> ApiResult<Page<ContentItem>>) = Proxy.newProxyInstance(
        CoolapkRepository::class.java.classLoader, arrayOf(CoolapkRepository::class.java)) { _, _, _ -> result() } as CoolapkRepository
    @Test fun leavingHomeAndInvalidatingRepliesCloseCapturedCapabilities() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var pending = pending()
            val model = BrowseViewModel(repository { ApiResult.Failure(FailureKind.CAPTCHA, "Synthetic", pageRead = pending) })
            model.load("home"); runCurrent(); model.leaveRead("home")
            assertEquals(DetailVerificationState.CLOSED, pending.state.value); assertNull(model.states.value["home"]?.failure)
            pending = pending(); val key = "ordered:42:POPULAR:false"
            model.load(key); runCurrent(); model.invalidateReplies("42")
            assertEquals(DetailVerificationState.CLOSED, pending.state.value); assertNull(model.states.value[key])
        } finally { Dispatchers.resetMain() }
    }
    @Test fun anotherRouteCannotConsumeOrPublishAnUnrelatedCapability() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val pending = pending()
            val model = BrowseViewModel(repository { ApiResult.Failure(FailureKind.CAPTCHA, "Synthetic", pageRead = pending) })
            model.load("home"); runCurrent(); assertTrue(pending.acceptHumanProof("NEC:abcdefgh:synthetic"))
            model.load("hot", refresh = true, pageRetry = pending); runCurrent()
            assertNull(model.states.value["hot"]); assertEquals(DetailVerificationState.VERIFIED, pending.state.value)
            model.leaveRead("home"); assertEquals(DetailVerificationState.CLOSED, pending.state.value)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun successfulEmptyHomeAndRepliesRemainLoadedAcrossDepartureAndReturn() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for (key in listOf("home", "ordered:42:POPULAR:false")) {
                var calls = 0
                val model = BrowseViewModel(repository { calls++; ApiResult.Success(Page(emptyList(), null)) })
                model.load(key); runCurrent(); assertEquals(1, calls)
                repeat(3) {
                    model.leaveRead(key)
                    val state = requireNotNull(model.states.value[key]); assertTrue(state.loaded); assertFalse(state.loading); assertTrue(state.items.isEmpty())
                    model.load(key); runCurrent(); assertEquals(1, calls)
                }
            }
        } finally { Dispatchers.resetMain() }
    }
    @Test fun departedEmptyChallengeAndFailureReloadButCannotReuseTheirCapability() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for (challenge in listOf(false, true)) {
                var calls = 0; val old = pending()
                val model = BrowseViewModel(repository { calls++; if (calls == 1)
                    ApiResult.Failure(if (challenge) FailureKind.CAPTCHA else FailureKind.NETWORK, "Synthetic", pageRead = old.takeIf { challenge })
                    else ApiResult.Success(Page(emptyList(), null)) })
                model.load("home"); runCurrent(); model.leaveRead("home")
                assertFalse(requireNotNull(model.states.value["home"]).loaded)
                if (challenge) assertEquals(DetailVerificationState.CLOSED, old.state.value)
                model.load("home"); runCurrent(); assertEquals(2, calls); assertTrue(requireNotNull(model.states.value["home"]).loaded)
            }
        } finally { Dispatchers.resetMain() }
    }
    @Test fun initialInflightDepartureRejectsLateCapabilityAndClosesItWithoutPublication() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var continuation: Continuation<ApiResult<Page<ContentItem>>>? = null
            val delayed = Proxy.newProxyInstance(CoolapkRepository::class.java.classLoader, arrayOf(CoolapkRepository::class.java)) { _, _, args ->
                @Suppress("UNCHECKED_CAST")
                val captured = args.last() as Continuation<ApiResult<Page<ContentItem>>>
                continuation = captured; COROUTINE_SUSPENDED
            } as CoolapkRepository
            val model = BrowseViewModel(delayed)
            model.load("home"); runCurrent(); assertTrue(requireNotNull(model.states.value["home"]).loading)
            model.leaveRead("home")
            assertFalse(requireNotNull(model.states.value["home"]).loading); assertFalse(requireNotNull(model.states.value["home"]).loaded)
            val late = pending()
            requireNotNull(continuation).resume(ApiResult.Failure(FailureKind.CAPTCHA, "Late synthetic", pageRead = late))
            runCurrent()
            assertEquals(DetailVerificationState.CLOSED, late.state.value)
            assertNull(model.states.value["home"]?.failure); assertFalse(requireNotNull(model.states.value["home"]).loaded)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun expiryPreventsOpeningHostBeforeAnyVerificationAction() {
        var now = 0L
        val pending = PendingPageRead(ApiChallenge.Netease("abcdefghijklmnop", ""), { true },
            { error("No image") }, { error("No validation") }, { error("No retry") }, { now })
        assertTrue(pending.canOpen()); now = 300001
        assertFalse(pending.canOpen()); assertEquals(DetailVerificationState.CLOSED, pending.state.value)
    }
}
