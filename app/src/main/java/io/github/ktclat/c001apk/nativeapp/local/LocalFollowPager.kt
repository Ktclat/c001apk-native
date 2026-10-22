// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.local

import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Device-local public follows, never an authenticated remote follow operation. */
class LocalFollowPager(
    uids: Collection<String>,
    private val fetch: suspend (String, PageCursor) -> ApiResult<Page<ContentItem>>,
    private val accepts: (ContentItem) -> Boolean = { true },
) {
    private data class UserCursor(val uid: String, var cursor: PageCursor? = PageCursor(), val buffer: MutableList<ContentItem> = mutableListOf(), val seen: MutableSet<String> = mutableSetOf())
    data class Batch(val items: List<ContentItem>, val endReached: Boolean, val failedUsers: Set<String>, val deferredUsers: Set<String>) {
        val warning: String? get() = when { failedUsers.isNotEmpty() -> "${failedUsers.size} 位用户加载失败，可重试"; deferredUsers.isNotEmpty() -> "部分动态已过滤，请继续加载"; else -> null }
    }
    private var users = uids.distinct().also { require(it.size <= LocalRepository.MAX_FOLLOWS && it.all(::validUid)) }.map(::UserCursor)
    private var seen = mutableSetOf<String>()
    private val mutex = Mutex()
    private val newest = compareByDescending<ContentItem> { it.publishedAtSeconds }.thenBy { it.id }
    suspend fun next(limit: Int = 20): Batch = mutex.withLock {
        require(limit in 1..100)
        // Work on a snapshot. Cancellation at any suspending fetch must not lose unpublished rows.
        val users = this.users.map { it.copy(buffer = it.buffer.toMutableList(), seen = it.seen.toMutableSet()) }
        val seen = this.seen.toMutableSet()
        val output = mutableListOf<ContentItem>()
        val failed = mutableSetOf<String>(); val deferred = mutableSetOf<String>()
        // Cap successful pages per user for the entire call, not only one refill cycle.
        val scans = users.associate { it.uid to 0 }.toMutableMap()
        while (output.size < limit) {
            val pending = users.filter { it.cursor != null && it.buffer.isEmpty() && it.uid !in failed && it.uid !in deferred }
            for (chunk in pending.chunked(4)) {
                val results = coroutineScope { chunk.map { user -> async {
                    var failure = false
                    var scanned = scans.getValue(user.uid)
                    while (user.cursor != null && user.buffer.isEmpty() && scanned < 3) {
                        val current = user.cursor!!
                        val response = try { fetch(user.uid, current) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { ApiResult.Failure(FailureKind.NETWORK, "加载失败") }
                        if (response is ApiResult.Failure) { failure = true; break }
                        val page = (response as ApiResult.Success).value
                        // Repeated cursor is an invalid response, not an infinite scan loop.
                        if (page.nextCursor == current) { failure = true; break }
                        user.cursor = page.nextCursor; scanned++
                        user.buffer.addAll(page.items.filter { item -> item.kind == "feed" && item.id.isNotBlank() && item.userId == user.uid && user.seen.add(item.id) && accepts(item) })
                        user.buffer.sortWith(newest)
                    }
                    Triple(user, failure, scanned)
                } }.awaitAll() }
                results.forEach { (user, failure, scanned) ->
                    scans[user.uid] = scanned
                    if (failure) failed += user.uid
                    else if (user.buffer.isEmpty() && user.cursor != null && scanned >= 3) deferred += user.uid
                }
            }
            val user = users.filter { it.buffer.isNotEmpty() }.minWithOrNull { a, b -> newest.compare(a.buffer.first(), b.buffer.first()) } ?: break
            val item = user.buffer.removeAt(0)
            if (seen.add(item.id)) output += item
        }
        this.users = users
        this.seen = seen
        Batch(output, users.all { it.cursor == null && it.buffer.isEmpty() }, failed, deferred)
    }
}
