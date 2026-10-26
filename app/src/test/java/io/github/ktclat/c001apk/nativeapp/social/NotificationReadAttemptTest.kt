// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

class NotificationReadAttemptTest {
    @Test fun cancellationBeforeCommitPreventsTheMutationCallback() {
        val attempt = NotificationReadAttempt(); attempt.cancel()
        assertFalse(attempt.mutateCache { error("A canceled attempt mutated memory") })
    }
    @Test fun admittedPureMemoryCommitLinearizesBeforeConcurrentCancellation() = runBlocking {
        val attempt = NotificationReadAttempt(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val order = CopyOnWriteArrayList<String>()
        try {
            val commit = async(Dispatchers.Default) { attempt.mutateCache {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); order += "commit"; true
            } }
            assertTrue(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
            val cancel = async(Dispatchers.Default) { attempt.cancel(); order += "cancel" }
            release.countDown(); assertTrue(commit.await()); cancel.await()
            assertEquals(listOf("commit", "cancel"), order); assertFalse(attempt.isCurrent())
            assertFalse(attempt.mutateCache { error("Canceled attempt mutated twice") })
        } finally { release.countDown() }
    }
}
