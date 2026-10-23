// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ktclat.c001apk.nativeapp.feature.media.ArticleMedia
import io.github.ktclat.c001apk.nativeapp.settings.LocalReadingStyles

/** Only the current bounded segment is composed. Parent card already supplies the source's 16dp inset. */
@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun ArticleText(article: PreparedFullPost.Article, identity: Any,
    pageState: MutableIntState, openLink: (String) -> Unit, openOriginal: (() -> Unit)?) {
    var page by pageState
    val current = page.coerceIn(article.pages.indices)
    val visible = article.pages[current]
    var linksOpen by remember(identity, current) { mutableStateOf(false) }
    val pageStart = remember(identity) { BringIntoViewRequester() }
    var navigation by remember(identity) { mutableIntStateOf(0) }
    LaunchedEffect(identity, navigation) { if (navigation > 0) pageStart.bringIntoView() }
    val links = remember(visible) { visible.fragments.flatMap { (it as? ArticleFragment.RichText)?.links.orEmpty() } }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("article-page")) {
        if (article.pages.size > 1) Text("第 ${current + 1}/${article.pages.size} 段",
            Modifier.testTag("full-post-progress").bringIntoViewRequester(pageStart), style = MaterialTheme.typography.bodySmall)
        visible.fragments.forEachIndexed { part, fragment ->
            key(identity, current, part) {
                when (fragment) {
                    is ArticleFragment.RichText -> {
                        val palette = MaterialTheme.colorScheme
                        val text = remember(fragment.value, palette.primary) {
                            AnnotatedString.Builder(fragment.value).apply {
                                fragment.value.getStringAnnotations("article-url", 0, fragment.value.length).forEach {
                                    addStyle(SpanStyle(color = palette.primary), it.start, it.end)
                                }
                            }.toAnnotatedString()
                        }
                        val style = if (fragment.role == ArticleTextRole.TITLE) LocalReadingStyles.current.articleTitle else LocalReadingStyles.current.articleBody
                        SelectionContainer {
                            ClickableText(text, Modifier.fillMaxWidth().testTag("article-text-${fragment.sourceIndex}"),
                                style = style.copy(color = palette.onSurface.copy(alpha = 0.92f)), onClick = { offset ->
                                    text.getStringAnnotations("article-url", offset, offset).firstOrNull()?.let { openLink(it.item) }
                                })
                        }
                    }
                    is ArticleFragment.Image -> ArticleMedia(fragment.url, fragment.caption, fragment.imageIndex,
                        article.images, identity, openOriginal, Modifier.fillMaxWidth())
                    is ArticleFragment.Share -> Surface(onClick = { fragment.url?.takeIf { it.isNotEmpty() }?.let(openLink) },
                        enabled = !fragment.url.isNullOrEmpty(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.fillMaxWidth().testTag("article-share-${fragment.sourceIndex}")) {
                        Column(Modifier.padding(10.dp)) {
                            Text(fragment.title.ifEmpty { "分享链接" }, style = MaterialTheme.typography.bodyMedium)
                            if (fragment.url.isNullOrEmpty()) {
                                Text("分享地址缺失，无法打开", style = MaterialTheme.typography.bodySmall)
                                openOriginal?.let { TextButton(onClick = it) { Text("浏览器打开原文") } }
                            }
                        }
                    }
                }
            }
        }
        if (article.pages.size > 1) Row {
            TextButton(enabled = current > 0, onClick = { page = current - 1; navigation++ }) { Text("上一段") }
            TextButton(enabled = current < article.pages.lastIndex, onClick = { page = current + 1; navigation++ }) { Text("下一段") }
        }
        if (article.pages.size > 1 && current == article.pages.lastIndex) Text("已到结构化正文末尾", style = MaterialTheme.typography.bodySmall)
        if (links.isNotEmpty()) TextButton(onClick = { linksOpen = true }) { Text("本段链接（${links.size}）") }
    }
    if (linksOpen) AlertDialog(onDismissRequest = { linksOpen = false }, title = { Text("本段链接") }, text = {
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            itemsIndexed(links, key = { index, _ -> index }) { _, link ->
                TextButton(onClick = { linksOpen = false; openLink(link.url) }, modifier = Modifier.fillMaxWidth()) {
                    Text(link.label.ifEmpty { "链接" }, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { linksOpen = false }) { Text("关闭") } })
}
