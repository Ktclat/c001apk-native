// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ktclat.c001apk.nativeapp.BuildConfig
import io.github.ktclat.c001apk.nativeapp.R
import io.github.ktclat.c001apk.nativeapp.platform.openWebLink
import io.github.ktclat.c001apk.nativeapp.ui.FlutterMaterialIcons
import io.github.ktclat.c001apk.nativeapp.ui.ProfileIcons
import java.io.IOException

internal object NativeAboutLinks {
    const val PROJECT = "https://github.com/Ktclat/c001apk-native"
    const val LICENSE = "$PROJECT/blob/main/LICENSE"
    const val ISSUES = "$PROJECT/issues"
    // main does not yet contain these notices; use the verified immutable project source.
    const val NOTICES = "$PROJECT/blob/aac8cf43362762d4e6763695e85bee2f71b9064f/THIRD_PARTY_NOTICES.md"
    const val FLUTTER_SOURCE = "https://github.com/Ktclat/c001apk"
    val all = setOf(PROJECT, LICENSE, ISSUES, NOTICES, FLUTTER_SOURCE)
}

internal data class BundledNotice(val file: String, val title: String)
internal val bundledNativeNotices = listOf(
    BundledNotice("Flutter-BSD.txt", "Flutter · BSD 许可"),
    BundledNotice("FlutterLauncher-NOTICE.txt", "c001apk 标志与启动图标声明"),
    BundledNotice("FlutterMaterialIcons-CC-BY-4.0.txt", "Flutter Material 图标 · CC BY 4.0"),
    BundledNotice("FlutterMaterialIcons-NOTICE.txt", "Flutter Material 图标来源与转换声明"),
    BundledNotice("NotoSansCJK-NOTICE.txt", "Noto Sans CJK 字体声明"),
    BundledNotice("NotoSansCJK-OFL.txt", "Noto Sans CJK · SIL OFL 1.1"),
    BundledNotice("PiliPlus-GPL-3.0.txt", "PiliPlus 配色来源 · GPL-3.0"),
    BundledNotice("TextEmoticons-NOTICE.txt", "文字表情来源声明"),
)
internal const val BUNDLED_NOTICE_SCOPE = "这里只展示本项目已打包的 8 份许可与来源声明，不代表完整依赖许可审计。项目源码的 AGPL-3.0 与第三方材料各自的许可并不相互替代。"

/** Reads local identity only. An external page opens solely after an explicit row click. */
@Composable
internal fun NativeAboutScreen(
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenUpdates: () -> Unit,
    onOpenNotices: () -> Unit,
    openExternal: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val appName = remember(context) { context.applicationInfo.loadLabel(context.packageManager).toString() }
    var error by remember { mutableStateOf<String?>(null) }
    fun open(url: String) {
        check(url in NativeAboutLinks.all)
        try {
            if (openExternal != null) openExternal(url) else openWebLink(context, url, preferExternal = true)
            error = null
        } catch (_: android.content.ActivityNotFoundException) {
            error = "未找到可打开此链接的浏览器，请安装或启用浏览器后重试。"
        } catch (_: SecurityException) {
            error = "系统未允许打开浏览器，此链接尚未打开。"
        }
    }
    AboutScaffold("关于", "native-about", onBack, error) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .testTag("native-about-list"), state = rememberLazyListState(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "logo") {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Surface(shape = RoundedCornerShape(24.dp), color = colorResource(R.color.ic_launcher_background)) {
                        Image(painterResource(R.drawable.ic_launcher_foreground), "c001apk 标志", Modifier.size(96.dp))
                    }
                }
            }
            item { HorizontalDivider() }
            item(key = "identity") { AboutRow(appName, "一个轻量的非官方酷安客户端 · Android 原生版", FlutterMaterialIcons.AllInclusive, "about-identity") }
            item(key = "version") { AboutRow("版本", "${BuildConfig.VERSION_NAME} · 版本号 ${BuildConfig.VERSION_CODE} · ${BuildConfig.BUILD_TYPE}", FlutterMaterialIcons.ErrorOutline, "about-version") }
            item(key = "diagnostics") { AboutRow("构建与本地诊断", "版本、窗口与脱敏请求摘要；不联网、不自动上传", ImageVector.vectorResource(R.drawable.flutter_build_outlined), "about-diagnostics", onOpenDiagnostics) }
            item(key = "project") { AboutRow("项目地址", NativeAboutLinks.PROJECT, ImageVector.vectorResource(R.drawable.flutter_code), "about-project") { open(NativeAboutLinks.PROJECT) } }
            item(key = "updates") { AboutRow("原生客户端更新", "打开更新设置；仅手动检查或沿用已有启动检查设置", FlutterMaterialIcons.Update, "about-updates", onOpenUpdates) }
            item(key = "license") { AboutRow("项目许可证 · AGPL-3.0", "查看 Native 仓库保留的完整 GNU AGPL-3.0 文本", ImageVector.vectorResource(R.drawable.flutter_gavel_outlined), "about-license") { open(NativeAboutLinks.LICENSE) } }
            item(key = "bundled") { AboutRow("已打包许可与声明", "离线查看 8 份声明；不是完整依赖许可审计", ImageVector.vectorResource(R.drawable.flutter_source_outlined), "about-bundled", onOpenNotices) }
            item { HorizontalDivider() }
            item(key = "issues") { AboutRow("问题反馈", "在 Native 项目仓库查看或提交问题，不会自动发送反馈", ImageVector.vectorResource(R.drawable.flutter_bug_report_outlined), "about-issues") { open(NativeAboutLinks.ISSUES) } }
            item(key = "flutter") { AboutRow("Flutter 来源", NativeAboutLinks.FLUTTER_SOURCE, ImageVector.vectorResource(R.drawable.flutter_code), "about-flutter") { open(NativeAboutLinks.FLUTTER_SOURCE) } }
            item(key = "notices") { AboutRow("来源与致谢", "保留原维护者署名与第三方授权声明", ProfileIcons.FavoriteBorder, "about-notices") { open(NativeAboutLinks.NOTICES) } }
        }
    }
}

