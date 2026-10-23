// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.imageLoader
import io.github.ktclat.c001apk.nativeapp.domain.ContentItem
import io.github.ktclat.c001apk.nativeapp.feature.media.LocalMediaImageLoader
import io.github.ktclat.c001apk.nativeapp.platform.AppLink
import io.github.ktclat.c001apk.nativeapp.platform.parseAppLink
import kotlinx.coroutines.launch

internal const val ICON_LINK_LIMIT = 100
internal fun iconLinkColumns(width: Float, fontScale: Float): Int =
    (width / maxOf(48f, 60f * fontScale.coerceAtLeast(1f))).toInt().coerceIn(1, 5)

internal data class IconLinkLabel(val text: String, val truncated: Boolean) {
    val accessible: String get() = text + if (truncated) "，标题过长，已缩略" else ""
}
/** Scan at most 80 code points, never the entire untrusted label; not a grapheme-count claim. */
internal fun iconLinkLabel(raw: String): IconLinkLabel {
    var end = 0
    var count = 0
    val text = StringBuilder(160)
    while (end < raw.length && count < 80) {
        val first = raw[end++]
        if (first.isHighSurrogate() && end < raw.length && raw[end].isLowSurrogate()) {
            text.append(first).append(raw[end++])
        } else text.append(if (first.isSurrogate()) '\uFFFD' else first)
        count++
    }
    return IconLinkLabel(text.toString().ifBlank { "未命名入口" }, end < raw.length)
}

/** Only one known template; nested cards are deliberately not recursively rendered. */
@Composable
internal fun IconLinkGridCard(item: ContentItem, openLink: (String) -> Unit) {
    val entries = item.children.take(ICON_LINK_LIMIT)
    val pages = entries.chunked(5)
    val loader = LocalMediaImageLoader.current ?: LocalContext.current.imageLoader
    val scope = rememberCoroutineScope()
    Card(Modifier.fillMaxWidth().testTag("icon-link-grid"), shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.inverseOnSurface)) {
        if (pages.isEmpty()) Text("暂无可显示的入口", Modifier.padding(16.dp))
        else {
            val pager = rememberPagerState(pageCount = { pages.size })
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns = iconLinkColumns(maxWidth.value, LocalDensity.current.fontScale)
                HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().testTag("icon-link-pages"),
                    verticalAlignment = Alignment.Top) { page ->
                    Column(Modifier.fillMaxWidth()) {
                        // Keep five slots even on the last page, matching source widths without losing its tail.
                        (0 until 5).chunked(columns).forEach { slots ->
                            Row(Modifier.fillMaxWidth()) {
                                slots.forEach { slot ->
                                    val child = pages[page].getOrNull(slot)
                                    if (child == null) Spacer(Modifier.weight(1f))
                                    else {
                                        val label = iconLinkLabel(child.title)
                                        val supported = child.children.isEmpty() && !child.childrenIncomplete &&
                                            child.entityTemplate.isNullOrBlank() &&
                                            parseAppLink(child.targetUrl.orEmpty()) != AppLink.Unsupported
                                        Column(Modifier.weight(1f).heightIn(min = 48.dp)
                                            .testTag("icon-link-entry-${page * 5 + slot}")
                                            .clickable(enabled = supported, role = Role.Button) { openLink(child.targetUrl.orEmpty()) }
                                            .padding(horizontal = 4.dp, vertical = 5.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally) {
                                            var loaded by remember(child.imageUrls.firstOrNull()) { mutableStateOf(false) }
                                            Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                                                if (!loaded) Text("◇", fontSize = 18.sp)
                                                child.imageUrls.firstOrNull()?.let { image ->
                                                    AsyncImage(image, null, imageLoader = loader, contentScale = ContentScale.Crop,
                                                        onSuccess = { loaded = true }, onError = { loaded = false }, modifier = Modifier.fillMaxSize())
                                                }
                                            }
                                            Text(label.text, fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.Center, maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = if (label.truncated) Modifier.semantics { contentDescription = label.accessible } else Modifier)
                                            if (!supported) Text("暂不支持", fontSize = 12.sp, textAlign = TextAlign.Center)
                                        }
                                    }
                                }
                                repeat(columns - slots.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
            if (pages.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                pages.indices.forEach { page ->
                    val selected = pager.currentPage == page
                    Box(Modifier.size(48.dp).testTag("icon-link-page-$page")
                        .semantics { contentDescription = "第 ${page + 1} 页，共 ${pages.size} 页" }
                        .selectable(selected, role = Role.Tab) { scope.launch { pager.scrollToPage(page) } },
                        contentAlignment = Alignment.Center) {
                        Box(Modifier.size(if (selected) 10.dp else 7.dp, 5.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = if (selected) 1f else .5f), RoundedCornerShape(12.dp)))
                    }
                }
            }
        }
        if (item.childrenIncomplete || item.children.size > ICON_LINK_LIMIT)
            Text("部分入口数据不完整或超出显示上限", Modifier.padding(12.dp))
    }
}
