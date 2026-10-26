// SPDX-License-Identifier: AGPL-3.0-only
// Source contracts: Flutter components/item_card.dart and cards/*.dart at 78e62d33.
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ktclat.c001apk.nativeapp.feature.media.PublicImage
import io.github.ktclat.c001apk.nativeapp.domain.ContentItem
import io.github.ktclat.c001apk.nativeapp.platform.AppLink
import io.github.ktclat.c001apk.nativeapp.platform.parseAppLink
import kotlinx.coroutines.launch

/** Only source-known containers. An unknown template is never flattened into plausible posts. */
internal enum class HomeCardTemplate(val wire: String) {
    CAROUSEL("imageCarouselCard_1"), MINI_SCROLL("iconMiniScrollCard"), MINI_GRID("iconMiniGridCard"),
    AVATAR_SCROLL("iconScrollCard"), SQUARE_SCROLL("imageSquareScrollCard"), IMAGE_TEXT_SCROLL("imageTextScrollCard"),
    TITLE("titleCard"), MESSAGE("messageCard"), END("noMoreDataCard"), REFRESH("refreshCard"),
}
internal fun homeCardTemplate(item: ContentItem): HomeCardTemplate? =
    if (item.kind == "card") HomeCardTemplate.entries.firstOrNull { it.wire == item.entityTemplate } else null

internal data class HomeCardEntry(val sourceIndex: Int, val label: String, val image: String?, val url: String?, val supported: Boolean)
internal fun homeCardEntries(item: ContentItem, template: HomeCardTemplate): List<HomeCardEntry> =
    item.children.take(100).withIndex().filter { (_, child) ->
        template != HomeCardTemplate.CAROUSEL || child.targetUrl?.startsWith("http") == false
    }.map { (index, child) ->
        val link = child.targetUrl?.let(::parseAppLink) ?: AppLink.Unsupported
        val supported = child.children.isEmpty() && !child.childrenIncomplete && child.entityTemplate.isNullOrBlank() &&
            link != AppLink.Unsupported && (template != HomeCardTemplate.CAROUSEL || link is AppLink.Internal)
        // Entity.title is distinct from feed.message_title; list cards may contain feed entities.
        val label = if (template == HomeCardTemplate.AVATAR_SCROLL) child.cardFields?.userName ?: child.userName
            else child.cardFields?.title ?: child.title
        val image = when (template) {
            HomeCardTemplate.AVATAR_SCROLL -> if (child.cardFields != null) child.cardFields.avatar else child.avatarUrl
            HomeCardTemplate.MINI_GRID -> child.cardFields?.logo
            HomeCardTemplate.MINI_SCROLL -> child.cardFields?.logo ?: child.cardFields?.picture
            else -> child.cardFields?.picture
        }
        HomeCardEntry(index, iconLinkLabel(label).text, image, child.targetUrl, supported)
    }

