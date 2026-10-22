// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.unit.Dp
import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
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
    OwnProfileDashboardContent(repository, accountState, scrollState, bottomPadding, onNavigate, onPersonalList)
}

/** State and effect belong to this exact account generation, never to the replacement account. */
@Composable internal fun OwnProfileDashboardContent(repository: OwnProfileRepository?, accountState: AccountState,
    scrollState: ScrollState, bottomPadding: Dp, onNavigate: (String) -> Unit,
    onPersonalList: (MyPersonalList) -> Unit) {
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
        MyDashboard(accountState, scrollState, bottomPadding, onNavigate, onPersonalList,
            profile = (result as? ApiResult.Success)?.value,
            profileStatus = when {
                uid == null -> null
                result == null -> "正在加载个人资料"
                result is ApiResult.Failure -> (result as ApiResult.Failure).message
                else -> null
            }, onRefreshProfile = { refresh++ }, profileLoading = uid != null && result == null)
    }
}
