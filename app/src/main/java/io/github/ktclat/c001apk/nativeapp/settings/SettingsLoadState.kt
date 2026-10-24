// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.settings

import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

sealed interface SettingsLoadState {
    data object Loading : SettingsLoadState
    data class Ready(val value: AppSettings) : SettingsLoadState
    data object Failed : SettingsLoadState
}
fun SettingsRepository.loadStates() = settings.map<AppSettings, SettingsLoadState> { SettingsLoadState.Ready(it) }.catch { emit(SettingsLoadState.Failed) }
