// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.github.ktclat.c001apk.nativeapp.local.LibraryKind
import io.github.ktclat.c001apk.nativeapp.ui.ProfileIcons
import io.github.ktclat.c001apk.nativeapp.ui.FlutterMaterialIcons
import io.github.ktclat.c001apk.nativeapp.BuildConfig

enum class SettingsSection(val title: String, val description: String) {
    APPEARANCE("外观与字体", "配色、字体与悬浮 Tab 导航"),
    CONTENT("关注与内容", "本地关注与悄悄关注"),
    PRIVACY("浏览与隐私", "黑名单、浏览历史与外部链接"),
    STORAGE("存储与更新", "本地记录、版本与更新检查"),
    PROTOCOL("协议与设备", "设备与客户端请求参数")
}
@Composable private fun sectionIcon(section: SettingsSection): ImageVector = when(section) {
    SettingsSection.APPEARANCE -> ProfileIcons.PaletteOutlined
    SettingsSection.CONTENT -> ProfileIcons.FeedOutlined
    SettingsSection.PRIVACY -> ProfileIcons.PrivacyTipOutlined
    SettingsSection.STORAGE -> ProfileIcons.StorageOutlined
    SettingsSection.PROTOCOL -> ProfileIcons.DeveloperMode
}
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun SettingsScreen(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    onBack: () -> Unit,
    onOpenLibrary: (LibraryKind) -> Unit,
    onOpenBlacklist: () -> Unit,
    onOpenFollows: () -> Unit,
    onNavigate: (String) -> Unit,
    isLoggedIn: Boolean = false,
    accountCount: Int = 0,
) {
    var selectedName by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = selectedName?.let { SettingsSection.valueOf(it) }
    var draftScale by remember(settings.fontScale) { mutableFloatStateOf(settings.fontScale) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        val detail = selected ?: SettingsSection.APPEARANCE
        BackHandler(!wide && selected != null) { selectedName = null }
        Scaffold(topBar = { TopAppBar(title = { Text(if (wide || selected == null) "设置" else detail.title) }, navigationIcon = {
            IconButton(onClick = { if (!wide && selected != null) selectedName = null else onBack() }) { Icon(FlutterMaterialIcons.ArrowBack, "返回") }
        }) }) { padding ->
            Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                if (wide || selected == null) LazyColumn(Modifier.then(if (wide) Modifier.width(320.dp) else Modifier.fillMaxWidth()).fillMaxHeight(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    SettingsSection.entries.forEach { section -> item {
                        NavigationSetting(section.title, section.description, sectionIcon(section), selected = wide && detail == section) { selectedName = section.name }
                    } }
                    item { HorizontalDivider() }
                    item { NavigationSetting("账号管理", "已保存 $accountCount 个账号 · 切换、添加或移除", ProfileIcons.SwitchAccountOutlined) { onNavigate("accounts") } }
                    item { NavigationSetting("关于", "原生客户端 ${BuildConfig.VERSION_NAME}", ProfileIcons.InfoOutline) { onNavigate("native-diagnostics") } }
                }
                if (wide) VerticalDivider()
                if (wide || selected != null) Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (wide) item { Text(detail.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp)) }
                        when(detail) {
                            SettingsSection.APPEARANCE -> {
            item { Text("外观", style = MaterialTheme.typography.titleMedium) }
            item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ThemeMode.entries.forEach { mode -> FilterChip(selected = settings.theme == mode, onClick = { onSettingsChange(settings.copy(theme = mode)) }, label = { Text(when(mode) { ThemeMode.SYSTEM -> "系统"; ThemeMode.LIGHT -> "浅色"; ThemeMode.DARK -> "深色" }) }) } } }
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
            item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ReadingPreset.entries.forEach { preset -> FilterChip(settings.readingPreset == preset, { onSettingsChange(settings.copy(readingPreset = preset)) }, label = { Text(if (preset == ReadingPreset.COMFORTABLE) "舒适阅读" else "原版排版") }) } } }
            item { SettingSwitch("悬浮导航栏", settings.floatingNavigation) { onSettingsChange(settings.copy(floatingNavigation = it)) } }

                            }
                            SettingsSection.CONTENT -> {
                                if (isLoggedIn) item { SettingSwitch("悄悄关注（仅保存在本机）", settings.secretFollow) { onSettingsChange(settings.copy(secretFollow = it)) } }
                                else item { Text("本地关注", style = MaterialTheme.typography.titleMedium); Text("游客可在用户主页直接关注，并在底栏“关注”查看内容。名单仅保存在本机。") }
                                item { NavigationSetting("管理本地关注", "查看本机关注名单、进入用户主页或取消关注", ProfileIcons.PersonAddOutlined, onClick = onOpenFollows) }
                                item { Text("表情图片显示尚未接入，当前保留内容中的表情文字标记。", style = MaterialTheme.typography.bodySmall) }
                            }
                            SettingsSection.PRIVACY -> {
                                item { NavigationSetting("用户与话题黑名单", "管理屏蔽的用户、话题与关键词", FlutterMaterialIcons.Block, onClick = onOpenBlacklist) }
                                item { SettingSwitch("记录浏览历史", settings.recordHistory) { onSettingsChange(settings.copy(recordHistory = it)) } }
                                item { SettingSwitch("在浏览器中打开", settings.openInBrowser) { onSettingsChange(settings.copy(openInBrowser = it)) } }
                                item { Text("收藏、历史与本地关注不会随账号切换而删除。", style = MaterialTheme.typography.bodySmall) }
                            }
                            SettingsSection.STORAGE -> {
                                item { NavigationSetting("c001apk 原生版更新", "自动检查开关、手动检查与正式版本", FlutterMaterialIcons.Update) { onNavigate("native-updates") } }
                                item { NavigationSetting("检查已安装应用更新", "选择应用后查询，不会自动安装", FlutterMaterialIcons.Smartphone) { onNavigate("app-updates") } }
                                item { NavigationSetting("本地收藏", "查看保存在本机的动态", ProfileIcons.ArchiveOutlined) { onOpenLibrary(LibraryKind.FAVORITES) } }
                                item { NavigationSetting("浏览历史", "查看本机浏览记录", ProfileIcons.History) { onOpenLibrary(LibraryKind.HISTORY) } }
                                item { Text("图片缓存清理尚未接入；不会把账号、本地收藏或历史当作缓存删除。", style = MaterialTheme.typography.bodySmall) }
                            }
                            SettingsSection.PROTOCOL -> {
                                item { Text("设备标识与协议参数", style = MaterialTheme.typography.titleMedium) }
                                item { Text("原生版当前使用独立的客户端请求与设备标识。官方设备标识导入、手动填写设备信息和协议参数编辑尚未接入；这里不会提供无效开关，也不会读取或显示登录凭据。") }
                                item { NavigationSetting("本地诊断", "版本、界面与脱敏首页请求摘要；仅手动复制，不含账号凭据", ProfileIcons.InfoOutline) { onNavigate("native-diagnostics") } }
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable private fun NavigationSetting(title: String, subtitle: String, icon: ImageVector, selected: Boolean = false, onClick: () -> Unit) {
    Surface(color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
        ListItem(headlineContent = { Text(title) }, supportingContent = { Text(subtitle) }, leadingContent = { Icon(icon, null) }, trailingContent = { Icon(ProfileIcons.KeyboardArrowRightOutlined, null) }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick), colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent))
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
