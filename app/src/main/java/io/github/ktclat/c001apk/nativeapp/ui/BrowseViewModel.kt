package io.github.ktclat.c001apk.nativeapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ktclat.c001apk.nativeapp.data.HttpCoolapkRepository
import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BrowseState(val items: List<ContentItem> = emptyList(), val loading: Boolean = false,
    val error: String? = null, val failure: ApiResult.Failure? = null, val next: PageCursor? = null, val loaded: Boolean = false,
    /** Retry a failed next cursor without restarting the successfully loaded list. */
    val moreError: Boolean = false)

class BrowseViewModel(private val repository: CoolapkRepository = HttpCoolapkRepository()) : ViewModel() {
    internal val previewGeometry = PreviewGeometryStore()
    internal val readerPositions = ReaderPositionStore()
    private val mutable = MutableStateFlow<Map<String, BrowseState>>(emptyMap())
    val states = mutable.asStateFlow()
    private var activeKey: String? = null
    private class Request(var job: kotlinx.coroutines.Job? = null)
    private val requests = mutableMapOf<String, Request>()
    fun markVisible(key: String) { activeKey = key }
    /** Route ownership, not rendering of an error row, owns outstanding detail verification. */
    fun leaveDetail(feedId: String) {
        val key = "feed:$feedId"
        requests.remove(key)?.job?.cancel()
        mutable.value[key]?.let { old ->
            old.failure?.detailRead?.cancel()
            old.items.forEach { it.accountDetailRetry?.cancel() }
            mutable.update { it + (key to old.copy(loading = false, failure = null,
                loaded = old.items.isNotEmpty(), error = null)) }
        }
    }
    /** Route-owned supplemental reads cannot publish after their screen/tab departs. */
    internal fun cancelLinkedRead(key: String) {
        if (!(key.startsWith("event:") || key.startsWith("dyh-list:") || key.startsWith("coolpic-list:"))) return
        val pending = requests.remove(key) ?: return
        pending.job?.cancel()
        mutable.update { current -> current[key]?.let { current + (key to it.copy(loading = false, loaded = false)) } ?: current }
    }
    fun invalidateReplies(feedId: String) {
        val affected = (mutable.value.keys + requests.keys).filter { it.startsWith("ordered:$feedId:") || it == "replies:$feedId" }.toSet()
        affected.forEach { requests.remove(it)?.job?.cancel() }
        mutable.update { states -> states.filterKeys { it !in affected } }
    }
    fun load(key: String, refresh: Boolean = false, more: Boolean = false, detailRetry: PendingDetailRead? = null) {
        activeKey = key
        val old = mutable.value[key] ?: BrowseState()
        if (key in mutable.value) mutable.update { (it - key) + (key to old) }
        if (old.loading || (!refresh && !more && old.loaded) || (more && old.next == null)) return
        if (old.failure?.detailRead !== detailRetry) old.failure?.detailRead?.cancel()
        old.items.mapNotNull { it.accountDetailRetry }.filter { it !== detailRetry }.forEach { it.cancel() }
        mutable.update { it + (key to old.copy(loading = true, error = if (detailRetry != null) old.error else null, moreError = false)) }
        val request = Request()
        requests[key] = request
        request.job = viewModelScope.launch {
            val cursor = if (more) old.next!! else PageCursor()
            val result = try { when {
                key.startsWith("following:") -> repository.following(FollowType.entries.getOrElse(key.substringAfter(":").toIntOrNull() ?: 0) { FollowType.ALL }, cursor)
                key == "home" -> repository.home(cursor)
                key == "hot" -> repository.page("/page?url=V9_HOME_TAB_RANKING", "热榜", cursor)
                key == "pictures" -> repository.page("/page?url=V11_FIND_COOLPIC", "酷图", cursor)
                key == "products" -> repository.productCategories()
                key.startsWith("product:") -> repository.productDetail(key.removePrefix("product:")).asPage()
                key == "topics" -> repository.page("V11_VERTICAL_TOPIC", "话题", cursor)
                key.startsWith("search:") -> repository.search(key.removePrefix("search:"), cursor)
                key.startsWith("thread:") -> repository.replyThread(key.removePrefix("thread:"), cursor)
                key.startsWith("ordered:") -> {
                    val parts = key.split(':')
                    repository.orderedReplies(parts[1], ReplyOrder.valueOf(parts[2]), parts[3].toBooleanStrict(), cursor)
                }
                key.startsWith("replies:") -> repository.replies(key.removePrefix("replies:"), cursor)
                key.startsWith("feed:") -> (detailRetry?.retryOnce() ?: repository.feedDetail(key.removePrefix("feed:"))).asPage()
                key.startsWith("event:") -> repository.eventDetail(key.removePrefix("event:")).asPage()
                key.startsWith("dyh-list:") || key.startsWith("coolpic-list:") -> {
                    val request = sourceListRequest(key)
                    if (request == null) ApiResult.Failure(FailureKind.INVALID_ARGUMENT, "内容分类参数无效")
                    else if (request.first.kind == "dyh") repository.dyhArticles(request.first.id, request.second, cursor)
                    else repository.pictures(request.first.id, request.second, cursor)
                }
                key.startsWith("dyh:") -> repository.dyhArticles(key.removePrefix("dyh:"), "all", cursor)
                key.startsWith("coolpic:") -> repository.pictures(key.removePrefix("coolpic:"), "recommend", cursor)
                key.startsWith("app:") -> repository.appDetail(key.removePrefix("app:")).asPage()
                key.startsWith("user:") -> repository.userFeed(key.removePrefix("user:"), cursor)
                key.startsWith("topic:") -> repository.topicDetail(key.removePrefix("topic:")).asPage()
                key.startsWith("page:") -> repository.page(key.removePrefix("page:"), cursor = cursor)
                else -> ApiResult.Failure(FailureKind.INVALID_ARGUMENT, "此入口尚未迁移，不展示模拟数据")
            }
            } catch (cancelled: CancellationException) {
                if (requests[key] === request) {
                    requests.remove(key)
                    mutable.update { current ->
                        val cleared = (current[key] ?: old).copy(loading = false)
                        current + (key to if (key.startsWith("feed:")) cleared.copy(error = "正文加载已取消，请重试") else cleared)
                    }
                }
                throw cancelled
            } catch (_: Exception) { ApiResult.Failure(FailureKind.NETWORK, "加载失败，请重试") }
            if (requests[key] !== request) return@launch
            mutable.update { current ->
                bounded((current - key) + (key to when (result) {
                    is ApiResult.Success -> if (key.startsWith("feed:") && old.items.firstOrNull()?.publicRead == true &&
                        old.items.firstOrNull()?.fullPostBody.let { it is FullPostBody.Available || it is FullPostBody.Structured } &&
                        result.value.items.firstOrNull()?.fullPostBody is FullPostBody.Unavailable) {
                        old.copy(loading = false, error = "新响应没有可完整读取的正文，仍保留上次已读取的内容", loaded = true)
                    } else BrowseState(
                        items = if (more) (old.items + result.value.items).distinctBy { "${it.kind}:${it.id}:${it.title}" } else result.value.items,
                        next = result.value.nextCursor, loaded = true)
                    is ApiResult.Failure -> old.copy(loading = false, error = result.message, failure = result, loaded = true, moreError = more)
                }), setOfNotNull(activeKey, key, activeKey?.takeIf { it.startsWith("ordered:") }?.split(':')?.getOrNull(1)?.let { "feed:$it" }))
            }
            readerPositions.retainCachedFeeds(mutable.value.keys)
            requests.remove(key)
        }
    }
    override fun onCleared() { mutable.value.values.forEach { state -> state.failure?.detailRead?.cancel(); state.items.forEach { it.accountDetailRetry?.cancel() } }; previewGeometry.close(); readerPositions.clear(); super.onCleared() }
}
private fun ApiResult<ContentItem>.asPage(): ApiResult<Page<ContentItem>> = when (this) {
    is ApiResult.Success -> ApiResult.Success(Page(listOf(value), null))
    is ApiResult.Failure -> this
}

internal fun bounded(states: Map<String, BrowseState>, protectedKeys: Set<String> = emptySet()): Map<String, BrowseState> {
    val roots = setOf("home", "hot", "topics", "products", "pictures")
    val remove = states.keys.filterNot { it in roots || it in protectedKeys || states[it]?.loading == true }.take((states.size - 24).coerceAtLeast(0)).toSet()
    return states.filterKeys { it !in remove }
}
