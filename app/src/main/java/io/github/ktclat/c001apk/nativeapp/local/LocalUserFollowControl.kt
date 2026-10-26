// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.local

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Explicit local action. No account session or network repository is accepted by this component. */
@OptIn(ExperimentalLayoutApi::class)
@Composable fun LocalUserFollowControl(uid: String, repository: LocalRepository, isLoggedIn: Boolean, name: String = "", avatar: String? = null, onManage: () -> Unit) {
    if (!validUid(uid)) return
    key(uid, repository) {
        val load by remember(repository) { repository.loadStates() }.collectAsState(initial = LocalLoadState.Loading)
        val follows = (load as? LocalLoadState.Ready)?.value?.follows
        val following = follows?.any { it.uid == uid } == true
        val label = if (isLoggedIn) "悄悄关注" else "本地关注"
        val scope = rememberCoroutineScope()
        var busy by remember { mutableStateOf(false) }
        var remove by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        fun save(remove: Boolean) {
            if (busy || follows == null) return
            busy = true
            // Public space metadata has larger read bounds than the persisted local-follow schema.
            // Preserve the action when optional metadata is long; never truncate a URL into another URL.
            val savedName = name.take(200).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
            val savedAvatar = avatar?.takeIf { it.length <= 2048 }
            scope.launch {
                try { if (remove) repository.unfollow(uid) else repository.follow(LocalFollowUser(uid, savedName, savedAvatar)); error = null }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "本地关注保存失败，请重试；原有名单未重置。" }
                finally { busy = false }
            }
        }
        OutlinedCard(Modifier.fillMaxWidth().testTag("local-user-follow:$uid")) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name.ifBlank { "用户 $uid" }, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${label}仅保存在本机，不会关注或取消账号中的关注。" + if (isLoggedIn) "在底栏“关注”选择“悄悄关注”查看本机名单的动态。" else "可在底栏“关注”查看本机名单的动态。", style = MaterialTheme.typography.bodySmall)
                if (load == LocalLoadState.Failed) Text("无法读取本地关注，原有数据未覆盖。请返回后重试。", color = MaterialTheme.colorScheme.error)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(enabled = follows != null && !busy, onClick = { if (following) remove = true else save(false) }) {
                        Text(if (following) "取消$label" else label)
                    }
                    TextButton(onClick = onManage) { Text("管理名单") }
                }
            }
        }
        if (remove) AlertDialog(onDismissRequest = { remove = false }, title = { Text("取消$label？") },
            text = { Text("仅从本机名单移除用户 $uid，不会改变账号关注。") },
            confirmButton = { TextButton(onClick = { remove = false; save(true) }) { Text("移除") } },
            dismissButton = { TextButton(onClick = { remove = false }) { Text("取消") } })
    }
}
