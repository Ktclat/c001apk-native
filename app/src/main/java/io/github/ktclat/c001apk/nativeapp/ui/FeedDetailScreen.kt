package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.launch

/** One continuous post/comment hierarchy; sort controls wrap instead of shrinking large text. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun FeedDetailScreen(id: String, model: BrowseViewModel, accounts: AccountRuntime,
    accountState: AccountState, blacklist: io.github.ktclat.c001apk.nativeapp.local.Blacklist, navigate: (String) -> Unit, openLink: (String) -> Unit,
    favorite: (ContentItem) -> Unit) {
    var order by rememberSaveable(id) { mutableStateOf(ReplyOrder.RECENT_REPLY) }
    var authorOnly by rememberSaveable(id) { mutableStateOf(false) }
    val postKey = "feed:$id"
    val replyKey = "ordered:$id:${order.name}:$authorOnly"
    val states by model.states.collectAsState()
    val post = states[postKey] ?: BrowseState(loading = true)
    val replies = states[replyKey] ?: BrowseState(loading = true)
    val visiblePosts = remember(post.items, blacklist) { filterBlocked(post.items, blacklist) }
    val visibleReplies = remember(replies.items, blacklist) { filterBlocked(replies.items, blacklist) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    SideEffect { model.markVisible(replyKey) }
    LaunchedEffect(postKey) { model.load(postKey) }
    LaunchedEffect(replyKey) { model.load(replyKey) }
    if (post.loaded && post.items.isNotEmpty() && visiblePosts.isEmpty()) {
        Text("该动态已被本地过滤规则隐藏", Modifier.padding(24.dp))
        return
    }
    LazyColumn(Modifier.fillMaxSize().testTag("feed-detail-list"), state = list,
        contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "post") {
            visiblePosts.firstOrNull()?.let { item -> InteractiveFeedCard(item, accounts, accountState, navigate,
                openLink, favorite, onChanged = { model.load(postKey, refresh = true) }, detail = true,
                onComments = { scope.launch { list.animateScrollToItem(1) } }) }
            if (post.loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("feed-detail-loading"))
            post.error?.let { error -> Column(Modifier.padding(16.dp)) { Text(error); TextButton(onClick = { model.load(postKey, refresh = true) }) { Text("重新加载动态") } } }
        }
        item(key = "comment-controls") {
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("评论 ${post.items.firstOrNull()?.feed?.replyCount ?: post.items.firstOrNull()?.replyCount ?: 0}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { navigate("compose-reply:feed:$id") }) { Text("回复动态") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf(ReplyOrder.RECENT_REPLY to "默认", ReplyOrder.NEWEST to "最新", ReplyOrder.POPULAR to "热门").forEach { (value, label) ->
                        FilterChip(selected = order == value, onClick = { order = value }, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                    FilterChip(selected = authorOnly, onClick = { authorOnly = !authorOnly }, label = { Text("只看楼主") }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
        }
        replies.error?.let { error -> item { Column(Modifier.padding(16.dp)) { Text(error); TextButton(onClick = { model.load(replyKey, refresh = true) }) { Text("重新加载评论") } } } }
        items(visibleReplies.distinctBy { "${it.kind}:${it.id}" }, key = { "reply:${it.kind}:${it.id}" }) { reply ->
            InteractiveFeedReplyRow(reply, accounts, accountState, navigate, openLink, onChanged = { model.load(replyKey, refresh = true) })
        }
        if (replies.loading) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        else if (replies.loaded && visibleReplies.isEmpty() && replies.error == null) item { Text("暂无评论", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        else if (replies.next != null) item { TextButton(onClick = { model.load(replyKey, more = true) }, Modifier.fillMaxWidth()) { Text("加载更多评论") } }
    }
}
