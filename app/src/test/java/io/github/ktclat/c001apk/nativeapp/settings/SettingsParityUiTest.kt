// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.ktclat.c001apk.nativeapp.platform.InstalledAppsScreen
import io.github.ktclat.c001apk.nativeapp.platform.NativeUpdateService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsParityUiTest {
    @get:Rule val compose = createComposeRule()
    private class Cache : ImageCacheController {
        var bytes = 4096L
        var reads = 0
        var clears = 0
        var fail = false
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun sizeBytes(): Long { reads++; return bytes }
        override suspend fun clear(): ImageCacheClearResult {
            clears++; gate?.await()
            if (fail) return ImageCacheClearResult(1024, null, 1)
            val removed = bytes; bytes = 0; return ImageCacheClearResult(removed, 0, 0)
        }
    }
    @Test fun clearNeedsConfirmationCancelDoesNothingAndRepeatedClicksAreSingleFlight() {
        val cache = Cache()
        compose.setContent { MaterialTheme { ImageCacheSetting(cache) } }
        compose.onNodeWithText("清理图片缓存").performClick()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(0, cache.clears); cache.gate = CompletableDeferred() }
        compose.onNodeWithText("清理图片缓存").performClick()
        compose.onNodeWithText("确认清理").performClick()
        compose.onNodeWithText("清理图片缓存").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, cache.clears); cache.gate!!.complete(Unit) }
        compose.onNodeWithText("清理图片缓存").assertIsEnabled()
        compose.onNodeWithText("已清理图片缓存", substring = true).assertExists()
        compose.runOnIdle { assertEquals(0L, cache.bytes) }
    }
    @Test fun partialCacheFailureNeverClaimsEmptyOrDeletesWithoutUserAction() {
        val cache = Cache().apply { fail = true }
        compose.setContent { MaterialTheme { ImageCacheSetting(cache) } }
        compose.onNodeWithText("清理图片缓存").performClick()
        compose.onNodeWithText("确认清理").performClick()
        compose.onNodeWithText("部分图片缓存未能清理", substring = true).assertExists()
        compose.onNodeWithText("磁盘图片缓存大小暂不可用").assertExists()
        compose.onNodeWithText("清理图片缓存").assertIsEnabled()
    }
    @Test fun storageAutoUpdateUsesExistingPersistentServiceAndSuppressesAutomaticNetworkCheck() {
        val context = RuntimeEnvironment.getApplication()
        val service = NativeUpdateService(context).apply { enabled = true }
        compose.setContent { MaterialTheme { NativeAutomaticUpdateSetting(service) } }
        compose.onNodeWithText("自动检查 c001apk 原生版更新").assertIsOn().performClick()
        compose.waitUntil(5_000) { !service.enabled }
        compose.waitForIdle()
        compose.onNodeWithText("自动检查 c001apk 原生版更新").assertIsOff()
        assertFalse(NativeUpdateService(context).enabled)
        assertEquals(io.github.ktclat.c001apk.nativeapp.platform.NativeUpdateResult.Suppressed,
            runBlocking { NativeUpdateService(context).check("0.1.0-dev", automatic = true) })
    }
    @Test fun failedDurableWriteDisplaysActualEffectiveValueAndRetryCanPersist() {
        val preference = object : io.github.ktclat.c001apk.nativeapp.platform.NativeUpdatePreference {
            override var enabled = true
            var fail = true
            override suspend fun setEnabledAndPersist(value: Boolean) { enabled = value; if (fail) throw java.io.IOException("synthetic disk failure") }
        }
        compose.setContent { MaterialTheme { NativeAutomaticUpdateSetting(preference) } }
        compose.onNodeWithText("自动检查 c001apk 原生版更新").performClick().assertIsOff()
        compose.onNodeWithText("无法确认更新检查设置已保存", substring = true).assertExists()
        compose.runOnIdle { preference.fail = false }
        compose.onNodeWithText("自动检查 c001apk 原生版更新").performClick().assertIsOn()
        compose.onNodeWithText("无法确认更新检查设置已保存", substring = true).assertDoesNotExist()
    }
    @Test fun explicitEqualToOldThemeAndFontResetAreNotLostBeforeSettingsEmission() {
        val queued = mutableListOf<(AppSettings) -> AppSettings>()
        val before = AppSettings()
        compose.setContent { MaterialTheme { SettingsScreen(before, {}, {}, {}, {}, {}, {},
            imageCache = Cache(), onSettingsEdit = { queued += it }) } }
        compose.onNodeWithText("外观与字体").performClick()
        compose.onNodeWithText("深色").performClick()
        compose.onNodeWithText("系统").performClick() // Still matches old UI value, but is a real explicit intent.
        compose.runOnIdle {
            assertEquals(ThemeMode.SYSTEM, queued.fold(before) { current, edit -> edit(current) }.theme)
            queued.clear(); queued += { it.copy(fontScale = 1.2f) }
        }
        compose.onNodeWithTag("settings-detail-list").performScrollToNode(hasText("恢复默认字号"))
        compose.onNodeWithText("恢复默认字号").performClick()
        compose.runOnIdle { assertEquals(1f, queued.fold(before) { current, edit -> edit(current) }.fontScale) }
    }
    @Test fun accountFollowChoicesAreRealAndGuestsCannotTriggerServerChoice() {
        var settings by mutableStateOf(AppSettings())
        var signedIn by mutableStateOf(true)
        compose.setContent { MaterialTheme { SettingsScreen(settings, { settings = it }, {}, {}, {}, {}, {},
            isLoggedIn = signedIn, imageCache = Cache()) } }
        compose.onNodeWithText("关注与内容").performClick()
        compose.onNodeWithText("话题关注").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(2, settings.followType); assertFalse(settings.secretFollow) }
        compose.onNodeWithText("悄悄关注").performClick().assertIsSelected()
        compose.runOnIdle { assertTrue(settings.secretFollow); assertEquals(2, settings.followType); signedIn = false }
        compose.onNodeWithText("话题关注").assertDoesNotExist()
        compose.onNodeWithText("管理本地关注").assertExists()
        compose.runOnIdle { signedIn = true }
        compose.onNodeWithText("悄悄关注").assertIsSelected()
    }
    @Test fun installedAppUpdateVisibilityDrivesActualAppListAndDoesNotOpenWithoutClick() {
        var show by mutableStateOf(true)
        var opens = 0
        compose.setContent { MaterialTheme { InstalledAppsScreen(showUpdateEntry = show, onOpenUpdates = { opens++ }) {} } }
        compose.onNodeWithTag("installed-app-updates-entry").assertExists()
        compose.runOnIdle { assertEquals(0, opens) }
        compose.onNodeWithTag("installed-app-updates-entry").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, opens); show = false }
        compose.onNodeWithTag("installed-app-updates-entry").assertDoesNotExist()
        compose.runOnIdle { show = true }
        compose.onNodeWithTag("installed-app-updates-entry").assertExists()
        compose.runOnIdle { assertEquals(1, opens) }
    }
}
