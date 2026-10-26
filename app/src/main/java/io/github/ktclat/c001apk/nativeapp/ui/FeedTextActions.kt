// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.ktclat.c001apk.nativeapp.account.AccountRuntime
import io.github.ktclat.c001apk.nativeapp.account.AccountState
import io.github.ktclat.c001apk.nativeapp.domain.ContentItem
import io.github.ktclat.c001apk.nativeapp.domain.FullPostBody
import io.github.ktclat.c001apk.nativeapp.feature.feed.*
import io.github.ktclat.c001apk.nativeapp.platform.AndroidFeedTextTransfer
import io.github.ktclat.c001apk.nativeapp.platform.FeedTextTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Test substitution affects the platform boundary only, not route/menu/ownership behavior. */
internal val LocalFeedTextTransfer = staticCompositionLocalOf<FeedTextTransfer?> { null }
internal data class FeedTextActions(val copy: (() -> Unit)?, val share: (() -> Unit)?,
    val controller: FeedTextActionController, val state: FeedTextActionState)

@Composable internal fun rememberFeedTextActions(item: ContentItem, accounts: AccountRuntime, account: AccountState, detail: Boolean = false): FeedTextActions {
    val context = LocalContext.current
    val override = LocalFeedTextTransfer.current
    val transfer = remember(context, override) { override ?: AndroidFeedTextTransfer(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val shareMessage = if (detail) (item.fullPostBody as? FullPostBody.Available)?.html ?: item.message else item.message
    val controller = remember(item.id, item.title, shareMessage, account.activeId, account.generation, transfer, lifecycle) { FeedTextActionController() }
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) controller.cancelPending() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); controller.dispose() }
    }
    val state by controller.state.collectAsState()
    fun current() = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        accounts.store.state.value.let { it.activeId == account.activeId && it.generation == account.generation }
    fun perform(action: FeedTextAction) {
        controller.start(scope, action, ::current, prepare = {
            if (action == FeedTextAction.COPY_LINK) feedCopyLink(item.id)
            else withContext(Dispatchers.Default) { feedTextPayload(item.id, item.title, shareMessage)?.shareText }
        }, transfer = transfer)
    }
    val available = validFeedTextId(item.id)
    return FeedTextActions(if (available) ({ perform(FeedTextAction.COPY_LINK) }) else null,
        if (available) ({ perform(FeedTextAction.SHARE) }) else null, controller, state)
}

@Composable internal fun FeedTextActionFeedback(actions: FeedTextActions) {
    actions.state.feedback?.let { feedback -> Row(Modifier.padding(horizontal = 16.dp).testTag("feed-text-feedback")
        .semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(feedback, Modifier.weight(1f).padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall,
            color = if (actions.state.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = actions.controller::dismissFeedback) { Text("关闭") }
    } }
}
