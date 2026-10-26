// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Existing account-read continuation, shared only by explicitly admitted GET readers.
 * The source supplier is invoked once; social callers supply an already-bound immutable session.
 * Read memory belongs to the caller and is never installed on a login or mutation transport.
 * Existing personal/count readers keep the direct default. Notice routes additionally serialize
 * only cache mutations with cancellation, without retaining route liveness in accepted memory.
 */
internal suspend fun <T> accountReadContinuation(
    uid: String,
    isCurrentAccount: () -> Boolean,
    requestSession: (() -> RequestSession)?,
    transport: CoolapkHttpTransport,
    readSessions: ReadSessionMemory,
    cacheMutation: ((() -> Boolean) -> Boolean)? = null,
    attach: (ApiResult.Failure, PendingRead<T>) -> ApiResult.Failure,
    read: suspend (CoolapkHttpTransport) -> ApiResult<T>,
): ApiResult<T> = withContext(Dispatchers.IO) {
    if (!isCurrentAccount()) throw CancellationException("Account changed")
    if (!(uid.isNotEmpty() && uid.all { it in '0'..'9' })) return@withContext failure(FailureKind.INVALID_ARGUMENT)
    val raw = requestSession?.invoke() ?: return@withContext failure(FailureKind.AUTHENTICATION)
    if (raw.isGuest || raw.isSessionCurrent == null) return@withContext failure(FailureKind.AUTHENTICATION)
    fun current() = isCurrentAccount() && raw.isCurrent() &&
        (raw.isReadSessionCurrent ?: raw.isSessionCurrent).invoke()
    if (!current()) throw CancellationException("Session changed")
    val scope = readSessions.capture(raw, cacheMutation)
    // Unlike public reads, these account-only transports must recheck raw revision
    // and the root UID/generation guard at dispatch, not merely before suspension.
    val operation = transport.forCapturedReadSession(scope.session.copy(isCurrent = { current() }))
    if (!current()) throw CancellationException("Session changed")
    val result = read(operation)
    // Keep the pre-existing strict raw-cookie-revision publication contract. An accepted
    // private read-cache SESSID rotation need not invalidate its own decoded response.
    if (!current()) throw CancellationException("Session changed")
    if (result !is ApiResult.Failure) return@withContext result
    if (result.kind == FailureKind.CAPTCHA) readSessions.revokeOnChallenge(scope)
    val challenge = result.challenge?.takeIf { operation.isCaptchaContinuationCurrent(it) }
        ?: return@withContext result
    val pending = PendingRead(challenge,
        isCurrent = { current() && operation.isCaptchaContinuationCurrent(challenge) },
        image = { operation.getCaptchaImage() }, verify = { operation.validateGraphicCaptcha(it) },
        retry = { proof ->
            if (!current()) throw CancellationException("Account changed")
            val verified = when (challenge) {
                is ApiChallenge.Graphic -> operation.withGraphicVerification(challenge)
                is ApiChallenge.Netease -> operation.withCaptchaProof(challenge, proof.orEmpty())
            }
            val retried = read(verified)
            if (!current() || !operation.isReadVerificationResultCurrent(challenge)) throw CancellationException("Session changed")
            if (retried is ApiResult.Success) {
                // Snapshot the private overlay before the memory/cache-commit locks.
                // The optional route monitor encloses final assignments only, not callbacks.
                val cookies = operation.readVerificationCookies(challenge)
                if (!readSessions.publish(scope, cookies,
                        proof?.takeIf { challenge is ApiChallenge.Netease && challenge.field.isEmpty() }))
                    throw CancellationException("Read session changed")
            }
            retried
        }, onClosed = { operation.closeReadVerification(challenge) })
    attach(result, pending)
}
