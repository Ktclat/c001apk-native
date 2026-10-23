// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.runtime.mutableStateOf
import io.github.ktclat.c001apk.nativeapp.domain.FullPostBodyBounds
import io.github.ktclat.c001apk.nativeapp.domain.FullPostBody
import io.github.ktclat.c001apk.nativeapp.domain.ArticleBodyBounds
import io.github.ktclat.c001apk.nativeapp.domain.ReplyOrder
import java.security.MessageDigest

internal data class ReaderOwner(val accountId: String?, val generation: Long)
internal data class ReaderIdentity(val owner: ReaderOwner, val feedId: String, val bodyDigest: String)
internal data class ReaderPosition(
    val page: Int = 0,
    val index: Int = 0,
    val offset: Int = 0,
    val rowKey: String? = "post",
    val replyKey: String? = null,
    val order: ReplyOrder = ReplyOrder.RECENT_REPLY,
    val authorOnly: Boolean = false,
)

/** Small, memory-only bookmark. It contains no body, parsed result, response or saved-state Bundle. */
internal class ReaderBookmark(val identity: ReaderIdentity, internal val preparedRevision: Any = Any()) {
    internal var invalidated: Boolean = false
        private set
    internal fun invalidate() { invalidated = true }
    var position: ReaderPosition = ReaderPosition()
        private set

    fun save(value: ReaderPosition) {
        position = value.copy(page = value.page.coerceIn(0, MAX_READER_PAGES - 1),
            index = value.index.coerceAtLeast(0), offset = value.offset.coerceAtLeast(0),
            rowKey = value.rowKey?.takeIf { it.length <= MAX_READER_KEY_UNITS },
            replyKey = value.replyKey?.takeIf { it.length <= MAX_READER_KEY_UNITS })
    }
}

internal const val MAX_READER_BOOKMARKS = 13 // Current route plus the existing 12-entry history.
internal const val MAX_READER_KEY_UNITS = 256
// A surrogate-safe page may contain 3999 rather than 4000 UTF-16 units.
internal const val MAX_ORDINARY_READER_PAGES = (FullPostBodyBounds.MAX_HTML_UNITS + FullPostBodyBounds.PAGE_UNITS - 2) / (FullPostBodyBounds.PAGE_UNITS - 1)
internal const val MAX_READER_PAGES = ArticleBodyBounds.MAX_SEGMENTS

/** Main-thread presentation state, owned by the generation-scoped BrowseViewModel.
 * Latest position per feed, not per navigation visit. Removed entries are never reinserted by disposal.
 */
internal class ReaderPositionStore(private val capacity: Int = MAX_READER_BOOKMARKS) {
    init { require(capacity in 1..MAX_READER_BOOKMARKS) }
    private val change = mutableStateOf(Any())
    internal val revision: Any get() = change.value
    private fun changed() { change.value = Any() }
    private var owner: ReaderOwner? = null
    private var retainedFeeds: Set<String> = emptySet()
    private val entries = linkedMapOf<String, ReaderBookmark>()
    internal val size: Int get() = entries.size
    internal fun feedIds(): Set<String> = entries.keys.toSet()

    fun controls(expected: ReaderOwner, feedId: String): ReaderPosition? =
        entries[feedId]?.takeIf { owner == expected }?.position