/** Fixed asset allowlist; no external link parsing, transport, dependency-scan or license claims. */
@Composable
internal fun NativeBundledNoticesScreen(onBack: () -> Unit) {
    var selectedFile by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = bundledNativeNotices.firstOrNull { it.file == selectedFile }
    val listState = rememberLazyListState()
    val back = { if (selected != null) selectedFile = null else onBack() }
    BackHandler(selected != null, back)
    AboutScaffold("已打包许可", "native-bundled-notices", back) { padding ->
        if (selected == null) {
            LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).testTag("bundled-notice-list"),
                state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                item { Text(BUNDLED_NOTICE_SCOPE, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) }
                items(bundledNativeNotices, key = { it.file }) { notice ->
                    AboutRow(notice.title, notice.file, ImageVector.vectorResource(R.drawable.flutter_source_outlined), "bundled-${notice.file}") { selectedFile = notice.file }
                }
            }
        } else key(selected.file) {
            val context = LocalContext.current
            val text = remember(context, selected.file) {
                try { context.assets.open("licenses/${selected.file}").bufferedReader(Charsets.UTF_8).use { it.readText() } }
                catch (_: IOException) { "此声明暂时无法读取，请返回后重试。" }
            }
            val paragraphs = remember(text) { text.split(Regex("\\n\\s*\\n")) }
            val readerState = rememberSaveable(selected.file, saver = LazyListState.Saver) { LazyListState() }
            LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).testTag("bundled-notice-reader"),
                state = readerState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text(selected.title, Modifier.testTag("bundled-notice-title"), style = MaterialTheme.typography.titleLarge) }
                item { Text(selected.file, style = MaterialTheme.typography.labelLarge) }
                items(paragraphs.size, key = { it }) { index ->
                    Text(paragraphs[index], Modifier.fillMaxWidth().testTag("bundled-notice-paragraph-$index"), style = MaterialTheme.typography.bodyMedium)
                }
                item { Text("声明结束", Modifier.testTag("bundled-notice-end"), style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AboutScaffold(title: String, tag: String, onBack: () -> Unit, message: String? = null, content: @Composable (PaddingValues) -> Unit) {
    // Union is a per-edge maximum, never status + gesture padding. Consume it exactly once.
    val safe = WindowInsets.safeDrawing.union(WindowInsets.systemGestures)
        .union(WindowInsets.mandatorySystemGestures).union(WindowInsets.tappableElement)
    Scaffold(Modifier.fillMaxSize().testTag(tag), contentWindowInsets = safe, topBar = {
        Column {
            TopAppBar(title = { Text(title) }, windowInsets = safe.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                navigationIcon = { IconButton(onClick = onBack, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("$tag-back")) { Icon(FlutterMaterialIcons.ArrowBack, "返回") } })
            message?.let {
                Text(it, Modifier.windowInsetsPadding(safe.only(WindowInsetsSides.Horizontal)).padding(16.dp)
                    .testTag("about-link-error").semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
            }
        }
    }, content = content)
}

@Composable
private fun AboutRow(title: String, subtitle: String, icon: ImageVector, tag: String, onClick: (() -> Unit)? = null) {
    ListItem(headlineContent = { Text(title, Modifier.testTag("$tag-title")) },
        supportingContent = { Text(subtitle, Modifier.testTag("$tag-subtitle")) },
        leadingContent = { Icon(icon, null) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag(tag)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier))
}
