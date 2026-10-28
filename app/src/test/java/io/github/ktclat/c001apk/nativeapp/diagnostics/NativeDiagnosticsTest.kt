// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.diagnostics

import io.github.ktclat.c001apk.nativeapp.feature.media.ImageLoadFailure
import io.github.ktclat.c001apk.nativeapp.feature.media.ImageLoadFailureKind
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class NativeDiagnosticsTest {
    @Test fun usesMeasuredPixelsAndEffectiveDensityNotDeviceSpecifications() {
        val text = sampleDiagnostics().copyText()
        assertTrue(text.startsWith("c001apk Native · 本地诊断\n"))
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
        val expectedNames = listOf(
            "版本", "构建类型", "Git commit（不等于当前源码）", "Git 分支", "构建时工作区", "源码指纹范围", "源码 SHA-256",
            "窗口内容实测 dp（Compose 根）", "像素密度 px/dp", "系统字体缩放", "当前有效字体缩放（含阅读设置）",
            "安全绘制边距 dp [左, 上, 右, 下]", "系统栏边距 dp [左, 上, 右, 下]", "系统手势边距 dp [左, 上, 右, 下]",
            "强制手势边距 dp [左, 上, 右, 下]", "输入法边距 dp [左, 上, 右, 下]", "悬浮导航偏好", "滚动隐藏行为",
            "触摸探索（系统信号）", "悬浮根页默认随滚动隐藏", "减少动态效果（阻止装饰动画）", "首页请求观察范围",
            "首页请求（本次进程，最多12条）", "公共图片观察范围", "公共图片加载（本次进程）",
        )
        assertEquals("No unreviewed diagnostic entry may be added or reordered", expectedNames, report.entries().map { it.first })
        assertTrue(report.entries().any { it.first == "首页请求观察范围" && it.second.contains("无记录不代表成功") })
        val entries = report.entries().toMap()
        assertEquals("unavailable", entries["首页请求（本次进程，最多12条）"])
        assertEquals("unavailable", entries["公共图片加载（本次进程）"])
        val imageScope = checkNotNull(entries["公共图片观察范围"])
        listOf("最多16组", "最多9999次", "不含地址、账号、时间、请求头或异常正文", "私信和独立文章加载器不在范围内",
            "成功可能来自缓存", "不能证明所有图片或当前页面正常").forEach { assertTrue(imageScope.contains(it)) }
        val fields = NativeDiagnostics::class.java.declaredFields.map { it.name.lowercase() }
        listOf("account", "device", "model", "manufacturer", "cookie", "token", "credential", "path", "email").forEach { prohibited ->
            assertFalse("Unexpected field: $prohibited", fields.any { it.contains(prohibited) })
        }
    }
    @Test fun publicImageExportContainsOnlyClosedFieldsAndNeverTreatsNoRecordsAsSuccess() {
        val empty = sampleDiagnostics().copy(publicImages = emptyList()).entries().toMap()
        assertEquals("暂无公共图片加载记录；无记录不代表成功", empty["公共图片加载（本次进程）"])
        val event = PublicImageObservation(PublicImageHost.IMAGE_CDN, ImageLoadFailure(ImageLoadFailureKind.HTTP, 567),
            PublicImageType.HTML, PublicImageSource.NETWORK)
        val observed = sampleDiagnostics().copy(publicImages = listOf(event to 2)).entries().toMap()
        assertEquals("host=IMAGE_CDN; result=HTTP; HTTP=567; contentType=HTML; source=NETWORK; count=2",
            observed["公共图片加载（本次进程）"])
        assertEquals(setOf("host", "failure", "contentType", "source"),
            PublicImageObservation::class.java.declaredFields.filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.toSet())
        assertEquals(setOf("kind", "httpStatus"),
            ImageLoadFailure::class.java.declaredFields.filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.toSet())
        val closedFields = PublicImageObservation::class.java.declaredFields + ImageLoadFailure::class.java.declaredFields
        assertFalse("Image diagnostics must not retain arbitrary text, bodies, URLs or exceptions",
            closedFields.any { it.type == String::class.java || Throwable::class.java.isAssignableFrom(it.type) })
    }
}

internal fun sampleDiagnostics() = NativeDiagnostics(
    "0.1.0-dev", 1, "debug", "a".repeat(40), "main", "dirty", "sha256:" + "b".repeat(64),
    "main-source-whitelist-v1", 1179, 2556, 3f, 1f, 2f,
    DiagnosticInsets(0, 72, 0, 48), DiagnosticInsets(0, 72, 0, 48),
    DiagnosticInsets(36, 0, 36, 72), DiagnosticInsets(0, 0, 0, 72), DiagnosticInsets(0, 0, 0, 0),
    true, false, false,
)
