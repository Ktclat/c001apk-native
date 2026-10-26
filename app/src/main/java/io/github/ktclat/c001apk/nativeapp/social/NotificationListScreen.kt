// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** At most one state per finite notification route/category. Private response text never goes into
 * a Bundle or disk. The root replaces this owner on account/generation/repository changes. */
class NotificationNavigationState {
    private val pages = NotificationPageCache()
    private val screens = mutableMapOf<NoticeCategory, NotificationScreenState>()
    internal fun screen(initial: NoticeCategory) = screens.getOrPut(initial) { NotificationScreenState(initial, pages) }
    internal fun retainRoutes(routes: List<String>) {
        val retained = routes.mapNotNull { route ->
            if (route == "notifications") NoticeCategory.MENTIONS
            else if (route.startsWith("notifications:")) NoticeCategory.entries.firstOrNull { it.name == route.substringAfter(':') }
            else null
        }.toSet()
        screens.keys.filterNot { it in retained }.toList().forEach { screens.remove(it) }
        if (retained.isEmpty()) pages.clear()
    }
    internal fun clear() { pages.clear(); screens.clear() }
}
/** Shared across initial routes: at most three recently visited categories × 500 bounded rows. */
internal class NotificationPageCache {
    private val pages = LinkedHashMap<NoticeCategory, NotificationPageState>(4, .75f, true)
    fun page(category: NoticeCategory): NotificationPageState {
        val page = pages.getOrPut(category) { NotificationPageState() }
        while (pages.size > 3) pages.remove(pages.keys.first())?.clear()
        return page
    }
    fun clear() { pages.values.forEach { it.clear() }; pages.clear() }
}
class NotificationScreenState internal constructor(initial: NoticeCategory, private val pages: NotificationPageCache = NotificationPageCache()) {
    internal var category by mutableStateOf(initial)
    internal fun page(category: NoticeCategory) = pages.page(category)
}
internal class NotificationPageState {
    var rows by mutableStateOf<List<SocialRow>>(emptyList())
    var next by mutableStateOf<PageCursor?>(PageCursor())
    var loaded by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var failure by mutableStateOf<ApiResult.Failure?>(null)
    var failedMore by mutableStateOf(false)
    val list = LazyListState()
    var alive = false
    var epoch = 0L
    fun leave() { alive = false; epoch++; loading = false }
    fun clear() { leave(); rows = emptyList(); next = PageCursor(); loaded = false; failure = null }
}

@Composable internal fun NotificationListScreen(repository: SocialRepository, generation: Long,
    category: NoticeCategory, state: NotificationPageState, navigate: (String) -> Unit,
    actions: @Composable (SocialRow, () -> Unit) -> Unit) {
    val scope = rememberCoroutineScope()
    DisposableEffect(state) {
        state.alive = true
        onDispose { state.leave() }
    }
    fun current() = state.alive && repository.isCurrentGeneration(generation)
    fun load(more: Boolean) {
        if (!current() || state.loading || more && state.next == null) return
        val cursor = if (more) state.next!! else PageCursor()
        val epoch = ++state.epoch
        state.loading = true; state.failure = null
        scope.launch {
            try {
                val result = repository.notices(category, cursor, generation)
                if (!current() || state.epoch != epoch || !currentCoroutineContext().isActive) return@launch
                when (result) {
                    is ApiResult.Failure -> { state.failure = result; state.failedMore = more; state.loaded = true }
                    is ApiResult.Success -> {
                        // Flutter does not deduplicate non-message notices. Two likes on the same
                        // feed/comment may have different actors; record IDs are not row identity.
                        val all = (if (more) state.rows else emptyList()) + result.value.items
                        state.rows = all.take(500)
                        state.next = result.value.nextCursor.takeIf { all.size < 500 }
                        state.loaded = true
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (current() && state.epoch == epoch) {
                    state.failure = ApiResult.Failure(FailureKind.NETWORK, "无法读取通知，请稍后重试")
                    state.failedMore = more; state.loaded = true
                }
            } finally { if (state.epoch == epoch) state.loading = false }
        }
    }
    fun open(destination: NotificationDestination) {
        if (current()) destination.route()?.let(navigate)
    }
    LaunchedEffect(state) { if (!state.loaded) load(false) }
    LazyColumn(Modifier.fillMaxSize().testTag("notification-list"), state = state.list,
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { TextButton(onClick = { load(false) }, enabled = !state.loading) { Text("刷新") } }
        itemsIndexed(state.rows, key = { index, row -> "$index:${row.entityType}:${row.id}" }) { index, row ->
            val content = row.notification
            val destination = content?.destination ?: NotificationDestination.Unavailable(NotificationTargetIssue.MISSING)
            val route = destination.route()
            val identity = "${row.entityType}:${row.id}:$index"
            val cardModifier = Modifier.fillMaxWidth().testTag("notice-row:$identity")
            Card(if (route != null) cardModifier.clickable { open(destination) } else cardModifier) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val actor = content?.actorName.orEmpty().ifBlank { "用户信息未提供" }
                    val actorUid = content?.actorUid
                    if (actorUid != null) TextButton(onClick = { open(NotificationDestination.User(actorUid)) },
                        modifier = Modifier.testTag("notice-actor:$identity"), contentPadding = PaddingValues(0.dp)) { Text(actor) }
                    else Text(actor, style = MaterialTheme.typography.titleSmall)
                    content?.context?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                    Text(content?.text.orEmpty().ifBlank { "通知未提供文字内容" })
                    if (content?.truncated == true) Text("通知摘要已截断，请打开动态查看完整内容", style = MaterialTheme.typography.bodySmall)
                    when (destination) {
                        is NotificationDestination.Feed -> TextButton(onClick = { open(destination) }, modifier = Modifier.testTag("notice-open:$identity")) {
                            Text(if (row.entityType == "feed_reply") "查看相关动态" else "打开动态")
                        }
                        is NotificationDestination.User -> TextButton(onClick = { open(destination) }, modifier = Modifier.testTag("notice-open:$identity")) { Text("查看用户主页") }
                        is NotificationDestination.Unavailable -> Text(when(destination.reason) {
                            NotificationTargetIssue.MISSING -> "未提供可打开的内容地址，内容可能已失效"
                            NotificationTargetIssue.UNSUPPORTED -> "此内容地址暂不支持，未打开外部链接"
                            NotificationTargetIssue.TOO_LONG -> "通知内容超过安全读取上限，未解析跳转地址"
                        }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("notice-unavailable:$identity"))
                    }
                    actions(row) { load(false) }
                }
            }
        }
        if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().testTag("notification-loading")) }
        state.failure?.let { error -> item {
            Text(error.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("notification-error"))
            if (error.kind == FailureKind.AUTHENTICATION) TextButton(onClick = { if (current()) navigate("login") }) { Text("重新登录") }
            TextButton(onClick = { load(state.failedMore) }, enabled = !state.loading) { Text("重试") }
        } }
        if (state.loaded && !state.loading && state.failure == null && state.rows.isEmpty()) item { Text("暂无内容") }
        if (!state.loading && state.failure == null && state.next != null) item { TextButton(onClick = { load(true) }) { Text("加载更多") } }
        if (state.rows.size >= 500) item { Text("已加载500条，达到本次浏览上限；可刷新以更新列表。") }
    }
}
