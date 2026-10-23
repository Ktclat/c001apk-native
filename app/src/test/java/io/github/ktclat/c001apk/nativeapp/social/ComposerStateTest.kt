// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ComposerStateTest {
    @get:Rule val temporary = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun image(name: String): SelectedImage {
        val file = temporary.newFile(name); file.writeBytes(byteArrayOf(1))
        return SelectedImage(file, "image/jpeg", 1, 1, "synthetic", "synthetic", name)
    }
    @Test fun preparationOnlyFreezesDraftAndDoesNotStartDelivery() {
        val model = ComposerViewModel(ComposerDraft("private draft", selfOnly = true), 7L); val image = image("a.jpg")
        model.images.add(image); model.prepare()
        val frozen = requireNotNull(model.pending)
        assertEquals(7L, frozen.generation)
        assertEquals(DeliveryState.Ready, model.runner.state.value)
        model.edit("changed"); model.updateOption(false)
        assertEquals("private draft", model.text); assertTrue(model.option)
        assertEquals(listOf(image), frozen.images); assertTrue(frozen.draft.selfOnly)
        model.dismissConfirmation(); model.edit("new draft"); model.updateOption(false)
        assertEquals("private draft", frozen.draft.text); assertTrue(frozen.draft.selfOnly)
        model.abandon()
    }
    @Test fun sameViewModelStoreAcrossConfigurationRetainsSelectedFilesAndDraft() = runBlocking {
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = ComposerViewModel(ComposerDraft("initial"), 7L) as T
        }
        val first = ViewModelProvider(store, factory)["composer", ComposerViewModel::class.java]
        first.edit("retained draft"); first.updateOption(true); val image = image("retained.jpg"); first.images.add(image)
        val afterConfiguration = ViewModelProvider(store, factory)["composer", ComposerViewModel::class.java]
        assertSame(first, afterConfiguration); assertEquals("retained draft", afterConfiguration.text)
        assertTrue(afterConfiguration.option); assertEquals(listOf(image), afterConfiguration.images.toList()); assertTrue(image.file.exists())
        store.clear()
        withTimeout(5_000) { while (image.file.exists()) delay(10) }
        assertEquals("", first.text)
    }
    @Test fun nativeBatchCapsRejectExcessCountAndTotalBytes() {
        val images = (1..10).map { image("$it.jpg") }
        assertFalse(validImageBatch(images)); assertTrue(validImageBatch(images.take(9)))
        images.take(4).forEach { java.io.RandomAccessFile(it.file, "rw").use { file -> file.setLength(MAX_IMAGE_BYTES) } }
        assertFalse(validImageBatch(images.take(4)))
        images.forEach { it.close() }
    }
    @Test fun abandoningComposerDropsRejectedContinuationAndDraft() = runBlocking {
        val model = ComposerViewModel(ComposerDraft("sensitive draft", selfOnly = true), 7L)
        val pending = PendingCaptcha(CaptchaPrompt.Graphic, { true },
            { io.github.ktclat.c001apk.nativeapp.domain.ApiResult.Success(byteArrayOf()) },
            { io.github.ktclat.c001apk.nativeapp.domain.ApiResult.Success(Unit) }, { WriteOutcome.Confirmed() })
        model.runner.submit { WriteOutcome.Rejected(io.github.ktclat.c001apk.nativeapp.domain.FailureKind.CAPTCHA, "verify", pending) }
        model.abandon()
        assertTrue(model.isAbandoned); assertFalse(model.editable()); assertEquals("", model.text)
        assertFalse(pending.retainsOperation()); assertEquals(DeliveryState.Ready, model.runner.state.value)
    }

    @Test fun routeSlotRetainsOnlyActiveModelAndRejectsStaleGeneration() {
        val slot = ComposerSlotViewModel()
        val original = slot.acquire(1, "saved-instance", ComposerDraft("private"))
        assertSame(original, slot.acquire(1, "saved-instance", ComposerDraft("ignored")))
        slot.release(original)
        val reentered = slot.acquire(1, "saved-instance", ComposerDraft("fresh"))
        assertNotSame(original, reentered); assertEquals("fresh", reentered.text); assertTrue(reentered.editable())
        val otherAccount = slot.acquire(2, "saved-instance", ComposerDraft("new account"))
        assertTrue(reentered.isAbandoned); assertEquals("", reentered.text)
        assertNotSame(reentered, otherAccount); slot.release(otherAccount)
    }

    @Test fun sameIdentifierWithDifferentReplyOrTargetTypeCannotReuseFrozenDestination() {
        val slot = ComposerSlotViewModel()
        val feedReply = slot.acquire(1, "same", ComposerDraft("feed draft", replyId = "123"))
        val commentReply = slot.acquire(1, "same", ComposerDraft("comment draft", replyId = "123", replyToReply = true))
        assertTrue(feedReply.isAbandoned); assertNotSame(feedReply, commentReply)
        commentReply.prepare(); assertTrue(requireNotNull(commentReply.pending).draft.replyToReply)
        val product = slot.acquire(1, "same", ComposerDraft("product", targetId = "123", targetType = "product"))
        val topic = slot.acquire(1, "same", ComposerDraft("topic", targetId = "123", targetType = "topic"))
        assertTrue(product.isAbandoned); topic.prepare()
        assertEquals("topic", requireNotNull(topic.pending).draft.targetType)
        slot.release(topic)
    }

    @Test fun overLimitDraftIsPreservedButCannotBeFrozenForSending() {
        val model = ComposerViewModel(ComposerDraft(""), 7L)
        val draft = "中".repeat(501)
        model.edit(draft); model.prepare()
        assertEquals(draft, model.text); assertNull(model.pending)
        assertEquals(COMPOSER_TEXT_TOO_LONG, model.error)
        model.edit("👩🏽‍💻".repeat(500)); model.prepare()
        assertEquals(500, model.characterCount); assertNotNull(model.pending)
        model.abandon()
    }
    @Test fun pathologicalPasteDoesNotReplaceExistingDraftOrSelection() {
        val model = ComposerViewModel(ComposerDraft("keep"), 7L)
        model.edit(androidx.compose.ui.text.input.TextFieldValue("keep", androidx.compose.ui.text.TextRange(2)))
        model.edit("x".repeat(MAX_COMPOSER_CODE_UNITS + 1))
        assertEquals("keep", model.text); assertEquals(2, model.input.selection.start)
        assertEquals(COMPOSER_PASTE_TOO_LARGE, model.error)
        model.abandon()
    }

    @Test fun acknowledgedCompletionIsRetainedUntilCurrentHostConsumesItExactlyOnce() = runBlocking {
        val model = ComposerViewModel(ComposerDraft("draft"), 7L)
        model.runner.submit { WriteOutcome.Confirmed() }
        assertFalse(model.consumeCompletion(8L))
        assertTrue(model.consumeCompletion(7L))
        assertFalse(model.consumeCompletion(7L))
        model.abandon()
    }
    @Test fun unknownDeliveryNeverCreatesNavigationCompletion() = runBlocking {
        val model = ComposerViewModel(ComposerDraft("draft"), 7L)
        model.runner.submit { WriteOutcome.Uncertain("unknown") }
        assertFalse(model.consumeCompletion(7L))
        model.abandon()
    }
    @Test fun abandonedConfirmedDraftCannotBeConsumedByReturningHost() = runBlocking {
        val model = ComposerViewModel(ComposerDraft("draft"), 7L)
        model.runner.submit { WriteOutcome.Confirmed() }
        model.abandon()
        assertFalse(model.consumeCompletion(7L))
    }

}
