// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.feature.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import coil.ImageLoader
import coil.decode.DataSource
import coil.disk.DiskCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.request.SuccessResult
import kotlinx.coroutines.runBlocking
import io.github.ktclat.c001apk.nativeapp.diagnostics.PublicImageDiagnostics
import io.github.ktclat.c001apk.nativeapp.diagnostics.PublicImageHost
import io.github.ktclat.c001apk.nativeapp.diagnostics.PublicImageSource
import io.github.ktclat.c001apk.nativeapp.diagnostics.PublicImageType
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Production Coil fetching/decoding; only transport TLS routing is local. Not CDN acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PublicMediaCoilRequestTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun png() = checkNotNull(javaClass.getResourceAsStream("/gallery/original-landscape.png")).use { it.readBytes() }
    private fun request(url: String) = ImageRequest.Builder(context).data(url).size(80).allowHardware(false)
        .memoryCachePolicy(CachePolicy.DISABLED)

    @Test fun realProductionFetcherAndDecoderSendScopedUa() {
        PublicMediaTlsFixture().use { f ->
            f.tunnel(); f.server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png())))
            val client = f.client(publicImageClient())
            val loader = buildPublicMediaImageLoader(context, client).newBuilder().diskCache(null).memoryCache(null).build()
            try {
                val result = executeBounded(loader, request("https://image.coolapk.com/production.png").build())
                assertTrue("Real decoder must succeed", result is SuccessResult)
                result as SuccessResult
                assertEquals(DataSource.NETWORK, result.dataSource)
                assertTrue(result.drawable.intrinsicWidth > 0 && result.drawable.intrinsicHeight > 0)
                val received = f.nextGet()
                assertEquals(checkNotNull(publicMediaSystemProfile()).userAgent(), received.getHeader("User-Agent"))
                PublicMediaTlsFixture.assertNoSecrets(received)
                assertTrue(PublicImageDiagnostics.counters.snapshot().any { (event, count) ->
                    event.host == PublicImageHost.IMAGE_CDN && event.failure == null &&
                        event.contentType == PublicImageType.IMAGE && event.source == PublicImageSource.NETWORK && count > 0
                })
            } finally { loader.shutdown() }
        }
    }
    @Test fun varyUaRetainsDiskHitAndEtag304RevalidationWithoutRepeatedDownload() {
        PublicMediaTlsFixture().use { f ->
            f.tunnel()
            f.server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setHeader("Cache-Control", "max-age=3600")
                .setHeader("Vary", "User-Agent").setHeader("ETag", "\"local-image\"").setBody(Buffer().write(png())))
            f.server.enqueue(MockResponse().setResponseCode(304).setHeader("Cache-Control", "max-age=3600")
                .setHeader("Vary", "User-Agent").setHeader("ETag", "\"local-image\""))
            val directory = Files.createTempDirectory(context.cacheDir.toPath(), "rebuilt-coil-disk-").toFile()
            val disk = DiskCache.Builder().directory(directory).maxSizeBytes(8L * 1024 * 1024).build()
            val loader = buildPublicMediaImageLoader(context, f.client(publicImageClient())).newBuilder().diskCache(disk).memoryCache(null).build()
            val url = "https://image.coolapk.com/cached.png"
            fun load(revalidate: Boolean = false): SuccessResult {
                val builder = request(url)
                if (revalidate) builder.headers(Headers.Builder().add("Cache-Control", "max-age=0").build())
                return executeBounded(loader, builder.build()).also { assertTrue(it is SuccessResult) } as SuccessResult
            }
            try {
                assertEquals(DataSource.NETWORK, load().dataSource)
                assertEquals(DataSource.DISK, load().dataSource)
                assertEquals(2, f.server.requestCount) // CONNECT + one GET, not a repeated download.
                assertEquals(DataSource.NETWORK, load(true).dataSource)
                assertEquals(DataSource.DISK, load().dataSource)
                val first = f.nextGet(); val conditional = f.nextGet()
                assertEquals(checkNotNull(publicMediaSystemProfile()).userAgent(), first.getHeader("User-Agent"))
                assertEquals(first.getHeader("User-Agent"), conditional.getHeader("User-Agent"))
                assertEquals("\"local-image\"", conditional.getHeader("If-None-Match"))
                PublicMediaTlsFixture.assertNoSecrets(first); PublicMediaTlsFixture.assertNoSecrets(conditional)
                assertEquals(3, f.server.requestCount)
            } finally { loader.shutdown(); disk.clear(); directory.deleteRecursively() }
        }
    }
    @Test fun allThreeFactoriesPreserveTheirDistinctSafetyControls() {
        val public = publicImageClient(); val article = articleImageClient(); val transfer = mediaTransferClient()
        for (client in listOf(public, article, transfer)) {
            assertSame(CookieJar.NO_COOKIES, client.cookieJar)
            assertSame(Authenticator.NONE, client.authenticator); assertSame(Authenticator.NONE, client.proxyAuthenticator)
            assertFalse(client.followSslRedirects); assertNull(client.cache)
            assertEquals(listOf(AnonymousMediaHeaders), client.interceptors)
            assertEquals(1, client.networkInterceptors.filterIsInstance<PublicMediaRequestPolicy>().size)
        }
        assertFalse(article.followRedirects); assertFalse(article.retryOnConnectionFailure)
        assertTrue(article.dns is ArticlePublicDns); assertTrue(article.proxySelector is ArticleProxySelector)
        assertEquals(1, article.networkInterceptors.filterIsInstance<ArticleProxyRequestGuard>().size)
        assertEquals(10_000, article.connectTimeoutMillis); assertEquals(10_000, article.readTimeoutMillis); assertEquals(30_000, article.callTimeoutMillis)
        assertEquals(15_000, transfer.connectTimeoutMillis); assertEquals(30_000, transfer.readTimeoutMillis); assertEquals(120_000, transfer.callTimeoutMillis)
    }

    /** Never block the paused Robolectric main thread waiting for Coil's main-thread completion. */
    private fun executeBounded(loader: ImageLoader, request: ImageRequest): ImageResult {
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "rebuilt-media-coil").apply { isDaemon = true } }
        val future = executor.submit<ImageResult> { runBlocking { loader.execute(request) } }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        var originalFailure: Throwable? = null
        try {
            while (!future.isDone && System.nanoTime() < deadline) {
                ShadowLooper.idleMainLooper()
                Thread.sleep(5)
            }
            check(future.isDone) { "Production Coil local request did not finish within 12 seconds" }
            return future.get(1, TimeUnit.SECONDS)
        } catch (failure: Throwable) { originalFailure = failure; throw failure }
        finally {
            try {
                future.cancel(true); executor.shutdownNow()
                val cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
                while (!executor.isTerminated && System.nanoTime() < cleanupDeadline) {
                    ShadowLooper.idleMainLooper(); Thread.sleep(5)
                }
                check(executor.isTerminated) { "Local Coil worker cleanup timed out" }
            } catch (cleanup: Throwable) {
                val original = originalFailure
                if (original == null) throw cleanup
                if (original !== cleanup) original.addSuppressed(cleanup)
            }
        }
    }
}
