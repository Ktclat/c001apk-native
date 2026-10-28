// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ktclat.c001apk.nativeapp.domain.*
import io.github.ktclat.c001apk.nativeapp.settings.LocalReadingStyles

internal fun fullPostLimitMessage(limit: FullPostBodyLimit, receivedUnits: Int? = null): String = when (limit) {
    FullPostBodyLimit.MALFORMED_ARTICLE -> "结构化正文格式异常，未将摘要作为完整文章展示。"
    FullPostBodyLimit.UNSUPPORTED_ARTICLE -> "结构化正文含未知或尚未支持的内容块，未跳过内容后宣称完整。"
    FullPostBodyLimit.ARTICLE_COMPLEXITY -> "结构化正文超过安全排版上限，未展示不完整文章。"
    FullPostBodyLimit.PARSING_ERROR -> "正文排版失败，尚未展示完整正文；可重新排版或用浏览器打开原文。"
    FullPostBodyLimit.BODY_TOO_LARGE -> "正文超过原生阅读的 262144 个 UTF-16 文本单位上限${receivedUnits?.let { "（收到 $it）" }.orEmpty()}，未载入完整正文。"
    FullPostBodyLimit.MISSING_MESSAGE -> "接口未提供可确认的正文，不能把摘要当作完整正文。"
    FullPostBodyLimit.STRUCTURED_ARTICLE -> "此动态使用结构化长文章（文字、图片或分享卡片），该格式尚未支持原生完整阅读。下方仅为接口预览。"
    FullPostBodyLimit.UNSUPPORTED_MARKUP -> "正文包含尚未支持的富文本或内嵌媒体，未作为完整正文展示。"
    FullPostBodyLimit.MARKUP_COMPLEXITY -> "正文排版超过安全上限（8192 个标签、64 层嵌套或单标签 8192 个文本单位），未作为完整正文展示。"
    FullPostBodyLimit.LINK_COMPLEXITY -> "正文链接超过安全上限（1024 个链接或单个地址 4096 个文本单位），未作为完整正文展示。"
}

@Composable internal fun rememberFullPost(
    body: FullPostBody,
    parser: (FullPostBody, () -> Unit) -> PreparedFullPost = ::prepareFullPost,
    attempt: Int = 0,
): State<PreparedFullPost?> {
    // Replacement is empty in the same composition frame; the old parser cannot publish into it.
    val state = remember(body, parser, attempt) { mutableStateOf<PreparedFullPost?>(null) }
    LaunchedEffect(body, parser, attempt) { parseAndPublishFullPost(body, parser) { state.value = it } }
    return state
}

