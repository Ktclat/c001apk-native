// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.diagnostics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.ktclat.c001apk.nativeapp.BuildConfig
import io.github.ktclat.c001apk.nativeapp.ui.SystemAccessibility

/** No network, storage, account access or clipboard reads. Copy is the sole explicit export action. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NativeDiagnosticsScreen(floatingNavigation: Boolean, accessibility: SystemAccessibility, onBack: () -> Unit) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val configuration = LocalConfiguration.current
    val clipboard = LocalClipboardManager.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    fun WindowInsets.snapshot() = DiagnosticInsets(getLeft(density, direction), getTop(density), getRight(density, direction), getBottom(density))
    val report = NativeDiagnostics(
        BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.BUILD_TYPE,
        NativeBuildInfo.COMMIT, NativeBuildInfo.BRANCH, NativeBuildInfo.WORKTREE,
        NativeBuildInfo.SOURCE_FINGERPRINT, NativeBuildInfo.FINGERPRINT_SCOPE,
        size.width, size.height, density.density, configuration.fontScale, density.fontScale,
        WindowInsets.safeDrawing.snapshot(), WindowInsets.systemBars.snapshot(),
        WindowInsets.systemGestures.snapshot(), WindowInsets.mandatorySystemGestures.snapshot(),
        WindowInsets.ime.snapshot(), floatingNavigation, accessibility.touchExploration, accessibility.reduceMotion,
    )
    NativeDiagnosticsContent(report, onBack, { clipboard.setText(AnnotatedString(it)) }, Modifier.onSizeChanged { size = it })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NativeDiagnosticsContent(report: NativeDiagnostics, onBack: () -> Unit, onCopy: (String) -> Unit, modifier: Modifier = Modifier) {
    var copied by remember { mutableStateOf(false) }
    Scaffold(modifier.fillMaxSize().testTag("native-diagnostics"), contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(title = { Text("本地诊断") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = { TextButton(onClick = { onCopy(report.copyText()); copied = true }, modifier = Modifier.testTag("copy-native-diagnostics")) { Text("复制") } })
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
            .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("仅在本机显示，不联网、不自动上传。只有点击复制才会写入系统剪贴板；不会包含账号、设备标识或本地路径。")
            if (copied) Text("已复制到系统剪贴板", Modifier.testTag("native-diagnostics-copied"))
            Text("源码指纹只覆盖白名单，不是 APK 哈希。dirty 表示源码有未提交改动；unavailable 表示无法确认，不能视为干净提交。")
            report.entries().forEach { (name, value) ->
                Column {
                    Text(name, style = MaterialTheme.typography.labelLarge)
                    Text(value, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text("窗口是当前应用内容的实测尺寸，不是设备屏幕规格。边距来自当前窗口；返回根页后才适用根页导航门控。")
        }
    }
}
