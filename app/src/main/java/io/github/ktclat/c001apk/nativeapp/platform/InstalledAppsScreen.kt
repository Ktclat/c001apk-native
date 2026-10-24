// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.platform

import io.github.ktclat.c001apk.nativeapp.ui.floatingDockRecovery
import io.github.ktclat.c001apk.nativeapp.ui.LocalFloatingDockReveal
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InstalledAppsScreen(listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(), onOpen: (String) -> Unit) {
    val context = LocalContext.current
    var loadState by remember { mutableStateOf(InstalledAppsLoadState()) }
    var refresh by remember { mutableIntStateOf(0) }
    var sort by remember { mutableStateOf(AppSort.NAME) }
    LaunchedEffect(refresh) {
        loadAndPublishInstalledApps(loadState, load = { installedLauncherApps(context) }) {
            loadState = it
        }
    }
    // Capture one immutable state value in this composition. The lazy builder must not reread
    // mutable load state after ordered was derived from a different composition snapshot.
    val snapshot = loadState
    val apps = snapshot.apps
    val error = if (snapshot.failed) "无法读取可见应用，请重试" else null
    val ordered = remember(apps, sort) { sortInstalledApps(apps.orEmpty(), sort) }
    val rootHeader = io.github.ktclat.c001apk.nativeapp.ui.LocalScrollableRootHeader.current
    io.github.ktclat.c001apk.nativeapp.ui.ObserveRootReselect(listState)
    val floatingTail = io.github.ktclat.c001apk.nativeapp.ui.LocalFloatingBottomSpace.current
    // The overlay tail already includes its safe visual gap. Adding the ordinary16dp again
    // pushed a tall final app row above the viewport at the absolute end.
    val bottomPadding = if (floatingTail > 0.dp) floatingTail else 16.dp
    LazyColumn(state = listState, modifier = Modifier.testTag("installed-apps-list").floatingDockRecovery(LocalFloatingDockReveal.current), contentPadding = PaddingValues(bottom = bottomPadding)) {
        rootHeader?.let { header -> item(key = "root-header") { header() } }
        item { Text("仅显示系统允许查询的非系统可启动应用，不申请全量应用列表权限。此范围与 Flutter 全量枚举不同。", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
        item { FlowRow {
            TextButton(onClick = { sort = AppSort.NAME }) { Text("名称排序") }
            TextButton(onClick = { sort = AppSort.UPDATED }) { Text("更新时间") }
            TextButton(onClick = { refresh++ }) { Text("刷新") }
        } }
        error?.let { item { Text(it, Modifier.padding(16.dp)) } }
        if (apps == null && error == null) item { CircularProgressIndicator() }
        if (apps?.isEmpty() == true) item { Text("暂无系统允许查询的非系统应用", Modifier.padding(16.dp)) }
        items(ordered, key = { it.packageName }) { app ->
            val icon by produceState<android.graphics.drawable.Drawable?>(null, app.packageName, app.lastUpdateTime) {
                value = withContext(Dispatchers.IO) { runCatching { context.packageManager.getApplicationIcon(app.packageName) }.getOrNull() }
            }
            ListItem(headlineContent = { Text(app.label) }, supportingContent = { Text("${app.packageName}\n${app.versionName} (${app.versionCode})\n${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(app.lastUpdateTime))}") },
                leadingContent = { AsyncImage(icon, null, Modifier.size(48.dp)) }, modifier = Modifier.testTag("installed-app:${app.packageName}").clickable { onOpen(app.packageName) })
        }
    }
}
