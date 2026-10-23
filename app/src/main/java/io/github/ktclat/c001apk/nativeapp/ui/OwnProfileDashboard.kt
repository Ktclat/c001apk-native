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
    key(uid, accountState.generation, repository) {
        var result by remember { mutableStateOf<ApiResult<OwnProfile>?>(null) }
        var refresh by remember { mutableIntStateOf(0) }
        LaunchedEffect(repository, refresh) {
            result = null
            if (repository != null && uid != null) {
                try {
                    val loaded = repository.load()
                    // Even a repository that returns after cancellation must not publish stale content.
                    coroutineContext.ensureActive()
                    result = loaded
                } catch (e: CancellationException) {
                    // A strict cookie revision can cancel a request without replacing this UI key.
                    // Release loading/retry state here; on key disposal this mutates only the old state.
                    result = ApiResult.Failure(FailureKind.UNCERTAIN, "个人资料加载已取消，请重试")
                    throw e
                } catch (_: Exception) {
                    result = ApiResult.Failure(FailureKind.NETWORK, "个人资料加载失败，请重试")
                }
            }
        }
        val counts = rememberNotificationCounts(countsRepository, uid)
        MyDashboard(accountState, scrollState, bottomPadding, onNavigate, onPersonalList,
            profile = (result as? ApiResult.Success)?.value,
            profileStatus = when {
                uid == null -> null
                result == null -> "正在加载个人资料"
                result is ApiResult.Failure -> (result as ApiResult.Failure).message
                else -> null
            }, onRefreshProfile = { refresh++ }, profileLoading = uid != null && result == null,
            counts = counts.value, countsStatus = counts.status,
            onRetryCounts = counts.retry, countsLoading = counts.loading)
    }
}

private data class NotificationCountsState(val value: NotificationCounts?, val status: String?,
    val loading: Boolean, val retry: (() -> Unit)?)

/** One lifecycle gate covers first entry, resume and explicit retry; no polling or saved snapshot. */
@Composable private fun rememberNotificationCounts(repository: NotificationCountsRepository?, uid: String?): NotificationCountsState {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var result by remember(repository, uid) { mutableStateOf<ApiResult<NotificationCounts>?>(null) }
    var loading by remember(repository, uid) { mutableStateOf(false) }
    var refresh by remember(repository, uid) { mutableIntStateOf(0) }
    LaunchedEffect(repository, uid, lifecycle) {
        if (repository == null || uid == null) return@LaunchedEffect
        var token = 0L
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                // snapshotFlow emits once on each resume, rather than a second initial launch.
                snapshotFlow { refresh }.collect {
                    val expectedToken = ++token
                    result = null
                    loading = true
                    try {
                        val loaded = repository.load()
                        coroutineContext.ensureActive()
                        if (expectedToken == token) result = loaded
                    } catch (e: CancellationException) {
                        // Revision mismatch can cancel just this operation, not the lifecycle job.
                        coroutineContext.ensureActive()
                        if (expectedToken == token) result = ApiResult.Failure(FailureKind.UNCERTAIN, "未读数量加载已取消，请重试")
                    } catch (_: Exception) {
                        coroutineContext.ensureActive()
                        if (expectedToken == token) result = ApiResult.Failure(FailureKind.NETWORK, "未读数量加载失败，请重试")
                    } finally {
                        if (coroutineContext.isActive && expectedToken == token) loading = false
                    }
                }
            } finally {
                token++
                result = null
                loading = false
            }
        }
    }
    val value = (result as? ApiResult.Success)?.value
    val enabled = uid != null && repository != null
    return NotificationCountsState(value, when {
        !enabled -> null
        loading -> "正在加载未读数量"
        result is ApiResult.Failure -> (result as ApiResult.Failure).message
        value?.hasUnknown == true -> "部分未读数量暂不可用"
        else -> null
    }, loading, if (!enabled) null else ({
        if (!loading && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            loading = true // Close the repeated-click window before the effect consumes the retry.
            refresh++
        }
    }))
}
