package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Replay-free events: returning to a tab does not replay an old scroll-to-top request. */
val LocalRootReselect = staticCompositionLocalOf<Flow<Boolean>> { emptyFlow() }
@Composable fun ObserveRootReselect(state: LazyListState) {
    val events = LocalRootReselect.current
    LaunchedEffect(events, state) { events.collect { animated ->
        if (animated) state.animateScrollToItem(0) else state.scrollToItem(0)
    } }
}

/** Stable trailing scroll clearance, not a permanently reserved viewport strip. */
val LocalFloatingBottomSpace = staticCompositionLocalOf { androidx.compose.ui.unit.Dp(0f) }
