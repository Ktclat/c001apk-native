// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

import io.github.ktclat.c001apk.nativeapp.account.HumanChallengeSpec
import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/** Explicitly pause the UI continuation after IO has completed, not a sleep-based race. */
class MutationOwnershipHandoffTest {
    private class PausedUi : CoroutineDispatcher() {
        private val work = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { work.add(block) }
        fun next(): Runnable = requireNotNull(work.poll(5, TimeUnit.SECONDS)) { "Expected UI continuation was not scheduled" }
    }
    private fun exercise(switchBeforeUiResumes: Boolean, result: WriteOutcome) = runBlocking {
        val current = AtomicBoolean(true); val replays = AtomicInteger(); val callbacks = AtomicInteger()
        val releaseReplay = CompletableDeferred<Unit>()
        val pending = PendingCaptcha(CaptchaPrompt.Netease(HumanChallengeSpec("414e5c9b866a03db03f860a1a9101672", "_v2_post_token")),
            isCurrent = { current.get() }, loadGraphic = { ApiResult.Failure(FailureKind.CAPTCHA, "unused") },
            validateGraphic = { ApiResult.Failure(FailureKind.CAPTCHA, "unused") },
            replay = { replays.incrementAndGet(); releaseReplay.await(); result })
        val runner = MutationRunner()
        runner.submit(isCurrent = { current.get() }, onConfirmed = { callbacks.incrementAndGet() }) {
            WriteOutcome.Rejected(FailureKind.CAPTCHA, "synthetic", pending)
        }
        assertTrue(pending.acceptHumanProof("NEC:414e5c9b:synthetic-human-proof"))
        val ui = PausedUi(); val scope = CoroutineScope(SupervisorJob() + ui)
        try {
            val job = scope.launch { runner.resumeChallenge(pending) }
            ui.next().run() // Start UI action; replay cannot complete until this runnable returns.
            releaseReplay.complete(Unit) // Force a genuine suspension before allowing real IO completion.
            val resumeUi = ui.next() // IO result is complete and queued, but not yet delivered to UI.
            assertEquals(1, replays.get())
            if (switchBeforeUiResumes) current.set(false)
            resumeUi.run(); job.join()
            assertEquals(if (!switchBeforeUiResumes && result is WriteOutcome.Confirmed) 1 else 0, callbacks.get())
            if (switchBeforeUiResumes) assertFalse(runner.state.value is DeliveryState.Finished)
            else assertSame(result, (runner.state.value as DeliveryState.Finished).outcome)
            for (name in listOf("acknowledged", "submissionCurrent")) {
                val field = runner.javaClass.getDeclaredField(name).apply { isAccessible = true }
                assertNull("Terminal handoff must release $name", field.get(runner))
            }
            runner.resumeChallenge(pending)
            assertEquals(1, replays.get())
        } finally { scope.cancel(); runner.dispose() }
    }
    @Test fun oldGenerationCannotRunConfirmationAfterIoSuccess() = exercise(true, WriteOutcome.Confirmed())
    @Test fun currentGenerationReceivesExactlyOneConfirmationAfterIoSuccess() = exercise(false, WriteOutcome.Confirmed())
    @Test fun oldGenerationCannotCommitUnknownDeliveryStateAfterIoCompletion() = exercise(true, WriteOutcome.Uncertain())
    @Test fun currentGenerationKeepsUnknownDeliveryTerminalWithoutConfirmation() = exercise(false, WriteOutcome.Uncertain())
}
