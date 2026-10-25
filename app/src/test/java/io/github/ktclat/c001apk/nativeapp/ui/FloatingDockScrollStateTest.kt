package io.github.ktclat.c001apk.nativeapp.ui

import org.junit.Assert.*
import org.junit.Test

class FloatingDockScrollStateTest {
    @Test fun movementTracksPixelsAndReversesContinuouslyWithoutThresholdJump() {
        val state = FloatingDockScrollState(28f, 20f, travelExtent = 100f)
        state.consume(-14f, true); assertEquals(.14f, state.hiddenFraction, .0001f)
        state.consume(-14f, true); assertEquals(.28f, state.hiddenFraction, .0001f)
        assertTrue(state.visible); assertFalse(state.fullyShown)
        state.consume(10f, true); assertEquals(.18f, state.hiddenFraction, .0001f)
        state.consume(-1f, true); assertEquals(.19f, state.hiddenFraction, .0001f)
        repeat(1000) { state.consume(-100f, true) }
        assertEquals(1f, state.hiddenFraction, 0f); assertFalse(state.visible)
        state.consume(1f, true); assertEquals(.99f, state.hiddenFraction, .0001f)
        repeat(1000) { state.consume(100f, true) }
        assertEquals(0f, state.hiddenFraction, 0f); assertTrue(state.fullyShown)
    }
    @Test fun reducedMotionRetainsDirectionalThresholdsWithoutTranslationFrames() {
        val state = FloatingDockScrollState(28f, 20f, reduceMotion = true)
        assertTrue(state.consume(-27f, true)); assertEquals(0f, state.hiddenFraction, 0f)
        assertFalse(state.consume(-1f, true)); assertEquals(1f, state.hiddenFraction, 0f)
        assertFalse(state.consume(19f, true)); assertTrue(state.consume(1f, true))
    }
    @Test fun noConsumptionAndProgrammaticScrollCannotMoveDock() {
        val state = FloatingDockScrollState(28f, 20f, 100f)
        state.consume(-100f, false); assertTrue(state.fullyShown)
        state.consume(-20f, true)
        for (delta in listOf(0f, Float.NaN, Float.POSITIVE_INFINITY)) state.consume(delta, true)
        assertEquals(.2f, state.hiddenFraction, .0001f)
        assertEquals(0f, state.endFling(), 0f)
        state.consume(-100f, false); assertEquals(.2f, state.hiddenFraction, .0001f)
    }
    @Test fun genuineUserFlingContinuesButHorizontalAndEndedFlingCannot() {
        val state = FloatingDockScrollState(28f, 20f, 100f)
        state.consume(-4f, true); state.beginFling(0f, -800f)
        state.consume(-24f, false); assertEquals(.28f, state.hiddenFraction, .0001f)
        assertEquals(1f, state.endFling(), 0f)
        state.consume(-100f, false); assertEquals(.28f, state.hiddenFraction, .0001f)
        state.reset(); state.consume(-4f, true); state.beginFling(1000f, 5f)
        state.consume(-100f, false); assertEquals(.04f, state.hiddenFraction, .0001f)
    }
    @Test fun settlingUsesIntentThenNearestEndAndResetCancelsHistory() {
        val state = FloatingDockScrollState(28f, 20f, 100f)
        state.consume(-30f, true); assertEquals(1f, state.endFling(), 0f)
        state.settleTo(.75f); state.consume(20f, true); assertEquals(0f, state.endFling(), 0f)
        state.settleTo(.75f); state.consume(1f, true); assertEquals(1f, state.endFling(), 0f)
        state.reset(); assertTrue(state.fullyShown); assertEquals(0f, state.distance, 0f)
        state.consume(-1000f, false); assertTrue(state.fullyShown)
    }
    @Test fun differentHeightsUseTheirActualTravelExtent() {
        val phone = FloatingDockScrollState(28f, 20f, 74f)
        val largeFontWithInsets = FloatingDockScrollState(28f, 20f, 108f)
        phone.consume(-37f, true); largeFontWithInsets.consume(-37f, true)
        assertEquals(.5f, phone.hiddenFraction, .0001f)
        assertEquals(37f, largeFontWithInsets.hiddenFraction * largeFontWithInsets.travelExtent, .0001f)
    }
}
