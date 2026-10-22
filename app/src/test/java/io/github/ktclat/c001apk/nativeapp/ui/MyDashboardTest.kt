package io.github.ktclat.c001apk.nativeapp.ui
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.ktclat.c001apk.nativeapp.account.AccountState
import io.github.ktclat.c001apk.nativeapp.account.AccountSummary
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MyDashboardTest {
    @get:Rule val compose = createComposeRule()
    @Test fun guestKeepsSourceShortcutsAndGatesAccountActions() {
        var destination = ""
        compose.setContent { MaterialTheme { MyDashboard(AccountState(), rememberScrollState(), 80.dp, { destination = it }) } }
        compose.onNodeWithText("点击登录").performClick()
        compose.runOnIdle { assertEquals("login", destination) }
        compose.onNodeWithText("本地收藏").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("library:FAVORITES", destination) }
        compose.onNodeWithText("浏览历史").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("library:HISTORY", destination) }
        compose.onNodeWithText("我的收藏").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("login", destination) }
        compose.onNodeWithText("私信与信息").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("login", destination) }
    }
    @Test fun authenticatedNotificationsUseExactCategoriesAndMissingListsAreHonest() {
        var destination = ""
        val account = AccountState(listOf(AccountSummary("123", "测试用户", false)), "123")
        compose.setContent { MaterialTheme { MyDashboard(account, rememberScrollState(), 80.dp, { destination = it }) } }
        compose.onNodeWithText("测试用户").performClick()
        compose.runOnIdle { assertEquals("user:123", destination) }
        compose.onNodeWithText("我的赞").performScrollTo().performClick()
        compose.onNodeWithText("原生版尚未接入此账号列表。没有用本地记录替代，也不会显示虚假的统计。").assertExists()
        compose.onNodeWithText("知道了").performClick()
        listOf("@我的动态" to "MENTIONS", "@我的评论" to "REPLIES", "我收到的赞" to "LIKES", "好友关注" to "FOLLOWERS", "私信与信息" to "MESSAGES").forEach { (label, category) ->
            compose.onNodeWithText(label).performScrollTo().performClick()
            compose.runOnIdle { assertEquals("notifications:$category", destination) }
        }
    }
}
