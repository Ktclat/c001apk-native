// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.feature.media

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Socket-free fixture injection, independent of the ordinary grid/gallery loader. */
internal val LocalArticleImageLoader = staticCompositionLocalOf<ArticleImageLoader> { defaultArticleImageLoader }

internal fun articlePreviewUrl(url: String?): String? {
    val safe = url?.let(ArticleImagePolicy::safeUrl) ?: return null
    val path = safe.substringBefore('?').lowercase()
    // Preserve the original bytes for formats that may animate; a JPEG thumbnail would conceal that fact.
    return if (path.endsWith(".jpg") || path.endsWith(".jpeg"))
        ArticleImagePolicy.safeUrl(thumbnailUrl(safe)) ?: safe else safe
}
internal fun articleImageHeightRatio(url: String?): Float =
    sourceImageSize(url.orEmpty()).let { minOf(it.height / it.width, 22f / 9f) }

/**
 * Compose only the current article segment. No retained map, prefetch, saved state or transfer actions.
 * identity must include body + owner lifetime; removing this composition drops preview/viewer references.
 * onOpenOriginal is the existing guarded FEED-original action, never an arbitrary image-URL intent.
 * Only source slots thinner than 48dp gain a stable minimum hit region; the image itself is not stretched.
 */
@Composable
internal fun ArticleMedia(
    url: String?,
    caption: String,
    occurrenceIndex: Int,
    allUrls: List<String?>,
    identity: Any,
    onOpenOriginal: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (allUrls.size !in 1..ArticleImagePolicy.MAX_OCCURRENCES || occurrenceIndex !in allUrls.indices) {
        Text("图片列表超出阅读限制", color = MaterialTheme.colorScheme.error)
        return
    }
    key(identity, occurrenceIndex, url) {
        var retry by remember { mutableIntStateOf(0) }
        key(retry) {
            var viewing by remember { mutableStateOf(false) }
            Column(modifier.fillMaxWidth().testTag("article-media-block-$occurrenceIndex")) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val sourceHeight = maxWidth * articleImageHeightRatio(url)
                    val hitHeight = maxOf(48.dp, sourceHeight)
                    val shape = RoundedCornerShape(12.dp)
                    ArticleImageContent(articlePreviewUrl(url), "article-media-$occurrenceIndex", occurrenceIndex,
                        ContentScale.FillBounds, onOpenOriginal, onRetry = { retry++ },
                        imageModifier = Modifier.fillMaxWidth().height(hitHeight).testTag("article-media-slot-$occurrenceIndex")
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), shape).clip(shape),
                        sourceModifier = Modifier.fillMaxWidth().height(sourceHeight).testTag("article-media-source-$occurrenceIndex"),
                        caption = caption, onView = { viewing = true })
                }
            }
            if (viewing) Dialog(onDismissRequest = { viewing = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)) {
                ArticleReadOnlyViewer(allUrls, occurrenceIndex, identity, onOpenOriginal) { viewing = false }
            }
        }
    }
}

@Composable
private fun ArticleReadOnlyViewer(
    urls: List<String?>,
    initialIndex: Int,
    identity: Any,
    onOpenOriginal: (() -> Unit)?,
    onClose: () -> Unit,
) {
    var selected by remember(identity, urls, initialIndex) { mutableIntStateOf(initialIndex) }
    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()
        .padding(16.dp).testTag("article-media-viewer")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("图片 ${selected + 1}/${urls.size}", Modifier.testTag("article-media-viewer-position"))
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp).testTag("article-media-viewer-close")) { Text("关闭") }
        }
        val viewerImageModifier = Modifier.fillMaxWidth().weight(1f)
        Box(Modifier.fillMaxWidth().weight(1f)) {
            // One selected result only; no neighboring pager composition or prefetch.
            key(identity, selected, urls[selected]) {
                var retry by remember { mutableIntStateOf(0) }
                key(retry) {
                    ArticleImageContent(urls[selected], "article-viewer-$selected", selected, ContentScale.Fit,
                        onOpenOriginal, onRetry = { retry++ }, imageModifier = viewerImageModifier,
                        modifier = Modifier.fillMaxSize())
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(enabled = selected > 0, onClick = { selected-- }, modifier = Modifier.heightIn(min = 48.dp).testTag("article-media-viewer-previous")) { Text("上一张") }
            TextButton(enabled = selected < urls.lastIndex, onClick = { selected++ }, modifier = Modifier.heightIn(min = 48.dp).testTag("article-media-viewer-next")) { Text("下一张") }
        }
    }
}

