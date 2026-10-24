// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationCountsDashboardTest {
    @get:Rule val compose = createComposeRule()
    private val account = AccountState(listOf(AccountSummary("123", "Local name", false)), "123")
    private val counts = NotificationCounts("123", 1, 0, 99, 100, Long.MAX_VALUE, 0)
    private val profile = OwnProfile("123", "Server name", null, 7, 25, 100, 2, 3, 4)
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private fun row(category: String) = compose.onNodeWithTag("my-notice-$category")
    private fun state(category: String, value: String) = row(category).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value))
    private fun badge(category: String) = compose.onNodeWithTag("my-notice-badge-$category", useUnmergedTree = true)

    @Test fun compactLabelsPreserveOneThroughNinetyNineAndBoundLargerDisplay() {
        assertNull(notificationBadgeText(null)); assertNull(notificationBadgeText(0))
        assertEquals("1", notificationBadgeText(1)); assertEquals("99", notificationBadgeText(99))
        assertEquals("99+", notificationBadgeText(100)); assertEquals("99+", notificationBadgeText(Long.MAX_VALUE))
    }
    @Test fun positiveZeroUnknownAndExactOverflowSpeechStayDistinctWithOneClickNode() {
        var route = ""; var snapshot by mutableStateOf(counts)
        compose.setContent { MaterialTheme { MyDashboard(account, rememberScrollState(), 80.dp, { route = it }, counts = snapshot) } }
        for ((category, value) in listOf("MENTIONS" to "1 条未读", "REPLIES" to "无未读消息", "LIKES" to "99 条未读", "FOLLOWERS" to "100 条未读", "MESSAGES" to "${Long.MAX_VALUE} 条未读")) {
            state(category, value).performScrollTo()
            row(category).assertHasClickAction()
            compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("my-notice-$category")), useUnmergedTree = true).assertCountEquals(0)
            val semantics = row(category).fetchSemanticsNode().config
            assertNull("Badge does not add a second content description", semantics.getOrNull(SemanticsProperties.ContentDescription))
            assertEquals(1, semantics[SemanticsProperties.Text].size)
        }
        badge("MENTIONS").assertExists(); badge("LIKES").assertExists(); badge("FOLLOWERS").assertExists()
        badge("REPLIES").assertDoesNotExist()
        row("MESSAGES").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("notifications:MESSAGES", route); snapshot = counts.copy(message = Long.MAX_VALUE, notification = 1, atMe = null) }
        state("MENTIONS", "未读数量未知"); state("MESSAGES", "未读数量未知")
        badge("MENTIONS").assertDoesNotExist(); badge("MESSAGES").assertDoesNotExist()
    }
    @Test fun guestRecoveryAndMismatchedSnapshotNeverExposeAnotherAccountsCounts() {
        var user by mutableStateOf(account); var route = ""
        compose.setContent { MaterialTheme { MyDashboard(user, rememberScrollState(), 80.dp, { route = it }, counts = counts.copy(uid = "456")) } }
        state("MENTIONS", "未读数量未知"); badge("MENTIONS").assertDoesNotExist()
        compose.runOnIdle { user = account.copy(recoveryRequired = true) }
        row("MENTIONS").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("login", route) }
        assertNull(row("MENTIONS").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
    }
    @Test fun countsFailureDoesNotHideProfileAndCountsRetryDoesNotReloadProfile() {
        var countCalls = 0; var profileCalls = 0
        val profileRepo = OwnProfileRepository { profileCalls++; ApiResult.Success(profile) }
        val countsRepo = NotificationCountsRepository {
            countCalls++
            if (countCalls == 1) ApiResult.Failure(FailureKind.NETWORK, "合成未读错误") else ApiResult.Success(counts)
        }
        compose.setContent { MaterialTheme { OwnProfileDashboardContent(profileRepo, account, rememberScrollState(), 80.dp, {}, {}, countsRepo) } }
        compose.onNodeWithText("Server name").assertExists(); compose.onNodeWithText("合成未读错误").assertExists()
        compose.onNodeWithText("重试未读数量").performScrollTo().performClick()
        state("MENTIONS", "1 条未读")
        compose.runOnIdle { assertEquals(2, countCalls); assertEquals(1, profileCalls) }
        compose.onNodeWithContentDescription("刷新个人资料").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(2, profileCalls); assertEquals(2, countCalls) }
    }
    @Test fun profileFailureDoesNotDelayOrHideSuccessfulCounts() {
        val profileRepo = OwnProfileRepository { ApiResult.Failure(FailureKind.NETWORK, "合成资料错误") }
        val countsRepo = NotificationCountsRepository { ApiResult.Success(counts) }
        compose.setContent { MaterialTheme { OwnProfileDashboardContent(profileRepo, account, rememberScrollState(), 80.dp, {}, {}, countsRepo) } }
        compose.onNodeWithText("合成资料错误").assertExists(); state("MENTIONS", "1 条未读")
    }
    @Test fun pendingProfileDoesNotBlockCountsAndPendingCountsDoesNotBlockProfile() {
        val profileRelease = CompletableDeferred<Unit>(); val countsRelease = CompletableDeferred<Unit>()
        var secondPhase by mutableStateOf(false)
        val pendingProfile = OwnProfileRepository { profileRelease.await(); ApiResult.Success(profile) }
        val readyProfile = OwnProfileRepository { ApiResult.Success(profile) }
        val readyCounts = NotificationCountsRepository { ApiResult.Success(counts) }
        val pendingCounts = NotificationCountsRepository { countsRelease.await(); ApiResult.Success(counts) }
        try {
            compose.setContent { MaterialTheme { OwnProfileDashboardContent(if (secondPhase) readyProfile else pendingProfile,
                account, rememberScrollState(), 80.dp, {}, {}, if (secondPhase) pendingCounts else readyCounts) } }
            state("MENTIONS", "1 条未读"); compose.onNodeWithText("正在加载个人资料").assertExists()
            compose.runOnIdle { secondPhase = true }
            compose.onNodeWithText("Server name").assertExists(); compose.onNodeWithText("正在加载未读数量").assertExists()
            state("MENTIONS", "未读数量未知")
        } finally { profileRelease.complete(Unit); countsRelease.complete(Unit) }
    }
    @Test fun sameGenerationRevisionCancellationEnablesOneExplicitSingleFlightRetry() {
        val release = CompletableDeferred<Unit>(); val calls = AtomicInteger(); val entered = AtomicBoolean()
        val countsRepo = NotificationCountsRepository {
            if (calls.incrementAndGet() == 1) throw CancellationException("synthetic cookie revision")
            entered.set(true); release.await(); ApiResult.Success(counts)
        }
        try {
            compose.setContent { MaterialTheme { OwnProfileDashboardContent(null, account, rememberScrollState(), 80.dp, {}, {}, countsRepo) } }
            compose.onNodeWithText("未读数量加载已取消，请重试").assertExists()
            compose.onNodeWithText("重试未读数量").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil { entered.get() }
            compose.onNodeWithText("重试未读数量").assertIsNotEnabled()
            compose.runOnIdle { assertEquals(2, calls.get()); release.complete(Unit) }
            state("MENTIONS", "1 条未读")
            compose.runOnIdle { assertEquals(2, calls.get()) }
        } finally { release.complete(Unit) }
    }
    @Test fun oneInitialLoadThenOnePerResumeAndNoneWhilePausedOrDisposed() {
        val owner = Owner(); val calls = AtomicInteger(); var shown by mutableStateOf(true)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        val repo = NotificationCountsRepository { calls.incrementAndGet(); ApiResult.Success(counts) }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { MaterialTheme {
            if (shown) OwnProfileDashboardContent(null, account, rememberScrollState(), 80.dp, {}, {}, repo)
        } } }
        compose.runOnIdle { assertEquals(0, calls.get()); owner.registry.currentState = Lifecycle.State.RESUMED }
        state("MENTIONS", "1 条未读")
        compose.runOnIdle { assertEquals(1, calls.get()); owner.registry.currentState = Lifecycle.State.STARTED }
        state("MENTIONS", "未读数量未知")
        compose.runOnIdle { assertEquals(1, calls.get()); owner.registry.currentState = Lifecycle.State.RESUMED }
        state("MENTIONS", "1 条未读")
        compose.runOnIdle { assertEquals(2, calls.get()); shown = false }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED; owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.runOnIdle { assertEquals(2, calls.get()) }
    }
    @Test fun pauseCancelsInFlightAndLateUncooperativeResultCannotPublish() {
        val owner = Owner(); val calls = AtomicInteger(); val entered = AtomicBoolean(); val returned = AtomicBoolean()
        val release = CompletableDeferred<Unit>()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        val repo = NotificationCountsRepository {
            if (calls.incrementAndGet() == 1) { entered.set(true); withContext(NonCancellable) { release.await() }; returned.set(true) }
            ApiResult.Success(counts)
        }
        try {
            compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { MaterialTheme {
                OwnProfileDashboardContent(null, account, rememberScrollState(), 80.dp, {}, {}, repo)
            } } }
            compose.waitUntil { entered.get() }
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED; release.complete(Unit) }
            compose.waitUntil { returned.get() }
            state("MENTIONS", "未读数量未知"); badge("MENTIONS").assertDoesNotExist()
            compose.runOnIdle { assertEquals(1, calls.get()); owner.registry.currentState = Lifecycle.State.RESUMED }
            state("MENTIONS", "1 条未读")
            compose.runOnIdle { assertEquals(2, calls.get()) }
        } finally { release.complete(Unit) }
    }
    @Test fun oldAccountResponseCannotReplaceNewSnapshot() = replacesAccount(false)
    @Test fun sameUidNewGenerationCannotReceiveOldSnapshot() = replacesAccount(true)
    @Test fun staleFailureCannotReplaceNewAccountStatus() = replacesAccount(false, lateFailure = true)
    @Test fun staleExceptionCannotReplaceSameUidNewGenerationStatus() = replacesAccount(true, lateException = true)
    private fun replacesAccount(sameUid: Boolean, lateFailure: Boolean = false, lateException: Boolean = false) {
        var user by mutableStateOf(account); val release = CompletableDeferred<Unit>()
        val entered = AtomicBoolean(); val returned = AtomicBoolean()
        val oldRepo = NotificationCountsRepository {
            entered.set(true); withContext(NonCancellable) { release.await() }; returned.set(true)
            if (lateException) throw IllegalStateException("Old counts error")
            if (lateFailure) ApiResult.Failure(FailureKind.NETWORK, "Old counts error") else ApiResult.Success(counts.copy(atMe = 42))
        }
        val nextId = if (sameUid) "123" else "456"
        val newRepo = NotificationCountsRepository { ApiResult.Success(counts.copy(uid = nextId, atMe = 8)) }
        try {
            compose.setContent { MaterialTheme { OwnProfileDashboardContent(null, user, rememberScrollState(), 80.dp, {}, {},
                if (user.generation == 0L) oldRepo else newRepo) } }
            compose.waitUntil { entered.get() }
            compose.runOnIdle { user = AccountState(listOf(AccountSummary(nextId, "New name", false)), nextId, generation = 1) }
            state("MENTIONS", "8 条未读")
            compose.runOnIdle { release.complete(Unit) }
            compose.waitUntil { returned.get() }
            state("MENTIONS", "8 条未读")
            compose.onNodeWithText("未读数量加载已取消，请重试").assertDoesNotExist()
            compose.onNodeWithText("Old counts error").assertDoesNotExist()
            compose.onNodeWithText("未读数量加载失败，请重试").assertDoesNotExist()
        } finally { release.complete(Unit) }
    }
}
