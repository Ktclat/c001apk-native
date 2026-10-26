package io.github.ktclat.c001apk.nativeapp.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationTabDragSessionTest {
    private class Fixture(scope: TestScope) {
        var selected = 0
        val calls = mutableListOf<Int>()
        val session = NavigationTabDragSession(scope, { selected }, { calls += it; selected = it }, FLOATING_TAB_SETTLE_MILLIS, 4f)
    }

    @Test fun rapidCrossingsDoNotNavigateAndReleaseCommitsOnlyFinalTab() = runTest {
        val f = Fixture(this)
        f.session.start(1, 100f, 20f)
        advanceTimeBy(70)
        f.session.move(2, 200f, 20f)
        advanceTimeBy(70)
        assertTrue(f.calls.isEmpty())
        f.session.finish()
        assertEquals(listOf(2), f.calls)
        advanceTimeBy(1000); runCurrent()
        assertEquals(listOf(2), f.calls)
    }

    @Test fun continuingMotionWithinTheSameTabRestartsTheQuietPeriod() = runTest {
        val f = Fixture(this)
        f.session.start(1, 100f, 20f)
        repeat(8) { index ->
            advanceTimeBy(90)
            f.session.move(1, 105f + index * 5f, 20f)
            runCurrent()
            assertTrue("Moving in the same target must not navigate", f.calls.isEmpty())
        }
        advanceTimeBy(199); runCurrent()
        assertTrue(f.calls.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf(1), f.calls)
        f.session.finish()
        assertEquals(listOf(1), f.calls)
    }

    @Test fun tinyJitterCanSettleButCumulativeMotionCannotBypassTheThreshold() = runTest {
        val f = Fixture(this)
        f.session.start(1, 100f, 20f)
        advanceTimeBy(80); f.session.move(1, 102f, 21f)
        advanceTimeBy(80); f.session.move(1, 101f, 19f)
        advanceTimeBy(40); runCurrent()
        assertEquals(listOf(1), f.calls)
        f.session.move(2, 200f, 20f)
        repeat(3) { advanceTimeBy(50); f.session.move(2, 202f + it * 2f, 20f) }
        advanceTimeBy(99); runCurrent()
        assertEquals(listOf(1), f.calls)
        advanceTimeBy(51); runCurrent()
        assertEquals(listOf(1, 2), f.calls)
    }

    @Test fun heldPointerCanReverseAfterACommittedPauseWithoutDuplicateReselect() = runTest {
        val f = Fixture(this)
        f.session.start(1, 100f, 20f)
        advanceTimeBy(200); runCurrent()
        f.session.move(2, 200f, 20f)
        advanceTimeBy(70)
        f.session.move(0, 20f, 20f)
        advanceTimeBy(200); runCurrent()
        f.session.finish()
        assertEquals(listOf(1, 0), f.calls)
    }

    @Test fun cancelOrLeavingBoundsDropsPendingAndReleaseDoesNotReviveIt() = runTest {
        for (leaveBounds in listOf(false, true)) {
            val f = Fixture(this)
            f.session.start(1, 100f, 20f)
            advanceTimeBy(100)
            if (leaveBounds) f.session.move(null, 400f, 20f) else f.session.cancel()
            advanceTimeBy(300); runCurrent()
            f.session.finish()
            assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun sameTabDragAndRoundTripNeverDispatchReselectOrFetchWork() = runTest {
        val f = Fixture(this)
        f.session.start(0, 20f, 20f)
        advanceTimeBy(300); runCurrent()
        f.session.move(1, 100f, 20f)
        advanceTimeBy(50)
        f.session.move(0, 20f, 20f)
        f.session.finish()
        advanceTimeBy(300); runCurrent()
        assertTrue(f.calls.isEmpty())
    }

    @Test fun externalSelectionWinsBeforeTimerOrReleaseEvenWithoutObserverNotification() = runTest {
        for (notify in listOf(false, true)) {
            val f = Fixture(this)
            f.session.start(1, 100f, 20f)
            f.selected = 2
            if (notify) f.session.selectionChanged(2)
            advanceTimeBy(300); runCurrent()
            f.session.finish()
            assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun ourDelayedComposeAcknowledgementDoesNotCancelTheHeldPointerButBackDoes() = runTest {
        var selected = 0
        val calls = mutableListOf<Int>()
        val session = NavigationTabDragSession(this, { selected }, { calls += it }, 200, 4f)
        session.start(1, 100f, 20f)
        advanceTimeBy(200); runCurrent()
        session.move(2, 200f, 20f) // Parent has not recomposed yet.
        selected = 1; session.selectionChanged(1)
        advanceTimeBy(200); runCurrent()
        assertEquals(listOf(1, 2), calls)
        selected = 2; session.selectionChanged(2)
        session.move(1, 100f, 20f)
        selected = 0; session.selectionChanged(0) // Back supersedes the pending drag.
        advanceTimeBy(300); runCurrent(); session.finish()
        assertEquals(listOf(1, 2), calls)
    }

    @Test fun backToPriorRootBeforeAcknowledgementCannotBeOverwrittenByAnotherCommit() = runTest {
        val calls = mutableListOf<Int>()
        // The owner commits root 1 and Back returns to 0 before a recomposition. The
        // modifier only observes 0, indistinguishable from a delayed acknowledgement.
        val session = NavigationTabDragSession(this, { 0 }, { calls += it }, 200, 4f)
        session.start(1, 100f, 20f)
        advanceTimeBy(200); runCurrent()
        session.move(2, 200f, 20f)
        advanceTimeBy(300); runCurrent()
        session.finish()
        assertEquals(listOf(1), calls)
    }
}
