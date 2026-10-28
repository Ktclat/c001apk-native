// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.ktclat.c001apk.nativeapp.MainActivity
import io.github.ktclat.c001apk.nativeapp.NativeApplication
import io.github.ktclat.c001apk.nativeapp.settings.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

/** UNMODIFIED production MainActivity/runtime. Guest public GETs may run/fail normally. No network security policy is changed. */
@RunWith(AndroidJUnit4::class)
class EmulatorProductionLaunchTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    @Test fun actualSettingsThemeNavigationContrastDialogReturnAndRecreation() {
        val settings = (compose.activity.application as NativeApplication).container.settings
        val original = runBlocking { settings.settings.first() }
        fun openAppearance() {
            awaitText("我的"); compose.onNodeWithText("我的").assertIsDisplayed().performClick()
            openSettings(); awaitText("外观与字体")
            compose.onNodeWithText("外观与字体").assertIsDisplayed().performClick()
            awaitText("深色")
        }
        fun checkBars(name: String, dark: Boolean, text: String = "外观与字体") {
            compose.waitUntil(30_000) {
                compose.runOnUiThread {
                    val window = compose.activity.window
                    val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                    !controller.isAppearanceLightNavigationBars == dark && !controller.isAppearanceLightStatusBars == dark &&
                        (android.os.Build.VERSION.SDK_INT !in 26..28 || window.navigationBarColor ==
                            if (dark) android.graphics.Color.argb(128,27,27,27) else android.graphics.Color.argb(230,255,255,255))
                }
            }
            val values = compose.runOnUiThread {
                "navigationBarColor=${Integer.toHexString(compose.activity.window.navigationBarColor)} systemUiVisibility=${compose.activity.window.decorView.systemUiVisibility} dark=$dark"
            }
            EmulatorEvidence.text("production-theme-$name-window.txt", values)
            EmulatorEvidence.capture("production-theme-$name", expectedText = text)
            assertFocusedWindowBars("production-theme-$name", dark, dialog = false)
        }
        fun select(label: String, dark: Boolean, name: String) {
            compose.onNodeWithText(label, substring = false).assertIsDisplayed().performClick()
            compose.waitUntil(30_000) { runBlocking { settings.settings.first().theme } ==
                when(label) { "深色" -> ThemeMode.DARK; "浅色" -> ThemeMode.LIGHT; else -> ThemeMode.SYSTEM } }
            compose.onNodeWithText(label, substring = false).assertIsSelected()
            checkBars(name, dark)
        }
        try {
            openAppearance()
            val systemDark = compose.activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
            select("系统", systemDark, "01-system")
            select("深色", true, "02-dark")
            select("浅色", false, "03-light")
            select("深色", true, "04-dark-again")
            select("深色", true, "05-dark-idempotent")
            compose.onNodeWithText("界面字体").performScrollTo().assertIsDisplayed().performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("appearance-detail").fetchSemanticsNodes().isNotEmpty() }
            EmulatorEvidence.capture("production-theme-06-font-dialog", expectedText = "界面字体")
            assertFocusedWindowBars("production-theme-06-font-dialog", true, dialog = true)
            device.pressBack()
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("appearance-detail").fetchSemanticsNodes().isEmpty() }
            checkBars("07-after-dialog-back", true)
            compose.onNodeWithText("界面字体").performScrollTo().performClick()
            compose.onNodeWithContentDescription("关闭界面字体").assertIsDisplayed().performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("appearance-detail").fetchSemanticsNodes().isEmpty() }
            checkBars("08-after-dialog-close", true)
            back(); back(); openSettings()
            compose.onNodeWithText("外观与字体").performClick()
            checkBars("09-settings-reentry", true)
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            checkBars("10-background-return", true)
            compose.activityRule.scenario.recreate()
            // NativeApp route and the selected Settings category are saveable state.
            // Recreation must restore Appearance, not be mistaken for a fresh Home launch.
            awaitText("深色")
            assertEquals(ThemeMode.DARK, runBlocking { settings.settings.first().theme })
            checkBars("11-persisted-dark-recreation", true)
            select("系统", systemDark, "12-restored-system")
        } finally {
            // Cleanup only on this disposable task AVD; no user device or account is involved.
            runBlocking { settings.update { original } }
        }
    }
    @Test fun actualActiveFontReadingAndScaleSheetBarsInLightDarkAndRecreation() {
        val settings = (compose.activity.application as NativeApplication).container.settings
        val original = runBlocking { settings.settings.first() }
        fun awaitSheet() = compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("appearance-detail").fetchSemanticsNodes().isNotEmpty()
        }
        fun awaitDismissed() = compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("appearance-detail").fetchSemanticsNodes().isEmpty()
        }
        fun capture(name: String, dark: Boolean, dialog: Boolean, text: String) {
            EmulatorEvidence.capture("production-modal-$name", expectedText = text)
            assertFocusedWindowBars("production-modal-$name", dark, dialog)
        }
        try {
            awaitText("我的"); compose.onNodeWithText("我的").assertIsDisplayed().performClick()
            openSettings(); compose.onNodeWithText("外观与字体").assertIsDisplayed().performClick()
            for ((label, dark) in listOf("深色" to true, "浅色" to false)) {
                compose.onNodeWithTag("settings-detail-list").performScrollToNode(hasText(label, substring = false))
                compose.onNodeWithText(label, substring = false).assertIsDisplayed().performClick()
                compose.waitUntil(30_000) { runBlocking { settings.settings.first().theme } == if (dark) ThemeMode.DARK else ThemeMode.LIGHT }
                for ((index, title) in listOf("界面字体", "正文排版", "应用字号").withIndex()) {
                    val stem = "${if (dark) "dark" else "light"}-$index"
                    compose.onNodeWithTag("settings-detail-list").performScrollToNode(hasText(title, substring = false))
                    compose.onNodeWithText(title, substring = false).assertIsDisplayed().performClick()
                    awaitSheet(); capture("$stem-active", dark, true, title)
                    when {
                        title == "应用字号" -> compose.onNodeWithText("取消", substring = false).performScrollTo().assertIsDisplayed().performClick()
                        (dark && index == 0) || (!dark && index == 1) -> device.pressBack()
                        else -> compose.onNodeWithContentDescription("关闭$title").assertIsDisplayed().performClick()
                    }
                    awaitDismissed(); capture("$stem-dismissed", dark, false, "外观与字体")
                }
            }
            compose.onNodeWithTag("settings-detail-list").performScrollToNode(hasText("深色", substring = false))
            compose.onNodeWithText("深色", substring = false).performClick()
            compose.waitUntil(30_000) { runBlocking { settings.settings.first().theme } == ThemeMode.DARK }
            compose.onNodeWithTag("settings-detail-list").performScrollToNode(hasText("界面字体", substring = false))
            compose.onNodeWithText("界面字体", substring = false).performClick(); awaitSheet()
            compose.activityRule.scenario.recreate(); awaitSheet()
            capture("dark-font-open-sheet-recreation", true, true, "界面字体")
            compose.onNodeWithContentDescription("关闭界面字体").assertIsDisplayed().performClick(); awaitDismissed()
            capture("dark-font-recreated-dismissed", true, false, "外观与字体")
            assertEquals(original.fontScale, runBlocking { settings.settings.first().fontScale }, .001f)
            assertEquals(original.font, runBlocking { settings.settings.first().font })
            assertEquals(original.readingPreset, runBlocking { settings.settings.first().readingPreset })
        } finally { runBlocking { settings.update { original } } }
    }
    @Test fun guestMainActivityLoginGateSettingsAndBlacklistBack() {
        awaitText("我的")
        EmulatorEvidence.capture("production-01-main-launch", expectedText = "首页")
        compose.onNodeWithText("我的").performClick()
        awaitText("点击登录")
        EmulatorEvidence.capture("production-02-guest-my", expectedText = "点击登录")
        compose.onNodeWithText("我的收藏").performScrollTo().performClick()
        awaitText("登录")
        compose.onNodeWithText("手机号 / 邮箱 / 用户名").assertExists()
        EmulatorEvidence.capture("production-03-login-gate-no-credentials", expectedText = "手机号 / 邮箱 / 用户名")
        back()
        openSettings()
        awaitText("浏览与隐私")
        compose.onNodeWithText("浏览与隐私").performClick()
        compose.onNodeWithText("用户与话题黑名单").performClick()
        awaitText("黑名单")
        EmulatorEvidence.capture("production-04-blacklist", expectedText = "黑名单")
        back(); awaitText("浏览与隐私")
        EmulatorEvidence.capture("production-05-settings-return", expectedText = "浏览与隐私")
    }
    @Test fun actualSafCategoryExportCancelAndSafeTemporaryDestination() {
        // Local synthetic record in this dedicated throwaway AVD only; no user store/device touched.
        val container = (compose.activity.application as NativeApplication).container
        runBlocking { container.local.addBlockedUser("42424242") }
        awaitText("我的")
        compose.onNodeWithText("我的").performClick()
        openSettings()
        awaitText("浏览与隐私"); compose.onNodeWithText("浏览与隐私").performClick()
        compose.onNodeWithText("用户与话题黑名单").performClick()
        awaitText("黑名单")
        action("导出用户列表").performClick()
        assertTrue("Actual OS document picker opened", device.wait(Until.hasObject(By.pkg("com.android.documentsui")),30_000))
        EmulatorEvidence.capture("production-saf-01-create-document", expectedText = "Downloads", expectedPackage = "com.android.documentsui")
        val hierarchy = java.io.ByteArrayOutputStream()
        device.dumpWindowHierarchy(hierarchy)
        EmulatorEvidence.text("production-saf-01-picker-hierarchy.xml", hierarchy.toString("UTF-8"))
        device.pressBack()
        compose.waitForIdle(); action("导出用户列表").assertIsEnabled()
        EmulatorEvidence.capture("production-saf-02-cancel-return", expectedText = "导出用户列表")
        action("导出用户列表").performClick()
        assertTrue(device.wait(Until.hasObject(By.pkg("com.android.documentsui")),30_000))
        // The prior task-run exports are preserved. Choose a fresh task-only name through
        // the actual observed API26 DocumentsUI filename field, never overwrite old evidence.
        val filename = "native-qa-users-${System.currentTimeMillis()}.json"
        val title = device.wait(Until.findObject(By.res("android", "title").clazz("android.widget.EditText")),10_000)
        checkNotNull(title) { "Observed DocumentsUI filename field is missing" }
        title.text = filename
        assertEquals(filename, title.text)
        EmulatorEvidence.capture("production-saf-03-create-unique-document", expectedText = filename, expectedPackage = "com.android.documentsui")
        val saveHierarchy = java.io.ByteArrayOutputStream()
        device.dumpWindowHierarchy(saveHierarchy)
        EmulatorEvidence.text("production-saf-observed-save-hierarchy.xml", saveHierarchy.toString("UTF-8"))
        EmulatorEvidence.text("production-saf-observed-filename.txt", title.text)
        // API26's actual DocumentsUI save toolbar uses this stable android resource id.
        val save = device.wait(Until.findObject(By.res("com.android.documentsui", "button1")),10_000)
            ?: device.findObject(By.text("SAVE")) ?: device.findObject(By.text("Save"))
        checkNotNull(save) { "Inspect actual DocumentsUI screenshot; no guessed coordinate click" }
        assertTrue("Picker permits safe task-only output", save.isEnabled)
        save.click()
        awaitText("已导出")
        compose.waitUntil(30_000) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
                ?.packageName?.toString() == "io.github.ktclat.c001apk.nativeapp"
        }
        // DocumentsUI may restore an IME asynchronously after returning. Establish one
        // explicit visible-field focus through a normal touch, positively observe the IME,
        // then use normal Back. Never treat an initial false visibility as final or press
        // Back blindly (which could leave Blacklist). No draft text is entered or changed.
        compose.onNodeWithTag("blacklist-list").performScrollToIndex(0)
        compose.onNodeWithTag("blacklist-input").assertIsDisplayed().performTouchInput { click() }
        compose.waitUntil(20_000) { imeVisible() }
        EmulatorEvidence.text("production-saf-ime-before-back.txt", "Positive native root WindowInsets IME visibility=true after visible input touch; no draft mutation.\n")
        device.pressBack()
        compose.waitUntil(20_000) { !imeVisible() }
        compose.onNodeWithTag("blacklist-list").assertExists()
        action("已导出").assertIsDisplayed()
        EmulatorEvidence.text("production-saf-ime-after-back.txt", "Native root WindowInsets IME visibility=false; Blacklist still present; actual lazy-list scroll shows 已导出.\n")
        EmulatorEvidence.capture("production-saf-04-export-completed", expectedText = "已导出")
        EmulatorEvidence.text("production-saf-scope.txt", "Actual unmodified MainActivity, actual Android DocumentsUI CreateDocument, Cancel then confirmed synthetic category export. Output contains only task-owned synthetic ID42424242. No user device, credentials or external provider.\n")
        runBlocking { container.local.removeBlockedUser("42424242") }
    }
    private fun imeVisible() = compose.runOnUiThread {
        androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
    }
    private fun assertFocusedWindowBars(name: String, dark: Boolean, dialog: Boolean) {
        val dump = EmulatorEvidence.windowDump(name)
        val id = Regex("mCurrentFocus=Window\\{([0-9a-fA-F]+) ").find(dump)?.groupValues?.get(1)
            ?: error("No positive focused WindowManager window in $name")
        val block = dump.split(Regex("(?m)^  Window #")).firstOrNull {
            it.lineSequence().firstOrNull().orEmpty().contains("Window{$id ")
        } ?: error("Focused window section missing for $name")
        assertTrue("Focused window belongs to production package", block.contains("package=io.github.ktclat.c001apk.nativeapp"))
        val type = Regex("\\bty=([0-9]+)\\b").find(block)?.groupValues?.get(1)?.toInt()
        assertEquals("Inspect the active dialog, not only MainActivity.window", if (dialog) 2 else 1, type)
        val flags = Regex("mSystemUiVisibility=0x([0-9a-fA-F]+)").find(block)?.groupValues?.get(1)?.toInt(16)
            ?: error("Focused window system-ui flags missing for $name")
        EmulatorEvidence.text("$name-focused-window.txt", "focusId=$id type=$type flags=0x${flags.toString(16)} expectedDark=$dark\n$block")
        assertEquals("Active window status icon appearance", !dark, flags and android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR != 0)
        assertEquals("Active window navigation icon appearance", !dark, flags and android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR != 0)
    }
    private fun openSettings() {
        val button = compose.onNodeWithContentDescription("设置")
        if (runCatching { button.assertIsDisplayed() }.isFailure) button.performScrollTo()
        button.performClick()
    }
    private fun action(text: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("blacklist-list").performScrollToNode(hasText(text))
        return compose.onNodeWithText(text)
    }
    private fun awaitText(text: String) = compose.waitUntil(30_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun back() { compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle() }
}
