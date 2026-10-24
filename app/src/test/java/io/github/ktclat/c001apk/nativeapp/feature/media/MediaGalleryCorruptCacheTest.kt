// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.feature.media

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import coil.ImageLoader
import coil.disk.DiskCache
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.FileSystem
import okio.buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Real Coil HTTP fetcher -> disk cache -> PNG decoder. HTTP is intercepted in memory, not a socket. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaGalleryCorruptCacheTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun explicitRetryBypassesCorruptCached200AndRepairsOnlyThatImage() {
        val url = "https://fixture.invalid/corrupt-then-valid.png"
        val corrupt = "Synthetic invalid PNG bytes".toByteArray()
        val valid = checkNotNull(javaClass.getResourceAsStream("/gallery/original-landscape.png")).use { it.readBytes() }
        val requests = CopyOnWriteArrayList<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.url.toString() == url) { "Unexpected fixture URL" }
            requests += request
            val now = System.currentTimeMillis()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic")
                .header("Cache-Control", "public, max-age=86400")
                .sentRequestAtMillis(now).receivedResponseAtMillis(now)
                .body((if (requests.size == 1) corrupt else valid).toResponseBody("image/png".toMediaType())).build()
            // Never chain.proceed: this response still goes through Coil's real disk/decode pipeline.
        }.build()
        val cache = DiskCache.Builder().directory(File(compose.activity.cacheDir, "gallery-corrupt-cache"))
            .maxSizeBytes(2L * 1024 * 1024).build()
        val unrelatedKey = "untouched-other-image"
        val unrelated = "Independent cache sentinel".toByteArray()
        checkNotNull(cache.openEditor(unrelatedKey)).run {
            FileSystem.SYSTEM.sink(metadata).buffer().use { it.writeUtf8("unrelated metadata") }
            FileSystem.SYSTEM.sink(data).buffer().use { it.write(unrelated) }
            commit()
        }
        val loader = ImageLoader.Builder(compose.activity).okHttpClient(client).diskCache(cache).memoryCache(null).build()
        try {
            compose.setContent { MaterialTheme { CompositionLocalProvider(LocalMediaImageLoader provides loader) {
                ImageGallery(listOf(url)) {}
            } } }
            awaitPhase("加载失败")
            assertEquals(1, requests.size)
            checkNotNull(cache.openSnapshot(url)).use { snapshot ->
                val cached = FileSystem.SYSTEM.source(snapshot.data).buffer().use { it.readByteArray() }
                assertArrayEquals("The initial bad HTTP 200 really reached disk, not a synthetic ErrorResult", corrupt, cached)
            }
            compose.onNodeWithTag("media-viewer-retry-0").performTouchInput { click(center) }
            awaitPhase("已加载")
            assertEquals("Exactly one explicit fresh request", 2, requests.size)
            assertTrue("Retry must force this request beyond the stale cache", requests[1].cacheControl.noCache)
            checkNotNull(cache.openSnapshot(url)).use { snapshot ->
                assertArrayEquals(valid, FileSystem.SYSTEM.source(snapshot.data).buffer().use { it.readByteArray() })
            }
            checkNotNull(cache.openSnapshot(unrelatedKey)).use { snapshot ->
                assertArrayEquals("No global cache clearing", unrelated, FileSystem.SYSTEM.source(snapshot.data).buffer().use { it.readByteArray() })
            }
        } finally {
            loader.shutdown()
            client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow()
        }
    }
    private fun awaitPhase(label: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithTag("media-viewer-image-0", useUnmergedTree = true).fetchSemanticsNodes().any {
            it.config.contains(SemanticsProperties.StateDescription) && it.config[SemanticsProperties.StateDescription] == "图片 1，$label"
        }
    }
}
