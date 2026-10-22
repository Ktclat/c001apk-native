// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

data class FeedShareItem(val id: String, val summary: String)
/** Inject the existing local repository; this feature creates no second persistence layer. */
typealias FavoriteFeedLoader = suspend () -> ApiResult<List<FeedShareItem>>

@Composable
fun FeedSharePicker(repository: SocialRepository, favorites: FavoriteFeedLoader?, onSelected: (String) -> Unit, onDismiss: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    var candidate by remember { mutableStateOf<String?>(null) }
    var link by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<FeedShareItem>>(emptyList()) }
    var next by remember { mutableStateOf<PageCursor?>(PageCursor()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun fetch(reset: Boolean) {
        if (loading || tab == 2) return
        loading = true; error = null
        try {
            val result = if (tab == 0) repository.ownFeeds(if (reset) PageCursor() else next ?: return)
            else when (val local = favorites?.invoke()) {
                null -> ApiResult.Failure(FailureKind.INVALID_ARGUMENT, "收藏数据尚未接入")
                is ApiResult.Failure -> local
                is ApiResult.Success -> ApiResult.Success(Page(local.value, null))
            }
            when (result) {
                is ApiResult.Failure -> error = result.message
                is ApiResult.Success -> { rows = ((if (reset) emptyList() else rows) + result.value.items).filter { validId(it.id) }.distinctBy { it.id }; next = result.value.nextCursor }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "无法读取动态或收藏，请稍后重试" }
        finally { loading = false }
    }
    LaunchedEffect(tab) { rows = emptyList(); next = PageCursor(); fetch(true) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(.8f).padding(12.dp)) {
                Text("分享动态", style = MaterialTheme.typography.titleLarge)
                TabRow(selectedTabIndex = tab) { listOf("我的动态", "本地收藏", "链接").forEachIndexed { index, label ->
                    Tab(selected = tab == index, onClick = { if (!loading) tab = index }, text = { Text(label) })
                } }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (tab == 2) {
                    OutlinedTextField(value = link, onValueChange = { link = it }, label = { Text("动态编号或链接") }, modifier = Modifier.fillMaxWidth())
                    TextButton(enabled = feedShareUrl(link) != null, onClick = { candidate = feedShareUrl(link) }) { Text("选择链接") }
                    Spacer(Modifier.weight(1f))
                } else {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    LazyColumn(Modifier.weight(1f)) {
                        items(rows, key = { it.id }) { row -> TextButton(onClick = { candidate = feedShareUrl(row.id) }) {
                            Column(Modifier.fillMaxWidth()) { Text(row.summary.ifBlank { "动态 ${row.id}" }, maxLines = 2, overflow = TextOverflow.Ellipsis); Text("动态 ${row.id}") }
                        } }
                        item { if (!loading && rows.isEmpty() && error == null) Text("暂无动态，可切换到链接") }
                    }
                    Row {
                        TextButton(enabled = !loading, onClick = { scope.launch { fetch(true) } }) { Text("刷新") }
                        if (tab == 0 && next != null) TextButton(enabled = !loading, onClick = { scope.launch { fetch(false) } }) { Text("加载更多") }
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    }
    candidate?.let { url -> AlertDialog(onDismissRequest = { candidate = null }, title = { Text("使用这条动态链接？") },
        text = { Text("$url\n选择后仍需点击聊天页的确认发送。") },
        dismissButton = { TextButton(onClick = { candidate = null }) { Text("取消") } },
        confirmButton = { TextButton(onClick = { candidate = null; onSelected(url) }) { Text("使用链接") } }) }
}
