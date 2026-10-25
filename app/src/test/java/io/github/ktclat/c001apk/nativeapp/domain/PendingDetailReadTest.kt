// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.domain

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PendingDetailReadTest {
    private val spec = ApiChallenge.Netease("abcdefghijklmnop", "_v2_post_token", "operation")
    private val proof = "NEC:abcdefgh:synthetic"
    private fun make(current: () -> Boolean = { true }, clock: () -> Long = { 0 }, retry: suspend (String?) -> ApiResult<ContentItem>) = PendingDetailRead(spec, current,
        { throw AssertionError("not graphic") }, { throw AssertionError("not graphic") }, retry, clock)
    @Test fun expiredVerifiedCapabilityNeverDispatches() = runBlocking {
        var now = 0L; var dispatched = false
        val pending = make(clock = { now }) { dispatched = true; ApiResult.Success(ContentItem("42", "feed")) }
        assertTrue(pending.acceptHumanProof(proof)); now = 300001
        assertTrue(pending.retryOnce() is ApiResult.Failure); assertFalse(dispatched); assertEquals(DetailVerificationState.CLOSED, pending.state.value)
    }
    @Test fun concurrentDuplicateRetriesConsumeAtMostOneOperation() = runBlocking {
        val calls = AtomicInteger(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val pending = make { calls.incrementAndGet(); entered.complete(Unit); release.await(); ApiResult.Success(ContentItem("42", "feed")) }
        assertTrue(pending.acceptHumanProof(proof))
        val first = async { pending.retryOnce() }; entered.await()
        val second = async { pending.retryOnce() }; assertTrue(second.await() is ApiResult.Failure); release.complete(Unit)
        assertTrue(first.await() is ApiResult.Success); assertEquals(1, calls.get())
    }
    @Test fun graphicValidationNeverAutomaticallyReplaysRead() = runBlocking {
        var imageCalls = 0; var validationCalls = 0; var readCalls = 0
        val pending = PendingDetailRead(ApiChallenge.Graphic("operation"), { true }, { imageCalls++; ApiResult.Success(byteArrayOf(1)) },
            { validationCalls++; ApiResult.Success(Unit) }, { readCalls++; ApiResult.Success(ContentItem("42", "feed")) })
        assertTrue(pending.image() is ApiResult.Success); assertFalse(pending.verifyGraphic("bad"))
        assertTrue(pending.verifyGraphic("a1B2")); assertEquals(1, imageCalls); assertEquals(1, validationCalls); assertEquals(0, readCalls)
        assertTrue(pending.retryOnce() is ApiResult.Success); assertEquals(1, readCalls)
    }
    @Test fun cancellationDuringGraphicImageOrValidationCannotResurrectUiState() = runBlocking {
        for (imagePhase in listOf(true, false)) {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val imageBytes = byteArrayOf(1, 2, 3)
            val pending = PendingDetailRead(ApiChallenge.Graphic("synthetic"), { true },
                { entered.complete(Unit); release.await(); ApiResult.Success(imageBytes) },
                { entered.complete(Unit); release.await(); ApiResult.Success(Unit) },
                { error("No read dispatch after cancellation") })
            val task = async { if (imagePhase) pending.image() else pending.verifyGraphic("a1B2") }
            entered.await(); pending.cancel(); release.complete(Unit); task.await()
            assertEquals(DetailVerificationState.CLOSED, pending.state.value)
            assertFalse(pending.canOpen()); assertTrue(pending.retryOnce() is ApiResult.Failure)
            if (imagePhase) assertArrayEquals(byteArrayOf(0, 0, 0), imageBytes)
        }
    }

}
