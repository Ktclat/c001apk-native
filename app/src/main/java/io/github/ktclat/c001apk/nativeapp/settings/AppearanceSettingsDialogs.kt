// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import io.github.ktclat.c001apk.nativeapp.ui.FlutterMaterialIcons
import kotlin.math.roundToInt

internal enum class AppearanceDetail(val title: String) {
    FONT("界面字体"), READING("正文排版"), SCALE("应用字号")
}
internal val FontChoice.label: String get() = when (this) {
    FontChoice.SYSTEM -> "跟随系统"
    FontChoice.MATERIAL -> "Material 原版"
    FontChoice.NOTO_SANS -> "思源黑体 · 清爽"
}
internal val ReadingPreset.label: String get() = if (this == ReadingPreset.COMFORTABLE) "舒适阅读" else "原版排版"
internal fun fontComparisonStyle(font: FontChoice) = TextStyle(
    fontFamily = appFontFamily(font), fontSize = 15.sp, lineHeight = 22.8.sp,
    fontWeight = FontWeight.Normal, letterSpacing = 0.sp,
)

/** Real choice-specific samples, never inherited from the currently selected app font. */
@Composable internal fun AppearanceSettingsDetail(
    detail: AppearanceDetail,
    settings: AppSettings,
    draftScale: Float,
    onDraftScaleChange: (Float) -> Unit,
    onDismiss: () -> Unit,
    onSettingsEdit: ((AppSettings) -> AppSettings) -> Unit,
) {
    AppearanceChoiceSheet(detail.title, onDismiss) {
        when (detail) {
            AppearanceDetail.FONT -> {
                Text("点击选项立即应用；仅关闭页面不会更改字体。", style = MaterialTheme.typography.bodySmall)
                Column(Modifier.selectableGroup()) {
                    FontChoice.entries.forEach { font ->
                        AppearanceChoice(font.label, settings.font == font, "font-choice-${font.name}", {
                            onDismiss()
                            onSettingsEdit { current -> current.copy(font = font) }
                        }) {
                            Text(when (font) {
                                FontChoice.SYSTEM -> "采用设备的 sans-serif 字体映射，受手机厂商与系统字体设置影响。"
                                FontChoice.MATERIAL -> "采用 Material 默认字体映射，在部分设备上可能与系统字体相同。"
                                FontChoice.NOTO_SANS -> "内置 Noto Sans CJK SC，离线显示一致，无需下载。"
                            }, style = MaterialTheme.typography.bodySmall)
                            Text("中文阅读效果 · 字形清晰，细节自然。123 ABC", style = fontComparisonStyle(font),
                                modifier = Modifier.testTag("font-sample-${font.name}"))
                        }
                    }
                }
                Text("字体应用于界面与阅读内容；字号与正文排版独立设置。", style = MaterialTheme.typography.bodySmall)
            }
            AppearanceDetail.READING -> {
                Text("点击选项立即应用；下方示例使用当前界面字体。", style = MaterialTheme.typography.bodySmall)
                Column(Modifier.selectableGroup()) {
                    ReadingPreset.entries.forEach { preset ->
                        val styles = remember(preset, settings.font) { readingStyles(preset, settings.font) }
                        AppearanceChoice(preset.label, settings.readingPreset == preset, "reading-choice-${preset.name}", {
                            onDismiss()
                            onSettingsEdit { current -> current.copy(readingPreset = preset) }
                        }) {
                            Text(if (preset == ReadingPreset.COMFORTABLE) "轻量字号与舒展行距，标题字重更柔和。" else "保留较大的正文字号与醒目的粗体标题。", style = MaterialTheme.typography.bodySmall)
                            Text("把想法写下来", style = styles.title, modifier = Modifier.testTag("reading-title-${preset.name}"))
                            Text("九寨沟的景色，不需要滤镜也很美。把每一次发现记录下来，慢慢阅读。", style = styles.body,
                                modifier = Modifier.testTag("reading-body-${preset.name}"))
                            Text("评论文字 · 轻松阅读每一次交流", style = styles.reply,
                                modifier = Modifier.testTag("reading-reply-${preset.name}"))
                        }
                    }
                }
                Text("仅调整动态、文章与主要评论的排版，不压缩导航或操作控件。界面字体、应用字号与系统字号仍独立生效。", style = MaterialTheme.typography.bodySmall)
            }
            AppearanceDetail.SCALE -> FontScaleEditor(settings, draftScale, onDraftScaleChange, onDismiss, onSettingsEdit)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun AppearanceChoiceSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    // ModalBottomSheet owns an Android window and reinstalls its resource density. Re-provide
    // the host’s effective system × app scale for both controls and comparison samples.
    val hostDensity = LocalDensity.current
    val containerColor = BottomSheetDefaults.ContainerColor
    val lightSystemBars = containerColor.luminance() > 0.5f
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = containerColor) {
        // material3:1.3.1 initializes this separate window from SYSTEM brightness. Match the
        // resolved sheet surface after those defaults; never change the host window or scrims.
        val sheetWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            sheetWindow?.let { window ->
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = lightSystemBars
                    isAppearanceLightNavigationBars = lightSystemBars
                }
            }
        }
        CompositionLocalProvider(LocalDensity provides hostDensity) {
        Column(Modifier.fillMaxWidth().testTag("appearance-detail")) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
                IconButton(onClick = onDismiss, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Icon(FlutterMaterialIcons.Close, "关闭$title") }
            }
            Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())
                .testTag("appearance-detail-scroll").padding(horizontal = 20.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
        }
    }
}

@Composable private fun AppearanceChoice(
    title: String,
    selected: Boolean,
    tag: String,
    onSelect: () -> Unit,
    samples: @Composable ColumnScope.() -> Unit,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag)
        .selectable(selected, role = Role.RadioButton, onClick = onSelect).padding(vertical = 12.dp),
        verticalAlignment = Alignment.Top) {
        RadioButton(selected, onClick = null)
        Column(Modifier.weight(1f).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            samples()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun FontScaleEditor(settings: AppSettings, draft: Float, onDraftChange: (Float) -> Unit, onDismiss: () -> Unit, onSettingsEdit: ((AppSettings) -> AppSettings) -> Unit) {
    var applied by remember { mutableStateOf(false) }
    val percent = (draft * 100).roundToInt()
    Text("预览字号：$percent%", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("font-scale-value"))
    Text("80–160%，每次调整 5%。只影响下方预览；点击“应用”后保存。关闭、取消或返回不会保存草稿。", style = MaterialTheme.typography.bodySmall)
    Slider(value = draft, onValueChange = { onDraftChange((it * 20).roundToInt() / 20f) }, valueRange = 0.8f..1.6f, steps = 15,
        modifier = Modifier.semantics { contentDescription = "应用字号"; stateDescription = "$percent%" })
    TextButton(onClick = { onDraftChange(1f) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("恢复默认字号") }
    val density = LocalDensity.current
    // NativeTheme already combines system and app scales; replace only the app factor for samples.
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * draft / settings.fontScale)) {
        Box(Modifier.testTag("font-scale-preview")) { ReadingPreview(settings) }
    }
    Text("应用字号会叠加系统字号，不会修改设备的系统设置。", style = MaterialTheme.typography.bodySmall)
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
        Button(enabled = !applied, modifier = Modifier.heightIn(min = 48.dp), onClick = {
            if (!applied) {
                applied = true
                val selectedScale = draft
                onDismiss()
                onSettingsEdit { current -> current.copy(fontScale = selectedScale) }
            }
        }) { Text("应用") }
    }
}
