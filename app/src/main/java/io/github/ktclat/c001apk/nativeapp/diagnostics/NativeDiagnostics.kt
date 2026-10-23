// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.diagnostics

import java.util.Locale

/** Explicit allowlist: no context, account, device/model identifier, paths or arbitrary extras. */
internal data class NativeDiagnostics(
    val versionName: String,
    val versionCode: Int,
    val buildType: String,
    val commit: String,
    val branch: String,
    val worktree: String,
    val sourceFingerprint: String,
    val fingerprintScope: String,
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val systemFontScale: Float,
    val effectiveFontScale: Float,
    val safeDrawing: DiagnosticInsets,
    val systemBars: DiagnosticInsets,
    val systemGestures: DiagnosticInsets,
    val mandatoryGestures: DiagnosticInsets,
    val ime: DiagnosticInsets,
    val floatingNavigation: Boolean,
    val touchExploration: Boolean,
    val reduceMotion: Boolean,
    val homeRequests: List<HomeRequestEvent>? = null,
) {
    fun entries(): List<Pair<String, String>> = listOf(
        "版本" to "$versionName ($versionCode)",
        "构建类型" to buildType,
        "Git commit（不等于当前源码）" to commit,
        "Git 分支" to branch,
        "构建时工作区" to worktree,
        "源码指纹范围" to fingerprintScope,
        "源码 SHA-256" to sourceFingerprint,
        "窗口内容实测 dp（Compose 根）" to "${dp(widthPx, measured = true)} × ${dp(heightPx, measured = true)}",
        "像素密度 px/dp" to number(density),
        "系统字体缩放" to number(systemFontScale),
        "当前有效字体缩放（含阅读设置）" to number(effectiveFontScale),
        "安全绘制边距 dp [左, 上, 右, 下]" to insets(safeDrawing),
        "系统栏边距 dp [左, 上, 右, 下]" to insets(systemBars),
        "系统手势边距 dp [左, 上, 右, 下]" to insets(systemGestures),
        "强制手势边距 dp [左, 上, 右, 下]" to insets(mandatoryGestures),
        "输入法边距 dp [左, 上, 右, 下]" to insets(ime),
        "悬浮导航偏好" to enabled(floatingNavigation),
        "滚动隐藏行为" to "默认随滚动隐藏；反向滚动或“显示底部导航”操作可恢复",
        "触摸探索（系统信号）" to enabled(touchExploration),
        "悬浮根页默认随滚动隐藏" to enabled(floatingNavigation),
        "减少动态效果（阻止装饰动画）" to enabled(reduceMotion),
    ) + listOf("首页请求观察范围" to "动态、热门、话题、数码、酷图的既有请求；tab由白名单请求路由推断，不证明当前可见页或调用入口；应用为本地列表、关注为范围外，均未观察。无记录不代表成功；transportResult不代表页面解析成功；仅保留当前账号代际最近12次，HTTP/内容类型不是403原因或修复证明。", "首页请求（本次进程，最多12条）" to (homeRequests?.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.text() }
        ?: if (homeRequests == null) "unavailable" else "暂无当前账号代际的请求记录；应用页为本地列表，不产生请求"))

    fun copyText(): String = "c001apk Native · 本地构建与窗口诊断\n" +
        entries().joinToString("\n") { (name, value) -> "$name: $value" } +
        "\n源码指纹仅覆盖白名单，不是 APK 哈希；dirty/unavailable 不代表已发布提交。\n" +
        "窗口是当前应用内容实测，不是设备屏幕规格；导航仍受根页、输入法及搜索状态约束，不代表此诊断页在执行自动隐藏。触摸探索不是滚动隐藏的总开关；减少动态效果只关闭装饰动画。"

    private fun dp(px: Int, measured: Boolean = false): String =
        if (px < 0 || (measured && px == 0) || !density.isFinite() || density <= 0f) "unavailable"
        else number(px / density)

    private fun insets(value: DiagnosticInsets): String =
        listOf(value.left, value.top, value.right, value.bottom).joinToString(", ", "[", "]") { dp(it) }
}

internal data class DiagnosticInsets(val left: Int, val top: Int, val right: Int, val bottom: Int)
private fun number(value: Float): String = if (value.isFinite() && value > 0f) String.format(Locale.ROOT, "%.2f", value) else if (value == 0f) "0.00" else "unavailable"
private fun enabled(value: Boolean): String = if (value) "开启" else "关闭"
