// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
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
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MyPullRefreshTest {
    @get:Rule val compose = createComposeRule()
    private val account = AccountState(listOf(AccountSummary("123", "Local", false)), "123")
    private val profile = OwnProfile("123", "Server", null, 7, 25, 100, 2, 3, 4)
    private val counts = NotificationCounts("123", 1, 0, 0, 0, 0, 0)
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private fun action() = compose.onNodeWithTag("my-root-list").fetchSemanticsNode().config
        .getOrNull(SemanticsActions.CustomActions)?.firstOrNull { it.label == REFRESH_MY_ACTION }?.action
    private fun refresh() { val callback = requireNotNull(action()); compose.runOnIdle { assertTrue(callback()) }; compose.waitForIdle() }
    private fun unread(value: String) = compose.onNodeWithTag("my-notice-MENTIONS")
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value))

    @Test fun aggregateStartsEachReadOnceAndRepeatedActionsAreSingleFlight() {
        val profiles = AtomicInteger(); val checks = AtomicInteger()
        val releaseProfile = CompletableDeferred<Unit>(); val releaseCounts = CompletableDeferred<Unit>()
        val profileRepo = OwnProfileRepository { if (profiles.incrementAndGet() > 1) releaseProfile.await(); ApiResult.Success(profile) }
        val countRepo = NotificationCountsRepository { if (checks.incrementAndGet() > 1) releaseCounts.await(); ApiResult.Success(counts) }
        try {
            compose.setContent { MaterialTheme { OwnProfileDashboardContent(profileRepo, account, rememberScrollState(), 80.dp, {}, {}, countRepo) } }
            val callback = requireNotNull(action())
            compose.runOnIdle { repeat(5) { callback() } }
            // Drive the composition/effects after the synchronous callback mutation before
            // polling external counters; wall-clock polling alone does not apply snapshots.
            compose.onNodeWithTag("my-refresh-active").assertExists()
            assertNull(action())
            compose.waitUntil { profiles.get() == 2 && checks.get() == 2 }
            compose.runOnIdle { repeat(5) { callback() }; releaseProfile.complete(Unit) }
            compose.onNodeWithText("Server").assertExists()
            assertNull("Counts still pending; aggregate remains unavailable", action())
            compose.runOnIdle { releaseCounts.complete(Unit) }
            unread("1 条未读")
            assertNotNull(action())
            compose.runOnIdle { assertEquals(2, profiles.get()); assertEquals(2, checks.get()) }
        } finally { releaseProfile.complete(Unit); releaseCounts.complete(Unit) }
    }

    @Test fun aggregateProfileFailureDoesNotHideCounts() = partialFailure(true)
    @Test fun aggregateCountsFailureDoesNotHideProfile() = partialFailure(false)
    private fun partialFailure(profileFails: Boolean) {
        var profiles = 0; var checks = 0
        val profileRepo = OwnProfileRepository { profiles++; if (profiles > 1 && profileFails) ApiResult.Failure(FailureKind.NETWORK, "资料错误") else ApiResult.Success(profile) }
        val countRepo = NotificationCountsRepository { checks++; if (checks > 1 && !profileFails) ApiResult.Failure(FailureKind.NETWORK, "数量错误") else ApiResult.Success(counts) }
        compose.setContent { MaterialTheme { OwnProfileDashboardContent(profileRepo, account, rememberScrollState(), 80.dp, {}, {}, countRepo) } }
        refresh()
        compose.onNodeWithText(if (profileFails) "资料错误" else "数量错误").assertExists()
        if (profileFails) {
            unread("1 条未读")
            compose.onNodeWithText("Server").assertDoesNotExist()
        } else {
            compose.onNodeWithText("Server").assertExists()
            unread("未读数量未知")
            compose.onNodeWithTag("my-notice-badge-MENTIONS", useUnmergedTree = true).assertDoesNotExist()
        }
        assertNotNull(action())
        compose.runOnIdle { assertEquals(2, profiles); assertEquals(2, checks) }
    }

    @Test fun revisionCancellationReleasesAggregateForRetry() {
        var profiles = 0; var checks = 0
        val profileRepo = OwnProfileRepository { if (++profiles == 2) throw CancellationException("revision"); ApiResult.Success(profile) }
        val countRepo = NotificationCountsRepository { if (++checks == 2) throw CancellationException("revision"); ApiResult.Success(counts) }
        compose.setContent { MaterialTheme { OwnProfileDashboardContent(profileRepo, account, rememberScrollState(), 80.dp, {}, {}, countRepo) } }
        refresh()
        compose.onNodeWithText("个人资料加载已取消，请重试").assertExists()
        compose.onNodeWithText("未读数量加载已取消，请重试").assertExists()
        refresh()
        compose.onNodeWithText("Server").assertExists(); unread("1 条未读")
        compose.runOnIdle { assertEquals(3, profiles); assertEquals(3, checks) }
    }

    @Test fun guestAndRecoveryDoNotReadOrOfferAggregate() {
        var state by mutableStateOf(AccountState())
        var requests = 0
        val profileRepo = OwnProfileRepository { requests++; ApiResult.Success(profile) }
        val countRepo = NotificationCountsRepository { requests++; ApiResult.Success(counts) }
        compose.setContent { MaterialTheme { OwnProfileDashboardContent(profileRepo, state, rememberScrollState(), 80.dp, {}, {}, countRepo) } }
        assertNull(action())
        compose.runOnIdle { state = account.copy(recoveryRequired = true) }
        assertNull(action())
        compose.runOnIdle { assertEquals(0, requests) }
    }

    @Test fun initialAndNewGenerationLoadsDoNotRetainEarlierContent() {
        var state by mutableStateOf(account)
        val first = CompletableDeferred<Unit>(); val next = CompletableDeferred<Unit>()
        val profiles = AtomicInteger(); val checks = AtomicInteger()
        val profileRepo = OwnProfileRepository {
            (if (profiles.incrementAndGet() == 1) first else next).await(); ApiResult.Success(profile)
        }
        val countRepo = NotificationCountsRepository {
            (if (checks.incrementAndGet() == 1) first else next).await(); ApiResult.Success(counts)
        }
        try {
            compose.setContent { MaterialTheme { OwnProfileDashboardContent(profileRepo, state, rememberScrollState(), 80.dp, {}, {}, countRepo) } }
            compose.onNodeWithText("正在加载个人资料").assertExists()
            compose.onNodeWithText("正在加载未读数量").assertExists()
            compose.onNodeWithText("Server").assertDoesNotExist(); unread("未读数量未知")
            assertNull(action())
            compose.runOnIdle { first.complete(Unit) }
            compose.onNodeWithText("Server").assertExists(); unread("1 条未读")
            // Same repositories and UID, but a new generation must start without old values.
            compose.runOnIdle { state = account.copy(generation = 1) }
            compose.onNodeWithText("正在加载个人资料").assertExists()
            compose.onNodeWithText("正在加载未读数量").assertExists()
            compose.onNodeWithText("Server").assertDoesNotExist(); unread("未读数量未知")
            compose.onNodeWithTag("my-notice-badge-MENTIONS", useUnmergedTree = true).assertDoesNotExist()
            compose.runOnIdle { assertEquals(2, profiles.get()); assertEquals(2, checks.get()); next.complete(Unit) }
            compose.onNodeWithText("Server").assertExists(); unread("1 条未读")
        } finally { first.complete(Unit); next.complete(Unit) }
    }

    @Test fun recoveryDuringRetainedSuccessRefreshErasesBothAndCancelsReads() {
        var state by mutableStateOf(account)
        val profiles = AtomicInteger(); val checks = AtomicInteger(); val cancelled = AtomicInteger()
        val profileRepo = OwnProfileRepository {
            if (profiles.incrementAndGet() > 1) try { awaitCancellation() } finally { cancelled.incrementAndGet() }
            ApiResult.Success(profile)
        }
        val countRepo = NotificationCountsRepository {
            if (checks.incrementAndGet() > 1) try { awaitCancellation() } finally { cancelled.incrementAndGet() }
            ApiResult.Success(counts)
        }
        compose.setContent { MaterialTheme { OwnProfileDashboardContent(profileRepo, state, rememberScrollState(), 80.dp, {}, {}, countRepo) } }
        refresh()
        compose.onNodeWithText("Server").assertExists(); unread("1 条未读")
        compose.runOnIdle { state = account.copy(recoveryRequired = true) }
        compose.onNodeWithText("点击登录").assertExists()
        compose.onNodeWithText("Server").assertDoesNotExist()
        compose.onNodeWithTag("my-notice-badge-MENTIONS", useUnmergedTree = true).assertDoesNotExist()
        assertNull(action())
        compose.waitUntil { cancelled.get() == 2 }
        compose.runOnIdle { assertEquals(2, profiles.get()); assertEquals(2, checks.get()) }
    }

    @Test fun pauseAndDisposalCancelBothAggregateReadsAndResumeDoesNotDuplicate() {
        val owner = Owner(); var shown by mutableStateOf(true)
        val profiles = AtomicInteger(); val checks = AtomicInteger(); val cancelled = AtomicInteger()
        val profileRepo = OwnProfileRepository {
            if (profiles.incrementAndGet() == 2) try { awaitCancellation() } finally { cancelled.incrementAndGet() }
            ApiResult.Success(profile)
        }
        val countRepo = NotificationCountsRepository {
            if (checks.incrementAndGet() == 2) try { awaitCancellation() } finally { cancelled.incrementAndGet() }
            ApiResult.Success(counts)
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { MaterialTheme {
            if (shown) OwnProfileDashboardContent(profileRepo, account, rememberScrollState(), 80.dp, {}, {}, countRepo)
        } } }
        refresh()
        compose.waitUntil { profiles.get() == 2 && checks.get() == 2 }
        compose.onNodeWithText("Server").assertExists(); unread("1 条未读")
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.onNodeWithText("Server").assertDoesNotExist(); unread("未读数量未知")
        compose.waitUntil { cancelled.get() == 2 }
        compose.runOnIdle { assertEquals(2, profiles.get()); assertEquals(2, checks.get()); owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.onNodeWithText("Server").assertExists(); unread("1 条未读")
        compose.runOnIdle { assertEquals(3, profiles.get()); assertEquals(3, checks.get()); shown = false }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED; owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.runOnIdle { assertEquals(3, profiles.get()); assertEquals(3, checks.get()) }
    }

    @Test fun disposalCancelsBothPendingAggregateReads() {
        var shown by mutableStateOf(true)
        val profiles = AtomicInteger(); val checks = AtomicInteger(); val cancelled = AtomicInteger()
        val profileRepo = OwnProfileRepository {
            if (profiles.incrementAndGet() > 1) try { awaitCancellation() } finally { cancelled.incrementAndGet() }
            ApiResult.Success(profile)
        }
        val countRepo = NotificationCountsRepository {
            if (checks.incrementAndGet() > 1) try { awaitCancellation() } finally { cancelled.incrementAndGet() }
            ApiResult.Success(counts)
        }
        compose.setContent { MaterialTheme {
            if (shown) OwnProfileDashboardContent(profileRepo, account, rememberScrollState(), 80.dp, {}, {}, countRepo)
        } }
        refresh()
        compose.waitUntil { profiles.get() == 2 && checks.get() == 2 }
        compose.runOnIdle { shown = false }
        // Prove disposal reached the actual UI before awaiting its cancellation side effects.
        compose.onNodeWithTag("my-root-list").assertDoesNotExist()
        compose.waitUntil { cancelled.get() == 2 }
        compose.runOnIdle { assertEquals(2, profiles.get()); assertEquals(2, checks.get()) }
    }

    @Test fun completedProfileSurvivesRepeatedResumeWithoutReloadingAlongsideCounts() {
        val owner = Owner(); var profiles = 0; var checks = 0
        val profileRepo = OwnProfileRepository { profiles++; ApiResult.Success(profile) }
        val countRepo = NotificationCountsRepository { checks++; ApiResult.Success(counts) }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { MaterialTheme {
            OwnProfileDashboardContent(profileRepo, account, rememberScrollState(), 80.dp, {}, {}, countRepo)
        } } }
        repeat(2) {
            compose.onNodeWithText("Server").assertExists()
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
            compose.onNodeWithText("Server").assertExists()
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            unread("1 条未读")
        }
        compose.runOnIdle { assertEquals(1, profiles); assertEquals(3, checks) }
        compose.onNodeWithContentDescription("刷新个人资料").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(2, profiles); assertEquals(3, checks) }
    }

    @Test fun completedAggregateDoesNotReviveIndicatorForCountsOnlyResume() {
        val owner = Owner(); val profiles = AtomicInteger(); val checks = AtomicInteger()
        val resumeCounts = CompletableDeferred<Unit>()
        val profileRepo = OwnProfileRepository { profiles.incrementAndGet(); ApiResult.Success(profile) }
        val countRepo = NotificationCountsRepository {
            if (checks.incrementAndGet() == 3) resumeCounts.await()
            ApiResult.Success(counts)
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        try {
            compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { MaterialTheme {
                OwnProfileDashboardContent(profileRepo, account, rememberScrollState(), 80.dp, {}, {}, countRepo)
            } } }
            refresh()
            compose.runOnIdle { assertEquals(2, profiles.get()); assertEquals(2, checks.get()) }
            compose.onNodeWithTag("my-refresh-active").assertDoesNotExist()
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
            compose.waitForIdle()
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.waitUntil { checks.get() == 3 }
            compose.onNodeWithText("正在加载未读数量").assertExists()
            compose.onNodeWithText("Server").assertExists()
            compose.onNodeWithTag("my-refresh-active").assertDoesNotExist()
            compose.runOnIdle { assertEquals(2, profiles.get()); resumeCounts.complete(Unit) }
            unread("1 条未读")
            assertNotNull(action())
        } finally { resumeCounts.complete(Unit) }
    }

    @Test fun replacementAccountRejectsBothLateAggregateResults() = replace(false)
    @Test fun sameUidNewGenerationRejectsBothLateAggregateResults() = replace(true)
    private fun replace(sameUid: Boolean) {
        var state by mutableStateOf(account)
        val release = CompletableDeferred<Unit>(); val profiles = AtomicInteger(); val checks = AtomicInteger(); val returned = AtomicInteger()
        val profileRepo = OwnProfileRepository {
            if (profiles.incrementAndGet() > 1) { withContext(NonCancellable) { release.await() }; returned.incrementAndGet() }
            ApiResult.Success(profile.copy(name = "Old"))
        }
        val countRepo = NotificationCountsRepository {
            if (checks.incrementAndGet() > 1) { withContext(NonCancellable) { release.await() }; returned.incrementAndGet() }
            ApiResult.Success(counts.copy(atMe = 42))
        }
        val nextId = if (sameUid) "123" else "456"
        val nextProfile = OwnProfileRepository { ApiResult.Success(profile.copy(uid = nextId, name = "New")) }
        val nextCounts = NotificationCountsRepository { ApiResult.Success(counts.copy(uid = nextId, atMe = 8)) }
        try {
            compose.setContent { MaterialTheme { OwnProfileDashboardContent(if (state.generation == 0L) profileRepo else nextProfile,
                state, rememberScrollState(), 80.dp, {}, {}, if (state.generation == 0L) countRepo else nextCounts) } }
            refresh()
            compose.waitUntil { profiles.get() == 2 && checks.get() == 2 }
            compose.runOnIdle { state = AccountState(listOf(AccountSummary(nextId, "New local", false)), nextId, generation = 1) }
            compose.onNodeWithText("New").assertExists(); unread("8 条未读")
            compose.runOnIdle { release.complete(Unit) }
            compose.waitUntil { returned.get() == 2 }
            compose.onNodeWithText("Old").assertDoesNotExist(); compose.onNodeWithText("New").assertExists(); unread("8 条未读")
        } finally { release.complete(Unit) }
    }

    @Test fun topPullRefreshesButOrdinaryScrollDoesNotAndDockRecoveryRemains() {
        var calls = 0; var reveals = 0
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 2f), LocalFloatingDockReveal provides { reveals++; true }) {
            MaterialTheme { Box(Modifier.width(240.dp).height(280.dp)) {
                MyDashboard(account, rememberScrollState(), 100.dp, {}, profile = profile, onRefreshAll = { calls++ })
            } }
        } }
        val root = compose.onNodeWithTag("my-root-list")
        root.performTouchInput { swipe(Offset(centerX, 25f), Offset(centerX, height - 10f), 600) }
        compose.runOnIdle { assertEquals(1, calls) }
        compose.onNodeWithText("私信与信息").performScrollTo().assertIsDisplayed()
        root.performTouchInput { swipe(Offset(centerX, 60f), Offset(centerX, 150f), 600) }
        compose.runOnIdle { assertEquals(1, calls) }
        compose.onNodeWithText("Server").performScrollTo().assertIsDisplayed()
        val actions = root.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(setOf(REFRESH_MY_ACTION, SHOW_FLOATING_DOCK_ACTION), actions.map { it.label }.toSet())
        compose.runOnIdle { actions.first { it.label == SHOW_FLOATING_DOCK_ACTION }.action(); assertEquals(1, reveals) }
    }
}
