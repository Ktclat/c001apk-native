package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
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
    sourceMenu: DetailSourceMenuState? = null, favorite: (ContentItem) -> Unit) {
    key(accountState.activeId, accountState.generation, id) {
        FeedDetailContent(id, model, accounts, accountState, blacklist, navigate, openLink, sourceMenu, favorite)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun FeedDetailContent(id: String, model: BrowseViewModel, accounts: AccountRuntime,
    accountState: AccountState, blacklist: io.github.ktclat.c001apk.nativeapp.local.Blacklist,
    navigate: (String) -> Unit, openLink: (String) -> Unit, sourceMenu: DetailSourceMenuState?,
    favorite: (ContentItem) -> Unit) {
    val owner = ReaderOwner(accountState.activeId, accountState.generation)
    val controls = remember(owner, id) { model.readerPositions.controls(owner, id) }
    var order by rememberSaveable(id) { mutableStateOf(controls?.order ?: ReplyOrder.RECENT_REPLY) }
    var authorOnly by rememberSaveable(id) { mutableStateOf(controls?.authorOnly ?: false) }
    val postKey = "feed:$id"
    val replyKey = "ordered:$id:${order.name}:$authorOnly"
    val states by model.states.collectAsState()
    val post = states[postKey] ?: BrowseState(loading = true)
    val replies = states[replyKey] ?: BrowseState(loading = true)
    val visiblePosts = remember(post.items, blacklist) { filterBlocked(post.items, blacklist) }
    val visibleReplies = remember(replies.items, blacklist) { filterBlocked(replies.items, blacklist) }
    val scope = rememberCoroutineScope()
    SideEffect { model.markVisible(replyKey) }
    LaunchedEffect(postKey) { model.load(postKey) }
    LaunchedEffect(replyKey) { model.load(replyKey) }
    if (post.loaded && post.items.isNotEmpty() && visiblePosts.isEmpty()) {
        Text("该动态已被本地过滤规则隐藏", Modifier.padding(24.dp))
        return
    }
    val body = visiblePosts.firstOrNull()?.fullPostBody
    var attempt by remember(body) { mutableIntStateOf(0) }
    val prepared = if (body != null) rememberPreparedDetailReader(body, attempt).value else null
    if (body != null && prepared == null) {
        // Do not measure a restored long-page offset against a short parser placeholder.
        Column(Modifier.padding(24.dp).testTag("reader-preparing"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator()
            Text("正在排版详情正文…")
        }
        return
    }
    val ready = prepared?.prepared?.takeIf { it.readyPageCount() != null }
    val identity = if (ready != null) ReaderIdentity(owner, id, requireNotNull(prepared?.bodyDigest)) else null
    val bookmark = remember(identity, model.readerPositions.revision) { identity?.let(model.readerPositions::prepare) }
    SideEffect {
        if (identity == null) model.readerPositions.discard(owner, id)
        else if (bookmark != null && model.states.value[postKey]?.items?.firstOrNull()?.fullPostBody == body)
            model.readerPositions.commit(bookmark)
    }
    val replyItems = visibleReplies.distinctBy { "${it.kind}:${it.id}" }
    val restored = remember(bookmark, identity) {
        if (bookmark == null || ready == null) ReaderPosition()
        else restoredReaderPosition(bookmark.position, requireNotNull(ready.readyPageCount()), replyKey, ready.readerPageLimit()) { rowKey ->
            // An old integer index may name a different reply after sorting, refresh or eviction.
            if (!replies.loaded || replies.loading || replies.error != null) null
            else replyItems.indexOfFirst { "reply:${it.kind}:${it.id}" == rowKey }
                .takeIf { it >= 0 }?.plus(2)
        }
    }
    val list = remember(bookmark, identity) { LazyListState(restored.index, restored.offset) }
    val page = remember(bookmark, identity) { mutableIntStateOf(restored.page) }
    // Raw and formatted geometry never share a bookmark. All raw state is route-local memory only.
    val sourceMode = remember(owner, id, prepared?.bodyDigest) { mutableStateOf(false) }
    val sourcePage = remember(owner, id, prepared?.bodyDigest) { mutableIntStateOf(0) }
    val sourceList = remember(owner, id, prepared?.bodyDigest) { LazyListState() }
    val displayedList = if (sourceMode.value) sourceList else list
    val control = remember(sourceMode) { DetailSourceControl(sourceMode) }
    SideEffect {
        if ((ready as? PreparedFullPost.Article)?.sourcePages != null) sourceMenu?.register(control)
        else sourceMenu?.clear(control)
    }
    DisposableEffect(control, sourceMenu) { onDispose { sourceMenu?.clear(control) } }
    val currentOrder by rememberUpdatedState(order)
    val currentAuthorOnly by rememberUpdatedState(authorOnly)
    val currentReplyKey by rememberUpdatedState(replyKey)
    DisposableEffect(bookmark, list) {
        onDispose {
            val first = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == list.firstVisibleItemIndex }
            bookmark?.save(ReaderPosition(page.intValue, list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset,
                first?.key as? String, currentReplyKey, currentOrder, currentAuthorOnly))
        }
    }
    val binding = if (body != null && prepared != null) DetailReaderBinding(body, prepared.prepared, page, prepared.bodyDigest, sourceMode, sourcePage) { attempt++ } else null
    CompositionLocalProvider(LocalDetailReaderBinding provides binding) {
        LazyColumn(Modifier.fillMaxSize().testTag("feed-detail-list"), state = displayedList,
            contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "post") {
                if (ready != null && bookmark == null) Text("此动态地址过长，阅读位置不会保留。",
                    Modifier.padding(16.dp).testTag("reader-position-unavailable"))
                visiblePosts.firstOrNull()?.let { item -> InteractiveFeedCard(item, accounts, accountState, navigate,
                    openLink, favorite, onChanged = { model.load(postKey, refresh = true) }, detail = true,
                    onComments = { scope.launch { displayedList.animateScrollToItem(1) } }) }
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
            items(replyItems, key = { "reply:${it.kind}:${it.id}" }) { reply ->
                InteractiveFeedReplyRow(reply, accounts, accountState, navigate, openLink, onChanged = { model.load(replyKey, refresh = true) })
            }
            if (replies.loading) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            else if (replies.loaded && visibleReplies.isEmpty() && replies.error == null) item { Text("暂无评论", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else if (replies.next != null) item { TextButton(onClick = { model.load(replyKey, more = true) }, Modifier.fillMaxWidth()) { Text("加载更多评论") } }
        }
    }
}
