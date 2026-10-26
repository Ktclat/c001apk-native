// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.ktclat.c001apk.nativeapp.domain.*
import io.github.ktclat.c001apk.nativeapp.local.*

internal fun isUserConnectionsKey(key: String) = UserConnectionKind.entries.any { key.startsWith("${it.routePrefix}:") }

/** Unlike feeds, Flutter FFFList keeps duplicate relationship records. Cursor IDs are unchanged. */
internal fun userConnectionsPage(old: BrowseState, page: Page<ContentItem>, more: Boolean): BrowseState {
    val incoming = expandSupportedListCards(page.items)
    val rows = (if (more) old.items else emptyList()) + incoming.filterNot(::isExplicitUserListEnd)
    return BrowseState(items = rows.take(2000), loaded = true, next = page.nextCursor.takeIf { incoming.none(::isExplicitUserListEnd) },
        displayLimitReached = rows.size >= 2000)
}

/** A read-only user list: no account follow/chat actions or inferred feed actions are mounted here. */
@Composable internal fun UserConnectionsScreen(uid: String, key: String, model: BrowseViewModel,
    localLoad: LocalLoadState,
    navigate: (String) -> Unit, openLink: (String) -> Unit) {
    if (!validUid(uid)) { Text("用户 ID 格式无效，未读取用户列表", Modifier.padding(24.dp)); return }
    val states by model.states.collectAsState()
    val blacklist = (localLoad as? LocalLoadState.Ready)?.value?.blacklist
    LaunchedEffect(model, key, blacklist != null) { if (blacklist != null) model.load(key) else model.leaveRead(key) }
    DisposableEffect(model, key) { onDispose { model.leaveRead(key) } }
    if (blacklist == null) {
        Text(if (localLoad == LocalLoadState.Failed) "本地过滤设置无法读取，未展示未过滤内容" else "正在读取本地过滤设置", Modifier.padding(24.dp)); return
    }
    val state = states[key] ?: BrowseState(loading = true)
    val rows = remember(state.items, blacklist) { filterBlocked(state.items, blacklist) }
    val listState = rememberLazyListState()
    CompositionLocalProvider(LocalPreviewGeometryStore provides model.previewGeometry) {
        LazyColumn(Modifier.fillMaxSize().testTag("user-connections-list"), state = listState,
            contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Occurrence indices remain stable under append, even if every record ID repeats.
            itemsIndexed(rows, key = { index, _ -> "connection:$index" }) { index, item ->
                if (item.kind in setOf("contacts", "user")) {
                    val action = item.userId.takeIf(::validUid)?.let { id -> ({ navigate("user:$id") }) }
                    EntityCard(item, navigate, openLink, Modifier.testTag("user-connection:$index"),
                        actionOverride = EntityCardAction(action), showSourceTabs = false)
                } else Text("此用户列表项暂不支持", Modifier.padding(16.dp).testTag("user-connection:$index"))
            }
            if (state.error != null) item {
                Column(Modifier.padding(12.dp)) {
                    Text(state.error, color = MaterialTheme.colorScheme.error)
                    state.failure?.pageRead?.let { pending -> PageReadVerification(pending, state.loading) {
                        model.load(key, refresh = !state.moreError, more = state.moreError, pageRetry = pending)
                    } }
                    TextButton(enabled = !state.loading, onClick = { model.load(key, refresh = !state.moreError, more = state.moreError) }) { Text("重试") }
                }
            }
            if (state.loading) item { CircularProgressIndicator(Modifier.padding(16.dp)) }
            if (state.loaded && rows.isEmpty() && !state.loading && state.error == null) item { Text("暂无内容", Modifier.padding(16.dp)) }
            if (!state.loading && state.error == null && state.next != null && !state.displayLimitReached) item {
                TextButton(onClick = { model.load(key, more = true) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("user-connections-more")) { Text("加载更多") }
            }
            if (state.displayLimitReached) item { Text("已加载 2000 条，达到本地展示上限；可刷新以更新列表。", Modifier.testTag("user-connections-limit")) }
        }
    }
}
