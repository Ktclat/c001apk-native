package io.github.ktclat.c001apk.nativeapp.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import io.github.ktclat.c001apk.nativeapp.domain.ContentItem
import io.github.ktclat.c001apk.nativeapp.feature.feed.feedCount
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-320dpi")
class FeedReplyRowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun largeCountsWrapAndAuthorTargetIsNamed() {
        val opened = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.6f)) {
                MaterialTheme { FeedReplyRow(ContentItem("10", "feed_reply", userId = "99", userName = "测试作者", message = "合成评论", replyCount = Int.MAX_VALUE, likeCount = Int.MAX_VALUE), opened::add, {}) }
            }
        }
        compose.onNodeWithContentDescription("查看测试作者主页").performClick()
        assertEquals(listOf("user:99"), opened)
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        listOf("回复", "${feedCount(Int.MAX_VALUE)}条回复").forEach { label ->
            val bounds = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= root.left); assertTrue(bounds.right <= root.right)
            assertTrue(bounds.bottom <= root.bottom); assertTrue(bounds.height >= 48f * compose.activity.resources.displayMetrics.density)
        }
        compose.onNodeWithContentDescription("2147483647赞，暂仅展示数量").assertExists()
    }
}
