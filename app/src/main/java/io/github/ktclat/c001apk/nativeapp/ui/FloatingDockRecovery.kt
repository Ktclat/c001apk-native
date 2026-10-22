// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics

internal const val SHOW_FLOATING_DOCK_ACTION = "显示底部导航"
internal val LocalFloatingDockReveal = staticCompositionLocalOf<(() -> Boolean)?> { null }

/** Add to an existing scroll container or labeled toolbar control, without merging its children. */
internal fun Modifier.floatingDockRecovery(reveal: (() -> Boolean)?): Modifier =
    if (reveal == null) this else semantics {
        customActions = listOf(CustomAccessibilityAction(SHOW_FLOATING_DOCK_ACTION, reveal))
    }
