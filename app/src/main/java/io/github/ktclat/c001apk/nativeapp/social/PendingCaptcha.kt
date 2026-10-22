// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

import io.github.ktclat.c001apk.nativeapp.account.HumanChallengeSpec
import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

sealed interface CaptchaPrompt {
    data object Graphic : CaptchaPrompt
    class Netease(val spec: HumanChallengeSpec) : CaptchaPrompt
}
enum class CaptchaProgress { AWAITING_HUMAN, LOADING, VERIFIED, REJECTED, CANCELLED, EXPIRED, CONSUMED }

/** One known-rejected immutable operation, one explicit retry. Proofs never enter StateFlow/logs/storage. */
class PendingCaptcha internal constructor(
    val prompt: CaptchaPrompt,
    isCurrent: () -> Boolean,
    loadGraphic: suspend () -> ApiResult<ByteArray>,
    validateGraphic: suspend (String) -> ApiResult<Unit>,
    replay: suspend (String?) -> WriteOutcome,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var currentCheck: (() -> Boolean)? = isCurrent
    private var imageLoader: (suspend () -> ApiResult<ByteArray>)? = loadGraphic
    private var graphicValidator: (suspend (String) -> ApiResult<Unit>)? = validateGraphic
    private var replayAction: (suspend (String?) -> WriteOutcome)? = replay
    private fun release() { currentCheck = null; imageLoader = null; graphicValidator = null; replayAction = null; proof = null }
    internal fun retainsOperation(): Boolean = currentCheck != null || imageLoader != null || graphicValidator != null || replayAction != null
    private val deadline = clock() + 5 * 60_000
    private val busy = AtomicBoolean(false)
    private val consumed = AtomicBoolean(false)
    private val mutableProgress = MutableStateFlow(CaptchaProgress.AWAITING_HUMAN)
    val progress: StateFlow<CaptchaProgress> = mutableProgress.asStateFlow()
    private var proof: String? = null
    override fun toString() = "PendingCaptcha(redacted)"
    private fun active(): Boolean {
        if (consumed.get()) return false
        if (clock() > deadline || !runCatching { currentCheck?.invoke() == true }.getOrDefault(false)) {
            consumed.set(true); release(); mutableProgress.value = CaptchaProgress.EXPIRED
            return false
        }
        return true
    }
    suspend fun image(): ApiResult<ByteArray> = withContext(Dispatchers.IO) {
        if (prompt != CaptchaPrompt.Graphic || !active() || !busy.compareAndSet(false, true)) return@withContext unavailable()
        mutableProgress.value = CaptchaProgress.LOADING
        try {
            val result = imageLoader?.invoke() ?: return@withContext unavailable()
            if (!active()) return@withContext unavailable()
            mutableProgress.value = CaptchaProgress.AWAITING_HUMAN
            result
        } catch (e: CancellationException) { cancel(); throw e }
        catch (_: Exception) { consumed.set(true); release(); mutableProgress.value = CaptchaProgress.REJECTED; unavailable() }
        finally { busy.set(false) }
    }
    /** This submits only the human-typed graphic answer, never the rejected original operation. */
    suspend fun verifyGraphic(code: String): Boolean = withContext(Dispatchers.IO) {
        if (prompt != CaptchaPrompt.Graphic || !Regex("[A-Za-z0-9]{4}").matches(code) || !active() ||
            !busy.compareAndSet(false, true)) return@withContext false
        mutableProgress.value = CaptchaProgress.LOADING
        try {
            val result = graphicValidator?.invoke(code) ?: return@withContext false
            if (!active()) return@withContext false
            if (result is ApiResult.Success) { mutableProgress.value = CaptchaProgress.VERIFIED; true }
            else { consumed.set(true); release(); mutableProgress.value = CaptchaProgress.REJECTED; false }
        } catch (e: CancellationException) { cancel(); throw e }
        catch (_: Exception) { consumed.set(true); release(); mutableProgress.value = CaptchaProgress.REJECTED; false }
        finally { busy.set(false) }
    }
    /** Provider proof comes exclusively from the visible shared HumanVerification host. */
    suspend fun acceptHumanProof(value: String): Boolean = withContext(Dispatchers.IO) {
        val challenge = prompt as? CaptchaPrompt.Netease ?: return@withContext false
        if (!active() || mutableProgress.value != CaptchaProgress.AWAITING_HUMAN || !challenge.spec.accepts(value) || !busy.compareAndSet(false, true)) return@withContext false
        try { proof = value; mutableProgress.value = CaptchaProgress.VERIFIED; true } finally { busy.set(false) }
    }
    suspend fun retryOnce(onStarted: () -> Unit = {}): WriteOutcome = withContext(Dispatchers.IO) {
        if (!active() || mutableProgress.value != CaptchaProgress.VERIFIED || !consumed.compareAndSet(false, true))
            return@withContext WriteOutcome.Rejected(FailureKind.CAPTCHA, "验证已失效或已使用，未重复发送")
        val action = replayAction
        val submittedProof = proof; release(); mutableProgress.value = CaptchaProgress.CONSUMED
        if (action == null) return@withContext WriteOutcome.Rejected(FailureKind.CAPTCHA, "验证已取消，原操作未重发")
        onStarted()
        try {
            val result = action(submittedProof)
            // A second challenge cannot create a verification/replay loop.
            if (result is WriteOutcome.Rejected) result.copy(continuation = null) else result
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { WriteOutcome.Uncertain() }
    }
    fun cancel() { consumed.set(true); release(); mutableProgress.value = CaptchaProgress.CANCELLED }
    private fun unavailable() = ApiResult.Failure(FailureKind.CAPTCHA, "验证已取消、过期或暂时不可用，原操作未重试")
}
