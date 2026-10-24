// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

import io.github.ktclat.c001apk.nativeapp.account.HumanChallengeSpec
import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PendingCaptchaTest {
    @Test fun humanVerificationNeverAutomaticallyReplays() = runBlocking {
        var calls = 0; var validations = 0
        val pending = PendingCaptcha(CaptchaPrompt.Graphic, { true }, { ApiResult.Success(byteArrayOf(1)) },
            { validations++; ApiResult.Success(Unit) }, { calls++; WriteOutcome.Confirmed("1") })
        assertTrue(pending.verifyGraphic("aB12")); assertEquals(1, validations); assertEquals(0, calls)
        assertEquals(WriteOutcome.Confirmed("1"), pending.retryOnce()); assertEquals(1, calls)
        assertTrue(pending.retryOnce() is WriteOutcome.Rejected); assertEquals(1, calls)
    }
    @Test fun wrongOrUncertainGraphicValidationCannotReplay() = runBlocking {
        var calls = 0; var validations = 0
        val pending = PendingCaptcha(CaptchaPrompt.Graphic, { true }, { ApiResult.Success(byteArrayOf()) },
            { validations++; ApiResult.Failure(FailureKind.UNCERTAIN, "unknown") }, { calls++; WriteOutcome.Confirmed() })
        assertFalse(pending.verifyGraphic("notfour")); assertEquals(0, validations)
        assertFalse(pending.verifyGraphic("1234")); assertEquals(1, validations)
        assertTrue(pending.retryOnce() is WriteOutcome.Rejected); assertEquals(0, calls)
    }
    @Test fun accountOrSessionChangeDiscardsVerifiedProof() = runBlocking {
        var current = true; var calls = 0
        val pending = PendingCaptcha(CaptchaPrompt.Graphic, { current }, { ApiResult.Success(byteArrayOf()) },
            { ApiResult.Success(Unit) }, { calls++; WriteOutcome.Confirmed() })
        assertTrue(pending.verifyGraphic("1234")); current = false
        assertTrue(pending.retryOnce() is WriteOutcome.Rejected); assertEquals(0, calls)
        assertEquals(CaptchaProgress.EXPIRED, pending.progress.value)
    }
    @Test fun expiredOrCancelledChallengeNeverReplays() = runBlocking {
        var time = 0L; var calls = 0
        val pending = PendingCaptcha(CaptchaPrompt.Graphic, { true }, { ApiResult.Success(byteArrayOf()) },
            { ApiResult.Success(Unit) }, { calls++; WriteOutcome.Confirmed() }, { time })
        assertTrue(pending.verifyGraphic("1234")); time = 300_001
        assertTrue(pending.retryOnce() is WriteOutcome.Rejected); assertEquals(0, calls)
        pending.cancel(); assertTrue(pending.retryOnce() is WriteOutcome.Rejected)
    }
    @Test fun neteaseProofMustMatchExactChallengeAndIsAcceptedOnce() = runBlocking {
        val spec = HumanChallengeSpec("a".repeat(32), "_v2_post_token")
        var received: String? = null
        val pending = PendingCaptcha(CaptchaPrompt.Netease(spec), { true }, { error("wrong protocol") },
            { error("wrong protocol") }, { proof -> received = proof; WriteOutcome.Confirmed() })
        assertFalse(pending.acceptHumanProof("NEC:bbbbbbbb:synthetic"))
        assertTrue(pending.acceptHumanProof("NEC:aaaaaaaa:synthetic"))
        assertFalse(pending.acceptHumanProof("NEC:aaaaaaaa:replacement")); assertNull(received)
        assertTrue(pending.retryOnce() is WriteOutcome.Confirmed)
        assertEquals("NEC:aaaaaaaa:synthetic", received)
    }
    @Test fun repeatedServerChallengeCannotCreateLoop() = runBlocking {
        val pending = PendingCaptcha(CaptchaPrompt.Graphic, { true }, { ApiResult.Success(byteArrayOf()) },
            { ApiResult.Success(Unit) }, { WriteOutcome.Rejected(FailureKind.CAPTCHA, "again") })
        assertTrue(pending.verifyGraphic("1234"))
        val result = pending.retryOnce() as WriteOutcome.Rejected
        assertNull(result.continuation)
        assertTrue(pending.retryOnce() is WriteOutcome.Rejected)
    }
    @Test fun resumedSuccessDispatchesOriginalUiCallbackExactlyOnce() = runBlocking {
        var callbacks = 0
        val pending = PendingCaptcha(CaptchaPrompt.Graphic, { true }, { ApiResult.Success(byteArrayOf()) },
            { ApiResult.Success(Unit) }, { WriteOutcome.Confirmed("1") })
        val runner = MutationRunner()
        runner.submit(onConfirmed = { callbacks++ }) { WriteOutcome.Rejected(FailureKind.CAPTCHA, "verify", pending) }
        assertEquals(0, callbacks); assertTrue(pending.verifyGraphic("1234"))
        runner.resumeChallenge(pending); assertEquals(1, callbacks)
        runner.resumeChallenge(pending); assertEquals(1, callbacks)
    }
    @Test fun uiCallbackFailureDoesNotDowngradeAcknowledgedWrite() = runBlocking {
        val runner = MutationRunner()
        runner.submit(onConfirmed = { error("synthetic UI failure") }) { WriteOutcome.Confirmed("1") }
        assertEquals(WriteOutcome.Confirmed("1"), (runner.state.value as DeliveryState.Finished).outcome)
    }

    @Test fun abandoningRejectedChallengeReturnsToEditingWithoutReplay() = runBlocking {
        var requests = 0
        val pending = PendingCaptcha(CaptchaPrompt.Graphic, { true }, { ApiResult.Success(byteArrayOf()) },
            { ApiResult.Success(Unit) }, { requests++; WriteOutcome.Confirmed() })
        val runner = MutationRunner()
        runner.submit { WriteOutcome.Rejected(FailureKind.CAPTCHA, "verify", pending) }
        pending.cancel()
        assertTrue(runner.abandonChallenge(pending)); assertEquals(DeliveryState.Ready, runner.state.value)
        assertEquals(0, requests)
        runner.submit { WriteOutcome.Uncertain() }
        assertFalse(runner.abandonChallenge(pending))
    }

    @Test fun disposalReleasesRejectedOperationAndCannotResubmit() = runBlocking {
        var calls = 0; var callbacks = 0
        val pending = PendingCaptcha(CaptchaPrompt.Graphic, { true }, { ApiResult.Success(byteArrayOf()) },
            { ApiResult.Success(Unit) }, { calls++; WriteOutcome.Confirmed() })
        val runner = MutationRunner()
        runner.submit(onConfirmed = { callbacks++ }) { WriteOutcome.Rejected(FailureKind.CAPTCHA, "verify", pending) }
        assertTrue(pending.retainsOperation())
        runner.dispose()
        assertFalse(pending.retainsOperation()); assertEquals(DeliveryState.Ready, runner.state.value)
        runner.resumeChallenge(pending); runner.submit { calls++; WriteOutcome.Confirmed() }
        assertEquals(0, calls); assertEquals(0, callbacks)
    }
    @Test fun terminalChallengeStatesReleaseAllOperationClosures() = runBlocking {
        fun pending(validate: ApiResult<Unit> = ApiResult.Success(Unit), current: () -> Boolean = { true }) =
            PendingCaptcha(CaptchaPrompt.Graphic, current, { ApiResult.Success(byteArrayOf()) },
                { validate }, { WriteOutcome.Confirmed() })
        val cancelled = pending(); cancelled.cancel(); assertFalse(cancelled.retainsOperation())
        val expired = pending(current = { false }); expired.image(); assertFalse(expired.retainsOperation())
        val rejected = pending(ApiResult.Failure(FailureKind.CAPTCHA, "wrong"))
        assertFalse(rejected.verifyGraphic("1234")); assertFalse(rejected.retainsOperation())
        val consumed = pending(); assertTrue(consumed.verifyGraphic("1234")); consumed.retryOnce()
        assertFalse(consumed.retainsOperation())
    }

}
