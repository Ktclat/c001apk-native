// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.settings

import android.app.Dialog
import android.os.Build
import android.view.View
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import io.github.ktclat.c001apk.nativeapp.applyNativeSystemBarAppearance
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** Inspect the showing Material3 dialog's Window, not the unrelated Activity window. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 29, 35], qualifiers = "w393dp-h852dp-notnight-420dpi")
class AppearanceSheetSystemBarTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var settings by mutableStateOf(AppSettings(theme = ThemeMode.DARK, dynamicColor = false))
    private val edits = mutableListOf<AppSettings>()
    private lateinit var restorer: StateRestorationTester
    private lateinit var hostPolicy: WindowPolicy

    @Test fun fontSheetFollowsLiveAppBrightnessAndPaletteWithoutChangingWindowPolicy() = checkLiveThemes("界面字体")
    @Test fun readingSheetFollowsLiveAppBrightnessAndPaletteWithoutChangingWindowPolicy() = checkLiveThemes("正文排版")
    @Test fun scaleSheetFollowsLiveAppBrightnessAndPaletteWithoutChangingWindowPolicy() = checkLiveThemes("应用字号")

    @Test @Config(qualifiers = "w393dp-h852dp-night-420dpi")
    fun forcedLightOnDarkSystemAndSystemFollowApplyToEveryActiveSheet() {
        settings = settings.copy(theme = ThemeMode.LIGHT)
        render()
        for (title in titles) {
            compose.runOnIdle { settings = settings.copy(theme = ThemeMode.LIGHT) }
            val dialog = open(title)
            assertIcons(dialog.window!!, dark = false)
            val policy = WindowPolicy(dialog.window!!)
            compose.runOnIdle { settings = settings.copy(theme = ThemeMode.SYSTEM) }
            assertIcons(dialog.window!!, dark = true)
            policy.assertUnchanged(dialog.window!!)
            hostPolicy.assertUnchanged(compose.activity.window)
            close(title)
        }
        assertTrue(edits.isEmpty())
    }

    @Test fun backCloseAndScaleCancelDoNotWriteSettingsOrChangeTheHostWindow() {
        render()
        for ((title, dismissal) in listOf("界面字体" to "back", "正文排版" to "close",
            "应用字号" to "cancel", "应用字号" to "back", "应用字号" to "close")) {
            val dialog = open(title)
            assertIcons(dialog.window!!, dark = true)
            if (title == "应用字号") setDraftScale(1.6f)
            when (dismissal) {
                "back" -> compose.runOnUiThread { dialog.onBackPressed() }
                "cancel" -> compose.onNodeWithText("取消").performScrollTo().performClick()
                else -> close(title)
            }
            compose.onNodeWithTag("appearance-detail").assertDoesNotExist()
            compose.runOnIdle { assertFalse(dialog.isShowing) }
            assertEquals(1f, settings.fontScale)
            assertTrue(edits.isEmpty())
            hostPolicy.assertUnchanged(compose.activity.window)
        }
    }

    @Test fun savedStateRestorationRecreatesEveryDarkSheetWithCorrectIconsAndUnsavedScaleDraft() {
        render()
        for (title in titles) {
            val previous = open(title)
            assertIcons(previous.window!!, dark = true)
            if (title == "应用字号") setDraftScale(1.6f)
            restorer.emulateSavedInstanceStateRestore()
            compose.onNodeWithTag("appearance-detail").assertExists()
            val restored = showingDialog()
            assertNotSame(previous, restored)
            assertNotSame(previous.window, restored.window)
            assertIcons(restored.window!!, dark = true)
            compose.runOnIdle { assertFalse(previous.isShowing) }
            if (title == "应用字号") compose.onNodeWithText("预览字号：160%").assertExists()
            close(title)
            assertEquals(1f, settings.fontScale)
            assertTrue(edits.isEmpty())
            hostPolicy.assertUnchanged(compose.activity.window)
        }
    }

    private fun checkLiveThemes(title: String) {
        render()
        val dialog = open(title)
        assertIcons(dialog.window!!, dark = true)
        val policy = WindowPolicy(dialog.window!!)
        // SYSTEM resolves light in this fixture. Dynamic schemes are real platform schemes on
        // API31+, and use the same production fixed-color fallback on older runtimes.
        for ((mode, dynamic, seed) in listOf(
            Triple(ThemeMode.LIGHT, false, 9), Triple(ThemeMode.SYSTEM, false, 18),
            Triple(ThemeMode.DARK, true, 0), Triple(ThemeMode.LIGHT, true, 0),
            Triple(ThemeMode.DARK, false, 18), Triple(ThemeMode.DARK, false, 18))) {
            compose.runOnIdle { settings = settings.copy(theme = mode, dynamicColor = dynamic, themeColor = seed) }
            assertSame(dialog, showingDialog())
            assertIcons(dialog.window!!, dark = mode == ThemeMode.DARK)
            policy.assertUnchanged(dialog.window!!)
            hostPolicy.assertUnchanged(compose.activity.window)
        }
        close(title)
        assertTrue(edits.isEmpty())
    }

    private fun render() {
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            // Deliberately independent host appearance: a helper targeting the Activity instead
            // of the sheet must fail these tests even if its requested polarity happens to match.
            applyNativeSystemBarAppearance(compose.activity.window, dark = false)
            hostPolicy = WindowPolicy(compose.activity.window, includeAppearance = true)
        }
        restorer = StateRestorationTester(compose)
        restorer.setContent {
            val dark = when (settings.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }
            val colors = if (settings.dynamicColor && Build.VERSION.SDK_INT >= 31) {
                if (dark) dynamicDarkColorScheme(compose.activity) else dynamicLightColorScheme(compose.activity)
            } else fixedColorScheme(settings.themeColor, dark)
            MaterialTheme(colorScheme = colors, typography = appTypography(settings.font)) {
                SettingsScreen(settings, {}, {}, {}, {}, {}, {}, onSettingsEdit = {
                    settings = it(settings)
                    edits += settings
                })
            }
        }
        compose.onNodeWithText("外观与字体").performScrollTo().performClick()
    }

    private fun open(title: String): Dialog {
        compose.onNodeWithTag("settings-detail-list").performScrollToNode(hasText(title) and hasClickAction())
        compose.onNode(hasText(title) and hasClickAction()).performClick()
        compose.onNodeWithTag("appearance-detail").assertExists()
        return showingDialog().also { assertNotSame(compose.activity.window, it.window) }
    }

    private fun showingDialog(): Dialog = compose.runOnIdle {
        requireNotNull(ShadowDialog.getLatestDialog()).also { assertTrue(it.isShowing) }
    }

    private fun close(title: String) {
        compose.onNodeWithContentDescription("关闭$title").performClick()
        compose.onNodeWithTag("appearance-detail").assertDoesNotExist()
    }

    private fun setDraftScale(value: Float) {
        compose.onNodeWithContentDescription("应用字号").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
    }

    private fun assertIcons(window: Window, dark: Boolean) = compose.runOnIdle {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        assertEquals("Active sheet status icons, SDK ${Build.VERSION.SDK_INT}, dark=$dark", !dark, controller.isAppearanceLightStatusBars)
        assertEquals("Active sheet navigation icons, SDK ${Build.VERSION.SDK_INT}, dark=$dark", !dark, controller.isAppearanceLightNavigationBars)
    }

    private inner class WindowPolicy(window: Window, private val includeAppearance: Boolean = false) {
        private val before = snapshot(window)
        fun assertUnchanged(window: Window) = compose.runOnIdle { assertEquals(before, snapshot(window)) }
        private fun snapshot(window: Window): Map<String, Any?> = buildMap {
            put("statusColor", window.statusBarColor)
            put("navigationColor", window.navigationBarColor)
            put("windowFlagsIncludingSecure", window.attributes.flags)
            put("layoutFlags", window.decorView.systemUiVisibility and APPEARANCE_FLAGS.inv())
            put("softInputMode", window.attributes.softInputMode)
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            put("barsBehavior", controller.systemBarsBehavior)
            if (includeAppearance) {
                put("lightStatus", controller.isAppearanceLightStatusBars)
                put("lightNavigation", controller.isAppearanceLightNavigationBars)
            }
            if (Build.VERSION.SDK_INT >= 28) put("cutout", window.attributes.layoutInDisplayCutoutMode)
            if (Build.VERSION.SDK_INT >= 29) {
                put("statusContrast", window.isStatusBarContrastEnforced)
                put("navigationContrast", window.isNavigationBarContrastEnforced)
                put("gestureExclusions", window.decorView.systemGestureExclusionRects.toList())
            }
        }
    }

    companion object {
        private val titles = listOf("界面字体", "正文排版", "应用字号")
        private const val APPEARANCE_FLAGS = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }
}
