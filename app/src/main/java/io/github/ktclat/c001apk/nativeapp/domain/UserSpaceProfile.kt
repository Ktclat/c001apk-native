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
    val gender: Int? = null,
    val registeredAtSeconds: Long? = null,
    val lastActiveSeconds: Long? = null,
)

/** Flutter FFFList USER_FOLLOW/FAN: relationship record IDs remain pagination cursors. */
enum class UserConnectionKind(val routePrefix: String, val path: String, val title: String) {
    FOLLOWING("user-following", "v6/user/followList", "TA关注的人"),
    FANS("user-fans", "v6/user/fansList", "TA的粉丝");
    fun route(uid: String) = "$routePrefix:$uid"
}
