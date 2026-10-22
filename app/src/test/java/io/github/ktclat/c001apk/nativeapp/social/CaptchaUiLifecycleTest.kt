// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CaptchaUiLifecycleTest {
    @get:Rule val compose = createComposeRule()
    @Test fun explicitRetrySurvivesChallengeUiDisposalAndUpdatesOriginalAction() {
        val release = CompletableDeferred<Unit>(); val calls = AtomicInteger(); val callbacks = AtomicInteger()
        val pending = PendingCaptcha(CaptchaPrompt.Graphic, { true }, { ApiResult.Success(byteArrayOf()) },
            { ApiResult.Success(Unit) }, { calls.incrementAndGet(); release.await(); WriteOutcome.Confirmed("1") })
        val runner = MutationRunner()
        runBlocking {
            runner.submit(onConfirmed = { callbacks.incrementAndGet() }) { WriteOutcome.Rejected(FailureKind.CAPTCHA, "verify", pending) }
            assertTrue(pending.verifyGraphic("1234"))
        }
        compose.setContent { MaterialTheme { val state by runner.state.collectAsState(); MutationStatus(state, runner) } }
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
            (runner.state.value as? DeliveryState.Finished)?.outcome is WriteOutcome.Confirmed &&
                compose.onNodeWithText("服务端已确认").isDisplayed()
        } } catch (failure: Throwable) {
            val root = runCatching { compose.onRoot().fetchSemanticsNode().boundsInRoot }.getOrNull()
            val result = runCatching { compose.onNodeWithText("服务端已确认").fetchSemanticsNode().boundsInRoot }.getOrNull()
            throw AssertionError("Confirmed UI not visible: runner=${runner.state.value}, root=$root, result=$result, callbacks=${callbacks.get()}, calls=${calls.get()}", failure)
        }
        compose.onNodeWithText("服务端已确认").assertIsDisplayed()
        assertEquals(1, callbacks.get()); assertEquals(1, calls.get())
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
        compose.onNodeWithText("keep this draft").assertIsEnabled()
    }
}
