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
    private val mutableState = MutableStateFlow<DeliveryState>(DeliveryState.Ready)
    val state: StateFlow<DeliveryState> = mutableState.asStateFlow()
    suspend fun submit(isCurrent: () -> Boolean = { true }, action: suspend () -> WriteOutcome) {
        if (!busy.compareAndSet(false, true)) return
        if (mutableState.value != DeliveryState.Ready || !isCurrent()) { busy.set(false); return }
        mutableState.value = DeliveryState.Sending
        try {
            val outcome = action()
            if (isCurrent()) mutableState.value = DeliveryState.Finished(outcome)
        } catch (e: CancellationException) {
            // A cancelled in-flight mutation may have reached the server. Never mark it failed/retryable.
            if (isCurrent()) mutableState.value = DeliveryState.Finished(WriteOutcome.Uncertain())
            throw e
        } catch (_: Exception) {
            if (isCurrent()) mutableState.value = DeliveryState.Finished(WriteOutcome.Uncertain())
        } finally { busy.set(false) }
    }
    fun beginNext(): Boolean {
        if (busy.get() || (mutableState.value as? DeliveryState.Finished)?.outcome !is WriteOutcome.Confirmed) return false
        mutableState.value = DeliveryState.Ready
        return true
    }
    /** Only for known rejection; captcha needs a separate verified human flow, not generic retry. */
    fun allowCorrection(): Boolean {
        val rejected = (mutableState.value as? DeliveryState.Finished)?.outcome as? WriteOutcome.Rejected ?: return false
        if (busy.get() || rejected.kind == io.github.ktclat.c001apk.nativeapp.domain.FailureKind.CAPTCHA) return false
        mutableState.value = DeliveryState.Ready
        return true
    }
}
