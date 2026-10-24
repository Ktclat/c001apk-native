package io.github.ktclat.c001apk.nativeapp.ui

import org.junit.Assert.*
import org.junit.Test

class FloatingDockScrollStateTest {
    @Test fun consumedDistanceHasDirectionalHysteresisAndBoundedAccumulation() {
        val state = FloatingDockScrollState(28f, 20f)
        assertTrue(state.consume(-27f, true)); assertFalse(state.consume(-1f, true))
        repeat(1000) { state.consume(-100f, true) }
        assertEquals(-28f, state.distance, 0f)
        assertFalse(state.consume(19f, true)); assertTrue(state.consume(1f, true))
        assertEquals(20f, state.distance, 0f)
    }
    @Test fun zeroHorizontalAndUnconsumedMovementCannotHideOrResetDirection() {
        val state = FloatingDockScrollState(28f, 20f)
        state.consume(-20f, true)
        repeat(100) { assertTrue(state.consume(0f, true)) }
        assertFalse(state.consume(-8f, true))
    }
    @Test fun userFlingContinuesShortDragButProgrammaticMovementDoesNot() {
        val state = FloatingDockScrollState(28f, 20f)
        assertTrue(state.consume(-100f, false))
        state.consume(-4f, true); state.beginFling(0f, -800f)
        assertFalse(state.consume(-24f, false))
        state.endFling()
        assertFalse(state.consume(1000f, false))
        state.reset(); assertTrue(state.consume(-1000f, false))
    }
    @Test fun pageChangeAndReselectDiscardOldDistanceAndFling() {
        val state = FloatingDockScrollState(28f, 20f)
        state.consume(-27f, true); state.beginFling(0f, -800f); state.reset()
        assertTrue(state.consume(-2f, true)); assertTrue(state.consume(-100f, false))
        assertEquals(-2f, state.distance, 0f)
    }
    @Test fun directionJitterAndHorizontalFlingDoNotToggleVisibility() {
        val state = FloatingDockScrollState(28f, 20f)
        repeat(100) { state.consume(-2f, true); assertTrue(state.consume(2f, true)) }
        state.beginFling(1000f, 5f); assertTrue(state.consume(-200f, false))
        assertTrue(state.consume(Float.NaN, true))
    }
}
