// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import io.github.ktclat.c001apk.nativeapp.account.AccountRuntime
import io.github.ktclat.c001apk.nativeapp.account.AccountState
import io.github.ktclat.c001apk.nativeapp.domain.ContentItem
import io.github.ktclat.c001apk.nativeapp.domain.ExplicitLikeState
import io.github.ktclat.c001apk.nativeapp.feature.feed.FeedLikeState
import io.github.ktclat.c001apk.nativeapp.feature.feed.canToggle
import io.github.ktclat.c001apk.nativeapp.social.*
import kotlinx.coroutines.launch

@Composable internal fun InteractiveFeedReplyRow(reply: ContentItem, accounts: AccountRuntime,
    accountState: AccountState, navigate: (String) -> Unit, openLink: (String) -> Unit, onChanged: () -> Unit) {
    val currentAccount by accounts.store.state.collectAsState()
    val validReply = reply.kind == "feed_reply" && validId(reply.id)
    val sameGeneration = currentAccount.generation == accountState.generation
    val initial = when (reply.replyInteraction?.likeState) {
        ExplicitLikeState.LIKED -> FeedLikeState.LIKED
        ExplicitLikeState.NOT_LIKED -> FeedLikeState.NOT_LIKED
        else -> FeedLikeState.UNKNOWN
    }
    val runner = remember(reply.kind, reply.id, accountState.generation, currentAccount.generation, initial) { MutationRunner() }
    DisposableEffect(runner) { onDispose { runner.dispose() } }
    val delivery by runner.state.collectAsState()
    val scope = rememberCoroutineScope()
    val displayed = when {
        !validReply || !sameGeneration -> FeedLikeState.UNKNOWN
        currentAccount.activeId == null -> FeedLikeState.LOGIN_REQUIRED
        delivery == DeliveryState.Sending -> FeedLikeState.PENDING
        delivery is DeliveryState.Finished -> FeedLikeState.UNKNOWN
        else -> initial
    }
    Column {
        FeedReplyRow(reply, navigate, openLink, likeState = displayed, onLike = when {
            !validReply || !sameGeneration -> null
            currentAccount.activeId == null -> ({ navigate("login") })
            initial.canToggle() -> ({ scope.launch {
                runner.submit(isCurrent = { accounts.store.state.value.generation == accountState.generation }, onConfirmed = onChanged) {
                    accounts.socialRepository.like(reply.id, reply = true,
                        targetLiked = initial == FeedLikeState.NOT_LIKED, expectedGeneration = accountState.generation)
                }
            }; Unit })
            else -> null
        })
        if (delivery != DeliveryState.Ready) MutationStatus(delivery, runner)
    }
}
