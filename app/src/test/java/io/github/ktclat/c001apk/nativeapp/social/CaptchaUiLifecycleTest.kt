// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class CaptchaUiLifecycleTest {
    @get:Rule val compose = createComposeRule(effectContext = StandardTestDispatcher())
    private val retryOwner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @After fun closeRetryOwner() { retryOwner.cancel() }
    @Test fun explicitRetrySurvivesChallengeUiDisposalAndUpdatesOriginalAction() {
        val uiThread = compose.runOnIdle { Thread.currentThread() }
        val threadEvidence = CopyOnWriteArrayList<String>()
        fun recordThread(phase: String) {
            val current = Thread.currentThread()
            val main = Looper.getMainLooper()?.thread
            threadEvidence.add("$phase: current=${current.name}/${current.id}, owner=${uiThread.name}/${uiThread.id}, " +
                "mainLooperThread=${main?.name}/${main?.id}, currentIsOwner=${current === uiThread}, " +
                "currentIsMainLooperThread=${current === main}, myLooperIsMain=${Looper.myLooper() === Looper.getMainLooper()}, " +
                "looperMode=${ShadowLooper.looperMode()}")
        }
        recordThread("fixture")
        val replayOnIo = AtomicBoolean(false)
        val release = CompletableDeferred<Unit>(); val calls = AtomicInteger(); val callbacks = AtomicInteger()
        val replayOffMain = AtomicBoolean(false); val callbackOnMain = AtomicBoolean(false)
        val confirmedUiOnMain = AtomicBoolean(false)
        val runner = MutationRunner()
        try {
            val pending = PendingCaptcha(CaptchaPrompt.Graphic, { true }, { ApiResult.Success(byteArrayOf()) },
                { ApiResult.Success(Unit) }, {
                    recordThread("replay")
                    val replayContext = currentCoroutineContext()
                    val replayInterceptor = replayContext[ContinuationInterceptor]
                    threadEvidence.add("replayContext=$replayContext; interceptorClass=${replayInterceptor?.javaClass?.name}; " +
                        "ioClass=${Dispatchers.IO.javaClass.name}; io=${Dispatchers.IO}")
                    replayOffMain.set(Thread.currentThread() !== uiThread)
                    replayOnIo.set(currentCoroutineContext()[ContinuationInterceptor] === Dispatchers.IO)
                    calls.incrementAndGet(); release.await(); WriteOutcome.Confirmed("1")
                })
            runBlocking {
                runner.submit(onConfirmed = { recordThread("callback"); callbackOnMain.set(Thread.currentThread() === uiThread); callbacks.incrementAndGet() }) { WriteOutcome.Rejected(FailureKind.CAPTCHA, "verify", pending) }
                assertTrue(pending.verifyGraphic("1234"))
            }
            compose.setContent { MaterialTheme {
                val state by runner.state.collectAsState()
                if ((state as? DeliveryState.Finished)?.outcome is WriteOutcome.Confirmed) {
                    SideEffect { recordThread("confirmed-ui"); confirmedUiOnMain.set(Thread.currentThread() === uiThread) }
                }
                // Exercise the supported caller-owned retry path, as the composer does. Compose
                // 1.7.6's test-only ApplyingContinuationInterceptor can otherwise intercept even IO.
                // This fixture does not claim to validate the default rememberCoroutineScope path.
                MutationStatus(state, runner, onChallengeRetry = { challenge ->
                    retryOwner.launch { runner.resumeChallenge(challenge) }
                })
            } }
            val retryBounds = compose.onNodeWithText("重试原操作一次").fetchSemanticsNode().boundsInRoot
            val abandonBounds = compose.onNodeWithText("放弃验证，返回编辑").fetchSemanticsNode().boundsInRoot
            assertTrue("Retry and abandon targets must not overlap", retryBounds.bottom <= abandonBounds.top)
            compose.onNodeWithText("重试原操作一次").performClick()
            try { compose.waitUntil(5_000) { calls.get() == 1 } }
            catch (failure: Throwable) {
                throw AssertionError("Retry did not start: progress=${pending.progress.value}, runner=${runner.state.value}, calls=${calls.get()}, callbacks=${callbacks.get()}", failure)
            }
            compose.waitForIdle()
            assertEquals(DeliveryState.Sending, runner.state.value)
            release.complete(Unit)
            // IO completion precedes StateFlow collection and layout. Await the actual visible result,
            // not only the repository state; retain the same bound and the visible-output assertion.
            try { compose.waitUntil(5_000) {
                // Always synchronize real UI work, even while the Main callback is still queued.
                compose.onNodeWithText("服务端已确认").isDisplayed() &&
                    (runner.state.value as? DeliveryState.Finished)?.outcome is WriteOutcome.Confirmed
            } } catch (failure: Throwable) {
                val root = runCatching { compose.onRoot().fetchSemanticsNode().boundsInRoot }.getOrNull()
                val result = runCatching { compose.onNodeWithText("服务端已确认").fetchSemanticsNode().boundsInRoot }.getOrNull()
                throw AssertionError("Confirmed UI not visible: runner=${runner.state.value}, root=$root, result=$result, callbacks=${callbacks.get()}, calls=${calls.get()}", failure)
            }
            compose.onNodeWithText("服务端已确认").assertIsDisplayed()
            assertEquals(1, callbacks.get()); assertEquals(1, calls.get())
            val evidence = threadEvidence.joinToString("\n")
            assertTrue("Replay must remain off Main: $evidence", replayOffMain.get())
            assertTrue("Replay must retain the real IO dispatcher: $evidence", replayOnIo.get())
            assertTrue("Confirmation callback must return to Main: $evidence", callbackOnMain.get())
            assertTrue("Confirmed UI composition must run on Main: $evidence", confirmedUiOnMain.get())
        } finally {
            File("build/verification/captcha-threads").apply { mkdirs() }
                .resolve("explicit-retry.txt").writeText(threadEvidence.joinToString("\n") +
                    "\nreplayOnIo=${replayOnIo.get()}, replayOffMain=${replayOffMain.get()}, " +
                    "callbackOnMain=${callbackOnMain.get()}, confirmedUiOnMain=${confirmedUiOnMain.get()}, " +
                    "runner=${runner.state.value}, calls=${calls.get()}, callbacks=${callbacks.get()}\n")
        }
    }
    @Test fun abandonCancelledVerificationPreservesDraftAndDoesNotSend() {
        val calls = AtomicInteger()
        val pending = PendingCaptcha(CaptchaPrompt.Graphic, { true }, { ApiResult.Success(byteArrayOf()) },
            { ApiResult.Success(Unit) }, { calls.incrementAndGet(); WriteOutcome.Confirmed() })
        val runner = MutationRunner()
        runBlocking { runner.submit { WriteOutcome.Rejected(FailureKind.CAPTCHA, "verify", pending) } }
        pending.cancel()
        compose.setContent { MaterialTheme {
            val state by runner.state.collectAsState(); var draft by remember { mutableStateOf("keep this draft") }
            Column { OutlinedTextField(draft, { draft = it }, enabled = state == DeliveryState.Ready); MutationStatus(state, runner) }
        } }
        compose.onNodeWithText("keep this draft").assertIsNotEnabled()
        compose.onNodeWithText("放弃验证，返回编辑").performClick()
        compose.waitForIdle()
        assertEquals(DeliveryState.Ready, runner.state.value); assertEquals(0, calls.get())
        // Scheduled StateFlow collection can trail the model; await the actual editable UI.
        compose.waitUntil(5_000) {
            isEnabled().matches(compose.onNodeWithText("keep this draft").fetchSemanticsNode())
        }
        compose.onNodeWithText("keep this draft").assertIsEnabled()
    }
}
