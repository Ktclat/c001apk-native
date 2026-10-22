// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.platform

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One value owns the inventory and its load error; null inventory means no successful load yet. */
internal data class InstalledAppsLoadState(
    val apps: List<InstalledApp>? = null,
    val failed: Boolean = false,
)

/**
 * Retain the previous inventory during refresh/error, but clear the prior error on a new attempt.
 * Both publications use Main and remain children of the calling composition's Job. Loading and
 * defensive list copying use IO; no detached scope, cache, or non-cancellable publication exists.
 */
internal suspend fun loadAndPublishInstalledApps(
    previous: InstalledAppsLoadState,
    load: suspend () -> List<InstalledApp>,
    publish: (InstalledAppsLoadState) -> Unit,
) {
    withContext(Dispatchers.Main.immediate) { publish(previous.copy(failed = false)) }
    val next = try {
        InstalledAppsLoadState(apps = withContext(Dispatchers.IO) { load().toList() })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        previous.copy(failed = true)
    }
    withContext(Dispatchers.Main.immediate) { publish(next) }
}
