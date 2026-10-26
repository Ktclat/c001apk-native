// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.*
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import io.github.ktclat.c001apk.nativeapp.account.AccountState
import io.github.ktclat.c001apk.nativeapp.domain.OwnProfile
import io.github.ktclat.c001apk.nativeapp.domain.NotificationCounts
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import io.github.ktclat.c001apk.nativeapp.feature.media.PublicImage

/** Source: message_page.dart and message_{header,first,second,third}_card.dart.
 * Server profile fields are nullable. Never substitute local counts or zeros.
 */
enum class MyPersonalList { FEED, FOLLOW, FAN, RECENT, COLLECTION, LIKE, REPLY }

internal const val REFRESH_MY_ACTION = "刷新个人资料和未读数量"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyDashboard(accountState: AccountState, scrollState: ScrollState, bottomPadding: Dp,
    onNavigate: (String) -> Unit, onPersonalList: ((MyPersonalList) -> Unit)? = null,
    profile: OwnProfile? = null, profileStatus: String? = null,
    onRefreshProfile: (() -> Unit)? = null, profileLoading: Boolean = false,
    counts: NotificationCounts? = null, countsStatus: String? = null,
    onRetryCounts: (() -> Unit)? = null, countsLoading: Boolean = false,
    onRefreshAll: (() -> Unit)? = null, refreshing: Boolean = false, refreshEnabled: Boolean = true,
    countsRead: io.github.ktclat.c001apk.nativeapp.domain.PendingCountsRead? = null,
    onVerifiedCountsRetry: ((io.github.ktclat.c001apk.nativeapp.domain.PendingCountsRead) -> Unit)? = null) {
    val active = accountState.accounts.firstOrNull { it.id == accountState.activeId }
    var unavailable by remember(accountState.activeId, accountState.generation) { mutableStateOf<String?>(null) }
    val loggedIn = personalListAccountId(accountState) != null
    val ownProfile = profile?.takeIf { loggedIn && it.uid == active?.id }
    val ownCounts = counts?.takeIf { loggedIn && it.uid == active?.id }
    val protectedAction: (String) -> Unit = { route -> onNavigate(if (loggedIn) route else "login") }
    val personal: (MyPersonalList, String) -> Unit = { kind, label ->
        if (!loggedIn) onNavigate("login") else if (onPersonalList != null) onPersonalList(kind) else unavailable = label
    }
    if (unavailable != null) AlertDialog(onDismissRequest = { unavailable = null },
        title = { Text(unavailable!!) },
        text = { Text("原生版尚未接入此账号列表。没有用本地记录替代，也不会显示虚假的统计。") },
        confirmButton = { TextButton(onClick = { unavailable = null }) { Text("知道了") } })
    val pullState = rememberPullToRefreshState()
    val revealDock = LocalFloatingDockReveal.current
    val canPull = loggedIn && onRefreshAll != null && refreshEnabled && !refreshing
    Box(Modifier.fillMaxSize().pullToRefresh(isRefreshing = refreshing, state = pullState,
        enabled = canPull, onRefresh = { onRefreshAll?.invoke() })) {
    Column(Modifier.fillMaxSize().testTag("my-root-list").semantics {
        customActions = buildList {
            revealDock?.let { add(CustomAccessibilityAction(SHOW_FLOATING_DOCK_ACTION, it)) }
            if (canPull) add(CustomAccessibilityAction(REFRESH_MY_ACTION) { onRefreshAll?.invoke(); true })
        }
    }.verticalScroll(scrollState)
        .padding(bottom = 10.dp + bottomPadding)) {
        LocalScrollableRootHeader.current?.invoke()
        Box(Modifier.fillMaxWidth().padding(vertical = 10.dp).heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
            if (!loggedIn) FilledTonalButton(onClick = { onNavigate("login") }, modifier = Modifier.heightIn(min = 48.dp)) { Text("点击登录") }
            else BoxWithConstraints(Modifier.fillMaxWidth()) {
                // Flutter reserves 25% of the post-avatar width; retain a real 48dp refresh target on narrow windows.
                val trailingWidth = ((maxWidth - 98.dp) * .25f).coerceAtLeast(48.dp)
                Row(Modifier.fillMaxWidth().clickable { onNavigate("user:${active!!.id}") }.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.size(48.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(ProfileIcons.PersonOutline, null)
                        ownProfile?.avatarUrl?.let { url -> PublicImage(url, "头像", modifier = Modifier.fillMaxSize().clip(CircleShape), avatar = true) }
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(ownProfile?.name ?: active!!.name, style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val experience = ownProfile?.experience
                    val next = ownProfile?.nextLevelExperience
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(ownProfile?.level?.let { "Lv.$it" }.orEmpty(), Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp))
                        if (experience != null && next != null) Text("$experience/$next", Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    }
                    ownProfile?.progress?.let { progress -> LinearProgressIndicator(progress = { progress },
                        modifier = Modifier.fillMaxWidth().padding(top = 5.dp).height(4.dp)) }
                }
                Box(Modifier.width(trailingWidth), contentAlignment = Alignment.CenterEnd) {
                    if (onRefreshProfile != null && profileStatus == null) {
                        IconButton(onClick = onRefreshProfile, enabled = !profileLoading, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                            Icon(FlutterMaterialIcons.Refresh, "刷新个人资料")
                        }
                    }
                }
                }
            }
        }
        HorizontalDivider()
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (loggedIn) Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column {
                    Row(Modifier.fillMaxWidth()) {
                        MyProfileStat("动态", ownProfile?.feed, Modifier.weight(1f)) { personal(MyPersonalList.FEED, "动态") }
                        MyProfileStat("关注", ownProfile?.follow, Modifier.weight(1f)) { personal(MyPersonalList.FOLLOW, "关注") }
                        MyProfileStat("粉丝", ownProfile?.fans, Modifier.weight(1f)) { personal(MyPersonalList.FAN, "粉丝") }
                    }
                    if (profileStatus != null) Text(profileStatus, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (onRefreshProfile != null && profileStatus != null) TextButton(onClick = onRefreshProfile, enabled = !profileLoading) { Text("刷新个人资料") }
                }
            }
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column {
                    Row {
                        MyShortcut("本地收藏", ProfileIcons.ArchiveOutlined, Modifier.weight(1f)) { onNavigate("library:FAVORITES") }
                        MyShortcut("浏览历史", ProfileIcons.History, Modifier.weight(1f)) { onNavigate("library:HISTORY") }
                        MyShortcut("我的常去", ProfileIcons.MyLocation, Modifier.weight(1f), status = if (!loggedIn) "登录后查看" else if (onPersonalList == null) "暂未接入" else null) { personal(MyPersonalList.RECENT, "我的常去") }
                    }
                    Row {
                        MyShortcut("我的收藏", ProfileIcons.StarBorder, Modifier.weight(1f), status = if (!loggedIn) "登录后查看" else if (onPersonalList == null) "暂未接入" else null) { personal(MyPersonalList.COLLECTION, "我的收藏") }
                        MyShortcut("我的赞", ProfileIcons.FavoriteBorder, Modifier.weight(1f), status = if (!loggedIn) "登录后查看" else if (onPersonalList == null) "暂未接入" else null) { personal(MyPersonalList.LIKE, "我的赞") }
                        MyShortcut("我的回复", ProfileIcons.MessengerOutline, Modifier.weight(1f), status = if (!loggedIn) "登录后查看" else if (onPersonalList == null) "暂未接入" else null) { personal(MyPersonalList.REPLY, "我的回复") }
                    }
                }
            }
            if (loggedIn && countsStatus != null) Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(countsStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (countsRead != null && onVerifiedCountsRetry != null) CountsReadVerification(countsRead, countsLoading) { onVerifiedCountsRetry(countsRead) }
                    if (onRetryCounts != null) TextButton(onClick = onRetryCounts, enabled = !countsLoading) { Text("重试未读数量") }
                }
            }
            MyNotice("@我的动态", "MENTIONS", ProfileIcons.AlternateEmail, Color(0xFF2196F3), ownCounts?.atMe, loggedIn) { protectedAction("notifications:MENTIONS") }
            MyNotice("@我的评论", "REPLIES", ProfileIcons.MessageOutlined, Color(0xFF00BCD4), ownCounts?.atCommentMe, loggedIn) { protectedAction("notifications:REPLIES") }
            MyNotice("我收到的赞", "LIKES", ProfileIcons.ThumbUpAltOutlined, Color(0xFF4CAF50), ownCounts?.feedLike, loggedIn) { protectedAction("notifications:LIKES") }
            MyNotice("好友关注", "FOLLOWERS", ProfileIcons.PersonAddOutlined, Color(0xFFF44336), ownCounts?.contactsFollow, loggedIn) { protectedAction("notifications:FOLLOWERS") }
            MyNotice("私信与信息", "MESSAGES", ProfileIcons.MailOutline, Color(0xFFFF9800), ownCounts?.messagesAndNotifications, loggedIn) { protectedAction("notifications:MESSAGES") }
        }
    }
    if (loggedIn && onRefreshAll != null) PullToRefreshDefaults.Indicator(
        state = pullState, isRefreshing = refreshing, modifier = Modifier.align(Alignment.TopCenter)
            .testTag(if (refreshing) "my-refresh-active" else "my-refresh-idle"))
    }
}
@Composable private fun MyShortcut(label: String, icon: ImageVector, modifier: Modifier, status: String? = null, onClick: () -> Unit) {
    Column(modifier.heightIn(min = 48.dp).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (status != null) Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
@Composable private fun MyNotice(label: String, category: String, icon: ImageVector, color: Color, count: Long?, loggedIn: Boolean, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag("my-notice-$category").clickable(onClick = onClick).semantics {
            if (loggedIn) stateDescription = when {
                count == null -> "未读数量未知"
                count == 0L -> "无未读消息"
                else -> "$count 条未读"
            }
        }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = color, modifier = Modifier.size(40.dp)) { Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = Color.White) } }
            Text(label, Modifier.weight(1f).padding(horizontal = 10.dp), style = MaterialTheme.typography.bodyLarge)
            if (count != null && count > 0) Surface(shape = CircleShape, color = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
                modifier = Modifier.testTag("my-notice-badge-$category").clearAndSetSemantics {}) {
                Box(Modifier.defaultMinSize(minWidth = 24.dp, minHeight = 24.dp).padding(horizontal = 6.dp, vertical = 2.dp), contentAlignment = Alignment.Center) {
                    Text(notificationBadgeText(count)!!, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
            Icon(ProfileIcons.KeyboardArrowRightOutlined, null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable private fun MyProfileStat(label: String, count: Long?, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.heightIn(min = 48.dp).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Text(count?.toString() ?: "未提供", style = if (count == null) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(label, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** Compact native presentation; exact counts remain on the single row semantics node. */
internal fun notificationBadgeText(count: Long?): String? = when {
    count == null || count <= 0 -> null
    count > 99 -> "99+"
    else -> count.toString()
}
