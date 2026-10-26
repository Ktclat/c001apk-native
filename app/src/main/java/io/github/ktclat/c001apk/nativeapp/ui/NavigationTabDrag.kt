package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Logical tab under a pointer inside the fixed inner row; padding/outside is not a destination. */
internal fun navigationTabAt(x: Float, width: Float, count: Int, rtl: Boolean): Int? {
    if (!x.isFinite() || !width.isFinite() || width <= 0f || count <= 0 || x < 0f || x >= width) return null
    val physical = (x / width * count).toInt().coerceIn(0, count - 1)
    return if (rtl) count - 1 - physical else physical
}

/** Shared by floating and standard bottom tab rows, inside their visual/system padding. */
@Composable
internal fun Modifier.navigationTabDrag(selected: Int, count: Int, enabled: Boolean = true,
    settleMillis: Long = 0L, onSelect: (Int) -> Unit): Modifier {
    val direction = LocalLayoutDirection.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentSelected by rememberUpdatedState(selected)
    // Selection/callback are NOT keys: selecting a root must not cancel an active pointer.
    return pointerInput(enabled, direction, count, settleMillis, lifecycle) {
        if (!enabled) return@pointerInput
        if (settleMillis > 0L) {
            // The pointer coroutine owns all timers. Pause, removal, disabling, a new
            // layout direction or pointer cancellation cannot leave a late navigation.
            coroutineScope {
                val session = NavigationTabDragSession(this, { currentSelected }, { currentOnSelect(it) }, settleMillis, 4.dp.toPx())
                val selectionObserver = launch { snapshotFlow { currentSelected }.collect(session::selectionChanged) }
                val lifecycleObserver = LifecycleEventObserver { _, _ ->
                    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) session.cancel()
                }
                lifecycle.addObserver(lifecycleObserver)
                fun targetAt(x: Float, y: Float): Int? =
                    if (!y.isFinite() || y < 0f || y >= size.height) null
                    else navigationTabAt(x, size.width.toFloat(), count, direction == LayoutDirection.Rtl)
                try {
                    detectHorizontalDragGestures(
                        // Register the detector inline: a lifecycle-launched child can miss
                        // the first DOWN that lazily starts this pointerInput coroutine.
                        onDragStart = { point ->
                            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                                session.start(targetAt(point.x, point.y), point.x, point.y)
                        },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            session.move(targetAt(change.position.x, change.position.y), change.position.x, change.position.y)
                        },
                        onDragEnd = session::finish,
                        onDragCancel = session::cancel,
                    )
                } finally {
                    lifecycle.removeObserver(lifecycleObserver)
                    selectionObserver.cancel()
                    session.cancel()
                }
            }
            return@pointerInput
        }
        // Standard navigation retains its existing live-scrub behavior.
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
