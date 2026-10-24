// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

/** Same filled/outlined pairs used by Flutter MainPage, not approximate redraws. */
@Composable
internal fun rootNavigationIcon(index: Int, selected: Boolean): ImageVector = when (index) {
    0 -> if (selected) FlutterMaterialIcons.Home else FlutterMaterialIcons.HomeOutlined
    1 -> if (selected) FlutterMaterialIcons.Feed else FlutterMaterialIcons.FeedOutlined
    else -> if (selected) FlutterMaterialIcons.Person else FlutterMaterialIcons.PersonOutline
}
