// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/** One user action at a time. An uncertain operation is terminal: there is no retry API. */
class MutationRunner {
    private val busy = AtomicBoolean(false)
    private val disposed = AtomicBoolean(false)
    private val mutableState = MutableStateFlow<DeliveryState>(DeliveryState.Ready)
    val state: StateFlow<DeliveryState> = mutableState.asStateFlow()
    private var acknowledged: (() -> Unit)? = null
    private var submissionCurrent: (() -> Boolean)? = null
    private fun releaseSubmission() { acknowledged = null; submissionCurrent = null }
    private fun ownsResult() = !disposed.get() && runCatching { submissionCurrent?.invoke() == true }.getOrDefault(false)
    private fun finish(outcome: WriteOutcome) {
        // IO completion is not UI ownership: an account switch can happen before Main resumes.
        if (!ownsResult()) { (outcome as? WriteOutcome.Rejected)?.continuation?.cancel(); releaseSubmission(); return }
        mutableState.value = DeliveryState.Finished(outcome)
        val callback = if (outcome is WriteOutcome.Confirmed) acknowledged else null
        if (outcome !is WriteOutcome.Rejected || outcome.continuation == null) releaseSubmission()
        runCatching { callback?.invoke() }
    }
    suspend fun submit(isCurrent: () -> Boolean = { true }, onConfirmed: () -> Unit = {}, action: suspend () -> WriteOutcome) {
        if (disposed.get() || !busy.compareAndSet(false, true)) return
        if (mutableState.value != DeliveryState.Ready || !isCurrent()) { busy.set(false); return }
        acknowledged = onConfirmed; submissionCurrent = isCurrent
        mutableState.value = DeliveryState.Sending
        try {
            val outcome = action()
            finish(outcome)
        } catch (e: CancellationException) {
            // A cancelled in-flight mutation may have reached the server. Never mark it failed/retryable.
            finish(WriteOutcome.Uncertain())
            throw e
        } catch (_: Exception) {
            finish(WriteOutcome.Uncertain())
        } finally { busy.set(false) }
    }
    suspend fun resumeChallenge(pending: PendingCaptcha) {
        if (disposed.get() || !busy.compareAndSet(false, true)) return
        val current = (mutableState.value as? DeliveryState.Finished)?.outcome as? WriteOutcome.Rejected
        if (current?.continuation !== pending) { busy.set(false); return }
        try {
            val outcome = pending.retryOnce { if (!disposed.get()) mutableState.value = DeliveryState.Sending }
            finish(outcome)
        } catch (e: CancellationException) { finish(WriteOutcome.Uncertain()); throw e }
        catch (_: Exception) { finish(WriteOutcome.Uncertain()) }
        finally { busy.set(false) }
    }
    /** Return to editing only after an explicit server rejection, never after uncertain delivery. */
    fun abandonChallenge(pending: PendingCaptcha): Boolean {
        val rejected = (mutableState.value as? DeliveryState.Finished)?.outcome as? WriteOutcome.Rejected ?: return false
        if (disposed.get() || busy.get() || rejected.continuation !== pending) return false
        pending.cancel(); releaseSubmission(); mutableState.value = DeliveryState.Ready
        return true
    }
    fun abandonRejected(): Boolean {
        val rejected = (mutableState.value as? DeliveryState.Finished)?.outcome as? WriteOutcome.Rejected ?: return false
        if (disposed.get() || busy.get()) return false
        rejected.continuation?.cancel(); releaseSubmission(); mutableState.value = DeliveryState.Ready
        return true
    }
    fun dispose() {
        disposed.set(true)
        ((mutableState.value as? DeliveryState.Finished)?.outcome as? WriteOutcome.Rejected)?.continuation?.cancel()
        releaseSubmission(); mutableState.value = DeliveryState.Ready
    }
    fun beginNext(): Boolean {
        if (disposed.get() || busy.get() || (mutableState.value as? DeliveryState.Finished)?.outcome !is WriteOutcome.Confirmed) return false
        mutableState.value = DeliveryState.Ready
        return true
    }
    /** Only for known rejection; captcha needs a separate verified human flow, not generic retry. */
    fun allowCorrection(): Boolean {
        val rejected = (mutableState.value as? DeliveryState.Finished)?.outcome as? WriteOutcome.Rejected ?: return false
        if (disposed.get() || busy.get() || rejected.kind == io.github.ktclat.c001apk.nativeapp.domain.FailureKind.CAPTCHA) return false
        mutableState.value = DeliveryState.Ready
        return true
    }
}
