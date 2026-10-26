// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import io.github.ktclat.c001apk.nativeapp.platform.NativeUpdateService
import io.github.ktclat.c001apk.nativeapp.local.LibraryKind
import io.github.ktclat.c001apk.nativeapp.ui.ProfileIcons
import io.github.ktclat.c001apk.nativeapp.ui.FlutterMaterialIcons
import io.github.ktclat.c001apk.nativeapp.BuildConfig
import kotlin.math.roundToInt

enum class SettingsSection(val title: String, val description: String) {
    APPEARANCE("外观与字体", "配色、字体与悬浮 Tab 导航"),
    CONTENT("关注与内容", "账号关注分类与本地关注"),
    PRIVACY("浏览与隐私", "黑名单、浏览历史与外部链接"),
    STORAGE("存储与更新", "图片缓存、本地记录与更新检查"),
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
    nativeUpdates: NativeUpdateService? = null,
    imageCache: ImageCacheController? = null,
    onFollowSelection: (io.github.ktclat.c001apk.nativeapp.domain.FollowType?) -> Unit = { onSettingsChange(settings.selectFollow(it)) },
    onSettingsEdit: ((AppSettings) -> AppSettings) -> Unit = { transform -> onSettingsChange(transform(settings)) },
) {
    val context = LocalContext.current
    val updater = nativeUpdates ?: remember(context) { NativeUpdateService(context.applicationContext) }
    val cache = imageCache ?: remember(context) { ImageCacheController.create(context.applicationContext) }
    var selectedName by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = selectedName?.let { SettingsSection.valueOf(it) }
    var appearanceDetailName by rememberSaveable { mutableStateOf<String?>(null) }
    // Keep the draft in the parent saveable owner, not the modal window’s separate registry.
    var appearanceDraftScale by rememberSaveable { mutableFloatStateOf(settings.fontScale) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        val detail = selected ?: SettingsSection.APPEARANCE
        BackHandler(!wide && selected != null) { selectedName = null }
        Scaffold(topBar = { TopAppBar(title = { Text(if (wide || selected == null) "设置" else detail.title) }, navigationIcon = {
            IconButton(onClick = { if (!wide && selected != null) selectedName = null else onBack() }) { Icon(FlutterMaterialIcons.ArrowBack, "返回") }
        }) }) { padding ->
            Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                if (wide || selected == null) LazyColumn(Modifier.testTag("settings-category-list").then(if (wide) Modifier.width(320.dp) else Modifier.fillMaxWidth()).fillMaxHeight(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    SettingsSection.entries.forEach { section -> item {
                        NavigationSetting(section.title, section.description, sectionIcon(section), selected = wide && detail == section) { selectedName = section.name }
                    } }
                    item { HorizontalDivider() }
                    item { NavigationSetting("账号管理", "已保存 $accountCount 个账号 · 切换、添加或移除", ProfileIcons.SwitchAccountOutlined) { onNavigate("accounts") } }
                    item { NavigationSetting("关于", "原生客户端 ${BuildConfig.VERSION_NAME}", ProfileIcons.InfoOutline) { onNavigate("native-about") } }
                }
                if (wide) VerticalDivider()
                if (wide || selected != null) Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    // A different category starts a fresh detail list, matching Flutter's keyed page.
                    key(detail) { LazyColumn(Modifier.testTag("settings-detail-list").widthIn(max = 760.dp).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (wide) item { Text(detail.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp)) }
                        when(detail) {
                            SettingsSection.APPEARANCE -> {
            item { Text("外观", style = MaterialTheme.typography.titleMedium) }
            item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ThemeMode.entries.forEach { mode -> FilterChip(selected = settings.theme == mode, onClick = { onSettingsEdit { current -> current.copy(theme = mode) } }, label = { Text(when(mode) { ThemeMode.SYSTEM -> "系统"; ThemeMode.LIGHT -> "浅色"; ThemeMode.DARK -> "深色" }) }) } } }
            item { SettingSwitch("动态取色（Android 12 及以上）", settings.dynamicColor) { onSettingsEdit { current -> current.copy(dynamicColor = it) } } }
            item { FixedColorPicker(settings) { index -> onSettingsEdit { it.copy(themeColor = index) } } }
            item { NavigationSetting("界面字体", "当前：${settings.font.label} · 比较字形与选择字体", ProfileIcons.PaletteOutlined) { appearanceDetailName = AppearanceDetail.FONT.name } }
            item { NavigationSetting("正文排版", "当前：${settings.readingPreset.label} · 对比正文、标题与评论", ProfileIcons.FeedOutlined) { appearanceDetailName = AppearanceDetail.READING.name } }
            item { NavigationSetting("应用字号", "当前：${(settings.fontScale * 100).roundToInt()}% · 预览后应用", ProfileIcons.SettingsOutlined) { appearanceDraftScale = settings.fontScale; appearanceDetailName = AppearanceDetail.SCALE.name } }
            item { SettingSwitch("悬浮导航栏", settings.floatingNavigation, "向后浏览时隐藏，反向滚动显示；键盘弹出时隐藏") { onSettingsEdit { current -> current.copy(floatingNavigation = it) } } }
            item { ReadingPreview(settings) }

                            }
                            SettingsSection.CONTENT -> {
                                if (isLoggedIn) item { FollowContentChoices(settings, onFollowSelection) }
                                else item { Text("本地关注", style = MaterialTheme.typography.titleMedium); Text("游客可在用户主页直接关注，并在底栏“关注”查看内容。名单仅保存在本机。") }
                                item { NavigationSetting("管理本地关注", "查看本机关注名单、进入用户主页或取消关注", ProfileIcons.PersonAddOutlined, onClick = onOpenFollows) }
                                item { Text("表情图片显示尚未接入，当前保留内容中的表情文字标记。", style = MaterialTheme.typography.bodySmall) }
                            }
                            SettingsSection.PRIVACY -> {
                                item { NavigationSetting("用户与话题黑名单", "管理屏蔽的用户、话题与关键词", FlutterMaterialIcons.Block, onClick = onOpenBlacklist) }
                                item { SettingSwitch("记录浏览历史", settings.recordHistory, "关闭后不再新增记录，已保存的历史不会删除") { onSettingsEdit { current -> current.copy(recordHistory = it) } } }
                                item { SettingSwitch("在浏览器中打开", settings.openInBrowser, "仅影响外部网页；酷安动态、用户和话题仍在应用内打开") { onSettingsEdit { current -> current.copy(openInBrowser = it) } } }
                                item { Text("收藏、历史与本地关注不会随账号切换而删除。", style = MaterialTheme.typography.bodySmall) }
                            }
                            SettingsSection.STORAGE -> {
                                item { NativeAutomaticUpdateSetting(updater) }
                                item { SettingSwitch("显示已安装应用更新入口", settings.checkAppUpdates, "在首页“应用”列表显示手动检查入口；不会自动扫描、查询或安装") { onSettingsEdit { current -> current.copy(checkAppUpdates = it) } } }
                                item { NavigationSetting("c001apk 原生版更新", "自动检查开关、手动检查与正式版本", FlutterMaterialIcons.Update) { onNavigate("native-updates") } }
                                item { NavigationSetting("检查已安装应用更新", "选择应用后查询，不会自动安装", FlutterMaterialIcons.Smartphone) { onNavigate("app-updates") } }
                                item { NavigationSetting("本地收藏", "查看保存在本机的动态", ProfileIcons.ArchiveOutlined) { onOpenLibrary(LibraryKind.FAVORITES) } }
                                item { NavigationSetting("浏览历史", "查看本机浏览记录", ProfileIcons.History) { onOpenLibrary(LibraryKind.HISTORY) } }
                                item { ImageCacheSetting(cache) }
                            }
                            SettingsSection.PROTOCOL -> {
                                item { Text("设备标识与协议参数", style = MaterialTheme.typography.titleMedium) }
                                item { Text("原生版当前使用独立的客户端请求与设备标识。官方设备标识导入、手动填写设备信息和协议参数编辑尚未接入；这里不会提供无效开关，也不会读取或显示登录凭据。") }
                                item { NavigationSetting("本地诊断", "版本、界面与脱敏首页请求摘要；仅手动复制，不含账号凭据", ProfileIcons.InfoOutline) { onNavigate("native-diagnostics") } }
                            }
                        }
                    } }
                }
            }
        }
    }
    appearanceDetailName?.let { name ->
        AppearanceSettingsDetail(AppearanceDetail.valueOf(name), settings, appearanceDraftScale,
            onDraftScaleChange = { appearanceDraftScale = it }, onDismiss = { appearanceDetailName = null }, onSettingsEdit = onSettingsEdit)
    }

}
@Composable private fun NavigationSetting(title: String, subtitle: String, icon: ImageVector, selected: Boolean = false, onClick: () -> Unit) {
    Surface(color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
        ListItem(headlineContent = { Text(title) }, supportingContent = { Text(subtitle) }, leadingContent = { Icon(icon, null) }, trailingContent = { Icon(ProfileIcons.KeyboardArrowRightOutlined, null) }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick), colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent))
    }
}
@Composable internal fun SettingSwitch(label: String, checked: Boolean, description: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 12.dp, horizontal = 0.dp).padding(end = 8.dp)) {
            Text(label)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable private fun FixedColorPicker(settings: AppSettings, onColorSelected: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text("主题颜色", style=MaterialTheme.typography.titleSmall)
        Text("关闭动态取色后可选择固定配色", style=MaterialTheme.typography.bodySmall)
        Box {
            OutlinedButton(onClick={ expanded=true }, enabled=!settings.dynamicColor) { Text(themeSeeds[settings.themeColor].label) }
            DropdownMenu(expanded=expanded, onDismissRequest={expanded=false}, modifier=Modifier.heightIn(max=320.dp)) {
                themeSeeds.forEachIndexed { index, seed -> DropdownMenuItem(text={Text(seed.label)}, onClick={expanded=false; onColorSelected(index)}) }
            }
        }
    }
}

@Composable internal fun ReadingPreview(settings: AppSettings, modifier: Modifier = Modifier) {
    val reading = remember(settings.font, settings.readingPreset) { readingStyles(settings.readingPreset, settings.font) }
    OutlinedCard(modifier.fillMaxWidth().testTag("settings-reading-preview")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("阅读预览", style = MaterialTheme.typography.labelLarge)
            Text("把想法写下来", style = reading.title, modifier = Modifier.testTag("settings-preview-title"))
            Text("这是一段动态正文。字体与阅读排版会应用到信息流、详情和评论；应用字号会叠加系统字号。", style = reading.body, modifier = Modifier.testTag("settings-preview-body"))
            Text("评论文字 · 轻松阅读每一次交流", style = reading.reply, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
