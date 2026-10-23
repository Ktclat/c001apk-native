// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.feature.media

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal enum class MediaImagePhase { Loading, Success, Error }

/** One image occurrence owns its request. Old callbacks cannot finish a newer explicit attempt. */
internal class MediaImageState {
    var attempt by mutableIntStateOf(0)
        private set
    var phase by mutableStateOf(MediaImagePhase.Loading)
        private set

    var bypassCache by mutableStateOf(false)
        private set

    /** Disposed requests lose ownership, even if their transport ignores cancellation. */
    fun leave() {
        if (phase == MediaImagePhase.Error) return // Failed pages stay failed until the user's tap.
        attempt++
        bypassCache = false
        phase = MediaImagePhase.Loading
    }

    fun finish(ownerAttempt: Int, result: MediaImagePhase) {
        if (ownerAttempt == attempt && phase == MediaImagePhase.Loading && result != MediaImagePhase.Loading) phase = result
    }

    fun retry() {
        if (phase != MediaImagePhase.Error) return
        attempt++
        bypassCache = true
        phase = MediaImagePhase.Loading
    }
}
