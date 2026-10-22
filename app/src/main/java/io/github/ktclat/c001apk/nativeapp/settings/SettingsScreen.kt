// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ktclat.c001apk.nativeapp.local.LibraryKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SettingsScreen(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    onBack: () -> Unit,
    onOpenLibrary: (LibraryKind) -> Unit,
    onOpenBlacklist: () -> Unit,
    onOpenFollows: () -> Unit,
) {
    var draftScale by remember(settings.fontScale) { mutableFloatStateOf(settings.fontScale) }
    Scaffold(topBar = { TopAppBar(title = { Text("设置") }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("外观", style = MaterialTheme.typography.titleMedium) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ThemeMode.entries.forEach { mode -> FilterChip(selected = settings.theme == mode, onClick = { onSettingsChange(settings.copy(theme = mode)) }, label = { Text(when(mode) { ThemeMode.SYSTEM -> "系统"; ThemeMode.LIGHT -> "浅色"; ThemeMode.DARK -> "深色" }) }) } } }
            item { SettingSwitch("动态取色（Android 12 及以上）", settings.dynamicColor) { onSettingsChange(settings.copy(dynamicColor = it)) } }
            item { FixedColorPicker(settings, onSettingsChange) }
            item { Text("界面字体", style = MaterialTheme.typography.titleMedium) }
            items(FontChoice.entries.size) { index ->
                val font = FontChoice.entries[index]
                Row(Modifier.fillMaxWidth().heightIn(min=56.dp).selectable(selected=settings.font==font, role=Role.RadioButton, onClick={ onSettingsChange(settings.copy(font=font)) })) {
                    RadioButton(selected=settings.font==font, onClick=null)
                    Column(Modifier.weight(1f).padding(vertical=8.dp)) {
                        Text(when(font) { FontChoice.SYSTEM -> "跟随系统"; FontChoice.MATERIAL -> "Material 原版"; FontChoice.NOTO_SANS -> "思源黑体 · 清爽" })
                        Text(when(font) { FontChoice.SYSTEM -> "采用设备的 sans-serif 字体映射"; FontChoice.MATERIAL -> "采用 Material 默认映射，可能与系统字体相同"; FontChoice.NOTO_SANS -> "内置 Noto Sans CJK SC，离线显示一致" }, style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { Text("应用字号：${(draftScale * 100).toInt()}%（叠加系统字号）"); Slider(modifier = Modifier.semantics { contentDescription = "应用字号"; stateDescription = "${(draftScale * 100).toInt()}%" }, value = draftScale, onValueChange = { draftScale = it }, onValueChangeFinished = { onSettingsChange(settings.copy(fontScale = draftScale)) }, valueRange = 0.8f..1.6f, steps = 7) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ReadingPreset.entries.forEach { preset -> FilterChip(settings.readingPreset == preset, { onSettingsChange(settings.copy(readingPreset = preset)) }, label = { Text(if (preset == ReadingPreset.COMFORTABLE) "舒适阅读" else "原版排版") }) } } }
            item { SettingSwitch("悬浮导航栏", settings.floatingNavigation) { onSettingsChange(settings.copy(floatingNavigation = it)) } }
            item { SettingSwitch("悄悄关注（仅保存在本机）", settings.secretFollow) { onSettingsChange(settings.copy(secretFollow = it)) } }
            item { SettingSwitch("记录浏览历史", settings.recordHistory) { onSettingsChange(settings.copy(recordHistory = it)) } }
            item { SettingSwitch("在外部浏览器打开网页", settings.openInBrowser) { onSettingsChange(settings.copy(openInBrowser = it)) } }
            item { TextButton(onClick = { onOpenLibrary(LibraryKind.FAVORITES) }) { Text("本地收藏") } }
            item { TextButton(onClick = { onOpenLibrary(LibraryKind.HISTORY) }) { Text("浏览历史") } }
            item { TextButton(onClick = onOpenFollows) { Text("管理本地关注") } }
            item { TextButton(onClick = onOpenBlacklist) { Text("黑名单") } }
            item { Text("本地收藏、历史与关注不会随账号切换而删除。未接入的功能不会在这里显示可用开关。", style = MaterialTheme.typography.bodySmall) }
        }
    }
}
@Composable private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, Modifier.weight(1f).padding(top = 12.dp, end = 8.dp)); Switch(checked, onCheckedChange = null) }
}

@Composable private fun FixedColorPicker(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text("主题颜色", style=MaterialTheme.typography.titleSmall)
        Text("关闭动态取色后可选择固定配色", style=MaterialTheme.typography.bodySmall)
        Box {
            OutlinedButton(onClick={ expanded=true }, enabled=!settings.dynamicColor) { Text(themeSeeds[settings.themeColor].label) }
            DropdownMenu(expanded=expanded, onDismissRequest={expanded=false}, modifier=Modifier.heightIn(max=320.dp)) {
                themeSeeds.forEachIndexed { index, seed -> DropdownMenuItem(text={Text(seed.label)}, onClick={expanded=false; onChange(settings.copy(themeColor=index))}) }
            }
        }
    }
}
