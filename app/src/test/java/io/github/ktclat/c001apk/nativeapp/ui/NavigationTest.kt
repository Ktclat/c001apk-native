package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NavigationTest {
    @get:Rule val compose = createComposeRule()
    @Test fun stackedTabsExposeLabelsSelectionAndStableClickTargets() {
        var selected = -1
        compose.setContent { MaterialTheme { FloatingNavigation(0, true, { selected = it }) } }
        compose.onNodeWithText("首页").assertIsDisplayed().assertIsSelected()
        listOf("首页", "关注", "我的").forEach { compose.onNodeWithText(it).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp) }
        compose.onNodeWithText("关注").assertIsDisplayed().performClick()
        org.junit.Assert.assertEquals(1, selected)
        compose.onNodeWithText("我的").assertIsDisplayed().performClick()
        org.junit.Assert.assertEquals(2, selected)
    }
}
