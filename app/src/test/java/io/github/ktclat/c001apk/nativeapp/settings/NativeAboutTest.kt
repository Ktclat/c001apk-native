// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.settings

import android.content.ActivityNotFoundException
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.ktclat.c001apk.nativeapp.BuildConfig
import io.github.ktclat.c001apk.nativeapp.platform.isSafePublicWebUrl
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NativeAboutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun settingsAboutAndProtocolDiagnosticsHaveDifferentRealDestinations() {
        val routes = mutableListOf<String>()
        compose.setContent { MaterialTheme { SettingsScreen(AppSettings(), {}, {}, {}, {}, {}, { routes.add(it) }) } }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("关于") and hasClickAction())
        compose.onNode(hasText("关于") and hasClickAction()).performClick()
        assertEquals(listOf("native-about"), routes)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("协议与设备") and hasClickAction())
        compose.onNode(hasText("协议与设备") and hasClickAction()).performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("本地诊断") and hasClickAction())
        compose.onNode(hasText("本地诊断") and hasClickAction()).performClick()
        assertEquals(listOf("native-about", "native-diagnostics"), routes)
    }

    @Test fun realNativeIdentityAndOnlyExplicitExternalClicksAreUsed() {
        val calls = mutableListOf<String>()
        var diagnostics = 0; var updates = 0; var notices = 0
        compose.setContent { MaterialTheme {
            NativeAboutScreen({}, { diagnostics++ }, { updates++ }, { notices++ }, { calls.add(it) })
        } }
        compose.onNodeWithText("c001apk Native").assertExists()
        compose.onNodeWithText("${BuildConfig.VERSION_NAME} · 版本号 ${BuildConfig.VERSION_CODE} · ${BuildConfig.BUILD_TYPE}").assertExists()
        assertTrue(calls.isEmpty())
        listOf("project" to NativeAboutLinks.PROJECT, "license" to NativeAboutLinks.LICENSE,
            "issues" to NativeAboutLinks.ISSUES, "flutter" to NativeAboutLinks.FLUTTER_SOURCE,
            "notices" to NativeAboutLinks.NOTICES).forEach { (tag, url) ->
            compose.onNodeWithTag("native-about-list").performScrollToNode(hasTestTag("about-$tag"))
            compose.onNodeWithTag("about-$tag").performClick()
            assertEquals(url, calls.last())
        }
        assertEquals(5, calls.size)
        listOf("diagnostics", "updates", "bundled").forEach { tag ->
            compose.onNodeWithTag("native-about-list").performScrollToNode(hasTestTag("about-$tag"))
            compose.onNodeWithTag("about-$tag").performClick()
        }
        assertEquals(1, diagnostics); assertEquals(1, updates); assertEquals(1, notices)
        assertEquals(5, calls.size)
        assertEquals(5, NativeAboutLinks.all.size)
        NativeAboutLinks.all.forEach { assertTrue(isSafePublicWebUrl(it)) }
        assertEquals("https://github.com/Ktclat/c001apk-native/blob/aac8cf43362762d4e6763695e85bee2f71b9064f/THIRD_PARTY_NOTICES.md", NativeAboutLinks.NOTICES)
        assertFalse(NativeAboutLinks.NOTICES.contains("/docs/"))
    }

    @Test fun unavailableBrowserFailsVisiblyWithoutChangingRouteOrRetrying() {
        var attempts = 0
        compose.setContent { MaterialTheme {
            NativeAboutScreen({}, {}, {}, {}, { attempts++; throw ActivityNotFoundException("Synthetic") })
        } }
        assertEquals(0, attempts)
        compose.onNodeWithTag("native-about-list").performScrollToNode(hasTestTag("about-project"))
        compose.onNodeWithTag("about-project").performClick()
        compose.onNodeWithTag("about-link-error").assertIsDisplayed()
        compose.onNodeWithTag("native-about").assertExists()
        assertEquals(1, attempts)
    }

    @Test fun bundledNoticeAllowlistExactlyMatchesPackagedAssetsAndDoesNotClaimFullAudit() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(8, bundledNativeNotices.size)
        assertEquals(context.assets.list("licenses")!!.toSet(), bundledNativeNotices.map { it.file }.toSet())
        bundledNativeNotices.forEach { assertTrue(context.assets.open("licenses/${it.file}").bufferedReader().use { reader -> reader.readText() }.isNotBlank()) }
        assertTrue(BUNDLED_NOTICE_SCOPE.contains("不代表完整依赖许可审计"))
        var exits = 0
        compose.setContent { MaterialTheme { NativeBundledNoticesScreen { exits++ } } }
        compose.onNodeWithText(BUNDLED_NOTICE_SCOPE).assertExists()
        compose.onNodeWithTag("bundled-notice-list").performScrollToNode(hasTestTag("bundled-TextEmoticons-NOTICE.txt"))
        compose.onNodeWithTag("bundled-TextEmoticons-NOTICE.txt").performClick()
        compose.onNodeWithTag("bundled-notice-title").assertTextEquals("文字表情来源声明")
        compose.onNodeWithTag("native-bundled-notices-back").performClick()
        compose.onNodeWithTag("bundled-notice-list").assertExists()
        assertEquals(0, exits)
        compose.onNodeWithTag("native-bundled-notices-back").performClick()
        assertEquals(1, exits)
    }
}
