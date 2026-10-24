// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FloatingDockSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun onlyNavigationSwitchRemainsAndPreservesOtherSettings() {
        var current = AppSettings(floatingNavigation = true, fontScale = 1.4f, secretFollow = true)
        compose.setContent {
            var settings by remember { mutableStateOf(current) }
            MaterialTheme { SettingsScreen(settings, { settings = it; current = it }, {}, {}, {}, {}, onNavigate = {}) }
        }
        compose.onNodeWithText("外观与字体").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("悬浮导航栏"))
        assertNoScrollPicker()
        compose.onNodeWithText("悬浮导航栏").assertIsOn().performClick().assertIsOff()
        compose.runOnIdle { assertFalse(current.floatingNavigation); assertEquals(1.4f, current.fontScale); assertTrue(current.secretFollow) }
        assertNoScrollPicker()
        compose.onNodeWithText("悬浮导航栏").performClick().assertIsOn()
        assertNoScrollPicker()
    }
    @Test fun noScrollPickerExistsWhenFloatingNavigationStartsDisabled() {
        compose.setContent { MaterialTheme { SettingsScreen(AppSettings(), {}, {}, {}, {}, {}, onNavigate = {}) } }
        compose.onNodeWithText("外观与字体").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("悬浮导航栏"))
        assertNoScrollPicker()
    }
    private fun assertNoScrollPicker() {
        listOf("悬浮导航栏的滚动显示", "自动（尊重无障碍）", "滚动时隐藏", "滚动时始终可见").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
    }
}
