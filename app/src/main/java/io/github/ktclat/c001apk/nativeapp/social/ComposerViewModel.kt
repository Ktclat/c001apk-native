// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

internal class FrozenPublication(val draft: ComposerDraft, val images: List<SelectedImage>, val generation: Long) {
    override fun toString() = "FrozenPublication(redacted)"
}
private val imageCleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
internal fun discardImages(images: List<SelectedImage>) { imageCleanup.launch { images.forEach { runCatching { it.close() } } } }

internal suspend fun stageImageBatch(context: Context, uris: List<Uri>, existing: List<SelectedImage>): List<SelectedImage> {
    require(uris.isNotEmpty() && uris.size + existing.size <= MAX_FEED_IMAGES) { "最多选择9张图片" }
    val owned = mutableListOf<SelectedImage>()
    try {
        var bytes = existing.sumOf { it.sizeBytes }
        for (uri in uris) {
            currentCoroutineContext().ensureActive()
            val image = stageSelectedImage(context, uri)
            owned.add(image); bytes += image.sizeBytes
            require(bytes <= MAX_FEED_IMAGE_BYTES) { "所有图片合计不能超过60MB" }
        }
        return owned.toList()
    } catch (e: Exception) {
        withContext(NonCancellable + Dispatchers.IO) { owned.forEach { it.close() } }
        throw e
    }
}

/** Survives configuration changes; actual route/account disposal cancels work and releases private spools. */
internal class ComposerViewModel(initial: ComposerDraft, private val accountGeneration: Long) : ViewModel() {
    private val template = initial.copy(text = "")
    var input by mutableStateOf(TextFieldValue(initial.text, TextRange(initial.text.length))); private set
    var characterCount by mutableIntStateOf(composerCharacterCount(initial.text)); private set
    val text: String get() = input.text
    var option by mutableStateOf(if (initial.replyId == null) initial.selfOnly else initial.replyAndForward); private set
    val images = mutableStateListOf<SelectedImage>()
    var selecting by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var pending by mutableStateOf<FrozenPublication?>(null); private set
    val runner = MutationRunner()
    private var work: Job? = null
    private var completionConsumed = false
    private val disposed = AtomicBoolean(false)
    val isAbandoned: Boolean get() = disposed.get()
    fun editable() = !disposed.get() && !selecting && pending == null && runner.state.value == DeliveryState.Ready
    fun edit(value: String) { edit(TextFieldValue(value, TextRange(value.length))) }
    fun edit(value: TextFieldValue) {
        if (!editable()) return
        if (value.text.length > MAX_COMPOSER_CODE_UNITS) { error = COMPOSER_PASTE_TOO_LARGE; return }
        input = value; characterCount = composerCharacterCount(value.text)
        if (error == COMPOSER_TEXT_TOO_LONG || error == COMPOSER_PASTE_TOO_LARGE) error = null
    }
    fun updateOption(value: Boolean) { if (editable()) option = value }
    fun remove(image: SelectedImage) { if (editable() && images.remove(image)) discardImages(listOf(image)) }
    fun select(context: Context, uris: List<Uri>) {
        if (!editable() || uris.isEmpty()) return
        selecting = true; error = null
        work = viewModelScope.launch {
            try {
                val staged = stageImageBatch(context.applicationContext, uris, images.toList())
                if (disposed.get() || !isActive) discardImages(staged) else images.addAll(staged)
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message?.take(160) ?: "无法读取图片，原草稿已保留" }
            finally { selecting = false }
        }
    }
    fun prepare() {
        if (!editable() || text.isBlank()) return
        if (characterCount > MAX_COMPOSER_CHARACTERS) { error = COMPOSER_TEXT_TOO_LONG; return }
        pending = FrozenPublication(template.copy(text = text, selfOnly = option, replyAndForward = option), images.toList(), accountGeneration)
    }
    fun dismissConfirmation() { pending = null }
    fun confirm(repository: SocialRepository) {
        val frozen = pending ?: return
        if (disposed.get() || runner.state.value != DeliveryState.Ready) return
        pending = null
        work = viewModelScope.launch { runner.submit(isCurrent = { repository.isCurrentGeneration(frozen.generation) }) { repository.publish(frozen.draft, frozen.images, expectedGeneration = frozen.generation) } }
    }
    /** Main-thread consumer owned by the currently resumed UI, never by retained network work. */
    fun consumeCompletion(generation: Long): Boolean {
        if (disposed.get() || generation != accountGeneration || completionConsumed) return false
        val finished = runner.state.value as? DeliveryState.Finished ?: return false
        if (finished.outcome !is WriteOutcome.Confirmed) return false
        completionConsumed = true
        return true
    }
    fun retry(pending: PendingCaptcha) {
        if (!disposed.get() && work?.isActive != true) work = viewModelScope.launch { runner.resumeChallenge(pending) }
    }
    fun abandon() {
        if (!disposed.compareAndSet(false, true)) return
        completionConsumed = true
        runner.dispose()
        val active = work; viewModelScope.cancel()
        val owned = images.toList(); images.clear(); pending = null; input = TextFieldValue(""); characterCount = 0; error = null
        imageCleanup.launch { active?.join(); work = null; owned.forEach { runCatching { it.close() } } }
    }
    override fun onCleared() { abandon(); super.onCleared() }
}

/** One active composer per route host. The Activity retains this empty slot, never every visited draft. */
internal class ComposerSlotViewModel : ViewModel() {
    private var identity: Triple<Long, String, ComposerDraft>? = null
    private var current: ComposerViewModel? = null
    fun acquire(generation: Long, instance: String, initial: ComposerDraft): ComposerViewModel {
        val requested = Triple(generation, instance, initial.copy(text = "", selfOnly = false, replyAndForward = false))
        current?.let { if (identity == requested && !it.isAbandoned) return it }
        current?.abandon()
        return ComposerViewModel(initial, generation).also { current = it; identity = requested }
    }
    fun release(model: ComposerViewModel) {
        model.abandon()
        if (current === model) { current = null; identity = null }
    }
    override fun onCleared() { current?.abandon(); current = null; identity = null; super.onCleared() }
}
