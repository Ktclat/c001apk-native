// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.diagnostics

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class NativeDiagnosticsTest {
    @Test fun usesMeasuredPixelsAndEffectiveDensityNotDeviceSpecifications() {
        val text = sampleDiagnostics().copyText()
        assertTrue(text.contains("393.00 × 852.00"))
        assertTrue(text.contains("像素密度 px/dp: 3.00"))
        assertTrue(text.contains("系统字体缩放: 1.00"))
        assertTrue(text.contains("当前有效字体缩放（含阅读设置）: 2.00"))
        assertTrue(text.contains("安全绘制边距 dp [左, 上, 右, 下]: [0.00, 24.00, 0.00, 16.00]"))
    }
    @Test fun unavailableMeasurementsAndProvenanceNeverBecomeZeroOrClean() {
        val text = sampleDiagnostics().copy(widthPx = 0, heightPx = 0, density = Float.NaN,
            commit = "unavailable", branch = "unavailable", worktree = "unavailable").copyText()
        assertTrue(text.contains("unavailable × unavailable"))
        assertTrue(text.contains("构建时工作区: unavailable"))
        assertFalse(text.contains("clean"))
        assertFalse(text.contains("NaN"))
    }
    @Test fun snapshotTracksWindowAndAccessibilityChanges() {
        val text = sampleDiagnostics().copy(widthPx = 2556, heightPx = 1179, floatingNavigation = false,
            touchExploration = true, reduceMotion = true, ime = DiagnosticInsets(0, 0, 0, 900)).copyText()
        assertTrue(text.contains("852.00 × 393.00"))
        assertTrue(text.contains("悬浮导航偏好: 关闭"))
        assertTrue(text.contains("触摸探索（系统信号）: 开启"))
        assertTrue(text.contains("减少动态效果（阻止装饰动画）: 开启"))
        assertTrue(text.contains("输入法边距 dp [左, 上, 右, 下]: [0.00, 0.00, 0.00, 300.00]"))
    }
    @Test fun reportsDefaultBehaviorWithoutAnAccessibilityBlanketBlockOrSelectableMode() {
        for (exploration in listOf(false, true)) for (reduced in listOf(false, true)) {
            val text = sampleDiagnostics().copy(touchExploration = exploration, reduceMotion = reduced).copyText()
            assertTrue(text.contains("悬浮根页默认随滚动隐藏: 开启"))
            assertTrue(text.contains("默认随滚动隐藏"))
            assertTrue(text.contains("显示底部导航"))
            assertTrue(text.contains("减少动态效果只关闭装饰动画"))
            assertFalse(text.contains("滚动隐藏模式"))
            assertFalse(text.contains("明确选择"))
        }
        assertTrue(sampleDiagnostics().copy(floatingNavigation = false).copyText().contains("悬浮根页默认随滚动隐藏: 关闭"))
    }
    @Test fun stableCopyFormatDoesNotDependOnLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val text = sampleDiagnostics().copyText()
            assertTrue(text.contains("393.00 × 852.00"))
            assertTrue(text.contains("构建时工作区: dirty"))
            assertFalse(text.contains("393,00"))
        } finally { Locale.setDefault(previous) }
    }
    @Test fun reportIsAnExplicitNonSensitiveAllowlist() {
        val report = sampleDiagnostics()
        assertEquals(21, report.entries().size)
        val fields = NativeDiagnostics::class.java.declaredFields.map { it.name.lowercase() }
        listOf("account", "device", "model", "manufacturer", "cookie", "token", "credential", "path", "email").forEach { prohibited ->
            assertFalse("Unexpected field: $prohibited", fields.any { it.contains(prohibited) })
        }
    }
}

internal fun sampleDiagnostics() = NativeDiagnostics(
    "0.1.0-dev", 1, "debug", "a".repeat(40), "main", "dirty", "sha256:" + "b".repeat(64),
    "main-source-whitelist-v1", 1179, 2556, 3f, 1f, 2f,
    DiagnosticInsets(0, 72, 0, 48), DiagnosticInsets(0, 72, 0, 48),
    DiagnosticInsets(36, 0, 36, 72), DiagnosticInsets(0, 0, 0, 72), DiagnosticInsets(0, 0, 0, 0),
    true, false, false,
)
