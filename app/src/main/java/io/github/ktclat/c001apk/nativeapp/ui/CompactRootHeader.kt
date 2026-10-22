// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/** Non-null only when fixed root chrome would leave less room than a real action plus clearance. */
val LocalScrollableRootHeader = staticCompositionLocalOf<(@Composable () -> Unit)?> { null }

/** No model names or orientation heuristics. Inputs come from actual window/insets and measured UI. */
internal fun rootHeaderMustScroll(
    windowHeightDp: Float,
    fixedHeaderHeightDp: Float,
    safeBottomDp: Float,
    dockHeightDp: Float,
    dockGapDp: Float,
    actionHeightDp: Float,
): Boolean = windowHeightDp - fixedHeaderHeightDp - safeBottomDp - dockHeightDp - dockGapDp <
    maxOf(48f, actionHeightDp) + 8f
