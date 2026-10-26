// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class SettingsUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun switchesHaveReadableLabelAndWholeRowAction() {
        var result: AppSettings? = null
        compose.setContent { MaterialTheme { SettingsScreen(AppSettings(), { result=it }, {}, {}, {}, {}, onNavigate = {}) } }
        compose.onNodeWithText("外观与字体").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("悬浮导航栏"))
        compose.onNodeWithText("悬浮导航栏").assertIsOff().performClick()
        compose.runOnIdle { assertEquals(true,result?.floatingNavigation) }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("应用字号"))
        compose.onNodeWithText("应用字号").performClick()
        compose.onNodeWithContentDescription("应用字号").assertExists()
    }
}
