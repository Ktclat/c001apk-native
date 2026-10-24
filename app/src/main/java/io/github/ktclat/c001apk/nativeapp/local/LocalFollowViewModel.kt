// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.local

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ktclat.c001apk.nativeapp.domain.*
import io.github.ktclat.c001apk.nativeapp.domain.CoolapkRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LocalFollowFeedState(val feeds: List<ContentItem> = emptyList(), val loading: Boolean = false, val end: Boolean = false, val warning: String? = null)
/** Activity-scoped state survives root navigation and rotation; never account-bound. */
class LocalFollowViewModel(private val fetch: suspend (String, PageCursor) -> ApiResult<Page<ContentItem>>) : ViewModel() {
    constructor(repository: CoolapkRepository) : this(repository::chronologicalUserFeed)
    private val mutableState = MutableStateFlow(LocalFollowFeedState())
    val state = mutableState.asStateFlow()
    private var config: Pair<List<String>, Blacklist>? = null
    private var pager: LocalFollowPager? = null
    private var job: Job? = null
    private var generation = 0
    fun configure(uids: List<String>, blacklist: Blacklist) {
        val next = uids.toList() to blacklist
        if (next == config) return
        config = next
        refresh()
    }
    fun refresh() {
        val (uids, blacklist) = config ?: return
        generation++
        job?.cancel()
        pager = LocalFollowPager(uids, fetch) { item -> !blacklist.blocks(item.userId, item.topicTags, item.topicTitle, item.firstRelationTitle) }
        mutableState.value = LocalFollowFeedState(end = uids.isEmpty())
        if (uids.isNotEmpty()) loadMore()
    }
    fun loadMore() {
        if (mutableState.value.loading || mutableState.value.end) return
        val currentPager = pager ?: return
        val currentGeneration = generation
        mutableState.value = mutableState.value.copy(loading = true)
        job = viewModelScope.launch {
            try {
                val result = currentPager.next()
                if (generation == currentGeneration) mutableState.value = LocalFollowFeedState(mutableState.value.feeds + result.items, false, result.endReached, result.warning)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (generation == currentGeneration) mutableState.value = mutableState.value.copy(loading=false, warning="加载失败，请重试") }
        }
    }
}
