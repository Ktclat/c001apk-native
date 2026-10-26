// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.domain.ContentItem
import io.github.ktclat.c001apk.nativeapp.domain.UserConnectionKind
import io.github.ktclat.c001apk.nativeapp.feature.media.PublicImage
import io.github.ktclat.c001apk.nativeapp.local.*

/** Two route-owned reads: a failed/empty feed cannot erase the independently loaded user identity. */
@Composable internal fun UserProfileScreen(uid: String, model: BrowseViewModel,
    accounts: AccountRuntime, accountState: AccountState, local: LocalRepository, localLoad: LocalLoadState,
    navigate: (String) -> Unit, openLink: (String) -> Unit, favorite: (ContentItem) -> Unit) {
    if (!validUid(uid)) {
        Text("用户 ID 格式无效，未读取用户资料", Modifier.padding(24.dp))
        return
    }
    val states by model.states.collectAsState()
    val profileKey = "user-profile:$uid"
    val feedKey = "user:$uid"
    val blacklist = (localLoad as? LocalLoadState.Ready)?.value?.blacklist
    val blocked = blacklist?.userBlackList?.contains(uid) == true
    LaunchedEffect(model, profileKey) { model.load(profileKey) }
    LaunchedEffect(model, feedKey, blacklist != null, blocked) {
        if (blacklist != null && !blocked) model.load(feedKey) else model.leaveRead(feedKey)
    }
    DisposableEffect(model, profileKey, feedKey) { onDispose { model.leaveRead(profileKey); model.leaveRead(feedKey) } }
    val profileState = states[profileKey] ?: BrowseState(loading = true)
    val profile = profileState.items.firstOrNull()?.takeIf { it.userId == uid && it.userProfile != null }
    val rawFeed = states[feedKey] ?: BrowseState(loading = true)
    val visibleFeed = remember(rawFeed, blacklist, blocked, localLoad) {
        when {
            blacklist == null -> BrowseState(error = if (localLoad == LocalLoadState.Failed)
                "本地过滤设置无法读取，未展示未过滤内容" else "正在读取本地过滤设置")
            blocked -> BrowseState()
            else -> rawFeed.copy(items = filterBlocked(rawFeed.items, blacklist))
        }
    }
    // Retain the existing owner-scoped preview geometry when child routes recompose this list.
    CompositionLocalProvider(LocalPreviewGeometryStore provides model.previewGeometry) {
        ContentList(visibleFeed, feedKey, accounts, accountState,
            retry = { if (blacklist != null && !blocked) model.load(feedKey, refresh = !rawFeed.moreError, more = rawFeed.moreError) },
            verificationRetry = { pending -> if (blacklist != null && !blocked)
                model.load(feedKey, refresh = !rawFeed.moreError, more = rawFeed.moreError, pageRetry = pending) },
            more = { if (blacklist != null && !blocked) model.load(feedKey, more = true) },
            navigate = navigate, openLink = openLink, favorite = favorite,
            header = {
                UserProfileHeader(uid, profileState, onConnections = { navigate(it.route(uid)) }) { model.load(profileKey, refresh = true) }
                LocalUserFollowControl(uid, local, accountState.activeId != null,
                    name = profile?.userName.orEmpty(), avatar = profile?.avatarUrl) { navigate("local-follows") }
                if (blocked) Text("已屏蔽此用户的动态", Modifier.padding(16.dp).testTag("user-feed-blocked"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else Text("动态", Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.titleMedium)
            })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun UserProfileHeader(uid: String, state: BrowseState, onConnections: (UserConnectionKind) -> Unit, retry: () -> Unit) {
    val item = state.items.firstOrNull()?.takeIf { it.userId == uid && it.userProfile != null }
    val profile = item?.userProfile
    Surface(Modifier.fillMaxWidth().testTag("user-profile:$uid"), shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.inverseOnSurface) {
        Column {
            if (item != null && profile != null) {
                Box(Modifier.fillMaxWidth().height(if (profile.coverUrl != null) 165.dp else 100.dp)) {
                    profile.coverUrl?.let { PublicImage(it, "用户封面", Modifier.fillMaxWidth().height(125.dp).testTag("user-cover:$uid")) }
                    Surface(Modifier.align(Alignment.BottomStart).padding(start = 16.dp).size(84.dp),
                        shape = CircleShape, color = MaterialTheme.colorScheme.inverseOnSurface) {
                        PublicImage(item.avatarUrl, "用户头像", Modifier.padding(4.dp).clip(CircleShape).testTag("user-avatar:$uid"), avatar = true)
                    }
                }
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.userName.ifBlank { "用户名未提供" }, style = MaterialTheme.typography.titleMedium,
                        maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("user-name:$uid"))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("UID: $uid", style = MaterialTheme.typography.bodyMedium)
                        profile.level?.let { Text("Lv.$it", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        profile.feedCount?.let { Text("$it 动态", style = MaterialTheme.typography.bodyMedium) }
                        profile.likesReceived?.let { Text("$it 赞", style = MaterialTheme.typography.bodyMedium) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { onConnections(UserConnectionKind.FOLLOWING) }, modifier = Modifier.heightIn(min = 48.dp).testTag("user-following:$uid")) {
                            Text(profile.followingCount?.let { "$it 关注" } ?: "关注")
                        }
                        TextButton(onClick = { onConnections(UserConnectionKind.FANS) }, modifier = Modifier.heightIn(min = 48.dp).testTag("user-fans:$uid")) {
                            Text(profile.fansCount?.let { "$it 粉丝" } ?: "粉丝")
                        }
                    }
                    profileLastActive(profile.lastActiveSeconds)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

                }
            }
            if (item == null || state.error != null || state.loading) Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item == null) Text("用户资料", style = MaterialTheme.typography.titleMedium)
                if (state.loading) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("正在读取用户资料")
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!state.loading && item == null && state.error == null) Text("用户资料未提供")
                if (!state.loading) TextButton(onClick = retry, modifier = Modifier.testTag("user-profile-retry:$uid")) { Text("重试用户资料") }
            }
        }
    }
}
