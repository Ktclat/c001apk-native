// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ktclat.c001apk.nativeapp.platform.NativeUpdatePreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable internal fun NativeAutomaticUpdateSetting(service: NativeUpdatePreference) {
    var enabled by remember(service) { mutableStateOf(service.enabled) }
    var busy by remember(service) { mutableStateOf(false) }
    var error by remember(service) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column {
        SettingSwitch("自动检查 c001apk 原生版更新", enabled, "每次启动时检查一次原生仓库正式版本，不下载或安装应用", enabled = !busy) { desired ->
            if (busy) return@SettingSwitch
            busy = true
            scope.launch {
                try { service.setEnabledAndPersist(desired); enabled = service.enabled; error = false }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = true }
                finally { enabled = service.enabled; busy = false }
            }
        }
        if (error) Text("无法确认更新检查设置已保存，重启后可能恢复。当前开关显示本次运行的状态，请重试。", color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

/** Cache APIs, not file deletion. Back/Cancel never initiates a clear. */
@Composable internal fun ImageCacheSetting(controller: ImageCacheController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bytes by remember(controller) { mutableStateOf<Long?>(null) }
    var busy by remember(controller) { mutableStateOf(true) }
    var confirm by remember(controller) { mutableStateOf(false) }
    var status by remember(controller) { mutableStateOf<String?>(null) }
    var readFailed by remember(controller) { mutableStateOf(false) }
    fun size(value: Long) = Formatter.formatFileSize(context, value)
    suspend fun inspect() {
        busy = true
        try { bytes = controller.sizeBytes(); readFailed = false; status = null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { bytes = null; readFailed = true; status = "无法读取图片缓存，请重试" }
        finally { busy = false }
    }
    LaunchedEffect(controller) { inspect() }
    OutlinedCard(Modifier.fillMaxWidth().testTag("settings-image-cache")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("图片缓存", style = MaterialTheme.typography.titleMedium)
            Text(when { busy -> "正在处理图片缓存…"; bytes != null -> "磁盘图片缓存：${size(bytes!!)}"; else -> "磁盘图片缓存大小暂不可用" })
            Text("清理已加载图片的内存与磁盘缓存。不会删除账号、本地收藏、历史、关注名单或待发送图片。正在加载的图片可能重新写入缓存。", style = MaterialTheme.typography.bodySmall)
            status?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = if (readFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !busy, onClick = { if (!busy) { busy = true; scope.launch { inspect() } } }) { Text("刷新大小") }
                Button(enabled = !busy, onClick = { if (!busy) confirm = true }) { Text("清理图片缓存") }
            }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("清理图片缓存？") },
        text = { Text("仅清理可重新下载的图片缓存。账号、收藏、历史、关注名单和待发送图片不会被删除。") },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            if (!confirm || busy) return@TextButton
            confirm = false; busy = true; status = null
            scope.launch {
                try {
                    val result = controller.clear()
                    bytes = result.remainingBytes
                    readFailed = result.failedCaches > 0
                    status = if (result.failedCaches > 0) "部分图片缓存未能清理，请重试。已清理约 ${size(result.removedBytes)}。"
                        else "已清理图片缓存，释放约 ${size(result.removedBytes)}；已清除内存图片缓存。"
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { readFailed = true; bytes = null; status = "图片缓存清理未完成，请重试；其他本地数据未删除。" }
                finally { busy = false }
            }
        }) { Text("确认清理") } })
}
