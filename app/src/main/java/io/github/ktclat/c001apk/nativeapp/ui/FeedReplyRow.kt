package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import coil.compose.LocalImageLoader
import io.github.ktclat.c001apk.nativeapp.domain.ContentItem
import io.github.ktclat.c001apk.nativeapp.feature.feed.feedRelativeTime
import io.github.ktclat.c001apk.nativeapp.feature.media.LocalMediaImageLoader
import io.github.ktclat.c001apk.nativeapp.feature.media.MediaGrid
import io.github.ktclat.c001apk.nativeapp.settings.LocalReadingStyles

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun FeedReplyRow(reply: ContentItem, navigate: (String) -> Unit, openLink: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
        Box(Modifier.size(48.dp).semantics { contentDescription = "查看${reply.userName.ifBlank { "作者" }}主页" }.clickable(enabled = reply.userId.isNotBlank()) { navigate("user:${reply.userId}") }, contentAlignment = Alignment.TopCenter) {
            if (reply.avatarUrl.isNullOrBlank()) Surface(Modifier.padding(top = 8.dp).size(32.dp), shape = CircleShape, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .12f)) { Box(contentAlignment = Alignment.Center) { Icon(FlutterMaterialIcons.Person, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) } }
            else AsyncImage(reply.avatarUrl, null, modifier = Modifier.padding(top = 8.dp).size(32.dp).clip(CircleShape), imageLoader = LocalMediaImageLoader.current ?: LocalImageLoader.current, contentScale = ContentScale.Crop)
        }
        Column(Modifier.weight(1f)) {
            Text(reply.userName.ifBlank { "作者信息未提供" }, fontSize = 15.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.primary)
            val reading = LocalReadingStyles.current
            CompositionLocalProvider(LocalReadingStyles provides reading.copy(body = reading.reply)) {
                PagedPostText(reply.message, openLink)
            }
            if (reply.imageUrls.isNotEmpty()) MediaGrid(reply.imageUrls)
            reply.replies.take(3).forEach { nested -> Surface(Modifier.fillMaxWidth().padding(top = 5.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.clickable { navigate("thread:${reply.id}") }.padding(8.dp)) {
                    Text("${nested.userName}：", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    PagedPostText(nested.message.take(1200), openLink)
                }
            } }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Text(feedRelativeTime(reply.publishedAtSeconds, System.currentTimeMillis() / 1000), Modifier.align(Alignment.CenterVertically), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (reply.replyCount > 0 || reply.replies.isNotEmpty()) TextButton(onClick = { navigate("thread:${reply.id}") }) { Text("${io.github.ktclat.c001apk.nativeapp.feature.feed.feedCount(reply.replyCount)}条回复") }
                TextButton(onClick = { navigate("compose-reply:reply:${reply.id}") }) { Text("回复") }
                Text("赞 ${io.github.ktclat.c001apk.nativeapp.feature.feed.feedCount(reply.likeCount)}", Modifier.align(Alignment.CenterVertically).semantics { contentDescription = "${reply.likeCount}赞，暂仅展示数量" }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
