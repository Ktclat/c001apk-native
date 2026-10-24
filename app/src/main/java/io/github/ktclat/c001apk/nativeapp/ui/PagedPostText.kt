package io.github.ktclat.c001apk.nativeapp.ui

import android.os.Build
import android.text.Html
import android.text.style.URLSpan
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.dp
import io.github.ktclat.c001apk.nativeapp.settings.LocalReadingStyles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class PostLink(val start: Int, val end: Int, val label: String, val url: String)
internal data class ParsedPost(val text: String, val links: List<PostLink>)
internal fun decodePostHtml(html: String): ParsedPost {
    val spanned = Html.fromHtml(normalizePostMarkup(html), Html.FROM_HTML_MODE_LEGACY)
    val text = spanned.toString()
    val links = spanned.getSpans(0, spanned.length, URLSpan::class.java).mapNotNull { span ->
        val start = spanned.getSpanStart(span); val end = spanned.getSpanEnd(span)
        if (start < 0 || end <= start || end > text.length || span.url.length > 4096) null
        else PostLink(start, end, text.substring(start, end).take(80), span.url)
    }.distinct()
    return ParsedPost(text, links)
}

/** Parsing and publication have explicit dispatch boundaries and remain owned by the caller's Job. */
internal suspend fun parseAndPublishPost(
    html: String,
    expanded: Boolean,
    parser: (String) -> ParsedPost = ::decodePostHtml,
    publish: (ParsedPost) -> Unit,
) {
    val decoded = withContext(Dispatchers.Default) { parser(if (expanded) html else html.take(12_000)) }
    withContext(Dispatchers.Main.immediate) { publish(decoded) }
}

@Composable
internal fun rememberParsedPost(
    html: String,
    expanded: Boolean,
    parser: (String) -> ParsedPost = ::decodePostHtml,
): State<ParsedPost?> {
    // Input changes create empty state immediately, before the replacement effect can be scheduled.
    val state = remember(html, expanded, parser) { mutableStateOf<ParsedPost?>(null) }
    LaunchedEffect(html, expanded, parser) { parseAndPublishPost(html, expanded, parser) { state.value = it } }
    return state
}

/** Tests can hold the existing background parser; the production default remains the real decoder. */
internal val LocalPreviewPostParser = staticCompositionLocalOf<(String) -> ParsedPost> { ::decodePostHtml }

/** Parsing never runs on Main; a large server body cannot become a single unbounded Text layout. */
@Composable internal fun PagedPostText(html: String, openLink: (String) -> Unit) = PagedPostText(html, false, openLink)

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PagedPostText(html: String, sourceIncomplete: Boolean, openLink: (String) -> Unit) {
    var expanded by rememberSaveable(html) { mutableStateOf(false) }
    var page by rememberSaveable(html) { mutableIntStateOf(0) }
    var linksOpen by rememberSaveable(html) { mutableStateOf(false) }
    val parsed by rememberParsedPost(html, expanded, LocalPreviewPostParser.current)
    val value = parsed
    val store = LocalPreviewGeometryStore.current
    val bodyDigest = remember(html, store) { if (store == null) null else previewBodyIdentity(html) }
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    BoxWithConstraints((if (store == null) Modifier else Modifier.fillMaxWidth()).testTag("post-preview-block")) {
        val geometryKey = bodyDigest?.let { PreviewGeometryKey(it, constraints, density.density, density.fontScale,
            LocalLayoutDirection.current, configuration.locales.toLanguageTags(),
            if (Build.VERSION.SDK_INT >= 31) configuration.fontWeightAdjustment else 0, LocalReadingStyles.current.body, MaterialTheme.typography.bodySmall,
            MaterialTheme.typography.labelLarge, LocalTextStyle.current, LocalMinimumInteractiveComponentSize.current,
            expanded, page, sourceIncomplete) }
        val currentKey by rememberUpdatedState(geometryKey)
        val currentStore by rememberUpdatedState(store)
        if (value == null) {
            val heightPx = geometryKey?.let { store?.height(it) }
            Box(if (heightPx == null) Modifier else Modifier.height(with(density) { heightPx.toDp() })) {
                Text("正在排版…", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            val size = if (expanded) 4000 else 1200
            val pages = if (expanded) ((value.text.length + size - 1) / size).coerceAtLeast(1) else 1
            val current = page.coerceIn(0, pages - 1)
            fun boundary(index: Int): Int {
                val bound = index.coerceAtMost(value.text.length)
                return if (bound > 0 && bound < value.text.length && value.text[bound - 1].isHighSurrogate() && value.text[bound].isLowSurrogate()) bound - 1 else bound
            }
            val start = boundary(current * size)
            val end = boundary((current + 1) * size)
            val visible = value.text.substring(start, end)
            val links = remember(value, start, end) { value.links.filter { it.start < end && it.end > start }.distinctBy { it.url } }
            val measuredFor = geometryKey
            Column(Modifier.onSizeChanged { size ->
                // A callback retained from a replaced input/owner cannot overwrite current geometry.
                if (measuredFor != null && currentKey == measuredFor && currentStore === store)
                    store?.put(measuredFor, size.height)
            }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SelectionContainer { Text(visible, style = LocalReadingStyles.current.body) }
                if (sourceIncomplete) Text("当前仅为预览，尚未包含完整正文。", style = MaterialTheme.typography.bodySmall)
                if (!expanded && (html.length > 12_000 || value.text.length > size)) {
                    if (!sourceIncomplete) Text("当前为长内容预览，已加载内容采用分段阅读以避免卡顿。", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { expanded = true; page = 0 }) { Text(if (sourceIncomplete) "展开已加载预览" else "阅读完整内容") }
                }
                if (expanded && pages > 1) Row {
                    TextButton(enabled = current > 0, onClick = { page-- }) { Text("上一段") }
                    Text("${current + 1}/$pages", style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = current + 1 < pages, onClick = { page++ }) { Text("下一段") }
                }
                if (links.isNotEmpty()) TextButton(onClick = { linksOpen = true }) { Text("本段链接（${links.size}）") }
            }
            if (linksOpen) AlertDialog(onDismissRequest = { linksOpen = false }, title = { Text("本段链接") }, text = {
                // Link-heavy posts stay lazy and bounded, rather than adding hundreds of action rows to a card.
                LazyColumn(Modifier.heightIn(max = 360.dp)) { items(links, key = { it.url }) { link ->
                    TextButton(onClick = { linksOpen = false; openLink(link.url) }, modifier = Modifier.fillMaxWidth()) {
                        Text(link.label, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                } }
            }, confirmButton = { TextButton(onClick = { linksOpen = false }) { Text("关闭") } })
        }
    }
}
