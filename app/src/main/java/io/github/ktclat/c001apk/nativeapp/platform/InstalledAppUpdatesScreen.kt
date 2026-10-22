// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.platform

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.ktclat.c001apk.nativeapp.domain.ApiResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstalledAppUpdatesScreen(repository: InstalledAppUpdatesRepository, onBack: () -> Unit, onOpenApp: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var selection by remember { mutableStateOf(setOf<String>()) }
    var pending by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<InstalledAppUpdate>?>(null) }
    var resolving by remember { mutableStateOf<String?>(null) }
    var downloadPreview by remember { mutableStateOf<Pair<InstalledAppUpdate, String>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) {
        try { apps = installedLauncherApps(context); selection = selection.intersect(apps.orEmpty().map { it.packageName }.toSet()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "本地应用列表读取失败，请刷新" }
    }
    pending?.let { reviewed ->
        AlertDialog(onDismissRequest = { pending = null }, title = { Text("向酷安检查所选应用更新？") }, text = {
            Column {
                Text("将向 api.coolapk.com 发送以下 ${reviewed.size} 个应用的包名和版本号。不会发送图标、名称或其他未选应用；不会后台自动检查。")
                LazyColumn(Modifier.heightIn(max = 240.dp)) { items(reviewed, key = { it.packageName }) { Text("${it.packageName} · ${it.versionCode}", Modifier.padding(vertical = 4.dp)) } }
            }
        }, confirmButton = { TextButton(onClick = {
            pending = null; loading = true; error = null; results = null
            scope.launch {
                try { when (val response = repository.check(reviewed)) {
                    is ApiResult.Success -> results = response.value
                    is ApiResult.Failure -> error = response.message
                } } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "检查失败，请重试" }
                finally { loading = false }
            }
        }) { Text("发送所选列表并检查") } }, dismissButton = { TextButton(onClick = { pending = null }) { Text("取消") } })
    }
    downloadPreview?.let { (update, url) ->
        AlertDialog(onDismissRequest = { downloadPreview = null }, title = { Text("在系统浏览器打开下载地址？") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${update.title} · ${update.versionName} (${update.versionCode})")
                Text("目标网站：${java.net.URI(url).host}")
                Text("地址由酷安接口返回，尚未核验为固定下载域名。继续后网站可能开始下载；不会传递应用账号 Cookie、签名或认证请求头，也不会自动安装。")
            }
        }, confirmButton = { TextButton(onClick = {
            downloadPreview = null
            try { openWebLink(context, url, true) } catch (_: Exception) { error = "无法打开系统浏览器" }
        }) { Text("打开系统浏览器") } }, dismissButton = { TextButton(onClick = { downloadPreview = null }) { Text("取消") } })
    }
    Scaffold(topBar = { TopAppBar(title = { Text("已安装应用更新") }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("仅扫描本机允许查询的非系统可启动应用。勾选并确认后才发送包名/版本；原生客户端自身更新是另一项功能。") }
            item { Row {
                TextButton(enabled = !loading, onClick = { refresh++; error = null; results = null }) { Text("刷新本机列表") }
                TextButton(enabled = !loading, onClick = { selection = emptySet(); results = null }) { Text("清空选择") }
            } }
            if (apps == null && error == null) item { CircularProgressIndicator() }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            items(apps.orEmpty(), key = { it.packageName }) { app ->
                Row { Checkbox(app.packageName in selection, enabled = !loading, onCheckedChange = { selected -> results = null; selection = if (selected) selection + app.packageName else selection - app.packageName })
                    Column { Text(app.label); Text("${app.packageName} · ${app.versionName} (${app.versionCode})", style = MaterialTheme.typography.bodySmall) } }
            }
            item { Button(enabled = selection.isNotEmpty() && !loading, onClick = { pending = apps.orEmpty().filter { it.packageName in selection }.toList() }) { Text("检查选中 ${selection.size} 个应用") } }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (results?.isEmpty() == true) item { Text("所选应用暂无更新") }
            items(results.orEmpty(), key = { "update:${it.packageName}" }) { update ->
                Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(update.title, style = MaterialTheme.typography.titleMedium)
                    Text("${update.installedVersion} (${update.installedVersionCode}) → ${update.versionName} (${update.versionCode})")
                    if (update.size.isNotBlank()) Text(update.size)
                    Text(update.changelog)
                    Text("下载将单独展示目标网站，由你确认后交给系统浏览器；不会自动安装。", style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = resolving == null, onClick = {
                        resolving = update.packageName; error = null
                        scope.launch {
                            try { when (val result = repository.downloadLocation(update)) {
                                is ApiResult.Success -> downloadPreview = update to result.value
                                is ApiResult.Failure -> error = result.message
                            } } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { error = "无法获取下载地址，请重试" }
                            finally { resolving = null }
                        }
                    }) { Text(if (resolving == update.packageName) "正在获取下载地址" else "下载（先查看目标网站）") }
                    TextButton(onClick = { onOpenApp(update.packageName) }) { Text("查看应用详情") }
                    TextButton(onClick = { try { openWebLink(context, "https://www.coolapk.com/apk/${update.packageName}", true) } catch (_: Exception) { error = "无法打开系统浏览器" } }) { Text("在官方网页查看下载") }
                } }
            }
        }
    }
}
