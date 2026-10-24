// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.domain

/** Server-only projection: absent/invalid counts are unknown, not zero. Never contains credentials. */
data class OwnProfile(val uid: String, val name: String?, val avatarUrl: String?,
    val level: Long?, val experience: Long?, val nextLevelExperience: Long?,
    val feed: Long?, val follow: Long?, val fans: Long?) {
    val progress: Float? get() = if (experience != null && nextLevelExperience != null && nextLevelExperience > 0)
        (experience.toDouble() / nextLevelExperience.toDouble()).coerceIn(0.0, 1.0).toFloat() else null
}
fun interface OwnProfileRepository { suspend fun load(): ApiResult<OwnProfile> }
