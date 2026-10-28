// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.feature.media

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** A URL + loader occurrence owns this state. Leaving the composition revokes every callback. */
internal class PublicImageRequestState {
    private var active = true
    var attempt by mutableIntStateOf(0)
        private set
    var phase by mutableStateOf(MediaImagePhase.Loading)
        private set
    var failure by mutableStateOf<ImageLoadFailure?>(null)
        private set
    val canRetry: Boolean get() = active && phase == MediaImagePhase.Error && attempt == 0
    fun finish(ownerAttempt: Int, error: ImageLoadFailure? = null) {
        if (!active || ownerAttempt != attempt || phase != MediaImagePhase.Loading) return
        failure = error
        phase = if (error == null) MediaImagePhase.Success else MediaImagePhase.Error
    }
    fun retry() {
        if (!canRetry) return
        attempt++
        failure = null
        phase = MediaImagePhase.Loading
    }
    fun dispose() { active = false }
}

/** Space for two 16sp reason lines, a 48dp-or-font-sized action, and padding. */
internal fun publicImageRetryFits(widthDp: Float, heightDp: Float, fontScale: Float): Boolean =
    widthDp.isFinite() && heightDp.isFinite() && fontScale.isFinite() && fontScale > 0 &&
        widthDp >= maxOf(120f, 80f * fontScale) && heightDp >= maxOf(120f, 16f + 64f * fontScale)
