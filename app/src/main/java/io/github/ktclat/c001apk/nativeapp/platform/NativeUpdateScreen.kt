// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.platform

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.ktclat.c001apk.nativeapp.BuildConfig
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeUpdateScreen(onBack: () -> Unit, service: NativeUpdateService? = null, onOpenDiagnostics: (() -> Unit)? = null) {
    val context = LocalContext.current
    val updater = service ?: remember(context) { NativeUpdateService(context.applicationContext) }
    var result by remember { mutableStateOf<NativeUpdateResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun open(url: String) { try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { error = "找不到可打开链接的浏览器" } }
    Scaffold(topBar = { TopAppBar(title = { Text("原生客户端更新") }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("当前版本 ${BuildConfig.VERSION_NAME}")
            onOpenDiagnostics?.let { openDiagnostics -> TextButton(onClick = openDiagnostics) { Text("关于构建与窗口（本地诊断）") } }
            Text("仅检查 Ktclat/c001apk-native 正式发布，不会下载 Flutter 版或自动安装。")
            io.github.ktclat.c001apk.nativeapp.settings.NativeAutomaticUpdateSetting(updater)
            Button(enabled = result != NativeUpdateResult.Busy, onClick = { scope.launch { result = NativeUpdateResult.Busy; result = updater.check(BuildConfig.VERSION_NAME) } }) { Text("检查更新") }
            when (val state = result) {
                NativeUpdateResult.NoRelease -> Text("原生仓库暂无正式版本。不会回退到 Flutter 安装包。")
                NativeUpdateResult.Busy -> CircularProgressIndicator()
                is NativeUpdateResult.Failed -> Text(state.message)
                is NativeUpdateResult.Current -> Text("已是最新正式版本 ${state.release.version}")
                is NativeUpdateResult.Available -> {
                    Text("发现版本 ${state.release.version}", style = MaterialTheme.typography.titleLarge)
                    Text(state.release.notes)
                    TextButton(onClick = { updater.skip(state.release.version); result = NativeUpdateResult.Suppressed }) { Text("跳过此版本的自动提示") }
                    // Download is deliberately left to a user-selected release asset/browser. No installer permission.
                    state.release.downloads.forEach { (name, url) -> TextButton(onClick = { open(url) }) { Text("在浏览器下载 $name") } }
                    TextButton(onClick = { open(state.release.page) }) { Text("查看此版本发布页面") }
                }
                NativeUpdateResult.Suppressed -> Text("已跳过自动提示，仍可手动检查")
                null -> Unit
            }
            TextButton(onClick = { open(NATIVE_RELEASES) }) { Text("打开原生仓库发布页面") }
            error?.let { Text(it) }
        }
    }
}

/** Host once at the application root; manual checks deliberately ignore skipped versions. */
@Composable
fun NativeUpdatePrompt(service: NativeUpdateService, onOpenUpdates: () -> Unit) {
    var release by remember { mutableStateOf<NativeRelease?>(null) }
    LaunchedEffect(service) {
        release = (service.check(BuildConfig.VERSION_NAME, automatic = true) as? NativeUpdateResult.Available)?.release
    }
    release?.let { available ->
        AlertDialog(onDismissRequest = { release = null }, title = { Text("发现原生版本 ${available.version}") },
            text = { Text(available.notes.take(1200)) },
            confirmButton = { TextButton(onClick = { release = null; onOpenUpdates() }) { Text("查看更新") } },
            dismissButton = { Row {
                TextButton(onClick = { release = null }) { Text("稍后") }
                TextButton(onClick = { service.skip(available.version); release = null }) { Text("跳过此版本") }
            } })
    }
}
