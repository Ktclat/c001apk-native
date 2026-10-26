// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.domain

/** Read-only /user/space fields. Missing numbers stay unknown; no account follow state is inferred. */
data class UserSpaceProfile(
    val coverUrl: String? = null,
    val level: Long? = null,
    val feedCount: Long? = null,
    val likesReceived: Long? = null,
    val followingCount: Long? = null,
    val fansCount: Long? = null,
)
