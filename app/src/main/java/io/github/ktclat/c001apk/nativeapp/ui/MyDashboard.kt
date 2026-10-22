// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.ktclat.c001apk.nativeapp.account.AccountState

/** Source: message_page.dart and message_{header,first,second,third}_card.dart.
 * AccountSummary has no server profile/count fields. Never substitute local counts or zeros.
 */
enum class MyPersonalList { FOLLOW, FAN, RECENT, COLLECTION, LIKE, REPLY }

@Composable
fun MyDashboard(accountState: AccountState, scrollState: ScrollState, bottomPadding: Dp,
    onNavigate: (String) -> Unit, onPersonalList: ((MyPersonalList) -> Unit)? = null) {
    val active = accountState.accounts.firstOrNull { it.id == accountState.activeId }
    var unavailable by remember { mutableStateOf<String?>(null) }
    val loggedIn = active != null && !active.needsLogin
    val protectedAction: (String) -> Unit = { route -> onNavigate(if (loggedIn) route else "login") }
    val personal: (MyPersonalList, String) -> Unit = { kind, label ->
        if (!loggedIn) onNavigate("login") else if (onPersonalList != null) onPersonalList(kind) else unavailable = label
    }
    if (unavailable != null) AlertDialog(onDismissRequest = { unavailable = null },
        title = { Text(unavailable!!) },
        text = { Text("原生版尚未接入此账号列表。没有用本地记录替代，也不会显示虚假的统计。") },
        confirmButton = { TextButton(onClick = { unavailable = null }) { Text("知道了") } })
    Column(Modifier.fillMaxSize().testTag("my-root-list").floatingDockRecovery(LocalFloatingDockReveal.current).verticalScroll(scrollState)
        .padding(bottom = 10.dp + bottomPadding)) {
        LocalScrollableRootHeader.current?.invoke()
        Box(Modifier.fillMaxWidth().padding(vertical = 10.dp).heightIn(min = 64.dp), contentAlignment = Alignment.Center) {
            if (!loggedIn) FilledTonalButton(onClick = { onNavigate("login") }, modifier = Modifier.heightIn(min = 48.dp)) { Text("点击登录") }
            else Row(Modifier.fillMaxWidth().clickable { onNavigate("user:${active!!.id}") }.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.size(48.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(ProfileIcons.PersonOutline, null) }
                }
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(active!!.name, style = MaterialTheme.typography.titleMedium)
                    Text("查看个人主页", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(ProfileIcons.KeyboardArrowRightOutlined, null)
            }
        }
        HorizontalDivider()
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (loggedIn) Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column {
                    Row(Modifier.fillMaxWidth()) {
                        MyShortcut("动态", ProfileIcons.FeedOutlined, Modifier.weight(1f)) { onNavigate("user:${active!!.id}") }
                        MyShortcut("关注", ProfileIcons.PersonAddOutlined, Modifier.weight(1f), status = if (!loggedIn) "登录后查看" else if (onPersonalList == null) "暂未接入" else null) { personal(MyPersonalList.FOLLOW, "关注") }
                        MyShortcut("粉丝", ProfileIcons.PersonOutline, Modifier.weight(1f), status = if (!loggedIn) "登录后查看" else if (onPersonalList == null) "暂未接入" else null) { personal(MyPersonalList.FAN, "粉丝") }
                    }
                    Text("账号统计尚未接入", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            MyNotice("@我的动态", ProfileIcons.AlternateEmail, Color(0xFF2196F3)) { protectedAction("notifications:MENTIONS") }
            MyNotice("@我的评论", ProfileIcons.MessageOutlined, Color(0xFF00BCD4)) { protectedAction("notifications:REPLIES") }
            MyNotice("我收到的赞", ProfileIcons.ThumbUpAltOutlined, Color(0xFF4CAF50)) { protectedAction("notifications:LIKES") }
            MyNotice("好友关注", ProfileIcons.PersonAddOutlined, Color(0xFFF44336)) { protectedAction("notifications:FOLLOWERS") }
            MyNotice("私信与信息", ProfileIcons.MailOutline, Color(0xFFFF9800)) { protectedAction("notifications:MESSAGES") }
        }
    }
}
@Composable private fun MyShortcut(label: String, icon: ImageVector, modifier: Modifier, status: String? = null, onClick: () -> Unit) {
    Column(modifier.heightIn(min = 72.dp).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (status != null) Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
@Composable private fun MyNotice(label: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(onClick = onClick).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = color, modifier = Modifier.size(40.dp)) { Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = Color.White) } }
            Text(label, Modifier.weight(1f).padding(horizontal = 10.dp), style = MaterialTheme.typography.bodyLarge)
            Icon(ProfileIcons.KeyboardArrowRightOutlined, null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}
