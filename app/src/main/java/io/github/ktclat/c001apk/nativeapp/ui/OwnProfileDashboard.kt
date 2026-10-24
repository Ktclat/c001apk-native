// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.unit.Dp
import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.coroutines.coroutineContext

@Composable fun OwnProfileDashboard(accounts: AccountRuntime, accountState: AccountState,
    scrollState: ScrollState, bottomPadding: Dp, onNavigate: (String) -> Unit,
    onPersonalList: (MyPersonalList) -> Unit) {
    val uid = personalListAccountId(accountState)
    val expectedGeneration = accountState.generation
    val repository = remember(uid, expectedGeneration, accounts.repository) {
        uid?.let { expected -> accounts.repository.ownProfile(expected) {
            val current = accounts.store.state.value
            personalListAccountId(current) == expected && current.generation == expectedGeneration
        } }
    }
    val countsRepository = remember(uid, expectedGeneration, accounts.repository) {
        uid?.let { expected -> accounts.repository.notificationCounts(expected) {
            val current = accounts.store.state.value
            personalListAccountId(current) == expected && current.generation == expectedGeneration
        } }
    }
    OwnProfileDashboardContent(repository, accountState, scrollState, bottomPadding, onNavigate, onPersonalList, countsRepository)
}

/** State and effect belong to this exact account generation, never to the replacement account. */
@Composable internal fun OwnProfileDashboardContent(repository: OwnProfileRepository?, accountState: AccountState,
    scrollState: ScrollState, bottomPadding: Dp, onNavigate: (String) -> Unit,
    onPersonalList: (MyPersonalList) -> Unit, countsRepository: NotificationCountsRepository? = null) {
    val uid = personalListAccountId(accountState)
    key(uid, accountState.generation, repository, countsRepository) {
        val profile = rememberDashboardRead(repository, uid, "个人资料", reloadOnResume = false) { repository!!.load() }
        val counts = rememberDashboardRead(countsRepository, uid, "未读数量") { countsRepository!!.load() }
        var aggregate by remember { mutableStateOf<Pair<Int, Int>?>(null) }
        // Derive the indicator from the admitted pair, rather than an effect that can miss
        // immediate completions coalesced into one composition. Later resource-only retries
        // have another epoch and must never revive an old aggregate indicator.
        val refreshing = aggregate?.let { (profileEpoch, countsEpoch) ->
            (profile.loading && profile.refreshEpoch == profileEpoch) ||
                (counts.loading && counts.refreshEpoch == countsEpoch)
        } == true
        val refreshAll: (() -> Unit)? = if (uid == null || repository == null || countsRepository == null) null else ({
            // Reserve both slots synchronously. A second gesture/action cannot queue another pair.
            if (profile.canRefresh() && counts.canRefresh()) {
                profile.refresh()
                counts.refresh()
                aggregate = profile.refreshEpoch to counts.refreshEpoch
            }
        })
        val countValue = counts.value
        MyDashboard(accountState, scrollState, bottomPadding, onNavigate, onPersonalList,
            profile = profile.value, profileStatus = profile.status,
            onRefreshProfile = if (repository != null && uid != null) profile::refresh else null,
            profileLoading = profile.loading,
            counts = countValue, countsStatus = counts.status ?: if (countValue?.hasUnknown == true) "部分未读数量暂不可用" else null,
            onRetryCounts = if (countsRepository != null && uid != null) counts::refresh else null,
            countsLoading = counts.loading, onRefreshAll = refreshAll, refreshing = refreshing,
            refreshEnabled = profile.canRefresh() && counts.canRefresh())
    }
}

/** Each resource owns its result and retry; the aggregate action only coordinates admission. */
private class DashboardRead<T>(val enabled: Boolean, val lifecycle: Lifecycle, val label: String) {
    var result by mutableStateOf<ApiResult<T>?>(null)
    var loading by mutableStateOf(enabled)
    var refreshEpoch by mutableIntStateOf(0)
    val value: T? get() = (result as? ApiResult.Success)?.value
    val status: String? get() = when {
        !enabled -> null
        loading && result !is ApiResult.Success -> "正在加载$label"
        result is ApiResult.Failure -> (result as ApiResult.Failure).message
        else -> null
    }
    fun canRefresh() = enabled && !loading && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    fun refresh() {
        if (canRefresh()) {
            loading = true
            refreshEpoch++
        }
    }
}

/** Pause/disposal cancels both reads. Counts reload on resume; completed profiles stay independent. */
@Composable private fun <T> rememberDashboardRead(repository: Any?, uid: String?, label: String,
    reloadOnResume: Boolean = true, load: suspend () -> ApiResult<T>): DashboardRead<T> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state = remember(repository, uid, lifecycle) { DashboardRead<T>(repository != null && uid != null, lifecycle, label) }
    LaunchedEffect(state) {
        if (!state.enabled) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var completed = state.result != null && !state.loading
            // A lifecycle-triggered read is a new admission too. Never reuse an old
            // aggregate epoch when only counts (or an interrupted profile) resumes.
            if (reloadOnResume || !completed) state.refreshEpoch++
            try {
                snapshotFlow { state.refreshEpoch }.collect {
                    // A completed profile need not refetch merely because counts resumed.
                    if (!reloadOnResume && !state.loading && state.result != null) return@collect
                    // Keep this owner's last success while refreshing, so avatars, badges
                    // and row geometry do not jump. A failure below replaces it explicitly.
                    completed = false
                    if (state.result !is ApiResult.Success) state.result = null
                    state.loading = true
                    try {
                        val loaded = load()
                        coroutineContext.ensureActive()
                        state.result = loaded
                    } catch (e: CancellationException) {
                        // A strict revision cancellation can leave the lifecycle job active.
                        coroutineContext.ensureActive()
                        state.result = ApiResult.Failure(FailureKind.UNCERTAIN, "${label}加载已取消，请重试")
                    } catch (_: Exception) {
                        coroutineContext.ensureActive()
                        state.result = ApiResult.Failure(FailureKind.NETWORK, "${label}加载失败，请重试")
                    } finally {
                        if (coroutineContext.isActive) {
                            state.loading = false
                            completed = true
                        }
                    }
                }
            } finally {
                if (reloadOnResume || !completed) state.result = null
                state.loading = false
            }
        }
    }
    return state
}
