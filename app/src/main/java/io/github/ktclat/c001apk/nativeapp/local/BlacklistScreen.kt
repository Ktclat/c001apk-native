// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.local

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun BlacklistScreen(repository: LocalRepository, onBack: () -> Unit) {
    val load by remember(repository) { repository.loadStates() }.collectAsState(initial = LocalLoadState.Loading)
    if (load !is LocalLoadState.Ready) { LocalStorageStatus(load, onBack); return }
    val state = (load as LocalLoadState.Ready).value
    val scope = rememberCoroutineScope(); val context = LocalContext.current
    var input by remember { mutableStateOf("") }; var userMode by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }; var clear by remember { mutableStateOf(false) }
    var pendingImportUserMode by rememberSaveable { mutableStateOf(true) }
    fun run(action: suspend () -> Unit) { scope.launch { try { action(); message = "已保存" } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { message = "操作失败：请检查文件格式、大小或存储权限，原有记录未清空" } } }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) run {
            val text = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) { val count = stream.read(buffer); if (count < 0) break; require(output.size() + count <= BlacklistJson.MAX_BYTES); output.write(buffer, 0, count) }
                    output.toString("UTF-8")
                } ?: error("无法打开文件")
            }
            repository.importBlacklist(text, pendingImportUserMode)
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) run { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(BlacklistJson.encode(state.blacklist).toByteArray(Charsets.UTF_8)) } ?: error("无法写入文件") } }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("黑名单") }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Row { FilterChip(userMode, { userMode = true }, label = { Text("用户 ID") }); Spacer(Modifier.width(8.dp)); FilterChip(!userMode, { userMode = false }, label = { Text("话题关键词") }) } }
            item { OutlinedTextField(input, { if (it.length <= 200) input = it }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = if (userMode) KeyboardType.Number else KeyboardType.Text), label = { Text(if (userMode) "数字用户 ID" else "话题关键词") }); TextButton(onClick = { run { if (userMode) repository.addBlockedUser(input) else repository.addBlockedTopic(input); input = "" } }, enabled = input.isNotBlank()) { Text("添加") } }
            item { Row { TextButton(onClick = { pendingImportUserMode = userMode; importer.launch(arrayOf("application/json", "text/plain")) }) { Text("导入并合并") }; TextButton(onClick = { exporter.launch("c001apk-blacklist.json") }) { Text("导出 JSON") }; TextButton(onClick = { clear = true }) { Text("清空") } } }
            message?.let { item { Text(it) } }
            val values = if (userMode) state.blacklist.userBlackList else state.blacklist.topicBlackList
            items(values, key = { it }) { value -> Row(Modifier.fillMaxWidth()) { Text(value, Modifier.weight(1f).padding(top = 12.dp)); TextButton(onClick = { run { if (userMode) repository.removeBlockedUser(value) else repository.removeBlockedTopic(value) } }) { Text("移除") } } }
        }
    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("清空全部黑名单？") }, text = { Text("包含用户和话题，无法撤销。可先导出备份。") }, confirmButton = { TextButton(onClick = { clear = false; run { repository.clearBlacklist() } }) { Text("清空") } }, dismissButton = { TextButton(onClick = { clear = false }) { Text("取消") } })
}
