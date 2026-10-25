// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ktclat.c001apk.nativeapp.domain.FollowType
import io.github.ktclat.c001apk.nativeapp.ui.FlutterMaterialIcons

/** Reuse persisted native choices without rewriting a user's existing local/server selection. */
internal fun AppSettings.selectFollow(type: FollowType?): AppSettings =
    if (type == null) copy(secretFollow = true) else copy(secretFollow = false, followType = type.ordinal)

internal fun AppSettings.followLabel(): String =
    if (secretFollow) "悄悄关注" else FollowType.entries[followType].title

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun FollowContentChoices(settings: AppSettings, onSelect: (FollowType?) -> Unit) {
    Text("关注页内容", style = MaterialTheme.typography.titleMedium)
    Text("选择底栏“关注”显示的分类。账号关注来自当前登录账号；悄悄关注读取本机名单，不会发送关注或取消关注请求。", style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FollowType.entries.forEach { type ->
            FilterChip(modifier = Modifier.heightIn(min = 48.dp), selected = !settings.secretFollow && settings.followType == type.ordinal,
                onClick = { onSelect(type) }, label = { Text(type.title) })
        }
        FilterChip(modifier = Modifier.heightIn(min = 48.dp), selected = settings.secretFollow, onClick = { onSelect(null) }, label = { Text("悄悄关注") })
    }
}

@Composable fun FollowContentMenu(settings: AppSettings, onSelect: (FollowType?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, modifier = Modifier.heightIn(min = 48.dp)
            .semantics { contentDescription = "切换关注内容：${settings.followLabel()}" }) {
            Text(settings.followLabel(), style = MaterialTheme.typography.titleMedium)
            Icon(FlutterMaterialIcons.KeyboardArrowDown, null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 400.dp)) {
            FollowType.entries.forEach { type ->
                DropdownMenuItem(text = { Text(type.title) },
                    leadingIcon = { RadioButton(selected = !settings.secretFollow && settings.followType == type.ordinal, onClick = null) },
                    onClick = { expanded = false; onSelect(type) })
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("悄悄关注") }, leadingIcon = { RadioButton(selected = settings.secretFollow, onClick = null) },
                onClick = { expanded = false; onSelect(null) })
        }
    }
}
