package io.github.ktclat.c001apk.nativeapp.ui

import android.text.Html
import android.text.style.URLSpan
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.dp
import io.github.ktclat.c001apk.nativeapp.settings.LocalReadingStyles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
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
    val state = remember(html, expanded, parser) {
        mutableStateOf<ParsedPost?>(null).also { PostParseTrace.record(it, "state-created") }
    }
    // TEMPORARY CI17 diagnostic only: no new state read, key, dispatcher or readiness condition.
    if (PostParseTrace.enabled) DisposableEffect(state) {
        PostParseTrace.record(state, "applied")
        onDispose { PostParseTrace.record(state, "disposed") }
    }
    LaunchedEffect(html, expanded, parser) {
        if (!PostParseTrace.enabled) {
            parseAndPublishPost(html, expanded, parser) { state.value = it }
        } else {
            val job = currentCoroutineContext()[Job]
            PostParseTrace.record(state, "effect-start", job)
            try {
                parseAndPublishPost(html, expanded, parser = { input ->
                    PostParseTrace.record(state, "default-parse-enter", job)
                    try {
                        parser(input).also { PostParseTrace.record(state, "default-parse-return", job) }
                    } catch (failure: Throwable) {
                        PostParseTrace.record(state, "default-parse-throw:${failure.javaClass.simpleName}", job)
                        throw failure
                    }
                }) { decoded ->
                    PostParseTrace.record(state, "main-publish-enter", job)
                    state.value = decoded
                    PostParseTrace.record(state, "main-publish-return", job, ready = state.value != null)
                }
                PostParseTrace.record(state, "effect-complete", job)
            } catch (failure: Throwable) {
                PostParseTrace.record(state, "effect-throw:${failure.javaClass.simpleName}", job)
                throw failure
            } finally {
                PostParseTrace.record(state, "effect-finally", job)
            }
        }
    }
    return state
}

/** Parsing never runs on Main; a large server body cannot become a single unbounded Text layout. */
@Composable internal fun PagedPostText(html: String, openLink: (String) -> Unit) = PagedPostText(html, false, openLink)

@Composable internal fun PagedPostText(html: String, sourceIncomplete: Boolean, openLink: (String) -> Unit) {
    var expanded by rememberSaveable(html) { mutableStateOf(false) }
    var page by rememberSaveable(html) { mutableIntStateOf(0) }
    var linksOpen by rememberSaveable(html) { mutableStateOf(false) }
    val parsedState = rememberParsedPost(html, expanded)
    val value = parsedState.value
    // Capture the existing render read, rather than adding a different state subscription.
    val traceModifier = if (PostParseTrace.enabled) {
        val traceId = PostParseTrace.id(parsedState)
        val observedReady = value != null
        SideEffect { PostParseTrace.record(parsedState, "committed-render-read", ready = observedReady) }
        Modifier.testTag("post-parse-state:$traceId")
    } else Modifier
    if (value == null) { Text("正在排版…", modifier = traceModifier, style = MaterialTheme.typography.bodySmall); return }
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
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SelectionContainer { Text(visible, modifier = traceModifier, style = LocalReadingStyles.current.body) }
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
