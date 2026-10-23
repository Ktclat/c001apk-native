// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.feature.media

import android.graphics.Bitmap
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.LinkedBlockingQueue
import kotlin.coroutines.CoroutineContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Entirely synthetic Calls, response bytes and BitmapFactory inputs: no sockets, accounts or API.
 * Fake Calls use an explicitly injected direct selector, never the host's proxy configuration.
 * Production selector defaults and the dedicated dynamic-proxy/pooled-guard fixtures stay intact.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArticleImageLoaderTest {
    @Test fun clientHasNoApiAuthCacheRedirectOrProxyBypass() {
        val client = articleImageClient()
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertSame(CookieJar.NO_COOKIES, client.cookieJar)
        assertSame(Authenticator.NONE, client.authenticator)
        assertSame(Authenticator.NONE, client.proxyAuthenticator)
        assertNull(client.proxy) // The system-selecting guard, not Proxy.NO_PROXY, owns routing.
        assertNull(client.cache)
        assertTrue(client.proxySelector is ArticleProxySelector)
        assertTrue(client.dns is ArticlePublicDns)
        assertTrue(client.interceptors.isEmpty())
        assertEquals(1, client.networkInterceptors.size)
        assertTrue(client.networkInterceptors.single() is ArticleProxyRequestGuard)
    }

    @Test fun boundedStaticSuccessHasOnlyAnonymousHeadersAndSamplesBeforeAllocation() = runBlocking {
        val factory = FixtureCalls { ByteBody(jpeg) }
        val events = mutableListOf<String>()
        val decoder = object : ArticleBitmapDecoder {
            override fun bounds(bytes: ByteArray): ArticleImageBounds { events += "bounds"; return ArticleImageBounds(4097, 20) }
            override fun decode(bytes: ByteArray, sampleSize: Int): Bitmap {
                events += "decode:$sampleSize"
                return Bitmap.createBitmap(1025, 5, Bitmap.Config.ARGB_8888)
            }
        }
        val result = BoundedArticleImageLoader(factory, decoder, proxySelector = ArticleProxySelector { directSelector() }).load(url)
        assertTrue(result is ArticleImageResult.Ready)
        assertEquals(listOf("bounds", "decode:4"), events)
        val request = factory.requests.single()
        assertEquals("GET", request.method)
        assertEquals(setOf("Accept", "Accept-Encoding"), request.headers.names())
        assertEquals("identity", request.header("Accept-Encoding"))
        assertTrue(factory.bodies.single().closed.get())
        assertTrue(factory.calls.single().cancelled.get()) // Watcher cleanup completed before return.
        (result as ArticleImageResult.Ready).bitmap.recycle()
    }

    @Test fun bytesAreCappedForAbsentLyingAndOversizedLengthsBeforeDecode() = runBlocking {
        for (length in listOf(-1L, 1L, ArticleImagePolicy.MAX_BYTES + 1L)) {
            val calls = FixtureCalls { ByteBody(ByteArray(ArticleImagePolicy.MAX_BYTES + 1) { 1 }, length) }
            val decoder = RecordingDecoder()
            assertFailure(ArticleImageFailure.ENCODED_LIMIT, BoundedArticleImageLoader(calls, decoder, proxySelector = ArticleProxySelector { directSelector() }).load(url))
            assertEquals(0, decoder.decodes.get())
            assertTrue(calls.bodies.single().closed.get())
        }
        val exact = ByteArray(ArticleImagePolicy.MAX_BYTES).also { jpeg.copyInto(it) }
        val exactCalls = FixtureCalls { ByteBody(exact, -1) }
        val result = BoundedArticleImageLoader(exactCalls, RecordingDecoder(), proxySelector = ArticleProxySelector { directSelector() }).load(url)
        assertTrue(result is ArticleImageResult.Ready)
        (result as ArticleImageResult.Ready).bitmap.recycle()
    }

    @Test fun malformedMimePixelsRedirectAndBlockedUrlsDoNotAllocate() = runBlocking {
        val decoder = RecordingDecoder()
        val spoof = FixtureCalls { ByteBody("GIF89a".toByteArray()) }
        assertFailure(ArticleImageFailure.UNSUPPORTED_FORMAT, BoundedArticleImageLoader(spoof, decoder, proxySelector = ArticleProxySelector { directSelector() }).load(url))
        val redirect = FixtureCalls(status = 302) { ByteBody(jpeg) }
        assertFailure(ArticleImageFailure.NETWORK, BoundedArticleImageLoader(redirect, decoder, proxySelector = ArticleProxySelector { directSelector() }).load(url))
        assertEquals(1, redirect.calls.size)
        val badDimensions = RecordingDecoder(ArticleImageBounds(32769, 1))
        assertFailure(ArticleImageFailure.SOURCE_LIMIT, BoundedArticleImageLoader(FixtureCalls { ByteBody(jpeg) }, badDimensions, proxySelector = ArticleProxySelector { directSelector() }).load(url))
        assertEquals(0, badDimensions.decodes.get())
        val blocked = FixtureCalls { ByteBody(jpeg) }
        assertFailure(ArticleImageFailure.BLOCKED_URL, BoundedArticleImageLoader(blocked, decoder, proxySelector = ArticleProxySelector { directSelector() }).load("https://other.example/a.jpg"))
        assertTrue(blocked.requests.isEmpty())
        assertEquals(0, decoder.decodes.get())
    }

    @Test fun cancellationUnblocksStreamingAndClosesBeforeReturn() = runBlocking {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val body = object : ByteBody(jpeg) {
            override fun source(): BufferedSource = object : Source {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    entered.countDown()
                    check(released.await(5, TimeUnit.SECONDS))
                    throw IOException("cancelled fixture stream")
                }
                override fun timeout(): Timeout = Timeout.NONE
                override fun close() { closed.set(true) }
            }.buffer()
        }
        val calls = FixtureCalls(onCancel = { released.countDown() }) { body }
        val job = launch(Dispatchers.Default) { BoundedArticleImageLoader(calls, RecordingDecoder(), proxySelector = ArticleProxySelector { directSelector() }).load(url) }
        awaitLatch(entered)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(calls.calls.single().cancelled.get())
        assertTrue(body.closed.get())
    }

    @Test fun globalTwoPermitsIncludeNoncooperativeDecodeAndCancellationCleanup() = runBlocking {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val started = AtomicInteger()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val decoder = object : ArticleBitmapDecoder {
            override fun bounds(bytes: ByteArray) = ArticleImageBounds(8, 8)
            override fun decode(bytes: ByteArray, sampleSize: Int): Bitmap {
                started.incrementAndGet()
                val count = active.incrementAndGet()
                maximum.updateAndGet { maxOf(it, count) }
                entered.countDown()
                try { check(release.await(5, TimeUnit.SECONDS)); return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }
                finally { active.decrementAndGet() }
            }
        }
        val first = async(Dispatchers.Default) { BoundedArticleImageLoader(FixtureCalls { ByteBody(jpeg) }, decoder, proxySelector = ArticleProxySelector { directSelector() }).load(url) }
        val second = async(Dispatchers.Default) { BoundedArticleImageLoader(FixtureCalls { ByteBody(jpeg) }, decoder, proxySelector = ArticleProxySelector { directSelector() }).load(url) }
        awaitLatch(entered)
        val thirdCalls = FixtureCalls { ByteBody(jpeg) }
        val third = async(Dispatchers.Default) { BoundedArticleImageLoader(thirdCalls, decoder, proxySelector = ArticleProxySelector { directSelector() }).load(url) }
        first.cancel()
        delay(50)
        assertTrue(thirdCalls.requests.isEmpty()) // Cancelled decoder still holds its permit until cleanup.
        assertEquals(2, started.get())
        release.countDown()
        first.join()
        (second.await() as ArticleImageResult.Ready).bitmap.recycle()
        (third.await() as ArticleImageResult.Ready).bitmap.recycle()
        assertEquals(2, maximum.get())
        assertEquals(3, started.get())
    }

    @Test fun cancelledReturnDispatchRecyclesBeforeReleasingGlobalPermitAndNeverPublishes() = runBlocking {
        val callerOne = QueuedDispatcher()
        val callerTwo = QueuedDispatcher()
        val decoded = java.util.concurrent.CopyOnWriteArrayList<Bitmap>()
        val decoder = object : ArticleBitmapDecoder {
            override fun bounds(bytes: ByteArray) = ArticleImageBounds(8, 8)
            override fun decode(bytes: ByteArray, sampleSize: Int): Bitmap =
                Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).also { decoded += it }
        }
        val calls = FixtureCalls { ByteBody(jpeg) }
        val loader = BoundedArticleImageLoader(calls, decoder, proxySelector = ArticleProxySelector { directSelector() })
        var publishedOne = false
        var secondResult: ArticleImageResult? = null
        val first = launch(callerOne) { loader.load(url); publishedOne = true }
        callerOne.runNext() // Enter withContext on the caller, then suspend for IO.
        callerOne.awaitQueued() // Ready exists, but its return to the caller is deliberately blocked.
        val second = launch(callerTwo) { secondResult = loader.load(url) }
        callerTwo.runNext()
        callerTwo.awaitQueued()
        val third = async(start = CoroutineStart.UNDISPATCHED) { loader.load(url) }
        try {
            assertEquals(2, calls.requests.size)
            assertEquals(2, decoded.size)
            first.cancel()
            assertFalse(decoded[0].isRecycled) // Cleanup has not run on the blocked caller yet.
            assertEquals(2, calls.requests.size) // Cancellation alone cannot release the owned permit.
            assertFalse(third.isCompleted)
            callerOne.runNext()
            first.join()
            assertTrue(decoded[0].isRecycled)
            assertFalse(publishedOne)
            callerTwo.runNext()
            second.join()
            assertFalse(decoded[1].isRecycled) // Ownership was actually transferred to this caller.
            (secondResult as ArticleImageResult.Ready).bitmap.recycle()
            (third.await() as ArticleImageResult.Ready).bitmap.recycle()
            assertEquals(3, calls.requests.size)
        } finally {
            first.cancel(); second.cancel(); third.cancel()
            withTimeout(5000) {
                while (!first.isCompleted || !second.isCompleted) {
                    callerOne.runIfQueued(); callerTwo.runIfQueued(); delay(1)
                }
            }
            third.join()
            decoded.forEach { if (!it.isRecycled) it.recycle() }
        }
    }

    @Test fun changedSystemProxyBlocksSameLoaderBeforeAnyNewCallOrHeaders() = runBlocking {
        var current = directSelector()
        val selector = ArticleProxySelector { current }
        val calls = FixtureCalls { ByteBody(jpeg) }
        val loader = BoundedArticleImageLoader(calls, RecordingDecoder(), proxySelector = selector)
        (loader.load(url) as ArticleImageResult.Ready).bitmap.recycle()
        assertEquals(1, calls.requests.size)
        current = configuredProxySelector()
        assertFailure(ArticleImageFailure.UNSUPPORTED_PROXY, loader.load(url))
        assertEquals(1, calls.requests.size) // No new Call can reach a pooled connection/header writer.
    }

    @Test fun actualNetworkGuardRechecksProxyBeforeReusedChainWritesHeaders() {
        var current = directSelector()
        val selector = ArticleProxySelector { current }
        val client = articleImageClient(selector)
        val guard = client.networkInterceptors.single()
        val request = Request.Builder().url(url).build()
        val headerWrites = AtomicInteger()
        // Reuse one synthetic chain/connection boundary; never open a socket or resolve a host.
        val chain = java.lang.reflect.Proxy.newProxyInstance(Interceptor.Chain::class.java.classLoader,
            arrayOf(Interceptor.Chain::class.java)) { _, method, _ ->
            when (method.name) {
                "request" -> request
                "proceed" -> {
                    headerWrites.incrementAndGet()
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                        .message("synthetic pooled connection").body(ByteBody(jpeg)).build()
                }
                else -> error("Unexpected chain operation: ${method.name}")
            }
        } as Interceptor.Chain
        guard.intercept(chain).close()
        assertEquals(1, headerWrites.get())
        current = configuredProxySelector()
        val failure = assertThrows(ArticleImageException::class.java) { guard.intercept(chain) }
        assertEquals(ArticleImageFailure.UNSUPPORTED_PROXY, failure.reason)
        assertEquals(1, headerWrites.get()) // The actual installed network guard did not proceed.
    }

    @Test fun platformDecodeIsSampledBeforeOutputBitmapAllocation() = runBlocking {
        val source = Bitmap.createBitmap(4097, 4, Bitmap.Config.ARGB_8888)
        val output = ByteArrayOutputStream()
        assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, output))
        source.recycle()
        val result = BoundedArticleImageLoader(FixtureCalls { ByteBody(output.toByteArray(), mime = "image/png") }, proxySelector = ArticleProxySelector { directSelector() }).load(url)
        assertTrue(result is ArticleImageResult.Ready)
        val bitmap = (result as ArticleImageResult.Ready).bitmap
        assertTrue(bitmap.width in 1..2048)
        assertTrue(bitmap.width.toLong() * bitmap.height <= 4_000_000)
        bitmap.recycle()
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val pending = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.add(block) }
        fun runNext() { checkNotNull(pending.poll(5, TimeUnit.SECONDS)).run() }
        fun runIfQueued() { pending.poll()?.run() }
        suspend fun awaitQueued() = withTimeout(5000) { while (pending.isEmpty()) delay(1) }
    }
    private fun directSelector(): ProxySelector = proxySelector(listOf(Proxy.NO_PROXY))
    private fun configuredProxySelector(): ProxySelector = proxySelector(listOf(
        Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("fixture-proxy.invalid", 8080))))
    private fun proxySelector(result: List<Proxy>) = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> = result
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
    }
    private suspend fun awaitLatch(latch: CountDownLatch) = withTimeout(5000) { while (latch.count > 0) delay(5) }
    private fun assertFailure(reason: ArticleImageFailure, result: ArticleImageResult) = assertEquals(ArticleImageResult.Unavailable(reason), result)
    private class RecordingDecoder(private val size: ArticleImageBounds = ArticleImageBounds(8, 8)) : ArticleBitmapDecoder {
        val decodes = AtomicInteger()
        override fun bounds(bytes: ByteArray) = size
        override fun decode(bytes: ByteArray, sampleSize: Int): Bitmap { decodes.incrementAndGet(); return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }
    }
    private open class ByteBody(private val bytes: ByteArray, private val declared: Long = bytes.size.toLong(), private val mime: String = "image/jpeg") : ResponseBody() {
        val closed = AtomicBoolean()
        override fun contentType(): MediaType = mime.toMediaType()
        override fun contentLength(): Long = declared
        private val data by lazy { Buffer().write(bytes) }
        override fun source(): BufferedSource = data
        override fun close() { closed.set(true); super.close() }
    }
    private class FixtureCalls(private val status: Int = 200, private val onCancel: () -> Unit = {}, private val body: () -> ByteBody) : Call.Factory {
        val requests = java.util.concurrent.CopyOnWriteArrayList<Request>()
        val bodies = java.util.concurrent.CopyOnWriteArrayList<ByteBody>()
        val calls = java.util.concurrent.CopyOnWriteArrayList<FixtureCall>()
        override fun newCall(request: Request): Call {
            requests += request
            val responseBody = body().also { bodies += it }
            return FixtureCall(request, responseBody, status, onCancel).also { calls += it }
        }
    }
    private class FixtureCall(private val request: Request, private val body: ByteBody, private val status: Int, private val onCancel: () -> Unit) : Call {
        val cancelled = AtomicBoolean()
        private var executed = false
        override fun request(): Request = request
        override fun execute(): Response {
            executed = true
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .header("Content-Type", body.contentType().toString()).body(body).build()
        }
        override fun enqueue(responseCallback: Callback) = error("No asynchronous fixture calls")
        override fun cancel() { cancelled.set(true); onCancel() }
        override fun isExecuted(): Boolean = executed
        override fun isCanceled(): Boolean = cancelled.get()
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = FixtureCall(request, body, status, onCancel)
    }
    private companion object {
        const val url = "https://image.coolapk.com/a.jpg"
        val jpeg = byteArrayOf(-1, -40, -1, -39)
    }
}