/** Detail-only reader. Main lays out one <=4000-unit page; all parsing/slicing/link indexing is off-Main. */
@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun FullPostText(body: FullPostBody, preview: String, openLink: (String) -> Unit, openOriginal: (() -> Unit)?,
    parser: (FullPostBody, () -> Unit) -> PreparedFullPost = ::prepareFullPost) {
    val retained = LocalDetailReaderBinding.current?.takeIf { it.body === body }
    var attempt by remember(body) { mutableIntStateOf(0) }
    val prepared = if (retained != null) retained.prepared else rememberFullPost(body, parser, attempt).value
    val pageState = retained?.page ?: rememberSaveable(body) { mutableIntStateOf(0) }
    var page by pageState
    val sourceMode = retained?.sourceMode ?: remember(body) { mutableStateOf(false) }
    val sourcePage = retained?.sourcePage ?: remember(body) { mutableIntStateOf(0) }
    val sourcePages = (prepared as? PreparedFullPost.Article)?.sourcePages
    var linksOpen by remember(body) { mutableStateOf(false) }
    val pageStart = remember(body) { BringIntoViewRequester() }
    var pageNavigation by remember(body) { mutableIntStateOf(0) }
    LaunchedEffect(body, pageNavigation) {
        // A bottom-of-page click must reveal the next page's beginning, not retain its old offset.
        if (pageNavigation > 0) pageStart.bringIntoView()
    }
    Column(Modifier.testTag("full-post-reader"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (retained == null && sourcePages != null) TextButton(
            onClick = { sourceMode.value = !sourceMode.value }, modifier = Modifier.testTag("detail-source-toggle")) {
            Text(if (sourceMode.value) "格式化显示" else "查看原文")
        }
        if (sourceMode.value && sourcePages != null) {
            RawDetailText(sourcePages, sourcePage, retained?.bodyDigest ?: body)
        } else when (val value = prepared) {
            null -> Text("正在排版详情正文…", Modifier.testTag("full-post-pending"), style = MaterialTheme.typography.bodySmall)
            is PreparedFullPost.Unavailable -> {
                Text(fullPostLimitMessage(value.limit, value.receivedUnits), Modifier.testTag("full-post-limit"), style = MaterialTheme.typography.bodySmall)
                if (preview.isNotBlank()) PagedPostText(preview, true, openLink)
                if (value.limit == FullPostBodyLimit.PARSING_ERROR) TextButton(onClick = { if (retained != null) retained.retry() else attempt++ }) { Text("重新排版正文") }
                openOriginal?.let { TextButton(onClick = it) { Text("浏览器打开原文") } }
            }
            is PreparedFullPost.Article -> ArticleText(value, retained?.bodyDigest ?: value, pageState, openLink, openOriginal)
            is PreparedFullPost.Ready -> {
                val current = page.coerceIn(value.pages.indices)
                val visible = value.pages[current]
                val menuLinks = remember(visible.links) { visible.links.distinctBy { it.url } }
                if (visible.text.isEmpty() && value.pages.size == 1) {
                    Text("此动态没有文本正文", style = MaterialTheme.typography.bodySmall)
                } else if (value.pages.size > 1) {
                    Text("第 ${current + 1}/${value.pages.size} 段",
                        Modifier.testTag("full-post-progress").bringIntoViewRequester(pageStart), style = MaterialTheme.typography.bodySmall)
                }
                SelectionContainer { NativeRichText(visible.richText, Modifier.testTag("full-post-page"), style = LocalReadingStyles.current.body,
                    links = visible.links, openLink = openLink, expandEmoticons = visible.renderInline) }
                if (value.pages.size > 1) Row {
                    TextButton(enabled = current > 0, onClick = { page = current - 1; linksOpen = false; pageNavigation++ }) { Text("上一段") }
                    TextButton(enabled = current < value.pages.lastIndex, onClick = { page = current + 1; linksOpen = false; pageNavigation++ }) { Text("下一段") }
                }
                if (value.pages.size > 1 && current == value.pages.lastIndex && visible.text.isNotEmpty()) Text("已到正文末尾", style = MaterialTheme.typography.bodySmall)
                if (menuLinks.isNotEmpty()) TextButton(onClick = { linksOpen = true }) { Text("本段链接（${menuLinks.size}）") }
                if (linksOpen) AlertDialog(onDismissRequest = { linksOpen = false }, title = { Text("本段链接") }, text = {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(menuLinks, key = { it.url }) { link ->
                            TextButton(onClick = { linksOpen = false; openLink(link.url) }, modifier = Modifier.fillMaxWidth()) {
                                Text(link.label, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }, confirmButton = { TextButton(onClick = { linksOpen = false }) { Text("关闭") } })
            }
        }
    }
}

/** Literal paged source. No link handlers, normalization, image loading, or saved-state source. */
@OptIn(ExperimentalFoundationApi::class)
@Composable private fun RawDetailText(pages: List<FullPostPage>, page: MutableIntState, identity: Any) {
    val current = page.intValue.coerceIn(pages.indices)
    val visible = pages[current]
    val start = remember(identity) { BringIntoViewRequester() }
    var navigation by remember(identity) { mutableIntStateOf(0) }
    LaunchedEffect(identity, navigation) { if (navigation > 0) start.bringIntoView() }
    Column(Modifier.testTag("detail-source-reader"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(visible.sourceLabel ?: "原始正文", Modifier.bringIntoViewRequester(start), style = MaterialTheme.typography.labelLarge)
        Text("原始文本，仅显示，不执行链接或加载图片。", style = MaterialTheme.typography.bodySmall)
        if (pages.size > 1) Text("第 ${current + 1}/${pages.size} 段", style = MaterialTheme.typography.bodySmall)
        SelectionContainer { Text(visible.text, Modifier.testTag("detail-source-page"), style = LocalReadingStyles.current.body) }
        if (pages.size > 1) Row {
            TextButton(enabled = current > 0, onClick = { page.intValue = current - 1; navigation++ }) { Text("上一段") }
            TextButton(enabled = current < pages.lastIndex, onClick = { page.intValue = current + 1; navigation++ }) { Text("下一段") }
        }
    }
}

/** Persistent detail toolbar action, mirroring upstream without moving the reader to its header. */
@Composable internal fun DetailSourceMenu(control: DetailSourceControl) {
    var open by remember(control.mode) { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("detail-source-menu")) {
            Icon(FlutterMaterialIcons.MoreVert, "正文显示选项")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(if (control.mode.value) "格式化显示" else "查看原文") },
                onClick = { open = false; control.toggle() },
                modifier = Modifier.testTag("detail-source-toggle"))
        }
    }
}
