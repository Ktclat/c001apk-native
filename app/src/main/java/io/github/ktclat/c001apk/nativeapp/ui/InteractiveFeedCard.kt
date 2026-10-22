package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import io.github.ktclat.c001apk.nativeapp.account.AccountRuntime
import io.github.ktclat.c001apk.nativeapp.account.AccountState
import io.github.ktclat.c001apk.nativeapp.domain.ContentItem
import io.github.ktclat.c001apk.nativeapp.feature.feed.*
import io.github.ktclat.c001apk.nativeapp.social.*
import kotlinx.coroutines.launch

/** Shared source-faithful feed presentation; mutation state is account-generation scoped. */
@Composable internal fun InteractiveFeedCard(
    item: ContentItem, accounts: AccountRuntime, accountState: AccountState,
    navigate: (String) -> Unit, openLink: (String) -> Unit, favorite: (ContentItem) -> Unit,
    onChanged: () -> Unit, detail: Boolean = false, onComments: () -> Unit = { navigate("feed:${item.id}") },
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var originalError by remember(item.id) { mutableStateOf(false) }
    val projected = item.toFeedCardModel(System.currentTimeMillis() / 1000, detail)
    val runner = remember(item.id, accountState.generation, projected.likeState) { MutationRunner() }
    DisposableEffect(runner) { onDispose { runner.dispose() } }
    val delivery by runner.state.collectAsState()
    val likeState = when {
        delivery == DeliveryState.Sending -> FeedLikeState.PENDING
        delivery is DeliveryState.Finished -> FeedLikeState.UNKNOWN
        accountState.activeId == null -> FeedLikeState.LOGIN_REQUIRED
        else -> projected.likeState
    }
    Column {
        NativeFeedCard(projected.copy(likeState = likeState), FeedCardActions(
            openPost = { navigate("feed:${item.id}") },
            openAuthor = item.userId.takeIf(String::isNotBlank)?.let { uid -> { navigate("user:$uid") } },
            openComments = onComments, openLink = openLink,
            openReference = { reference -> reference.destination()?.let { target ->
                navigate(if (target.startsWith("replies:")) "feed:${target.substringAfter(':')}" else target)
            } },
            favorite = { favorite(item) }, login = { navigate("login") },
            openOriginal = item.id.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.let { id ->
                { originalError = runCatching { io.github.ktclat.c001apk.nativeapp.platform.openWebLink(context,
                    "https://www.coolapk.com/feed/$id", preferExternal = true) }.isFailure }
            },
            toggleLike = if (projected.likeState.canToggle()) ({ scope.launch {
                runner.submit(isCurrent = { accounts.store.state.value.generation == accountState.generation }, onConfirmed = onChanged) {
                    accounts.socialRepository.like(item.id, reply = false, targetLiked = projected.likeState == FeedLikeState.NOT_LIKED, expectedGeneration = accountState.generation)
                }
            }; Unit }) else null,
        ), detail = detail)
        if (item.bodyTruncated && detail && item.fullPostBody == null) {
            androidx.compose.material3.Text("当前为长内容预览，完整正文请在原始页面阅读。")
            if (item.id.isNotBlank() && item.id.all { it in '0'..'9' }) androidx.compose.material3.TextButton(onClick = { originalError = runCatching { io.github.ktclat.c001apk.nativeapp.platform.openWebLink(context, "https://www.coolapk.com/feed/${item.id}", preferExternal = true) }.isFailure }) { androidx.compose.material3.Text("浏览器打开完整原文") }
        }
        if (originalError) androidx.compose.material3.AlertDialog(onDismissRequest = { originalError = false }, title = { androidx.compose.material3.Text("无法打开原文") }, text = { androidx.compose.material3.Text("没有可用的浏览器，完整正文仍待原生阅读支持。") }, confirmButton = { androidx.compose.material3.TextButton(onClick = { originalError = false }) { androidx.compose.material3.Text("知道了") } })
        if (delivery != DeliveryState.Ready) MutationStatus(delivery, runner)
    }
}
