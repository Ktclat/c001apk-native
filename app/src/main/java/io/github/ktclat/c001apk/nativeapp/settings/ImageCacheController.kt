// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.settings

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.imageLoader
import coil.memory.MemoryCache
import io.github.ktclat.c001apk.nativeapp.feature.media.publicMediaImageLoader
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Sizes describe disk image entries, not live bitmaps or the entire application cache directory. */
data class ImageCacheClearResult(
    val removedBytes: Long,
    val remainingBytes: Long?,
    val failedCaches: Int,
)

interface ImageCacheController {
    suspend fun sizeBytes(): Long
    suspend fun clear(): ImageCacheClearResult

    companion object {
        fun create(context: Context): ImageCacheController {
            val application = context.applicationContext
            return CoilImageCacheController(listOf(application.imageLoader, publicMediaImageLoader(application)))
        }
    }
}

/**
 * Operates only on the caches already owned by public image loaders, using Coil's locking APIs.
 * Does not enumerate directories, close loaders, rotate account state or delete uploads, shares,
 * messages, history, favorites, cookies or preferences. Private message image requests already
 * disable both Coil caches and their loader is not part of this controller.
 *
 * Active requests/snapshots may outlive eviction and later requests may repopulate a cache. The
 * result is an observed disk-entry delta, not a promise that all cached bitmap references or
 * filesystem blocks have been released. Shared default disk caches are counted/cleared once.
 */
internal class CoilImageCacheController(
    private val loaders: List<ImageLoader>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ImageCacheController {
    private val mutex = Mutex()

    override suspend fun sizeBytes(): Long = withContext(dispatcher) {
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            val disks = identitySet<DiskCache>()
            loaders.forEach { loader ->
                currentCoroutineContext().ensureActive()
                loader.diskCache?.let(disks::add)
            }
            disks.sumOf { disk ->
                currentCoroutineContext().ensureActive()
                disk.size.coerceAtLeast(0)
            }
        }
    }

    override suspend fun clear(): ImageCacheClearResult = withContext(dispatcher) {
        mutex.withLock {
            val memories = identitySet<MemoryCache>()
            val disks = identitySet<DiskCache>()
            var failed = 0
            var remainingKnown = true
            loaders.forEach { loader ->
                currentCoroutineContext().ensureActive()
                try { loader.memoryCache?.let(memories::add) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failed++ }
                try { loader.diskCache?.let(disks::add) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failed++; remainingKnown = false }
            }
            memories.forEach { cache ->
                currentCoroutineContext().ensureActive()
                try { cache.clear() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failed++ }
            }
            var removed = 0L
            var remaining = 0L
            disks.forEach { disk ->
                currentCoroutineContext().ensureActive()
                var cacheFailed = false
                val before = try { disk.size.coerceAtLeast(0) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { cacheFailed = true; null }
                try { disk.clear() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { cacheFailed = true }
                currentCoroutineContext().ensureActive()
                val after = try { disk.size.coerceAtLeast(0) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { cacheFailed = true; null }
                if (before != null && after != null) removed += (before - after).coerceAtLeast(0)
                if (after != null) remaining += after else remainingKnown = false
                if (cacheFailed) failed++
            }
            ImageCacheClearResult(removed, remaining.takeIf { remainingKnown }, failed)
        }
    }

    private fun <T> identitySet(): MutableSet<T> = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
}
