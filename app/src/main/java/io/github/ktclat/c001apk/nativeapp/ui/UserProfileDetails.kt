// SPDX-License-Identifier: AGPL-3.0-only
// Read-only fields and labels from Flutter user_page.dart / DateUtil.fromToday.
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun profileRegisteredAt(seconds: Long?, now: Long = System.currentTimeMillis() / 1000,
    zone: ZoneId = ZoneId.systemDefault()): String? = seconds?.takeIf { it in 1..now }?.let {
    DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss").withZone(zone).format(Instant.ofEpochSecond(it))
}

internal fun profileLastActive(seconds: Long?, now: Long = System.currentTimeMillis() / 1000,
    zone: ZoneId = ZoneId.systemDefault()): String? {
    val value = seconds?.takeIf { it in 1..now } ?: return null
    val elapsed = now - value
    val label = when {
        elapsed == 0L -> "刚刚"
        elapsed < 60 -> "${elapsed}秒前"
        elapsed <= 3600 -> "${elapsed / 60}分钟前"
        elapsed <= 86400 -> "${elapsed / 3600}小时前"
        elapsed <= 86400 * 2 -> "1天前"
        elapsed <= 86400 * 3 -> "2天前"
        elapsed <= 2592000 -> "${elapsed / 86400}天前"
        else -> {
            val time = Instant.ofEpochSecond(value).atZone(zone)
            val pattern = if (time.year == Instant.ofEpochSecond(now).atZone(zone).year) "MM月dd日" else "yyyy年MM月dd日"
            DateTimeFormatter.ofPattern(pattern).format(time)
        }
    }
    return "${label}活跃"
}

@Composable internal fun UserProfileInfoAction(uid: String, state: BrowseState?) {
    val item = state?.items?.firstOrNull()?.takeIf { it.userId == uid && it.userProfile != null } ?: return
    val profile = requireNotNull(item.userProfile)
    var open by remember(uid, item) { mutableStateOf(false) }
    // Dialog windows replace LocalDensity with resource density. Restore system × app once
    // inside each slot, not around AlertDialog (which cannot cross that window boundary).
    val hostDensity = LocalDensity.current
    IconButton(onClick = { open = true }, modifier = Modifier.testTag("user-info:$uid")) {
        Icon(FlutterMaterialIcons.InfoOutline, "用户信息")
    }
    if (open) AlertDialog(onDismissRequest = { open = false },
        title = { CompositionLocalProvider(LocalDensity provides hostDensity) {
            Text(item.userName.ifBlank { "用户信息" }, maxLines = 2, overflow = TextOverflow.Ellipsis)
        } },
        text = { CompositionLocalProvider(LocalDensity provides hostDensity) {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("user-info-dialog:$uid"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("UID: $uid")
                Text(profile.level?.let { "等级: Lv.$it" } ?: "等级: 未提供")
                Text("性别: " + when (profile.gender) { 1 -> "男"; 0 -> "女"; else -> "未知" })
                val now = System.currentTimeMillis() / 1000
                val registered = profileRegisteredAt(profile.registeredAtSeconds, now)
                if (registered == null) Text("注册时间: 未提供") else {
                    Text("注册时长: ${(now - requireNotNull(profile.registeredAtSeconds)) / 86400} 天")
                    Text("注册时间: $registered")
                }
            }
        } }, confirmButton = { CompositionLocalProvider(LocalDensity provides hostDensity) {
            TextButton(onClick = { open = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("关闭") }
        } })
}
