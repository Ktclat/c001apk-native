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
    val error: String? = null, val next: PageCursor? = null, val loaded: Boolean = false)

class BrowseViewModel(private val repository: CoolapkRepository = HttpCoolapkRepository()) : ViewModel() {
    internal val readerPositions = ReaderPositionStore()
    private val mutable = MutableStateFlow<Map<String, BrowseState>>(emptyMap())
    val states = mutable.asStateFlow()
    private var activeKey: String? = null
    private class Request(var job: kotlinx.coroutines.Job? = null)
    private val requests = mutableMapOf<String, Request>()
    fun markVisible(key: String) { activeKey = key }
    fun invalidateReplies(feedId: String) {
        val affected = (mutable.value.keys + requests.keys).filter { it.startsWith("ordered:$feedId:") || it == "replies:$feedId" }.toSet()
        affected.forEach { requests.remove(it)?.job?.cancel() }
        mutable.update { states -> states.filterKeys { it !in affected } }
    }
    fun load(key: String, refresh: Boolean = false, more: Boolean = false) {
        activeKey = key
        val old = mutable.value[key] ?: BrowseState()
        if (key in mutable.value) mutable.update { (it - key) + (key to old) }
        if (old.loading || (!refresh && !more && old.loaded) || (more && old.next == null)) return
        mutable.update { it + (key to old.copy(loading = true, error = null)) }
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
                key.startsWith("feed:") -> repository.feedDetail(key.removePrefix("feed:")).asPage()
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
                    is ApiResult.Success -> BrowseState(
                        items = if (more) (old.items + result.value.items).distinctBy { "${it.kind}:${it.id}:${it.title}" } else result.value.items,
                        next = result.value.nextCursor, loaded = true)
                    is ApiResult.Failure -> old.copy(loading = false, error = result.message, loaded = true)
                }), setOfNotNull(activeKey, key, activeKey?.takeIf { it.startsWith("ordered:") }?.split(':')?.getOrNull(1)?.let { "feed:$it" }))
            }
            readerPositions.retainCachedFeeds(mutable.value.keys)
            requests.remove(key)
        }
    }
    override fun onCleared() { readerPositions.clear(); super.onCleared() }
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
