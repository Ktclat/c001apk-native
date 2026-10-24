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
@Config(sdk = [35])
class CategorizedSettingsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun categoryIndexAndPrivacyActionsAreReal() {
        var blocked = false
        var changed: AppSettings? = null
        compose.setContent { MaterialTheme { SettingsScreen(AppSettings(), { changed = it }, {}, {}, { blocked = true }, {}, onNavigate = {}) } }
        SettingsSection.entries.forEach { compose.onNodeWithText(it.title).performScrollTo().assertIsDisplayed() }
        compose.onNodeWithText("浏览与隐私").performScrollTo().performClick()
        compose.onNodeWithText("用户与话题黑名单").performClick()
        compose.runOnIdle { assertTrue(blocked) }
        compose.onNodeWithText("记录浏览历史").performClick()
        compose.runOnIdle { assertEquals(false, changed?.recordHistory) }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("存储与更新").performScrollTo().assertIsDisplayed()
    }
    @Test fun storageCallsNativeUpdaterNotFlutterDownload() {
        var route = ""
        compose.setContent { MaterialTheme { SettingsScreen(AppSettings(), {}, {}, {}, {}, {}, onNavigate = { route = it }) } }
        compose.onNodeWithText("存储与更新").performScrollTo().performClick()
        compose.onNodeWithText("c001apk 原生版更新").performClick()
        compose.runOnIdle { assertEquals("native-updates", route) }
        compose.onNodeWithText("检查已安装应用更新").performClick()
        compose.runOnIdle { assertEquals("app-updates", route) }
    }
    @Test fun protocolDiagnosticsEntryDescribesLocalRequestSummaryAndKeepsRoute() {
        var route = ""
        compose.setContent { MaterialTheme { SettingsScreen(AppSettings(), {}, {}, {}, {}, {}, onNavigate = { route = it }) } }
        compose.onNodeWithText("协议与设备").performScrollTo().performClick()
        compose.onNodeWithText("本地诊断").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("版本、界面与脱敏首页请求摘要；仅手动复制，不含账号凭据").assertIsDisplayed()
        compose.onNodeWithText("本地诊断").performClick()
        compose.runOnIdle { assertEquals("native-diagnostics", route) }
    }
}
