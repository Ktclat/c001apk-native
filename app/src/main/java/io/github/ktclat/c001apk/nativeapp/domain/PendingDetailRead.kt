// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

enum class DetailVerificationState { AWAITING_HUMAN, LOADING, VERIFIED, CLOSED }

/** One API read, one user-completed proof, one explicit retry. Nothing is persisted or logged. */
class PendingRead<T> internal constructor(
    val challenge: ApiChallenge,
    isCurrent: () -> Boolean,
    image: suspend () -> ApiResult<ByteArray>,
    verify: suspend (String) -> ApiResult<Unit>,
    retry: suspend (String?) -> ApiResult<T>,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    onClosed: () -> Unit = {},
) {
    private var currentCheck: (() -> Boolean)? = isCurrent
    private var imageAction: (suspend () -> ApiResult<ByteArray>)? = image
    private var verifyAction: (suspend (String) -> ApiResult<Unit>)? = verify
    private var retryAction: (suspend (String?) -> ApiResult<T>)? = retry
    private var proof: String? = null
    private var closeAction: (() -> Unit)? = onClosed
    private val deadline = clock() + 300_000
    private val closed = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)
    private val mutable = MutableStateFlow(DetailVerificationState.AWAITING_HUMAN)
    val state = mutable.asStateFlow()
    override fun toString() = "PendingDetailRead(redacted)"
    private fun active(): Boolean {
        if (closed.get()) return false
        if (clock() > deadline || currentCheck?.invoke() != true) { cancel(); return false }
        return true
    }
    @Synchronized fun canOpen(): Boolean = active() && mutable.value == DetailVerificationState.AWAITING_HUMAN
    @Synchronized private fun release() { proof = null; currentCheck = null; imageAction = null; verifyAction = null; retryAction = null }
    @Synchronized fun cancel() { closed.set(true); release(); closeAction?.invoke(); closeAction = null; mutable.value = DetailVerificationState.CLOSED }
    private fun unavailable() = ApiResult.Failure(FailureKind.CAPTCHA, "验证已取消或失效，请重新加载内容")
    suspend fun image(): ApiResult<ByteArray> = withContext(Dispatchers.IO) {
        synchronized(this@PendingRead) {
            if (challenge !is ApiChallenge.Graphic || !active() || !busy.compareAndSet(false, true)) return@withContext unavailable()
            mutable.value = DetailVerificationState.LOADING
        }
        try {
            val response = imageAction?.invoke() ?: return@withContext unavailable()
            synchronized(this@PendingRead) {
                if (!active()) { (response as? ApiResult.Success)?.value?.fill(0); return@withContext unavailable() }
                mutable.value = DetailVerificationState.AWAITING_HUMAN
                response
            }
        } catch (e: CancellationException) { cancel(); throw e }
        catch (_: Exception) { cancel(); unavailable() }
        finally { busy.set(false) }
    }
    suspend fun verifyGraphic(code: String): Boolean = withContext(Dispatchers.IO) {
        synchronized(this@PendingRead) {
            if (challenge !is ApiChallenge.Graphic || !Regex("^[A-Za-z0-9]{4}$").matches(code) || !active() || !busy.compareAndSet(false, true)) return@withContext false
            mutable.value = DetailVerificationState.LOADING
        }
        try {
            val response = verifyAction?.invoke(code)
            synchronized(this@PendingRead) {
                if (!active()) return@withContext false
                if (response is ApiResult.Success) { mutable.value = DetailVerificationState.VERIFIED; true }
                else { cancel(); false }
            }
        } catch (e: CancellationException) { cancel(); throw e }
        catch (_: Exception) { cancel(); false }
        finally { busy.set(false) }
    }
    @Synchronized fun acceptHumanProof(value: String): Boolean {
        val spec = challenge as? ApiChallenge.Netease ?: return false
        if (!active() || mutable.value != DetailVerificationState.AWAITING_HUMAN || !spec.acceptsProof(value)) return false
        proof = value; mutable.value = DetailVerificationState.VERIFIED; return true
    }
    suspend fun retryOnce(): ApiResult<T> = withContext(Dispatchers.IO) {
        val action: suspend (String?) -> ApiResult<T>
        val submitted: String?
        val close: (() -> Unit)?
        synchronized(this@PendingRead) {
            if (!active() || mutable.value != DetailVerificationState.VERIFIED || !closed.compareAndSet(false, true)) return@withContext unavailable()
            action = retryAction ?: return@withContext unavailable()
            submitted = proof; close = closeAction; closeAction = null; release(); mutable.value = DetailVerificationState.CLOSED
        }
        try { action(submitted) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { ApiResult.Failure(FailureKind.NETWORK, "读取重试失败，请重新加载内容") }
        finally { close?.invoke() }
    }
}

/** Read capabilities never authorize social mutations or login. */
typealias PendingDetailRead = PendingRead<ContentItem>
typealias PendingPageRead = PendingRead<Page<ContentItem>>

/** Account-required read capabilities; never accepted by mutation/login adapters. */
typealias PendingPersonalRead = PendingRead<Page<PersonalEntry>>
typealias PendingCountsRead = PendingRead<NotificationCounts>

/** Notice-list reads only; never a conversation, acknowledgement, delete or send capability. */
typealias PendingNoticeRead = PendingRead<Page<io.github.ktclat.c001apk.nativeapp.social.SocialRow>>
