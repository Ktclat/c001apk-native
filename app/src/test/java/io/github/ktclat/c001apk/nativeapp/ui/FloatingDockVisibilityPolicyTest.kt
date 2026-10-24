// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import org.junit.Assert.*
import org.junit.Test

class FloatingDockVisibilityPolicyTest {
    @Test fun defaultObservationUsesOnlyRootKeyboardAndSearchGates() {
        for (root in listOf(false, true)) for (ime in listOf(false, true)) for (search in listOf(false, true)) {
            assertEquals(root && !ime && !search, shouldObserveFloatingDockScroll(root, ime, search))
        }
    }
    @Test fun keyboardAndInnerRoutesHideWhileSearchRevealsWithoutBypassingKeyboard() {
        for (root in listOf(false, true)) for (ime in listOf(false, true))
            for (search in listOf(false, true)) for (visible in listOf(false, true)) {
                assertEquals(root && !ime && (visible || search), shouldShowFloatingDock(root, ime, search, visible))
            }
    }
    @Test fun recoveryActionExistsOnlyForAnEligibleHiddenDock() {
        for (root in listOf(false, true)) for (ime in listOf(false, true))
            for (search in listOf(false, true)) for (visible in listOf(false, true)) {
                assertEquals(root && !ime && !search && !visible, shouldOfferFloatingDockRecovery(root, ime, search, visible))
            }
    }
    @Test fun defaultUsesUnchangedConsumedDistanceThresholds() {
        val state = FloatingDockScrollState(28f, 20f)
        assertTrue(state.consume(-27f, true)); assertFalse(state.consume(-1f, true))
        assertFalse(shouldShowFloatingDock(true, false, false, state.visible))
        assertFalse(state.consume(19f, true)); assertTrue(state.consume(1f, true))
        assertTrue(shouldShowFloatingDock(true, false, false, state.visible))
    }
    @Test fun accessibilityRecoveryResetDiscardsDistanceAndFling() {
        val state = FloatingDockScrollState(28f, 20f)
        state.consume(-28f, true); state.beginFling(0f, -800f)
        state.reset()
        assertTrue(state.visible)
        assertEquals(0f, state.distance, 0f)
        assertTrue(state.consume(-1000f, false))
        assertTrue(state.consume(-20f, true))
        assertFalse(state.consume(-8f, true))
    }
}
