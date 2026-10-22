// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.runtime.*
import io.github.ktclat.c001apk.nativeapp.domain.FullPostBody

internal data class PreparedDetailReader(val prepared: PreparedFullPost, val bodyDigest: String?)
internal class DetailReaderBinding(val body: FullPostBody, val prepared: PreparedFullPost,
    val page: MutableIntState, val retry: () -> Unit)
internal val LocalDetailReaderBinding = staticCompositionLocalOf<DetailReaderBinding?> { null }

/** Parse and fingerprint the same immutable body before the detail list can measure its saved offset. */
@Composable internal fun rememberPreparedDetailReader(body: FullPostBody, attempt: Int,
    parser: (FullPostBody, () -> Unit) -> PreparedFullPost = ::prepareFullPost): State<PreparedDetailReader?> {
    // FullPostBody is an unstable interface: a caller's changedInstance dirty bit can otherwise
    // invalidate remember for an equal immutable value. Key the exact stable value fields instead.
    val html = (body as? FullPostBody.Available)?.html
    val unavailable = body as? FullPostBody.Unavailable
    val limit = unavailable?.reason
    val receivedUnits = unavailable?.receivedUnits
    val result = remember(html, limit, receivedUnits, attempt, parser) { mutableStateOf<PreparedDetailReader?>(null) }
    LaunchedEffect(html, limit, receivedUnits, attempt, parser) {
        var digest: String? = null
        parseAndPublishFullPost(body, parser = { input, active ->
            val prepared = parser(input, active)
            if (prepared is PreparedFullPost.Ready && input is FullPostBody.Available) digest = readerBodyDigest(input.html, active)
            prepared
        }) { result.value = PreparedDetailReader(it, digest) }
    }
    return result
}
