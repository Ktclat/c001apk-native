// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.feature.feed

import io.github.ktclat.c001apk.nativeapp.platform.FeedTextTransfer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class FeedTextAction { COPY_LINK, SHARE }
internal data class FeedTextActionState(val busy: Boolean = false, val feedback: String? = null, val error: Boolean = false)

/** Main-thread controller; only an explicit gesture calls start. No replay after stop/disposal. */
internal class FeedTextActionController {
    private val mutable = MutableStateFlow(FeedTextActionState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var disposed = false
    private var epoch = 0L

    fun start(scope: CoroutineScope, action: FeedTextAction, isCurrent: () -> Boolean,
        prepare: suspend () -> String?, transfer: FeedTextTransfer): Boolean {
        if (disposed || mutable.value.busy || !isCurrent()) return false
        val token = ++epoch
        mutable.value = FeedTextActionState(busy = true)
        job = scope.launch {
            try {
                val text = prepare()
                if (disposed || token != epoch || !isCurrent()) return@launch
                if (text == null) {
                    mutable.value = FeedTextActionState(feedback = "动态地址无效，无法复制或分享", error = true)
                } else {
                    when (action) {
                        FeedTextAction.COPY_LINK -> transfer.copyLink(text)
                        FeedTextAction.SHARE -> transfer.shareText(text)
                    }
                    if (!disposed && token == epoch && isCurrent()) mutable.value = FeedTextActionState(feedback =
                        if (action == FeedTextAction.COPY_LINK) "已复制动态链接" else "已打开系统分享面板，可选择应用或取消")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: FeedShareContentTooLarge) {
                if (!disposed && token == epoch && isCurrent()) mutable.value = FeedTextActionState(
                    feedback = "内容过长，暂时无法分享；可以复制链接", error = true)
            } catch (_: Exception) {
                if (!disposed && token == epoch && isCurrent()) mutable.value = FeedTextActionState(feedback =
                    if (action == FeedTextAction.COPY_LINK) "复制失败，请重试" else "无法打开分享面板，请重试或复制链接", error = true)
            } finally {
                if (!disposed && token == epoch) mutable.value = mutable.value.copy(busy = false)
            }
        }
        return true
    }

    fun dismissFeedback() { mutable.value = mutable.value.copy(feedback = null, error = false) }
    fun cancelPending() { epoch++; job?.cancel(); job = null; mutable.value = FeedTextActionState() }
    fun dispose() { disposed = true; cancelPending() }
}
