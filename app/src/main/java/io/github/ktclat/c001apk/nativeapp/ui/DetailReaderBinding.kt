// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.runtime.*
import io.github.ktclat.c001apk.nativeapp.domain.FullPostBody

/** The toolbar receives mode state only, never the body, parsed document or source. */
internal class DetailSourceControl(val mode: MutableState<Boolean>) {
    private var active = false
    private var revoked = false
    internal fun activate(): Boolean { if (revoked) return false; active = true; return true }
    internal fun invalidate() { active = false; revoked = true }
    fun toggle() { if (active) mode.value = !mode.value }
}

/** Registration is route/owner scoped. Departed cleanup cannot clear or activate a newer control. */
internal class DetailSourceMenuState {
    var control: DetailSourceControl? by mutableStateOf(null)
        private set
    private var disposed = false
    fun register(value: DetailSourceControl) {
        if (disposed || control === value) return
        if (!value.activate()) return
        control?.invalidate()
        control = value
    }
    fun clear(expected: DetailSourceControl) {
        expected.invalidate()
        if (control === expected) control = null
    }
    fun dispose() { disposed = true; control?.invalidate(); control = null }
}

internal data class PreparedDetailReader(val prepared: PreparedFullPost, val bodyDigest: String?)
internal class DetailReaderBinding(val body: FullPostBody, val prepared: PreparedFullPost,
    val page: MutableIntState, val bodyDigest: String? = null,
    val sourceMode: MutableState<Boolean> = mutableStateOf(false),
    val sourcePage: MutableIntState = mutableIntStateOf(0), val retry: () -> Unit) {
    val sourcePages: List<FullPostPage>? get() = (prepared as? PreparedFullPost.Article)?.sourcePages
}
/** Test fixtures can gate the production reader without replacing NativeApp or its route. */
internal val LocalFullPostParser = staticCompositionLocalOf<((FullPostBody, () -> Unit) -> PreparedFullPost)?> { null }
internal val LocalDetailReaderBinding = staticCompositionLocalOf<DetailReaderBinding?> { null }

/** Parse and fingerprint the same immutable body before the detail list can measure its saved offset. */
@Composable internal fun rememberPreparedDetailReader(body: FullPostBody, attempt: Int,
    parser: (FullPostBody, () -> Unit) -> PreparedFullPost = LocalFullPostParser.current ?: ::prepareFullPost): State<PreparedDetailReader?> {
    // FullPostBody is an unstable interface: a caller's changedInstance dirty bit can otherwise
    // invalidate remember for an equal immutable value. Key the exact stable value fields instead.
    val html = (body as? FullPostBody.Available)?.html
    val article = body as? FullPostBody.Structured
    val rawOutput = article?.rawOutput
    val title = article?.title
    val cover = article?.cover
    val unavailable = body as? FullPostBody.Unavailable
    val limit = unavailable?.reason
    val receivedUnits = unavailable?.receivedUnits
    val result = remember(html, rawOutput, title, cover, limit, receivedUnits, attempt, parser) { mutableStateOf<PreparedDetailReader?>(null) }
    LaunchedEffect(html, rawOutput, title, cover, limit, receivedUnits, attempt, parser) {
        var digest: String? = null
        parseAndPublishFullPost(body, parser = { input, active ->
            val prepared = parser(input, active)
            if (prepared.readyPageCount() != null && input is FullPostBody.Available) digest = readerBodyDigest(input.html, active)
            if (prepared is PreparedFullPost.Article && input is FullPostBody.Structured) digest = readerArticleDigest(input, active)
            prepared
        }) { result.value = PreparedDetailReader(it, digest) }
    }
    return result
}