@Composable
internal fun HomeTemplateCard(item: ContentItem, template: HomeCardTemplate, openLink: (String) -> Unit, refresh: () -> Unit) {
    val entries = remember(item, template) { homeCardEntries(item, template) }
    val message = template == HomeCardTemplate.MESSAGE
    Card(Modifier.fillMaxWidth().testTag("home-template:${item.id}"), shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = if (message) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.inverseOnSurface)) {
        Column(Modifier.padding(vertical = 6.dp)) {
            when (template) {
                HomeCardTemplate.TITLE -> HomeCardTitle(item, openLink)
                HomeCardTemplate.MESSAGE -> Text(item.cardFields?.description.orEmpty().ifBlank { "暂无消息内容" },
                    Modifier.fillMaxWidth().padding(12.dp), color = MaterialTheme.colorScheme.onSecondaryContainer)
                HomeCardTemplate.END -> Text(item.title.ifBlank { "没有更多内容了" }, Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(12.dp),
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HomeCardTemplate.REFRESH -> {
                    Text(item.title.ifBlank { "刷新以查看新内容" }, Modifier.fillMaxWidth().padding(horizontal = 12.dp), textAlign = TextAlign.Center)
                    TextButton(onClick = refresh, modifier = Modifier.align(Alignment.CenterHorizontally).testTag("home-refresh:${item.id}")) { Text("刷新") }
                }
                else -> {
                    if (template in setOf(HomeCardTemplate.MINI_GRID, HomeCardTemplate.AVATAR_SCROLL, HomeCardTemplate.IMAGE_TEXT_SCROLL) && item.title.isNotBlank())
                        HomeCardTitle(item, openLink)
                    if (entries.isEmpty()) Text("暂无可显示的入口", Modifier.padding(12.dp))
                    else when (template) {
                        HomeCardTemplate.CAROUSEL -> HomeCarousel(item.id, entries, openLink)
                        HomeCardTemplate.MINI_GRID -> BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val columns = if (maxWidth.value >= 300f * LocalDensity.current.fontScale.coerceAtLeast(1f)) 2 else 1
                            Column {
                                entries.chunked(columns).forEach { row ->
                                    Row(Modifier.fillMaxWidth()) {
                                        row.forEach { entry -> HomeMiniEntry(item.id, entry, openLink, Modifier.weight(1f)) }
                                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            }
                        }
                        else -> BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
                            val width = when (template) {
                                HomeCardTemplate.IMAGE_TEXT_SCROLL -> (maxWidth * .72f).coerceAtLeast(160.dp)
                                HomeCardTemplate.MINI_SCROLL -> (150 * scale).dp.coerceAtMost(maxWidth)
                                else -> (84 * scale).dp.coerceAtMost(maxWidth)
                            }
                            LazyRow(Modifier.fillMaxWidth().testTag("home-entries:${item.id}"),
                                contentPadding = PaddingValues(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                itemsIndexed(entries, key = { _, entry -> entry.sourceIndex }) { _, entry ->
                                    if (template == HomeCardTemplate.MINI_SCROLL) HomeMiniEntry(item.id, entry, openLink, Modifier.width(width))
                                    else HomeImageEntry(item.id, entry, template, openLink, Modifier.width(width))
                                }
                            }
                        }
                    }
                }
            }
            if (item.childrenIncomplete || item.children.size > 100) Text("部分入口数据不完整或超出显示上限", Modifier.padding(12.dp))
            if (template in setOf(HomeCardTemplate.TITLE, HomeCardTemplate.MESSAGE, HomeCardTemplate.END, HomeCardTemplate.REFRESH) && item.children.isNotEmpty())
                Text("此卡片另含尚未支持的嵌套内容", Modifier.padding(12.dp))
        }
    }
}

@Composable
private fun HomeCardTitle(item: ContentItem, openLink: (String) -> Unit) {
    val url = item.targetUrl?.takeIf { parseAppLink(it) != AppLink.Unsupported }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("home-title:${item.id}")
        .then(if (url == null) Modifier else Modifier.clickable(role = Role.Button) { openLink(url) })
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(iconLinkLabel(item.title).text, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Bold,
            maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (url != null) Icon(FlutterMaterialIcons.KeyboardArrowRightOutlined, contentDescription = "打开更多内容")
    }
}

private fun Modifier.homeEntry(id: String, entry: HomeCardEntry, openLink: (String) -> Unit): Modifier =
    heightIn(min = 48.dp).testTag("home-entry:$id:${entry.sourceIndex}")
        .clickable(enabled = entry.supported, role = Role.Button) { entry.url?.let(openLink) }

@Composable
private fun HomeMiniEntry(id: String, entry: HomeCardEntry, openLink: (String) -> Unit, modifier: Modifier) {
    Row(modifier.homeEntry(id, entry, openLink).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        HomeCardImage(entry.image, Modifier.size(24.dp)); Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.label, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!entry.supported) Text("暂不支持此入口", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HomeImageEntry(id: String, entry: HomeCardEntry, template: HomeCardTemplate, openLink: (String) -> Unit, modifier: Modifier) {
    Column(modifier.homeEntry(id, entry, openLink).padding(bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        HomeCardImage(entry.image, Modifier.fillMaxWidth().aspectRatio(if (template == HomeCardTemplate.IMAGE_TEXT_SCROLL) 2.22f else 1f),
            circle = template == HomeCardTemplate.AVATAR_SCROLL)
        Text(entry.label, Modifier.padding(horizontal = 4.dp, vertical = 5.dp), fontSize = 14.sp,
            maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = if (template == HomeCardTemplate.IMAGE_TEXT_SCROLL) TextAlign.Start else TextAlign.Center)
        if (!entry.supported) Text("暂不支持此入口", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HomeCarousel(id: String, entries: List<HomeCardEntry>, openLink: (String) -> Unit) {
    val pager = rememberPagerState(pageCount = { entries.size })
    val scope = rememberCoroutineScope()
    HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().testTag("home-carousel:$id"), verticalAlignment = Alignment.Top) { page ->
        HomeImageEntry(id, entries[page], HomeCardTemplate.IMAGE_TEXT_SCROLL, openLink, Modifier.fillMaxWidth().padding(horizontal = 10.dp))
    }
    if (entries.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }, enabled = pager.currentPage > 0,
            modifier = Modifier.testTag("home-previous:$id")) { Text("上一项") }
        Text("${pager.currentPage + 1} / ${entries.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }, enabled = pager.currentPage < entries.lastIndex,
            modifier = Modifier.testTag("home-next:$id")) { Text("下一项") }
    }
}

@Composable
private fun HomeCardImage(url: String?, modifier: Modifier, circle: Boolean = false) {
    PublicImage(url, modifier = modifier.clip(if (circle) CircleShape else RoundedCornerShape(10.dp)), avatar = circle)
}
