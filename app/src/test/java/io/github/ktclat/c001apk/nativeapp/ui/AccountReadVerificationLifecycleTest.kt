// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Actual production owner lifecycle with synthetic typed read capabilities, no HTTP or SDK execution. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccountReadVerificationLifecycleTest {
    @get:Rule val compose = createComposeRule()
    private val account = AccountState(listOf(AccountSummary("123", "Synthetic", false)), "123")
    private val counts = NotificationCounts("123", 7, 0, 0, 0, 0, 0)
    private val proof = "NEC:abcdefgh:synthetic-lifecycle-proof"
    private fun <T> pending(retry: suspend () -> ApiResult<T>) = PendingRead(ApiChallenge.Netease("abcdefghijklmnop", ""),
        { true }, { error("No image request") }, { error("No graphic answer") }, { retry() })
    private fun countFailure(pending: PendingCountsRead) = ApiResult.Failure(FailureKind.CAPTCHA, "合成未读验证", countsRead = pending)
    private fun personalFailure(pending: PendingPersonalRead) = ApiResult.Failure(FailureKind.CAPTCHA, "合成列表验证", personalRead = pending)
    private fun entry(id: String) = PersonalEntry(id, ContentItem(id, "feed"), id, "", PersonalDestination.Unsupported)
    private fun awaitTag(tag: String) = compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }
    @Test fun countsExplicitRetryIsSingleFlightAndDoesNotReloadProfile() {
        val reads = AtomicInteger(); val retries = AtomicInteger(); var profiles = 0
        val release = CompletableDeferred<Unit>()
        val pending: PendingCountsRead = pending { retries.incrementAndGet(); release.await(); ApiResult.Success(counts) }
        val repository = NotificationCountsRepository { reads.incrementAndGet(); countFailure(pending) }
        val profile = OwnProfileRepository { profiles++; ApiResult.Success(OwnProfile("123", "PROFILE_UNCHANGED", null, null, null, null, 0, 0, 0)) }
        try {
            compose.setContent { MaterialTheme { OwnProfileDashboardContent(profile, account, rememberScrollState(), 80.dp, {}, {}, repository) } }
            awaitTag("counts-human-verify"); compose.runOnIdle { assertTrue(pending.acceptHumanProof(proof)) }
            awaitTag("counts-verified-retry")
            val click = compose.onNodeWithTag("counts-verified-retry").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            compose.runOnIdle { click(); click() }
            // The captured action changes snapshotFlow; flush the Compose/main frame before
            // polling the IO-side counter. Atomic-only polling does not advance that frame.
            compose.waitForIdle()
            try { compose.waitUntil(10_000) { retries.get() == 1 } }
            catch (problem: Throwable) {
                println("ACCOUNT_RETRY_DIAGNOSTIC retries=${retries.get()} reads=${reads.get()} profiles=$profiles state=${pending.state.value}")
                println(compose.onRoot(useUnmergedTree = true).printToString())
                throw problem
            }
            compose.runOnIdle { assertEquals(1, reads.get()); assertEquals(1, profiles); release.complete(Unit) }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("my-notice-badge-MENTIONS", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            compose.runOnIdle { assertEquals(1, retries.get()); assertEquals(1, reads.get()); assertEquals(1, profiles) }
        } finally { release.complete(Unit) }
    }
    @Test fun countsHostPauseGenerationAndDisposalCancelWithoutRetry() {
        val owner = Owner(); val all = mutableListOf<PendingCountsRead>(); val replays = AtomicInteger()
        var shown by mutableStateOf(true); var user by mutableStateOf(account)
        val repository = NotificationCountsRepository {
            pending { replays.incrementAndGet(); ApiResult.Success(counts) }.also { all += it }.let(::countFailure)
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { MaterialTheme {
            if (shown) OwnProfileDashboardContent(null, user, rememberScrollState(), 80.dp, {}, {}, repository)
        } } }
        awaitTag("counts-human-verify"); compose.onNodeWithTag("counts-human-verify").performScrollTo().performClick()
        compose.onNodeWithText("取消验证").assertIsDisplayed()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.runOnIdle { assertEquals(DetailVerificationState.CLOSED, all[0].state.value); owner.registry.currentState = Lifecycle.State.RESUMED }
        awaitTag("counts-human-verify")
        compose.runOnIdle { user = user.copy(generation = 1) }
        awaitTag("counts-human-verify")
        compose.runOnIdle { assertEquals(DetailVerificationState.CLOSED, all[1].state.value); shown = false }
        compose.runOnIdle { assertEquals(DetailVerificationState.CLOSED, all.last().state.value); assertEquals(0, replays.get()) }
    }
    @Test fun aggregateRefreshCancelsPendingAndNeverConsumesItsProof() {
        val all = mutableListOf<PendingCountsRead>(); var profiles = 0; var reads = 0; val retries = AtomicInteger()
        val repository = NotificationCountsRepository { reads++; pending { retries.incrementAndGet(); ApiResult.Success(counts) }.also { all += it }.let(::countFailure) }
        val profile = OwnProfileRepository { profiles++; ApiResult.Success(OwnProfile("123", "Synthetic", null, null, null, null, 0, 0, 0)) }
        compose.setContent { MaterialTheme { OwnProfileDashboardContent(profile, account, rememberScrollState(), 80.dp, {}, {}, repository) } }
        awaitTag("counts-human-verify")
        val refresh = compose.onNodeWithTag("my-root-list").fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == REFRESH_MY_ACTION }.action
        compose.runOnIdle { assertTrue(refresh()) }
        awaitTag("counts-human-verify")
        compose.runOnIdle { assertEquals(2, profiles); assertEquals(2, reads); assertEquals(0, retries.get()); assertEquals(DetailVerificationState.CLOSED, all.first().state.value) }
    }
    @Test fun lateCountChallengeAfterPauseIsClosedInsteadOfPublished() {
        val owner = Owner(); var continuation: Continuation<ApiResult<NotificationCounts>>? = null
        val late: PendingCountsRead = pending { error("Late capability replayed") }
        val repository = NotificationCountsRepository { suspendCoroutine { continuation = it } }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { MaterialTheme {
            OwnProfileDashboardContent(null, account, rememberScrollState(), 80.dp, {}, {}, repository)
        } } }
        compose.waitUntil(10_000) { continuation != null }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED; continuation!!.resume(countFailure(late)) }
        compose.waitUntil(10_000) { late.state.value == DetailVerificationState.CLOSED }
        compose.onAllNodesWithTag("counts-human-verify").assertCountEquals(0)
    }
    @Test fun failedPersonalRefreshRetriesReplacementNotAppend() = personalMode(false)
    @Test fun failedPersonalNextPageRetriesAppendNotRefresh() = personalMode(true)
    private fun personalMode(more: Boolean) {
        val calls = mutableListOf<PageCursor>(); val retries = AtomicInteger()
        val pending: PendingPersonalRead = pending { retries.incrementAndGet(); ApiResult.Success(Page(listOf(entry("NEW_ROW")), null)) }
        val repository = PersonalListRepository { _, _, cursor ->
            calls += cursor
            if (calls.size == 1) ApiResult.Success(Page(listOf(entry("OLD_ROW")), PageCursor(2, "OLD_ROW", "OLD_ROW"))) else personalFailure(pending)
        }
        compose.setContent { MaterialTheme { PersonalListScreen(repository, PersonalListRequest(PersonalListKind.LIKE), "123", 0, {}, {}, {}) } }
        compose.onNodeWithText(if (more) "加载更多" else "刷新").performScrollTo().performClick()
        awaitTag("personal-human-verify"); compose.runOnIdle { assertTrue(pending.acceptHumanProof(proof)) }
        awaitTag("personal-verified-retry"); compose.onNodeWithTag("personal-verified-retry").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("NEW_ROW").fetchSemanticsNodes().isNotEmpty() }
        if (more) compose.onNodeWithText("OLD_ROW").assertExists() else compose.onNodeWithText("OLD_ROW").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, retries.get()); assertEquals(if (more) PageCursor(2, "OLD_ROW", "OLD_ROW") else PageCursor(), calls.last()); assertEquals(2, calls.size) }
    }
    @Test fun personalTabAccountAndRouteDisposalCancelCapabilities() {
        val all = mutableListOf<PendingPersonalRead>(); var generation by mutableLongStateOf(0); var shown by mutableStateOf(true)
        val repository = PersonalListRepository { _, _, _ -> pending<Page<PersonalEntry>> { error("Cancelled read replayed") }.also { all += it }.let(::personalFailure) }
        compose.setContent { MaterialTheme { if (shown) PersonalListScreen(repository, PersonalListRequest(PersonalListKind.FOLLOW), "123", generation, {}, {}, {}) } }
        awaitTag("personal-human-verify"); compose.onNodeWithText("话题").performClick(); awaitTag("personal-human-verify")
        compose.runOnIdle { assertEquals(DetailVerificationState.CLOSED, all[0].state.value); generation++ }
        awaitTag("personal-human-verify")
        compose.runOnIdle { assertEquals(DetailVerificationState.CLOSED, all[1].state.value); shown = false }
        compose.runOnIdle { assertEquals(DetailVerificationState.CLOSED, all.last().state.value) }
    }
    @Test fun latePersonalChallengeAfterOwnerReplacementIsClosed() {
        var generation by mutableLongStateOf(0); var continuation: Continuation<ApiResult<Page<PersonalEntry>>>? = null
        val late: PendingPersonalRead = pending { error("Late personal read replayed") }
        var calls = 0
        val repository = PersonalListRepository { _, _, _ -> if (++calls == 1) suspendCoroutine { continuation = it } else ApiResult.Success(Page(emptyList(), null)) }
        compose.setContent { MaterialTheme { PersonalListScreen(repository, PersonalListRequest(PersonalListKind.FAN), "123", generation, {}, {}, {}) } }
        compose.waitUntil(10_000) { continuation != null }
        compose.runOnIdle { generation++ }
        compose.onNodeWithText("暂无内容").assertExists()
        compose.runOnIdle { continuation!!.resume(personalFailure(late)) }
        compose.waitUntil(10_000) { late.state.value == DetailVerificationState.CLOSED }
        compose.onAllNodesWithTag("personal-human-verify").assertCountEquals(0)
    }
}
