// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.*
import org.junit.Test

class DetailSourceMenuStateTest {
    @Test fun staleBodyRegistrationCannotToggleOrClearNewControl() {
        val menu = DetailSourceMenuState()
        val old = DetailSourceControl(mutableStateOf(false))
        val replacement = DetailSourceControl(mutableStateOf(false))
        menu.register(old)
        val departedClick = { old.toggle() }
        menu.register(replacement)
        departedClick()
        assertFalse(old.mode.value); assertFalse(replacement.mode.value)
        menu.clear(old)
        menu.register(old) // A delayed old publication cannot resurrect revoked control.
        assertSame(replacement, menu.control)
        replacement.toggle(); assertTrue(replacement.mode.value)
    }

    @Test fun ownerRouteDisposalRejectsLateRegistrationAndCapturedAction() {
        val menu = DetailSourceMenuState()
        val control = DetailSourceControl(mutableStateOf(false))
        menu.register(control)
        menu.dispose()
        menu.register(control)
        control.toggle()
        assertNull(menu.control); assertFalse(control.mode.value)
        val newOwner = DetailSourceMenuState()
        val newControl = DetailSourceControl(mutableStateOf(false))
        newOwner.register(newControl)
        menu.clear(control)
        assertSame(newControl, newOwner.control)
        newControl.toggle(); assertTrue(newControl.mode.value)
    }

    @Test fun loadingFilteredUnavailableAndRemovedControlsHaveNoActiveAction() {
        val menu = DetailSourceMenuState()
        assertNull(menu.control)
        repeat(4) {
            val control = DetailSourceControl(mutableStateOf(false))
            menu.register(control)
            menu.clear(control)
            control.toggle()
            assertNull(menu.control); assertFalse(control.mode.value)
        }
    }
}
