// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.feature.feed

import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.LocalImageLoader
import io.github.ktclat.c001apk.nativeapp.feature.media.LocalMediaImageLoader
import io.github.ktclat.c001apk.nativeapp.feature.media.MediaGrid
import io.github.ktclat.c001apk.nativeapp.settings.LocalReadingStyles
import io.github.ktclat.c001apk.nativeapp.ui.FlutterMaterialIcons
import io.github.ktclat.c001apk.nativeapp.ui.PagedPostText
import io.github.ktclat.c001apk.nativeapp.ui.FullPostText

/** Required navigation callbacks have no fake/no-op defaults. Null actions are not offered. */
data class FeedCardActions(
    val openPost: () -> Unit,
    val openAuthor: (() -> Unit)?,
    val openComments: () -> Unit,
    val openLink: (String) -> Unit,
    val openReference: (FeedReference) -> Unit,
    val toggleLike: (() -> Unit)? = null,
    val login: (() -> Unit)? = null,
    val favorite: (() -> Unit)? = null,
    val copy: (() -> Unit)? = null,
    val block: (() -> Unit)? = null,
    val report: (() -> Unit)? = null,
    val openOriginal: (() -> Unit)? = null,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NativeFeedCard(model: FeedCardModel, actions: FeedCardActions, modifier: Modifier = Modifier, detail: Boolean = false) {
    val palette = MaterialTheme.colorScheme
    var menu by remember(model.id) { mutableStateOf(false) }
    val hasMenu = actions.favorite != null || actions.copy != null || actions.block != null || actions.report != null
    Surface(modifier.fillMaxWidth().testTag("feed:${model.id}"), shape = RoundedCornerShape(if (detail) 0.dp else 12.dp),
        color = if (detail) palette.surface else palette.inverseOnSurface) {
        Column(Modifier.padding(horizontal = if (detail) 16.dp else 10.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // 35dp visual avatar, expanded to a non-overlapping 48dp profile target.
                Box(Modifier.size(48.dp).semantics { contentDescription = "查看${model.author.ifBlank { "作者" }}的主页" }.then(if (actions.openAuthor != null) Modifier.clickable(onClickLabel = "查看${model.author}的主页", onClick = actions.openAuthor) else Modifier), contentAlignment = Alignment.Center) {
                    if (model.avatar.isNullOrBlank()) Surface(Modifier.size(35.dp), shape = CircleShape, color = palette.onSurface.copy(alpha = .12f)) { Box(contentAlignment = Alignment.Center) { Icon(FlutterMaterialIcons.Person, null, Modifier.size(24.dp), tint = palette.onSurfaceVariant) } }
                    else AsyncImage(model.avatar, null, modifier = Modifier.size(35.dp).clip(CircleShape), contentScale = ContentScale.Crop, imageLoader = LocalMediaImageLoader.current ?: LocalImageLoader.current)
                }
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text(model.author.ifBlank { "作者信息未提供" }, fontSize = 15.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val metadata by produceState("" to "", model.info, model.device) {
                        value = withContext(Dispatchers.Default) {
                            fun plain(text: String) = Html.fromHtml(text.take(4096), Html.FROM_HTML_MODE_LEGACY).toString().trim()
                            plain(model.info) to plain(model.device)
                        }
                    }
                    if (metadata.first.isNotBlank() || metadata.second.isNotBlank()) Text(buildAnnotatedString {
                        append(metadata.first)
                        if (metadata.second.isNotBlank()) {
                            if (metadata.first.isNotBlank()) append("  ")
                            appendInlineContent("device", "设备")
                            append(metadata.second)
                        }
                    }, inlineContent = mapOf("device" to InlineTextContent(Placeholder(14.sp, 14.sp, PlaceholderVerticalAlign.TextCenter)) {
                        Icon(FlutterMaterialIcons.Smartphone, null, Modifier.fillMaxSize(), tint = palette.onSurfaceVariant)
                    }), fontSize = 14.sp, lineHeight = 18.sp, color = palette.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (model.pinned) Text("置顶", Modifier.padding(horizontal = 4.dp), fontSize = 12.sp, color = palette.primary)
                if (hasMenu) Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.testTag("feed-more:${model.id}")) { Icon(FlutterMaterialIcons.KeyboardArrowDown, "更多动态操作") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        fun close(action: () -> Unit): () -> Unit = { menu = false; action() }
                        actions.copy?.let { DropdownMenuItem(text = { Text("复制内容") }, onClick = close(it)) }
                        actions.favorite?.let { DropdownMenuItem(text = { Text("收藏到本机") }, onClick = close(it)) }
                        actions.block?.let { DropdownMenuItem(text = { Text("屏蔽") }, onClick = close(it)) }
                        actions.report?.let { DropdownMenuItem(text = { Text("举报") }, onClick = close(it)) }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().then(if (!detail) Modifier.clickable(onClickLabel = "查看动态详情", onClick = actions.openPost) else Modifier).testTag("feed-body:${model.id}")) {
                if (model.title.isNotBlank()) { Spacer(Modifier.height(5.dp)); Text(model.title.take(600), style = LocalReadingStyles.current.title) }
                if (detail && model.fullPostBody != null) {
                    Spacer(Modifier.height(5.dp))
                    key(model.id) { FullPostText(model.fullPostBody, model.body, actions.openLink, actions.openOriginal) }
                } else {
                    if (model.body.isNotBlank()) { Spacer(Modifier.height(5.dp)); PagedPostText(model.body, model.bodyTruncated, actions.openLink) }
                    if (model.bodyTruncated && !detail) TextButton(onClick = actions.openPost) { Text("加载详情正文") }
                }
            }
            if (model.images.isNotEmpty()) { Spacer(Modifier.height(10.dp)); MediaGrid(model.images) }
            model.forward?.let { Spacer(Modifier.height(10.dp)); ReferenceSurface(it, false, actions) }
            model.link?.let { link ->
                Spacer(Modifier.height(10.dp))
                Surface(shape = RoundedCornerShape(12.dp), color = palette.surface) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = "打开${link.title}") { actions.openLink(link.url) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        link.image?.let { AsyncImage(it, null, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop, imageLoader = LocalMediaImageLoader.current ?: LocalImageLoader.current); Spacer(Modifier.width(10.dp)) }
                        Column(Modifier.weight(1f)) {
                            Text(link.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                            Text(link.url, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = palette.onSurfaceVariant)
                        }
                    }
                }
            }
            model.hotReply?.let { Spacer(Modifier.height(10.dp)); ReferenceSurface(it, true, actions) }
            if (model.unsupportedChildren > 0) {
                if (detail) Text("另有${model.unsupportedChildren}项嵌套内容尚未支持原生展示", fontSize = 13.sp, color = palette.onSurfaceVariant)
                else TextButton(onClick = actions.openPost) { Text("查看嵌套内容（${model.unsupportedChildren}）", fontSize = 13.sp) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(model.time, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = palette.onSurfaceVariant)
                TextButton(onClick = actions.openComments, colors = ButtonDefaults.textButtonColors(contentColor = palette.onSurfaceVariant), modifier = Modifier.heightIn(min = 48.dp).testTag("feed-comments:${model.id}").semantics { contentDescription = "评论 ${model.replies ?: "数量未知"}" }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(FlutterMaterialIcons.MessageOutlined, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(feedCount(model.replies), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val likeAction = if (model.likeState == FeedLikeState.LOGIN_REQUIRED) actions.login else if (model.likeState.canToggle()) actions.toggleLike else null
                TextButton(onClick = { likeAction?.invoke() }, colors = ButtonDefaults.textButtonColors(contentColor = if (model.likeState == FeedLikeState.LIKED) palette.primary else palette.onSurfaceVariant, disabledContentColor = palette.onSurfaceVariant), enabled = likeAction != null, modifier = Modifier.heightIn(min = 48.dp).testTag("feed-like:${model.id}").semantics {
                    contentDescription = "赞 ${model.likes ?: "数量未知"}"
                    stateDescription = when(model.likeState) { FeedLikeState.UNKNOWN -> "当前点赞状态未知"; FeedLikeState.LOGIN_REQUIRED -> "登录后点赞"; FeedLikeState.PENDING -> "正在提交"; FeedLikeState.LIKED -> "已点赞"; FeedLikeState.NOT_LIKED -> "未点赞" }
                }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(if (model.likeState == FeedLikeState.LIKED) FlutterMaterialIcons.ThumbUp else FlutterMaterialIcons.ThumbUpAltOutlined, null, Modifier.size(16.dp), tint = if (model.likeState == FeedLikeState.LIKED) palette.primary else palette.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp)); Text(feedCount(model.likes), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (model.relations.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                model.relations.take(12).forEach { relation -> key(relation.key) {
                    if (relation.url != null) AssistChip(onClick = { actions.openLink(relation.url) }, label = { Text(relation.title, maxLines = 2, overflow = TextOverflow.Ellipsis) }, modifier = Modifier.heightIn(min = 48.dp))
                    else Text(relation.title, Modifier.padding(horizontal = 8.dp, vertical = 10.dp), fontSize = 13.sp, color = palette.primary)
                } }
                if (model.relations.size > 12) {
                    if (detail) Text("另有${model.relations.size - 12}项关联内容尚未展示", fontSize = 13.sp)
                    else TextButton(onClick = actions.openPost) { Text("更多关联内容") }
                }
            }
        }
    }
}

@Composable
private fun ReferenceSurface(reference: FeedReference, hotReply: Boolean, actions: FeedCardActions) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = reference.destination() != null, onClickLabel = if (hotReply) "查看热评" else "查看转发动态") { actions.openReference(reference) }.padding(10.dp)) {
            if (reference.deleted) Text("原动态已删除", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else {
                val body by produceState<String?>(null, reference.body, hotReply) {
                    value = withContext(Dispatchers.Default) { Html.fromHtml(reference.body.take(if (hotReply) 1200 else 4000), Html.FROM_HTML_MODE_LEGACY).toString().trim() }
                }
                if (reference.title.isNotBlank()) Text(reference.title.take(300), style = LocalReadingStyles.current.title)
                val authorColor = MaterialTheme.colorScheme.primary
                if (body == null) Text("正在排版…") else Text(buildAnnotatedString {
                    withStyle(SpanStyle(color = authorColor)) { append((if (hotReply) "" else "@") + reference.author + "：") }
                    append(body.orEmpty())
                }, style = if (hotReply) LocalReadingStyles.current.reply else LocalReadingStyles.current.body)
                if (reference.images.isNotEmpty()) { Spacer(Modifier.height(10.dp)); MediaGrid(reference.images) }
            }
        }
    }
}
