package io.github.ktclat.c001apk.nativeapp.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class HomeCategoryTabsTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val labels = listOf("应用", "动态", "热门", "话题", "数码", "酷图")
    @Test fun sourceDensityKeepsTextSizedIndicatorAndAccessibleTargets() {
        compose.setContent { MaterialTheme { HomeCategoryTabs(labels, 1, {}, Modifier.width(342.dp)) } }
        val tab = compose.onNodeWithTag("home-category:1").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
        val line = compose.onNodeWithTag("home-category-indicator:1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Indicator must not span padded hit target", line.width < tab.width * .7f)
        compose.onNodeWithTag("home-category:4").assertIsDisplayed()
        compose.onNodeWithTag("home-category:5").performScrollTo().assertIsDisplayed()
    }
    @Test fun largeLabelsCanScrollAndSelectWithoutShrinkingTargets() {
        val selected = mutableIntStateOf(0)
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,1.6f)) {
            MaterialTheme { HomeCategoryTabs(labels, selected.intValue, {selected.intValue=it}, Modifier.width(272.dp)) }
        } }
        compose.onNodeWithTag("home-category:5").performScrollTo().performClick().assertIsSelected().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }
}