    /** Pure lookup/preparation: an abandoned composition cannot evict entries or change the owner. */
    fun prepare(identity: ReaderIdentity): ReaderBookmark? {
        if (identity.feedId.isEmpty() || identity.feedId.length > MAX_READER_KEY_UNITS ||
            identity.bodyDigest.length != 64 || identity.bodyDigest.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        return entries[identity.feedId]?.takeIf { owner == identity.owner && it.identity == identity }
            ?: ReaderBookmark(identity, revision)
    }
    /** Called only after composition commits, with the current body rechecked by the caller. */
    fun commit(bookmark: ReaderBookmark): Boolean {
        val identity = bookmark.identity
        if (bookmark.invalidated || owner != identity.owner || identity.feedId !in retainedFeeds) return false
        if (entries[identity.feedId] === bookmark) {
            if (entries.keys.lastOrNull() != identity.feedId) {
                entries.remove(identity.feedId); entries[identity.feedId] = bookmark; changed()
            }
            return true
        }
        if (bookmark.preparedRevision !== revision) return false
        entries.remove(identity.feedId)?.invalidate()
        entries[identity.feedId] = bookmark
        while (entries.size > capacity) remove(entries.keys.first())
        changed()
        return true
    }
    private fun remove(feedId: String) { entries.remove(feedId)?.let { it.invalidate(); changed() } }
    fun discard(expected: ReaderOwner, feedId: String) {
        if (owner == expected) remove(feedId)
    }
    fun retainRoutes(expected: ReaderOwner, routes: List<String>) {
        if (owner != expected) { clear(); owner = expected }
        val next = routes.takeLast(MAX_READER_BOOKMARKS).filter { it.startsWith("feed:") }
            .map { it.removePrefix("feed:") }.filter { it.length <= MAX_READER_KEY_UNITS }.toSet()
        if (next != retainedFeeds) { retainedFeeds = next; changed() }
        entries.keys.filter { it !in retainedFeeds }.forEach(::remove)
    }
    fun retainCachedFeeds(keys: Set<String>) {
        entries.keys.filter { "feed:$it" !in keys }.forEach(::remove)
        // Also reject unattached candidates prepared before the data cache changed.
        changed()
    }
    fun clear() {
        entries.values.forEach { it.invalidate() }; entries.clear(); retainedFeeds = emptySet(); owner = null; changed()
    }

}

/** An exact UTF-16 fingerprint. Called only on the parser dispatcher, never during Main layout. */
internal fun readerBodyDigest(html: String, checkActive: () -> Unit = {}): String {
    require(html.length <= FullPostBodyBounds.MAX_HTML_UNITS)
    val digest = MessageDigest.getInstance("SHA-256")
    for (index in html.indices) {
        if (index % 1024 == 0) checkActive()
        val code = html[index].code
        digest.update((code ushr 8).toByte())
        digest.update(code.toByte())
    }
    checkActive()
    val digits = "0123456789abcdef"
    return buildString(64) { for (byte in digest.digest()) { append(digits[(byte.toInt() ushr 4) and 15]); append(digits[byte.toInt() and 15]) } }
}

/** Resolve one stable row, not an unchecked old index or a retained copy of the reply hierarchy. */
internal fun restoredReaderPosition(saved: ReaderPosition, pageCount: Int, replyKey: String,
    maximumPages: Int = MAX_READER_PAGES, findReplyRow: (String) -> Int?): ReaderPosition {
    require(maximumPages in 1..MAX_READER_PAGES)
    require(pageCount in 1..maximumPages)
    val index = when (saved.rowKey) {
        "post" -> 0
        "comment-controls" -> 1
        else -> if (saved.replyKey == replyKey) saved.rowKey?.let(findReplyRow) else null
    }
    return saved.copy(page = saved.page.coerceIn(0, pageCount - 1), index = index ?: 0,
        offset = if (index == null) 0 else saved.offset.coerceAtLeast(0))
}

/** Article identity is exact-source, typed and length-framed; ordinary vectors are unchanged. */
internal fun readerArticleDigest(body: FullPostBody.Structured, checkActive: () -> Unit = {}): String {
    val units = body.rawOutput.length.toLong() + (body.title?.length ?: 0) + (body.cover?.length ?: 0)
    require(units <= FullPostBodyBounds.MAX_HTML_UNITS)
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("c001apk:article:v1\u0000".toByteArray(Charsets.US_ASCII))
    for (field in listOf(body.rawOutput, body.title, body.cover)) {
        checkActive()
        val length = field?.length ?: -1
        for (shift in listOf(24, 16, 8, 0)) digest.update((length ushr shift).toByte())
        field?.forEachIndexed { index, value ->
            if (index % 1024 == 0) checkActive()
            digest.update((value.code ushr 8).toByte()); digest.update(value.code.toByte())
        }
    }
    checkActive()
    val digits = "0123456789abcdef"
    return buildString(64) { for (byte in digest.digest()) { append(digits[(byte.toInt() ushr 4) and 15]); append(digits[byte.toInt() and 15]) } }
}

internal fun PreparedFullPost.readerPageLimit(): Int = if (this is PreparedFullPost.Article) ArticleBodyBounds.MAX_SEGMENTS else MAX_ORDINARY_READER_PAGES
