// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.feature.feed

import io.github.ktclat.c001apk.nativeapp.platform.FeedTextTransfer
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FeedTextActionControllerTest {
    private val copied = mutableListOf<String>()
    private val shared = mutableListOf<String>()
    private val transfer = object : FeedTextTransfer {
        override fun copyLink(link: String) { copied += link }
        override fun shareText(text: String) { shared += text }
    }
    @Test fun explicitStartOnlyAndPendingSecondClickDoesNotDuplicate() = runTest {
        val controller = FeedTextActionController()
        val payload = CompletableDeferred<String>()
        assertTrue(copied.isEmpty()); assertTrue(shared.isEmpty())
        assertTrue(controller.start(this, FeedTextAction.SHARE, { true }, { payload.await() }, transfer))
        assertFalse(controller.start(this, FeedTextAction.SHARE, { true }, { "duplicate" }, transfer))
        runCurrent(); assertTrue(shared.isEmpty()); assertTrue(controller.state.value.busy)
        payload.complete("synthetic payload"); runCurrent()
        assertEquals(listOf("synthetic payload"), shared)
        assertFalse(controller.state.value.busy)
        assertEquals("已打开系统分享面板，可选择应用或取消", controller.state.value.feedback)
    }
    @Test fun changedOwnerBeforeDeliveryPreventsTransfer() = runTest {
        val controller = FeedTextActionController(); var current = true
        val payload = CompletableDeferred<String>()
        controller.start(this, FeedTextAction.COPY_LINK, { current }, { payload.await() }, transfer)
        runCurrent(); current = false; payload.complete("not copied"); runCurrent()
        assertTrue(copied.isEmpty()); assertFalse(controller.state.value.busy)
        assertNull(controller.state.value.feedback)
        assertFalse(controller.start(this, FeedTextAction.COPY_LINK, { current }, { "stale" }, transfer))
    }
    @Test fun routeDisposalCancelsAndCannotReplayAfterCompletion() = runTest {
        val controller = FeedTextActionController(); val payload = CompletableDeferred<String>()
        controller.start(this, FeedTextAction.SHARE, { true }, { payload.await() }, transfer)
        runCurrent(); controller.dispose(); payload.complete("departed"); runCurrent()
        assertTrue(shared.isEmpty()); assertEquals(FeedTextActionState(), controller.state.value)
        assertFalse(controller.start(this, FeedTextAction.SHARE, { true }, { "replay" }, transfer))
    }
    @Test fun pauseCancelsPendingAndResumeRequiresNewGesture() = runTest {
        val controller = FeedTextActionController(); val payload = CompletableDeferred<String>()
        controller.start(this, FeedTextAction.SHARE, { true }, { payload.await() }, transfer)
        runCurrent(); controller.cancelPending(); payload.complete("interrupted"); runCurrent()
        assertTrue(shared.isEmpty()); assertEquals(FeedTextActionState(), controller.state.value)
        controller.start(this, FeedTextAction.SHARE, { true }, { "new gesture" }, transfer); runCurrent()
        assertEquals(listOf("new gesture"), shared)
    }
    @Test fun cancellationDoesNotBecomeErrorFeedback() = runTest {
        val controller = FeedTextActionController()
        controller.start(this, FeedTextAction.SHARE, { true }, { throw CancellationException() }, transfer)
        runCurrent(); assertEquals(FeedTextActionState(), controller.state.value)
    }
    @Test fun invalidIdAndContentLimitHaveActionableFeedbackAndNeverTransfer() = runTest {
        val controller = FeedTextActionController()
        controller.start(this, FeedTextAction.COPY_LINK, { true }, { null }, transfer); runCurrent()
        assertTrue(controller.state.value.error); assertEquals("动态地址无效，无法复制或分享", controller.state.value.feedback)
        controller.start(this, FeedTextAction.SHARE, { true }, { throw FeedShareContentTooLarge() }, transfer); runCurrent()
        assertEquals("内容过长，暂时无法分享；可以复制链接", controller.state.value.feedback)
        assertTrue(copied.isEmpty()); assertTrue(shared.isEmpty())
    }
    @Test fun platformExceptionDoesNotLeakRawExceptionAndExplicitRetryWorks() = runTest {
        val controller = FeedTextActionController()
        val broken = object : FeedTextTransfer {
            override fun copyLink(link: String) = throw SecurityException("private synthetic auth state")
            override fun shareText(text: String) = throw IllegalStateException("private synthetic auth state")
        }
        controller.start(this, FeedTextAction.SHARE, { true }, { "safe" }, broken); runCurrent()
        assertEquals("无法打开分享面板，请重试或复制链接", controller.state.value.feedback)
        controller.dismissFeedback(); assertNull(controller.state.value.feedback)
        controller.start(this, FeedTextAction.COPY_LINK, { true }, { "safe" }, broken); runCurrent()
        assertEquals("复制失败，请重试", controller.state.value.feedback)
        controller.start(this, FeedTextAction.COPY_LINK, { true }, { "safe" }, transfer); runCurrent()
        assertEquals(listOf("safe"), copied); assertEquals("已复制动态链接", controller.state.value.feedback)
    }
}
