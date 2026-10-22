// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.local

import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** Never silently replace damaged persisted data with a fake successful empty list. */
sealed interface LocalLoadState {
    data object Loading : LocalLoadState
    data class Ready(val value: LocalState) : LocalLoadState
    data object Failed : LocalLoadState
}
fun LocalRepository.loadStates() = state.map<LocalState, LocalLoadState> { LocalLoadState.Ready(it) }.catch { emit(LocalLoadState.Failed) }
