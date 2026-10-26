package io.github.ktclat.c001apk.nativeapp.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val FLOATING_TAB_SETTLE_MILLIS = 200L

/** One held pointer. Only a quiet hover or a valid release may navigate; motion is cheap. */
internal class NavigationTabDragSession(
    private val scope: CoroutineScope,
    private val selected: () -> Int,
    private val onSelect: (Int) -> Unit,
    private val settleMillis: Long,
    private val jitterTolerance: Float,
) {
    private var pending: Job? = null
    private var active = false
    private var expectedSelection = -1
    private var selectionBeforeCommit = -1
    private var awaitingSelection = false
    private var target: Int? = null
    private var anchorX = 0f
    private var anchorY = 0f

    fun start(target: Int?, x: Float, y: Float) {
        cancel()
        active = true
        expectedSelection = selected()
        awaitingSelection = false
        move(target, x, y)
    }

    fun move(next: Int?, x: Float, y: Float) {
        if (!isCurrent()) return
        if (next == null || !x.isFinite() || !y.isFinite()) {
            pending?.cancel()
            pending = null
            target = null
            return
        }
        val dx = x - anchorX
        val dy = y - anchorY
        // Accumulate from the last meaningful point, not each event: a slow continuing
        // drag within one tab must restart the timer too. Tiny hand jitter may settle.
        if (next == target && dx * dx + dy * dy < jitterTolerance * jitterTolerance) return
        target = next
        anchorX = x
        anchorY = y
        pending?.cancel()
        pending = if (next == expectedSelection) null else scope.launch {
            delay(settleMillis)
            commit()
        }
    }

    fun finish() {
        pending?.cancel()
        pending = null
        commit()
        active = false
    }

    fun selectionChanged(selection: Int) {
        // A Back/tap/other navigation wins over the held gesture. Our own callback
        // changes expectedSelection first, so it never cancels a legitimate reversal.
        if (!active) return
        if (selection == expectedSelection) awaitingSelection = false
        else if (!awaitingSelection || selection != selectionBeforeCommit) cancel()
    }

    fun cancel() {
        active = false
        target = null
        pending?.cancel()
        pending = null
    }

    private fun isCurrent(): Boolean {
        if (!active) return false
        selectionChanged(selected())
        return active
    }

    private fun commit() {
        if (!isCurrent()) return
        // Do not issue a second navigation until the owner has acknowledged the first.
        // Back may have returned to the previous root before Compose published that
        // intermediate selection; treating the old value as permission would undo Back.
        if (awaitingSelection) return
        val destination = target ?: return
        if (destination == expectedSelection) return // A drag never triggers tap/reselect work.
        selectionBeforeCommit = selected()
        awaitingSelection = true // Compose may not have applied our callback's state yet.
        expectedSelection = destination
        onSelect(destination)
    }
}
