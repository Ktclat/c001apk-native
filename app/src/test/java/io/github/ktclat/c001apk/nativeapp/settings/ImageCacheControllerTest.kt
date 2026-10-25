// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.settings

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import coil.ImageLoader
import coil.disk.DiskCache
import coil.imageLoader
import coil.memory.MemoryCache
import io.github.ktclat.c001apk.nativeapp.feature.media.publicMediaImageLoader
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Coil disk/memory caches; no HTTP requests, directory traversal or synthetic deletion. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImageCacheControllerTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun clearsActualCachesAndPreservesUserDataAndOtherCacheDirectories() = runBlocking {
        val root = Files.createTempDirectory("native-image-cache").toFile()
        val disk = cache(File(root, "image-loader-owned"))
        val loader = ImageLoader.Builder(context).diskCache(disk).build()
        try {
            put(disk, "public-image", "synthetic image")
            val key = MemoryCache.Key("public-bitmap")
            val memory = checkNotNull(loader.memoryCache)
            memory[key] = MemoryCache.Value(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))
            assertNotNull(memory[key])
            val protected = listOf("files/settings", "databases/library", "shared_prefs/accounts.xml",
                "cache/shared-media/image.png", "cache/composer-images/draft.jpg", "cache/unknown/sentinel")
                .map { name -> File(root, name).apply { parentFile.mkdirs(); writeText(name) } }
            val controller = CoilImageCacheController(listOf(loader))
            val bytes = disk.size
            assertTrue(bytes > 0)
            assertEquals(bytes, controller.sizeBytes())

            val result = controller.clear()

            assertEquals(bytes, result.removedBytes)
            assertEquals(0L, result.remainingBytes)
            assertEquals(0, result.failedCaches)
            assertEquals(0L, controller.sizeBytes())
            assertNull(disk.openSnapshot("public-image"))
            assertNull(memory[key])
            protected.forEach { assertEquals(it.relativeTo(root).path, it.readText()) }
            assertEquals(ImageCacheClearResult(0, 0, 0), controller.clear())
        } finally { loader.shutdown(); root.deleteRecursively() }
    }

    @Test fun sharedDiskIsCountedAndClearedOnceWhileEachMemoryCacheIsCleared() = runBlocking {
        val root = Files.createTempDirectory("native-shared-cache").toFile()
        val actual = cache(root)
        var clears = 0
        val counted = object : DiskCache by actual {
            override fun clear() { clears++; actual.clear() }
        }
        val first = ImageLoader.Builder(context).diskCache(counted).build()
        val second = ImageLoader.Builder(context).diskCache(counted).build()
        try {
            put(actual, "shared-image", "only count these bytes once")
            val bytes = actual.size
            val key = MemoryCache.Key("bitmap")
            for (loader in listOf(first, second)) checkNotNull(loader.memoryCache)[key] =
                MemoryCache.Value(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))
            val controller = CoilImageCacheController(listOf(first, second, first))
            assertEquals(bytes, controller.sizeBytes())
            assertEquals(bytes, controller.clear().removedBytes)
            assertEquals(1, clears)
            assertNull(first.memoryCache?.get(key))
            assertNull(second.memoryCache?.get(key))
        } finally { first.shutdown(); second.shutdown(); root.deleteRecursively() }
    }

    @Test fun productionFactoryUsesExistingDefaultAndGalleryCachesWithoutReplacingLoaders() = runBlocking {
        val default = context.imageLoader
        val gallery = publicMediaImageLoader(context)
        val caches = listOfNotNull(default.diskCache, gallery.diskCache).distinct()
        assertTrue("Production image cache must exist", caches.isNotEmpty())
        caches.forEachIndexed { index, disk -> put(disk, "cache-settings-factory-$index", "factory fixture") }
        val controller = ImageCacheController.create(context)
        assertSame(default, context.imageLoader)
        assertSame(gallery, publicMediaImageLoader(context))
        assertEquals(caches.sumOf { it.size }, controller.sizeBytes())
        assertEquals(0, controller.clear().failedCaches)
        caches.forEach { assertEquals(0L, it.size) }
        // Keep process-owned loaders alive: cache clearing must not shut them down.
        caches.forEachIndexed { index, disk ->
            put(disk, "cache-settings-reusable-$index", "reusable")
            checkNotNull(disk.openSnapshot("cache-settings-reusable-$index")).close()
        }
        controller.clear()
        Unit
    }

    @Test fun activeSnapshotRemainsReadableAndItsBytesAreNotFalselyReportedAsReleased() = runBlocking {
        val root = Files.createTempDirectory("native-cache-active-reader").toFile()
        val disk = cache(root)
        val loader = ImageLoader.Builder(context).diskCache(disk).memoryCache(null).build()
        try {
            put(disk, "active-image", "bytes still being decoded")
            val before = disk.size
            checkNotNull(disk.openSnapshot("active-image")).use { snapshot ->
                val result = CoilImageCacheController(listOf(loader)).clear()
                assertEquals(0, result.failedCaches)
                assertEquals(0L, result.removedBytes)
                assertEquals(before, result.remainingBytes)
                assertEquals("bytes still being decoded", FileSystem.SYSTEM.source(snapshot.data).buffer().use { it.readUtf8() })
                assertNull(disk.openSnapshot("active-image"))
            }
            assertEquals(0L, disk.size)
            put(disk, "new-image", "future requests still work")
            checkNotNull(disk.openSnapshot("new-image")).close()
        } finally { loader.shutdown(); root.deleteRecursively() }
    }

    @Test fun inflightEditorCanFinishAfterClearWithoutBreakingCacheOrPublishingEvictedEntry() = runBlocking {
        val root = Files.createTempDirectory("native-cache-active-writer").toFile()
        val disk = cache(root)
        val loader = ImageLoader.Builder(context).diskCache(disk).memoryCache(null).build()
        try {
            val editor = checkNotNull(disk.openEditor("inflight-image"))
            FileSystem.SYSTEM.sink(editor.metadata).buffer().use { it.writeUtf8("synthetic metadata") }
            FileSystem.SYSTEM.sink(editor.data).buffer().use { it.writeUtf8("inflight image bytes") }
            assertEquals(0, CoilImageCacheController(listOf(loader)).clear().failedCaches)
            editor.commit()
            assertNull(disk.openSnapshot("inflight-image"))
            assertEquals(0L, disk.size)
            put(disk, "new-image", "future requests still work")
            checkNotNull(disk.openSnapshot("new-image")).close()
        } finally { loader.shutdown(); root.deleteRecursively() }
    }

    @Test fun oneFailureDoesNotPreventClearingAnIndependentCache() = runBlocking {
        val root = Files.createTempDirectory("native-cache-failure").toFile()
        val stuck = cache(File(root, "stuck"))
        val working = cache(File(root, "working"))
        val failure = object : DiskCache by stuck {
            override fun clear() { throw IOException("Synthetic cache failure") }
        }
        val first = ImageLoader.Builder(context).diskCache(failure).memoryCache(null).build()
        val second = ImageLoader.Builder(context).diskCache(working).memoryCache(null).build()
        try {
            put(stuck, "preserved", "stuck entry")
            put(working, "removed", "working entry")
            val bytes = working.size
            val result = CoilImageCacheController(listOf(first, second)).clear()
            assertEquals(bytes, result.removedBytes)
            assertEquals(stuck.size, result.remainingBytes)
            assertEquals(1, result.failedCaches)
            checkNotNull(stuck.openSnapshot("preserved")).close()
            assertNull(working.openSnapshot("removed"))
        } finally { first.shutdown(); second.shutdown(); root.deleteRecursively() }
    }

    @Test fun unknownRemainingSizeIsNotMisreportedAsZero() = runBlocking {
        val root = Files.createTempDirectory("native-cache-size-failure").toFile()
        val actual = cache(root)
        val failure = object : DiskCache by actual {
            override val size: Long get() = throw IOException("Synthetic size failure")
        }
        val loader = ImageLoader.Builder(context).diskCache(failure).memoryCache(null).build()
        try {
            put(actual, "image", "image bytes")
            val controller = CoilImageCacheController(listOf(loader))
            try { controller.sizeBytes(); fail("Size failure silently returned zero") }
            catch (_: IOException) { }
            val result = controller.clear()
            assertNull(result.remainingBytes)
            assertEquals(1, result.failedCaches)
            assertEquals(0L, result.removedBytes)
            assertEquals(0L, actual.size)
        } finally { loader.shutdown(); root.deleteRecursively() }
    }

    @Test fun cancellationIsRethrownRatherThanReportedAsSuccessfulOrPartialCleanup() = runBlocking {
        val root = Files.createTempDirectory("native-cache-cancelled").toFile()
        val actual = cache(root)
        val cancelled = object : DiskCache by actual {
            override fun clear() { throw CancellationException("Synthetic cancellation") }
        }
        val loader = ImageLoader.Builder(context).diskCache(cancelled).memoryCache(null).build()
        try {
            put(actual, "image", "preserve on interrupted clear")
            try { CoilImageCacheController(listOf(loader)).clear(); fail("Cancellation was swallowed") }
            catch (_: CancellationException) { }
            checkNotNull(actual.openSnapshot("image")).close()
        } finally { loader.shutdown(); root.deleteRecursively() }
    }

    private fun cache(directory: File): DiskCache = DiskCache.Builder().directory(directory)
        .maxSizeBytes(2L * 1024 * 1024).build()

    private fun put(cache: DiskCache, key: String, data: String) {
        checkNotNull(cache.openEditor(key)).run {
            FileSystem.SYSTEM.sink(metadata).buffer().use { it.writeUtf8("synthetic metadata") }
            FileSystem.SYSTEM.sink(this.data).buffer().use { it.writeUtf8(data) }
            commit()
        }
    }
}
