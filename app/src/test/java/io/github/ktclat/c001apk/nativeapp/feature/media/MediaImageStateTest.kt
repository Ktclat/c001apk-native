// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.feature.media

import org.junit.Assert.*
import org.junit.Test

class MediaImageStateTest {
    @Test fun startsLoadingAndOnlyTerminalCompletionIsAccepted() {
        val state = MediaImageState()
        assertEquals(MediaImagePhase.Loading, state.phase)
        state.finish(0, MediaImagePhase.Success)
        state.finish(0, MediaImagePhase.Error)
        assertEquals(MediaImagePhase.Success, state.phase)
    }
    @Test fun onlyAnExplicitFailureRetryStartsOneNewAttempt() {
        val state = MediaImageState()
        state.retry()
        assertEquals(0, state.attempt)
        state.finish(0, MediaImagePhase.Error)
        assertEquals(MediaImagePhase.Error, state.phase)
        state.retry(); state.retry()
        assertEquals(1, state.attempt)
        assertEquals(MediaImagePhase.Loading, state.phase)
    }
    @Test fun lateCallbacksCannotOverwriteNewAttempt() {
        val state = MediaImageState()
        state.finish(0, MediaImagePhase.Error); state.retry()
        state.finish(0, MediaImagePhase.Success); state.finish(0, MediaImagePhase.Error)
        assertEquals(MediaImagePhase.Loading, state.phase)
        state.finish(1, MediaImagePhase.Success)
        assertEquals(MediaImagePhase.Success, state.phase)
    }
    @Test fun repeatedFailureDoesNotRetryItself() {
        val state = MediaImageState()
        state.finish(0, MediaImagePhase.Error); state.retry(); state.finish(1, MediaImagePhase.Error)
        repeat(10) { state.finish(0, MediaImagePhase.Success) }
        assertEquals(1, state.attempt)
        assertEquals(MediaImagePhase.Error, state.phase)
    }
    @Test fun separateOccurrencesAndReplacedOwnerNeverShareState() {
        val previous = MediaImageState(); val duplicate = MediaImageState(); val replacement = MediaImageState()
        previous.finish(0, MediaImagePhase.Error); previous.retry(); previous.finish(1, MediaImagePhase.Success)
        assertEquals(MediaImagePhase.Loading, duplicate.phase)
        assertEquals(MediaImagePhase.Loading, replacement.phase)
        assertEquals(0, replacement.attempt)
    }
    @Test fun leavingPendingPageInvalidatesItsCallbackAndDoesNotForceCacheBypass() {
        val state = MediaImageState()
        state.leave()
        state.finish(0, MediaImagePhase.Error)
        assertEquals(MediaImagePhase.Loading, state.phase)
        assertEquals(1, state.attempt)
        assertFalse(state.bypassCache)
        state.finish(1, MediaImagePhase.Success)
        assertEquals(MediaImagePhase.Success, state.phase)
    }
    @Test fun leavingFailedPageDoesNotAutomaticallyRetry() {
        val state = MediaImageState()
        state.finish(0, MediaImagePhase.Error); state.leave()
        assertEquals(MediaImagePhase.Error, state.phase)
        assertEquals(0, state.attempt)
        state.retry()
        assertTrue(state.bypassCache)
        state.finish(1, MediaImagePhase.Success); state.leave()
        assertFalse("Revisiting a repaired image uses the normal cache again", state.bypassCache)
        assertEquals(MediaImagePhase.Loading, state.phase)
    }
}
