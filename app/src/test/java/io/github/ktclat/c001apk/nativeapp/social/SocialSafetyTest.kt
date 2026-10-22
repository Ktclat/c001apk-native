// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SocialSafetyTest {
    @Test fun feedShareRejectsOtherOriginsAndQueryTokens() {
        assertEquals("https://www.coolapk.com/feed/123", feedShareUrl("123"))
        assertEquals("https://www.coolapk.com/feed/123", feedShareUrl("https://coolapk.com/feed/123"))
        assertNull(feedShareUrl("https://coolapk.com.evil/feed/123"))
        assertNull(feedShareUrl("https://www.coolapk.com/feed/123?token=secret"))
    }
    @Test fun malformedOrMissingMessageRecordIsUncertain() {
        assertTrue(confirmWrite(Json.parseToJsonElement("{\"count\":1}"), true, true) is WriteOutcome.Uncertain)
        assertTrue(confirmWrite(Json.parseToJsonElement("{\"entityType\":\"feed\",\"id\":1}"), true, true) is WriteOutcome.Uncertain)
        assertEquals(WriteOutcome.Confirmed("1"), confirmWrite(Json.parseToJsonElement("[{\"entityType\":\"message\",\"id\":1}]"), true, true))
    }
    @Test fun conversationNeedsCountNotUnrelatedId() {
        assertTrue(confirmWrite(Json.parseToJsonElement("{\"id\":123}"), messageMutation = true) is WriteOutcome.Uncertain)
        assertTrue(confirmWrite(Json.parseToJsonElement("{\"count\":false}"), messageMutation = true) is WriteOutcome.Uncertain)
        assertTrue(confirmWrite(Json.parseToJsonElement("{\"count\":1}"), messageMutation = true) is WriteOutcome.Confirmed)
    }
    @Test fun uncertainTransportIsNeverRetryable() = runTest {
        val runner = MutationRunner(); var calls = 0
        runner.submit { calls++; ApiResult.Failure(FailureKind.UNCERTAIN, "unknown").writeOutcome() }
        assertFalse(runner.allowCorrection()); assertFalse(runner.beginNext())
        runner.submit { calls++; WriteOutcome.Confirmed() }
        assertEquals(1, calls)
    }
    @Test fun duplicateTapCannotStartSecondWrite() = runTest {
        val runner = MutationRunner(); val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>(); var calls = 0
        val first = launch { runner.submit { calls++; started.complete(Unit); finish.await(); WriteOutcome.Confirmed() } }
        started.await(); runner.submit { calls++; WriteOutcome.Confirmed() }; finish.complete(Unit); first.join()
        assertEquals(1, calls); assertTrue(runner.beginNext())
    }
    @Test fun captchaDoesNotEnableGenericRetry() = runTest {
        val runner = MutationRunner()
        runner.submit { WriteOutcome.Rejected(FailureKind.CAPTCHA, "captcha") }
        assertFalse(runner.allowCorrection()); assertFalse(runner.beginNext())
    }
    @Test fun staleGenerationCannotCommitSuccess() = runTest {
        val runner = MutationRunner(); var current = true
        runner.submit(isCurrent = { current }) { current = false; WriteOutcome.Confirmed() }
        assertFalse((runner.state.value as? DeliveryState.Finished)?.outcome is WriteOutcome.Confirmed)
    }
    @Test fun chatPaginationKeepsDistinctMessagesWithSameConversationKey() {
        val first = SocialRow("1", "first", "peer", "2", "1_2")
        val second = first.copy(id = "2", message = "second")
        assertEquals(listOf(first, second), mergeSocialRows(listOf(first), listOf(first, second), false))
        assertEquals(listOf(first), mergeSocialRows(listOf(first), listOf(second), true))
    }

}
