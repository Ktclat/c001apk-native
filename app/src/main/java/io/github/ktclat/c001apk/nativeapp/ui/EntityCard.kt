// SPDX-License-Identifier: AGPL-3.0-only
// Source: Flutter components/cards/app_card.dart and collection_card.dart.
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.LocalImageLoader
import io.github.ktclat.c001apk.nativeapp.domain.ContentItem
import io.github.ktclat.c001apk.nativeapp.domain.validCollectionId
import io.github.ktclat.c001apk.nativeapp.feature.feed.feedRelativeTime
import io.github.ktclat.c001apk.nativeapp.feature.media.LocalMediaImageLoader
import io.github.ktclat.c001apk.nativeapp.feature.media.MediaGrid
import io.github.ktclat.c001apk.nativeapp.platform.AppLink
import io.github.ktclat.c001apk.nativeapp.platform.parseAppLink

internal fun isEntityCard(item: ContentItem) = item.kind in setOf("apk", "product", "topic", "user", "contacts", "recentHistory", "collection")

/** Prefer the explicit source destination; contacts and collections have source-owned IDs.
 * This returns the existing app routes only, never assembles an arbitrary remote URL.
 */
internal fun entityCardDestination(item: ContentItem): AppLink {
    if (!isEntityCard(item)) return AppLink.Unsupported
    if (item.kind == "contacts") return item.userId.takeIf { it.matches(Regex("[0-9]+")) }
        ?.let { AppLink.Internal("user:$it") } ?: AppLink.Unsupported
    if (item.kind == "collection") return item.id.takeIf(::validCollectionId)
        ?.let { AppLink.Internal("collection:$it") } ?: AppLink.Unsupported
    item.targetUrl?.takeIf(String::isNotBlank)?.let { return parseAppLink(it) }
    // Preserve the previous native detail affordances when the source omitted its URL.
    return when (item.kind) {
        "user" -> item.userId.takeIf { it.matches(Regex("[0-9]+")) }?.let { AppLink.Internal("user:$it") }
        "apk", "product" -> item.id.takeIf { it.matches(Regex("[0-9]+")) }?.let { AppLink.Internal("${if (item.kind == "apk") "app" else "product"}:$it") }
        "topic" -> item.title.takeIf { it.isNotBlank() }?.let { parseAppLink("/t/${java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")}") }
        else -> null
    } ?: AppLink.Unsupported
}

internal fun entityCardTitle(item: ContentItem): String = when (item.kind) {
    "user", "contacts" -> item.userName
    "recentHistory" -> listOf(item.entityCard?.targetTypeTitle.orEmpty(), item.title).filter(String::isNotBlank).joinToString(": ")
    else -> item.title
}.take(600).ifBlank { "内容信息未提供" }

/** Supplying this wrapper explicitly replaces inferred navigation; a null action is disabled. */
internal data class EntityCardAction(val onClick: (() -> Unit)?)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EntityCard(item: ContentItem, navigate: (String) -> Unit, openLink: (String) -> Unit,
    modifier: Modifier = Modifier, actionOverride: EntityCardAction? = null, titleOverride: String? = null, showSourceTabs: Boolean = true) {
    val destination = remember(item) { entityCardDestination(item) }
    val title = remember(item, titleOverride) { titleOverride?.take(600)?.takeIf(String::isNotBlank) ?: entityCardTitle(item) }
    val metadata = item.entityCard
    val image = if (metadata != null) metadata.image else if (item.kind in setOf("user", "contacts")) item.avatarUrl else item.cardFields?.logo
    val clickable = if (actionOverride != null) actionOverride.onClick != null else destination != AppLink.Unsupported
    Surface(modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.inverseOnSurface) {
        Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("entity-card:${item.kind}:${item.id}")
            .then(if (clickable) Modifier.clickable(role = Role.Button, onClickLabel = "查看$title") {
                if (actionOverride != null) actionOverride.onClick?.invoke() else when (destination) {
                    is AppLink.Internal -> navigate(destination.route)
                    is AppLink.External -> openLink(destination.url)
                    AppLink.Unsupported -> Unit
                }
            } else Modifier).padding(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val url = image
                var failed by remember(url) { mutableStateOf(false) }
                Box(Modifier.width(if (item.kind == "collection") 53.dp else 40.dp).height(40.dp)
                    .testTag("entity-icon:${item.kind}:${item.id}")
                    .clip(if (metadata?.circular == true || item.kind in setOf("user", "contacts")) CircleShape else RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    if (url != null && !failed) AsyncImage(url, "$title${if (metadata?.circular == true) "头像" else "图标"}",
                        imageLoader = LocalMediaImageLoader.current ?: LocalImageLoader.current,
                        contentScale = ContentScale.Crop, onError = { failed = true }, modifier = Modifier.fillMaxSize())
                    else Icon(if (item.kind in setOf("user", "contacts")) FlutterMaterialIcons.Person else FlutterMaterialIcons.Image,
                        if (failed) "图片加载失败" else "未提供图片", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 15.sp, lineHeight = 21.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    val metrics = metadata?.metrics.orEmpty() + listOfNotNull(metadata?.lastActiveSeconds?.let {
                        feedRelativeTime(it, System.currentTimeMillis() / 1000).takeIf(String::isNotBlank)?.let { time -> "${time}活跃" }
                    })
                    if (metrics.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        metrics.take(4).forEach { Text(it, fontSize = 14.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if (!clickable) Text("未提供可打开的内容地址", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (item.message.isNotBlank()) { Spacer(Modifier.height(6.dp)); PagedPostText(item.message, item.bodyTruncated, openLink) }
            // Keep additional detail media and source tabs that the old generic renderer exposed.
            // The role-specific logo/avatar above is not repeated as an oversized gallery tile.
            val additionalImages = remember(item.imageUrls, image) { item.imageUrls.filterNot { it == image }.take(18) }
            if (additionalImages.isNotEmpty()) { Spacer(Modifier.height(8.dp)); MediaGrid(additionalImages) }
            if (showSourceTabs) item.tabs.take(50).forEach { tab -> TextButton(onClick = { navigate("page:${tab.url}") }) { Text(tab.title) } }
            if (item.children.isNotEmpty() || item.childrenIncomplete) Text("此内容另含尚未支持的嵌套内容", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