@Composable
private fun ArticleImageContent(
    url: String?, tag: String, occurrenceIndex: Int, scale: ContentScale, onOpenOriginal: (() -> Unit)?,
    onRetry: () -> Unit, imageModifier: Modifier, modifier: Modifier = Modifier,
    sourceModifier: Modifier = Modifier.fillMaxSize(), caption: String = "", onView: (() -> Unit)? = null,
) {
    val loader = LocalArticleImageLoader.current
    key(url, loader) {
        var details by remember { mutableStateOf(false) }
        val result by produceState<ArticleImageResult?>(if (url == null) ArticleImageResult.Unavailable(ArticleImageFailure.BLOCKED_URL) else null) {
            if (url != null) {
                val loaded = try { loader.load(url) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { ArticleImageResult.Unavailable(ArticleImageFailure.NETWORK) }
                try { currentCoroutineContext().ensureActive() }
                catch (cancelled: CancellationException) {
                    (loaded as? ArticleImageResult.Ready)?.bitmap?.recycle()
                    throw cancelled
                }
                value = loaded
            }
        }
        val reason = (result as? ArticleImageResult.Unavailable)?.reason
        val fullStatus = when (val state = result) {
            null -> "正在加载静态图片"
            is ArticleImageResult.Ready -> "静态图片已显示"
            is ArticleImageResult.Unavailable -> state.reason.label()
        }
        val shortStatus = when (val state = result) {
            null -> "加载中"
            is ArticleImageResult.Ready -> "已显示"
            is ArticleImageResult.Unavailable -> state.reason.shortLabel()
        }
        Column(modifier) {
            Box(imageModifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                // The outer hit region is fixed before bytes arrive. The source image retains its exact
                // height inside that region, including a source ratio that rounds to zero physical pixels.
                Box(sourceModifier, contentAlignment = Alignment.Center) {
                    (result as? ArticleImageResult.Ready)?.let { ready ->
                        Box(Modifier.fillMaxSize().testTag("$tag-status").semantics { stateDescription = fullStatus }) {
                            Image(ready.bitmap.asImageBitmap(), "静态图片", Modifier.fillMaxSize().testTag("$tag-ready"), contentScale = scale)
                        }
                    }
                }
                if (result !is ArticleImageResult.Ready) {
                    Text("$shortStatus · 点击查看", Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                        .testTag(if (result == null) "$tag-loading" else "$tag-unavailable"),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // A sibling hit target keeps the source/state semantic nodes inspectable without
                // merging their test tags into a clickable ancestor or adding any layout height.
                Box(Modifier.matchParentSize().testTag("$tag-open").clickable {
                    if (result is ArticleImageResult.Ready && onView != null) onView() else details = true
                }.semantics {
                    contentDescription = "图片 ${occurrenceIndex + 1}，$fullStatus，" +
                        if (result is ArticleImageResult.Ready && onView != null) "查看图片" else "查看说明"
                })
            }
            if (caption.isNotEmpty()) Text(caption, Modifier.fillMaxWidth().padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium, fontSize = 14.sp, lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center)
        }
        if (details) AlertDialog(onDismissRequest = { details = false }, modifier = Modifier.testTag("$tag-details"),
            title = { Text("图片 ${occurrenceIndex + 1}") },
            text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text(fullStatus)
                onView?.let { open -> TextButton(onClick = { details = false; open() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("$tag-view")) { Text("查看图片") } }
                if (reason in setOf(ArticleImageFailure.NETWORK, ArticleImageFailure.UNSUPPORTED_PROXY,
                        ArticleImageFailure.BLOCKED_ADDRESS, ArticleImageFailure.DECODE_FAILED)) {
                    TextButton(onClick = { details = false; onRetry() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("$tag-retry")) { Text("重试") }
                }
                onOpenOriginal?.let { open -> TextButton(onClick = { details = false; open() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("$tag-original")) { Text("浏览器查看原帖") } }
            } },
            confirmButton = { TextButton(onClick = { details = false },
                modifier = Modifier.heightIn(min = 48.dp).testTag("$tag-details-close")) { Text("关闭") } })
    }
}

private fun ArticleImageFailure.shortLabel(): String = when (this) {
    ArticleImageFailure.BLOCKED_URL, ArticleImageFailure.BLOCKED_ADDRESS -> "地址受限"
    ArticleImageFailure.UNSUPPORTED_PROXY -> "代理不支持"
    ArticleImageFailure.ENCODED_LIMIT, ArticleImageFailure.SOURCE_LIMIT -> "图片超限"
    ArticleImageFailure.UNSUPPORTED_FORMAT -> "格式不支持"
    ArticleImageFailure.DECODE_FAILED, ArticleImageFailure.NETWORK -> "加载失败"
}
private fun ArticleImageFailure.label(): String = when (this) {
    ArticleImageFailure.BLOCKED_URL -> "图片地址不可用或不符合安全限制"
    ArticleImageFailure.BLOCKED_ADDRESS -> "图片服务器地址不符合安全限制"
    ArticleImageFailure.UNSUPPORTED_PROXY -> "当前代理暂不支持安全图片读取"
    ArticleImageFailure.ENCODED_LIMIT -> "图片超过 8 MiB 阅读限制或内容为空"
    ArticleImageFailure.SOURCE_LIMIT -> "图片尺寸超过阅读限制"
    ArticleImageFailure.UNSUPPORTED_FORMAT -> "暂不支持此图片格式或动画"
    ArticleImageFailure.DECODE_FAILED -> "图片解码失败"
    ArticleImageFailure.NETWORK -> "图片加载失败"
}
