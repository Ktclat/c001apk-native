// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

/** One notice-route attempt. Cancellation and memory commits share a linearization monitor.
 * Accepted memory stays identity-bound after a completed read; it does not retain this route.
 * IO never inspects mutable Compose state or a replacement page's epoch.
 */
class NotificationReadAttempt {
    private var active = true
    @Synchronized fun isCurrent(): Boolean = active
    @Synchronized fun cancel() { active = false }
    @Synchronized internal fun mutateCache(action: () -> Boolean): Boolean = active && action()
}
