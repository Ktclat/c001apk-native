// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

/** Default consumed-scroll behavior. Touch exploration is not a blanket scroll blocker. */
internal fun shouldObserveFloatingDockScroll(
    floatingRoot: Boolean,
    keyboardOpen: Boolean,
    searchOpen: Boolean,
): Boolean = floatingRoot && !keyboardOpen && !searchOpen

internal fun shouldShowFloatingDock(
    floatingRoot: Boolean,
    keyboardOpen: Boolean,
    searchOpen: Boolean,
    scrollVisible: Boolean,
): Boolean = floatingRoot && !keyboardOpen && (scrollVisible || searchOpen)

internal fun shouldOfferFloatingDockRecovery(
    floatingRoot: Boolean,
    keyboardOpen: Boolean,
    searchOpen: Boolean,
    scrollVisible: Boolean,
): Boolean = shouldObserveFloatingDockScroll(floatingRoot, keyboardOpen, searchOpen) && !scrollVisible
