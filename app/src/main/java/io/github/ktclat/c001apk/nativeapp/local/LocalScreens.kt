// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.local

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun LocalLibraryScreen(repository: LocalRepository, kind: LibraryKind, onBack: () -> Unit, onOpenFeed: (String) -> Unit) {
    val load by remember(repository) { repository.loadStates() }.collectAsState(initial = LocalLoadState.Loading)
    if (load !is LocalLoadState.Ready) { LocalStorageStatus(load, onBack); return }
    val state = (load as LocalLoadState.Ready).value
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var delete by remember { mutableStateOf<LocalFeedEntry?>(null) }
    var clear by remember { mutableStateOf(false) }
    val entries = if (kind == LibraryKind.FAVORITES) state.favorites else state.history
    fun run(action: suspend () -> Unit) { scope.launch { try { action(); error = null } catch (e: CancellationException) { throw e } catch (_: Exception) { error = "保存失败，请重试" } } }
    Scaffold(topBar = { TopAppBar(title = { Text(if (kind == LibraryKind.FAVORITES) "本地收藏" else "浏览历史") }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }, actions = { TextButton(onClick = { clear = true }, enabled = entries.isNotEmpty()) { Text("清空") } }) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (entries.isEmpty()) item { Text("暂无记录") }
            items(entries, key = { it.id }) { entry ->
                Column(Modifier.fillMaxWidth().clickable { onOpenFeed(entry.id) }.padding(vertical = 12.dp)) {
                    Text(entry.userName.ifBlank { entry.userId }, style = MaterialTheme.typography.labelLarge)
                    Text(entry.summary)
                    TextButton(onClick = { delete = entry }) { Text("删除此条记录") }
                }
            }
        }
    }
    if (clear || delete != null) AlertDialog(onDismissRequest = { clear = false; delete = null }, title = { Text(if (clear) "清空当前列表？" else "删除此条记录？") }, text = { Text("仅删除本机记录，不会删除服务器内容或另一列表，操作不可撤销。") }, confirmButton = { TextButton(onClick = { val target = delete; val all = clear; clear = false; delete = null; run { if (all) repository.clear(kind) else target?.let { repository.delete(kind, it.id) } } }) { Text("删除") } }, dismissButton = { TextButton(onClick = { clear = false; delete = null }) { Text("取消") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun LocalFollowsScreen(repository: LocalRepository, onBack: () -> Unit, onOpenUser: (String) -> Unit) {
    val load by remember(repository) { repository.loadStates() }.collectAsState(initial = LocalLoadState.Loading)
    if (load !is LocalLoadState.Ready) { LocalStorageStatus(load, onBack); return }
    val state = (load as LocalLoadState.Ready).value
    val scope = rememberCoroutineScope(); var uid by remember { mutableStateOf("") }; var error by remember { mutableStateOf<String?>(null) }
    var remove by remember { mutableStateOf<LocalFollowUser?>(null) }
    fun run(action: suspend () -> Unit) { scope.launch { try { action(); error = null } catch (e: CancellationException) { throw e } catch (_: Exception) { error = "无法保存，请检查用户 ID 或重试" } } }
    Scaffold(topBar = { TopAppBar(title = { Text("本地关注") }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            item { Text("仅保存在设备上，不会发送远程关注请求。") }
            item { OutlinedTextField(uid, { if (it.length <= 32) uid = it }, label = { Text("数字用户 ID") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)); TextButton(onClick = { run { repository.follow(LocalFollowUser(uid.trim())); uid = "" } }, enabled = validUid(uid.trim())) { Text("添加") } }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            items(state.follows, key = { it.uid }) { user -> Row(Modifier.fillMaxWidth()) { TextButton(onClick = { onOpenUser(user.uid) }, Modifier.weight(1f)) { Text(user.name.ifBlank { user.uid }) }; TextButton(onClick = { remove = user }) { Text("移除") } } }
        }
    }
    remove?.let { user -> AlertDialog(onDismissRequest = { remove = null }, title = { Text("移除本地关注？") }, confirmButton = { TextButton(onClick = { remove = null; run { repository.unfollow(user.uid) } }) { Text("移除") } }, dismissButton = { TextButton(onClick = { remove = null }) { Text("取消") } }) }
}

@Composable internal fun LocalStorageStatus(load: LocalLoadState, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        TextButton(onClick = onBack) { Text("返回") }
        if (load == LocalLoadState.Loading) CircularProgressIndicator()
        else Text("无法读取本地数据。原有数据未删除，请返回后重试；不要清除应用数据。", color = MaterialTheme.colorScheme.error)
    }
}
