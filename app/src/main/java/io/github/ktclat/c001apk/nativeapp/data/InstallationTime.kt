// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

/** Flutter storage_util.dart stores milliseconds. This is NOT the V3 signature timestamp. */
object InstallationTime {
    /** Upgrade native v0's persisted seconds once; never rotate any client/account identity.
     * Legacy seconds must be at least 1e9 (September 2001, before Android existed).
     * Smaller values cannot be this app's install time and are initialized from now.
     * This also ensures every converted result is in the retained-millisecond range,
     * so even corrupted small preferences cannot be multiplied on each launch.
     * Existing millisecond values are preserved, not revalidated against wall-clock drift.
     */
    fun persistedMillis(stored: Long, nowMillis: Long): Long {
        require(nowMillis >= 100_000_000_000L)
        return when {
            stored < 1_000_000_000L -> nowMillis
            stored < 100_000_000_000L -> stored * 1000L
            else -> stored
        }
    }
}
