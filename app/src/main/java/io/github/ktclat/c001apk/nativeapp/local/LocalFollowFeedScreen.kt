// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.local

import io.github.ktclat.c001apk.nativeapp.ui.NativeTokenText
import io.github.ktclat.c001apk.nativeapp.ui.floatingDockRecovery
import io.github.ktclat.c001apk.nativeapp.ui.LocalFloatingDockReveal
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.ktclat.c001apk.nativeapp.domain.ContentItem
import io.github.ktclat.c001apk.nativeapp.domain.CoolapkRepository
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Public, device-local follows. It never submits remote follow or account mutations. */
@OptIn(ExperimentalLayoutApi::class)
@Composable fun LocalFollowFeedScreen(
    localRepository: LocalRepository,
    repository: CoolapkRepository,
    onOpenFeed: (String) -> Unit,
    onOpenUser: (String) -> Unit,
    onManageFollows: () -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
) {
    val rootHeader = io.github.ktclat.c001apk.nativeapp.ui.LocalScrollableRootHeader.current
    val bottomSpace = 16.dp + io.github.ktclat.c001apk.nativeapp.ui.LocalFloatingBottomSpace.current
    io.github.ktclat.c001apk.nativeapp.ui.ObserveRootReselect(listState)
    val load by remember(localRepository) { localRepository.loadStates() }.collectAsState(initial = LocalLoadState.Loading)
    if (load !is LocalLoadState.Ready) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("local-follow-list").floatingDockRecovery(LocalFloatingDockReveal.current), contentPadding = PaddingValues(bottom = bottomSpace)) {
            rootHeader?.let { header -> item(key = "root-header") { header() } }
            item { LocalStorageStatus(load, onManageFollows) }
        }
        return
    }
    val local = (load as LocalLoadState.Ready).value
    val uids = local.follows.map { it.uid }
    val model: LocalFollowViewModel = viewModel(key = "device-local-follow", factory = viewModelFactory { initializer { LocalFollowViewModel(repository) } })
    val feedState by model.state.collectAsStateWithLifecycle()
    val feeds = feedState.feeds
    val loading = feedState.loading
    val warning = feedState.warning
    val end = feedState.end
    LaunchedEffect(model, uids, local.blacklist) { model.configure(uids, local.blacklist) }
    // Header actions must share the real scroll owner even at ordinary landscape heights.
    // A fixed FlowRow previously left only38dp of the last card exposed at font1.6.
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("local-follow-list").floatingDockRecovery(LocalFloatingDockReveal.current),
        contentPadding = PaddingValues(bottom = bottomSpace)) {
        rootHeader?.let { header -> item(key = "root-header") { header() } }
        if (uids.isEmpty()) item { Text("尚未关注用户。添加用户后，将合并他们的公开动态。", Modifier.padding(16.dp)) }
        // Empty explanation comes first so the real management action stays last at the end.
        item(key = "local-follow-controls") {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onManageFollows) { Text("管理本地关注（${uids.size}）") }
                TextButton(onClick = model::refresh, enabled = !loading && uids.isNotEmpty()) { Text("刷新") }
            }
        }
        if (uids.isNotEmpty()) {
            items(feeds, key = { it.id }) { item ->
                Card(Modifier.fillMaxWidth().testTag("local-follow-card:${item.id}").padding(horizontal = 12.dp, vertical = 6.dp).clickable { onOpenFeed(item.id) }) {
                    Column(Modifier.padding(16.dp)) {
                        TextButton(onClick = { onOpenUser(item.userId) }) { Text(item.userName.ifBlank { item.userId }) }
                        if (item.title.isNotBlank()) NativeTokenText(item.title, style = MaterialTheme.typography.titleMedium)
                        NativeTokenText(item.message, maxLines = 8, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        if (item.imageUrls.isNotEmpty()) Text("${item.imageUrls.size} 张图片 · 打开详情查看", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    warning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (loading) CircularProgressIndicator()
                    else if (!end) TextButton(onClick = model::loadMore) { Text(if (warning != null) "重试 / 继续加载" else "加载更多") }
                    else Text(if (feeds.isEmpty()) "暂无可显示动态" else "已加载全部动态")
                }
            }
        }
    }
}
