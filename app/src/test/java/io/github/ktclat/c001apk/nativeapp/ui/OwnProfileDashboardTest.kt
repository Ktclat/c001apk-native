// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OwnProfileDashboardTest {
    @get:Rule val compose = createComposeRule()
    private val account = AccountState(listOf(AccountSummary("123","Local name",false)),"123")
    private val profile = OwnProfile("123","Server name",null,7,25,100,0,9,null)
    @Test fun rendersServerProfileAndSeparatesProfileFromFeedListRoute() {
        var route = ""
        compose.setContent { MaterialTheme { MyDashboard(account,rememberScrollState(),80.dp,{ route=it },
            onPersonalList = { route=personalListRoute(it) },profile=profile) } }
        compose.onNodeWithText("Server name").performClick()
        compose.runOnIdle { assertEquals("user:123",route) }
        compose.onNodeWithText("Lv.7").assertExists(); compose.onNodeWithText("25/100").assertExists()
        compose.onNodeWithText("0").assertExists(); compose.onNodeWithText("9").assertExists()
        compose.onNodeWithText("未提供").assertExists()
        compose.onNodeWithText("动态").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(PersonalListRequest(PersonalListKind.FEED),parsePersonalListRoute(route)) }
    }
    @Test fun mismatchedAccountProfileIsNotDisplayedAndRecoveryGatesLogin() {
        var state by mutableStateOf(account)
        compose.setContent { MaterialTheme { MyDashboard(state,rememberScrollState(),80.dp,{},profile=profile.copy(uid="456")) } }
        compose.onNodeWithText("Server name").assertDoesNotExist()
        compose.onNodeWithText("Local name").assertExists()
        compose.runOnIdle { state=account.copy(recoveryRequired=true) }
        compose.onNodeWithText("点击登录").assertExists()
        compose.onNodeWithText("Local name").assertDoesNotExist()
    }
    @Test fun shortLargeTextWindowKeepsHeaderAndLastActionScrollable() {
        var route = ""
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f,2f)) {
            MaterialTheme { Box(Modifier.width(240.dp).height(280.dp)) {
                MyDashboard(account,rememberScrollState(),100.dp,{ route=it },profile=profile)
            } }
        } }
        compose.onNodeWithText("私信与信息").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("notifications:MESSAGES",route) }
        compose.onNodeWithText("Server name").performScrollTo().assertIsDisplayed()
    }
    @Test fun sameGenerationCancellationEnablesManualRetry() {
        var calls = 0
        val repository = OwnProfileRepository {
            calls++
            if (calls == 1) throw CancellationException("synthetic cookie revision")
            ApiResult.Success(profile)
        }
        compose.setContent { MaterialTheme { OwnProfileDashboardContent(repository, account,
            rememberScrollState(),80.dp,{}, {}) } }
        compose.onNodeWithText("个人资料加载已取消，请重试").assertExists()
        compose.onNodeWithText("刷新个人资料").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Server name").assertExists()
        compose.onNodeWithText("个人资料加载已取消，请重试").assertDoesNotExist()
        compose.runOnIdle { assertEquals(2,calls) }
    }
    @Test fun oldAccountCompletionCannotReplaceNewAccountHeader() = verifyCancelledReplacement(false)
    @Test fun sameIdNewGenerationCannotReceiveOldHeaderOrCancellationState() = verifyCancelledReplacement(true)
    private fun verifyCancelledReplacement(sameId: Boolean) {
        var state by mutableStateOf(account)
        val release = CompletableDeferred<Unit>()
        val started = AtomicBoolean(false)
        val returned = AtomicBoolean(false)
        val oldRepository = OwnProfileRepository {
            started.set(true)
            // Deliberately uncooperative fixture proves the UI's post-await cancellation check.
            withContext(NonCancellable) { release.await() }
            returned.set(true)
            ApiResult.Success(profile.copy(name="Old response"))
        }
        val nextId = if (sameId) "123" else "456"
        val nextRepository = OwnProfileRepository { ApiResult.Success(profile.copy(uid=nextId,name="New response")) }
        try {
            compose.setContent { MaterialTheme { OwnProfileDashboardContent(
                if (state.generation == 0L) oldRepository else nextRepository, state,
                rememberScrollState(),80.dp,{}, {}) } }
            compose.waitUntil { started.get() }
            compose.runOnIdle { state=AccountState(listOf(AccountSummary(nextId,"New local",false)),nextId,generation=1) }
            compose.onNodeWithText("New response").assertExists()
            compose.runOnIdle { release.complete(Unit) }
            compose.waitUntil { returned.get() }
            compose.waitForIdle()
            compose.onNodeWithText("New response").assertExists()
            compose.onNodeWithText("Old response").assertDoesNotExist()
            compose.onNodeWithText("个人资料加载已取消，请重试").assertDoesNotExist()
            compose.onNodeWithContentDescription("刷新个人资料").assertIsEnabled()
        } finally {
            release.complete(Unit)
        }
    }

}
