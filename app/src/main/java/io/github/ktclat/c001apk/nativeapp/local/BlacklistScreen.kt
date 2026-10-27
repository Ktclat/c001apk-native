// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.local

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun BlacklistScreen(
    repository: LocalRepository,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    documents: BlacklistDocumentAccess? = null,
) {
    val load by remember(repository) { repository.loadStates() }.collectAsState(initial = LocalLoadState.Loading)
    val context = LocalContext.current
    val documentAccess = documents ?: remember(context) { ResolverBlacklistDocuments(context.contentResolver) }
    val scope = rememberCoroutineScope()
    val hostDensity = LocalDensity.current
    val titleLineHeight = MaterialTheme.typography.titleLarge.lineHeight
    val toolbarHeight = maxOf(64.dp, with(hostDensity) { titleLineHeight.toDp() } + 16.dp)
    var kindName by rememberSaveable { mutableStateOf(BlacklistKind.USER.name) }
    val kind = BlacklistKind.valueOf(kindName)
    var userInput by rememberSaveable { mutableStateOf("") }
    var topicInput by rememberSaveable { mutableStateOf("") }
    val input = if (kind == BlacklistKind.USER) userInput else topicInput
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var clearRequest by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingImportKind by rememberSaveable { mutableStateOf<String?>(null) }
    // Only a small discriminator enters saved state; blacklist contents never enter the Bundle.
    var pendingExportKind by rememberSaveable { mutableStateOf<String?>(null) }
    var operationBusy by remember { mutableStateOf(false) }
    fun requestBusy() = operationBusy || pendingImportKind != null || pendingExportKind != null
    val busy = requestBusy()
    val userScroll = rememberLazyListState()
    val topicScroll = rememberLazyListState()

    fun run(success: String = "已保存", action: suspend () -> Unit) {
        if (operationBusy) return
        operationBusy = true
        message = null
        scope.launch {
            try { action(); message = success }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { message = "操作未完成：请检查当前名单、文件格式、大小或存储权限后重试。导出失败的文件不可用。" }
            finally { operationBusy = false }
        }
    }
    // Register consistently even while local data loads. Each result consumes its saved launch
    // kind before starting work; cancellation and orphan/duplicate callbacks never mutate data.
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = pendingImportKind?.let(BlacklistKind::valueOf)
        pendingImportKind = null
        if (uri != null && target != null) run("已导入并合并") {
            repository.importBlacklist(documentAccess.read(uri), target == BlacklistKind.USER)
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val target = pendingExportKind
        pendingExportKind = null
        if (uri != null && target != null) run("已导出") {
            // Read the latest saved data, but keep the requested scope even if the tab changed.
            val blacklist = repository.state.first().blacklist
            val text = if (target == "ALL") BlacklistJson.encode(blacklist)
                else BlacklistJson.encodeCategory(blacklist, BlacklistKind.valueOf(target))
            documentAccess.write(uri, text)
        }
    }
    fun export(target: BlacklistKind?) {
        if (requestBusy() || (target != null && kindName != target.name)) return
        message = null
        pendingExportKind = target?.name ?: "ALL"
        try { exporter.launch(if (target == null) "c001apk-blacklist.json" else "${target.fileStem}-blacklist.json") }
        catch (_: Exception) { pendingExportKind = null; message = "无法打开文件选择器，请重试" }
    }
    if (load !is LocalLoadState.Ready) { LocalStorageStatus(load, onBack); return }
    val state = (load as LocalLoadState.Ready).value
    val values = kind.values(state.blacklist)
    val validInput = if (kind == BlacklistKind.USER) validUid(input.trim())
        else input.isNotBlank() && input.length <= 200 && !input.any(Char::isISOControl)
    Scaffold(topBar = { TopAppBar(modifier = Modifier.testTag("blacklist-topbar"), expandedHeight = toolbarHeight, title = { Text("黑名单", Modifier.testTag("blacklist-title")) }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }) }) { padding ->
        LazyColumn(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize().testTag("blacklist-list"),
            state = if (kind == BlacklistKind.USER) userScroll else topicScroll,
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "kinds") { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BlacklistKind.entries.forEach { option -> FilterChip(kind == option, { kindName = option.name },
                    label = { Text(if (option == BlacklistKind.USER) "用户 ID" else "话题关键词") }) }
            } }
            item(key = "input") {
                OutlinedTextField(input, { value ->
                    if (value.length <= if (kind == BlacklistKind.USER) 32 else 200) {
                        if (kind == BlacklistKind.USER) userInput = value else topicInput = value
                    }
                }, modifier = Modifier.fillMaxWidth().testTag("blacklist-input"), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = if (kind == BlacklistKind.USER) KeyboardType.Number else KeyboardType.Text),
                    label = { Text(if (kind == BlacklistKind.USER) "数字用户 ID" else "话题关键词") })
                TextButton(onClick = {
                    val currentDraft = if (kind == BlacklistKind.USER) userInput else topicInput
                    if (requestBusy() || kindName != kind.name || input != currentDraft || !validInput) return@TextButton
                    val target = kind; val draft = input; val value = draft.trim()
                    run {
                        if (target == BlacklistKind.USER) repository.addBlockedUser(value) else repository.addBlockedTopic(value)
                        // A new draft typed while persistence was pending belongs to the user.
                        if (target == BlacklistKind.USER && userInput == draft) userInput = ""
                        if (target == BlacklistKind.TOPIC && topicInput == draft) topicInput = ""
                    }
                }, enabled = !busy && validInput) { Text("添加") }
            }
            item(key = "actions") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !busy, onClick = {
                        if (!requestBusy() && kindName == kind.name) {
                            message = null
                            pendingImportKind = kind.name
                            try { importer.launch(arrayOf("application/json", "text/plain")) }
                            catch (_: Exception) { pendingImportKind = null; message = "无法打开文件选择器，请重试" }
                        }
                    }) { Text("导入并合并") }
                    TextButton(enabled = !busy && values.isNotEmpty(), onClick = { export(kind) }) { Text("导出${kind.label}列表") }
                    TextButton(enabled = !busy && (state.blacklist.userBlackList.isNotEmpty() || state.blacklist.topicBlackList.isNotEmpty()), onClick = { export(null) }) { Text("完整备份") }
                    TextButton(enabled = !busy && values.isNotEmpty(), onClick = { if (!requestBusy() && kindName == kind.name) clearRequest = "${kind.name}:${java.util.UUID.randomUUID()}" }) { Text("清空${kind.label}列表") }
                }
                Text("分类导出可导入 Flutter 版；完整备份包含用户与话题两类名单，供原生版恢复。导入只合并，不替换原有记录。", style = MaterialTheme.typography.bodySmall)
            }
            if (busy) item(key = "busy") { Text(if (operationBusy) "正在处理…" else "等待文件选择结果…", Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            message?.let { item(key = "message") { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) } }
            item(key = "count") { Text("${kind.label}黑名单 · ${values.size} 条", style = MaterialTheme.typography.titleSmall) }
            if (values.isEmpty()) item(key = "empty") { Text("暂无${kind.label}黑名单") }
            items(values, key = { "${kind.name}:$it" }) { value ->
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { onNavigate(kind.route(value)) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        .testTag("blacklist-open:$value").semantics { contentDescription = "查看${kind.label} $value" }) { Text(value) }
                    TextButton(enabled = !busy, onClick = {
                        val currentBlacklist = (load as? LocalLoadState.Ready)?.value?.blacklist
                        if (!requestBusy() && kindName == kind.name && currentBlacklist != null && value in kind.values(currentBlacklist)) {
                            val target = kind
                            run { if (target == BlacklistKind.USER) repository.removeBlockedUser(value) else repository.removeBlockedTopic(value) }
                        }
                    }, modifier = Modifier.heightIn(min = 48.dp).testTag("blacklist-remove:$value")) { Text("移除") }
                }
            }
        }
    }
    clearRequest?.let { request ->
        val target = BlacklistKind.valueOf(request.substringBefore(':'))
        fun dismissCurrent() { if (clearRequest == request) clearRequest = null }
        // The platform's ~320 dp landscape dialog width wraps the enlarged title into
        // two lines and can starve the body to less than one line. Use the available
        // safe window width (capped for tablets), without reducing the user's font.
        AlertDialog(onDismissRequest = ::dismissCurrent,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).widthIn(max = 560.dp).fillMaxWidth(),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            title = { CompositionLocalProvider(LocalDensity provides hostDensity) { Text("清空${target.label}黑名单？", Modifier.testTag("blacklist-clear-title")) } },
            text = { CompositionLocalProvider(LocalDensity provides hostDensity) {
                Box(Modifier.testTag("blacklist-clear-viewport").verticalScroll(rememberScrollState())) {
                    Text("仅清空${target.label}名单，无法撤销。另一类黑名单与收藏、历史、关注均保留。可先导出备份。", Modifier.testTag("blacklist-clear-body"))
                }
            } },
            confirmButton = { CompositionLocalProvider(LocalDensity provides hostDensity) { TextButton(enabled = !busy, onClick = {
                if (!requestBusy() && clearRequest == request) { clearRequest = null; run("已清空${target.label}黑名单") { repository.clearBlacklist(target) } }
            }) { Text("确认清空") } } }, dismissButton = { CompositionLocalProvider(LocalDensity provides hostDensity) { TextButton(onClick = ::dismissCurrent) { Text("取消") } } })
    }
}
