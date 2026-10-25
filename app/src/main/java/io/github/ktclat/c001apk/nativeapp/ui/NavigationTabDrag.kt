package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/** Logical tab under a pointer inside the fixed inner row; padding/outside is not a destination. */
internal fun navigationTabAt(x: Float, width: Float, count: Int, rtl: Boolean): Int? {
    if (!x.isFinite() || !width.isFinite() || width <= 0f || count <= 0 || x < 0f || x >= width) return null
    val physical = (x / width * count).toInt().coerceIn(0, count - 1)
    return if (rtl) count - 1 - physical else physical
}

/** Shared by floating and standard bottom tab rows, inside their visual/system padding. */
@Composable
internal fun Modifier.navigationTabDrag(selected: Int, count: Int, enabled: Boolean = true, onSelect: (Int) -> Unit): Modifier {
    val direction = LocalLayoutDirection.current
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentSelected by rememberUpdatedState(selected)
    // Selection/callback are NOT keys: selecting a root must not cancel an active pointer.
    return pointerInput(enabled, direction, count) {
        if (!enabled) return@pointerInput
        var lastTarget = currentSelected
        fun selectAt(x: Float, y: Float) {
            if (y < 0f || y >= size.height) return
            val target = navigationTabAt(x, size.width.toFloat(), count, direction == LayoutDirection.Rtl) ?: return
            if (target != lastTarget) {
                lastTarget = target
                currentOnSelect(target)
            }
        }
        detectHorizontalDragGestures(
            onDragStart = { point -> lastTarget = currentSelected; selectAt(point.x, point.y) },
            onHorizontalDrag = { change, _ ->
                change.consume()
                selectAt(change.position.x, change.position.y)
            },
        )
    }
}
