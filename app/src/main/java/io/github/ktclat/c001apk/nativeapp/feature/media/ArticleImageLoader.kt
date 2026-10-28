// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.feature.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

internal sealed interface ArticleImageResult {
    data class Ready(val bitmap: Bitmap) : ArticleImageResult
    data class Unavailable(val reason: ArticleImageFailure) : ArticleImageResult
}
internal fun interface ArticleImageLoader {
    suspend fun load(url: String): ArticleImageResult
}
internal data class ArticleImageBounds(val width: Int, val height: Int)
internal interface ArticleBitmapDecoder {
    fun bounds(bytes: ByteArray): ArticleImageBounds
    fun decode(bytes: ByteArray, sampleSize: Int): Bitmap?
}
private object AndroidArticleBitmapDecoder : ArticleBitmapDecoder {
    override fun bounds(bytes: ByteArray): ArticleImageBounds {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return ArticleImageBounds(options.outWidth, options.outHeight)
    }
    override fun decode(bytes: ByteArray, sampleSize: Int): Bitmap? = BitmapFactory.decodeByteArray(
        bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        },
    )
}

/** Isolated from API transport, identity, cookies, auth, caches and system-proxy bypasses. */
internal class ArticleProxyRequestGuard(private val selector: ArticleProxySelector) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        // Network interceptors run for pooled connections too, before request headers are written.
        selector.select(chain.request().url.toUri())
        return chain.proceed(chain.request())
    }
}
internal fun articleImageClient(selector: ArticleProxySelector = ArticleProxySelector()): OkHttpClient = anonymousMediaClientBuilder()
    .dns(ArticlePublicDns()).proxySelector(selector).addNetworkInterceptor(ArticleProxyRequestGuard(selector))
    .cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
    .cache(null).connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS).build()

private val articleImagePermits = Semaphore(2)
internal val defaultArticleImageLoader: ArticleImageLoader by lazy { BoundedArticleImageLoader() }

/** Test seams do not replace the policy: all bytes and decoded dimensions remain checked. */
internal class BoundedArticleImageLoader(
    private val calls: Call.Factory = articleImageClient(),
    private val decoder: ArticleBitmapDecoder = AndroidArticleBitmapDecoder,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val proxySelector: ArticleProxySelector = ArticleProxySelector(),
) : ArticleImageLoader {
    override suspend fun load(url: String): ArticleImageResult {
        currentCoroutineContext().ensureActive()
        val safe = ArticleImagePolicy.safeUrl(url)
            ?: return ArticleImageResult.Unavailable(ArticleImageFailure.BLOCKED_URL)
        return articleImagePermits.withPermit {
            var ownedBitmap: Bitmap? = null
            var delivered = false
            try {
                val result = withContext(dispatcher) {
                    // Permit also covers the return dispatch and undispatched bitmap cleanup below.
                    coroutineScope {
                        val context = currentCoroutineContext()
                        context.ensureActive()
                        proxySelector.select(java.net.URI(safe))
                        context.ensureActive()
                        val call = calls.newCall(Request.Builder().url(safe)
                            .header("Accept", "image/jpeg,image/png,image/webp")
                            .header("Accept-Encoding", "identity").get().build())
                        // A separate nonblocking child cancels the call immediately during a blocking read.
                        val cancellation = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                            try { awaitCancellation() } finally { call.cancel() }
                        }
                        try {
                            val bytes = call.execute().use { response ->
                                context.ensureActive()
                                if (!response.isSuccessful) throw ArticleImageException(ArticleImageFailure.NETWORK)
                                if (response.header("Content-Encoding")?.let { !it.equals("identity", true) } == true)
                                    throw ArticleImageException(ArticleImageFailure.UNSUPPORTED_FORMAT)
                                val body = response.body ?: throw ArticleImageException(ArticleImageFailure.NETWORK)
                                val length = body.contentLength()
                                if (length > ArticleImagePolicy.MAX_BYTES) throw ArticleImageException(ArticleImageFailure.ENCODED_LIMIT)
                                val output = ByteArrayOutputStream(minOf(length.coerceAtLeast(0), 64 * 1024L).toInt())
                                body.byteStream().use { input ->
                                    val buffer = ByteArray(8192)
                                    while (true) {
                                        context.ensureActive()
                                        val count = input.read(buffer, 0, minOf(buffer.size, ArticleImagePolicy.MAX_BYTES - output.size() + 1))
                                        context.ensureActive()
                                        if (count < 0) break
                                        if (count == 0) continue
                                        if (count > ArticleImagePolicy.MAX_BYTES - output.size())
                                            throw ArticleImageException(ArticleImageFailure.ENCODED_LIMIT)
                                        output.write(buffer, 0, count)
                                    }
                                }
                                output.toByteArray().also {
                                    context.ensureActive()
                                    ArticleImagePolicy.validateFormat(it, response.header("Content-Type")) { context.ensureActive() }
                                    context.ensureActive()
                                }
                            }
                            context.ensureActive()
                            val bounds = decoder.bounds(bytes) // No pixel allocation.
                            context.ensureActive()
                            val sample = ArticleImagePolicy.sampleSize(bounds.width, bounds.height)
                            context.ensureActive()
                            val bitmap = decoder.decode(bytes, sample)
                                ?: throw ArticleImageException(ArticleImageFailure.DECODE_FAILED)
                            ownedBitmap = bitmap
                            context.ensureActive()
                            if (bitmap.width > ArticleImagePolicy.MAX_OUTPUT_SIDE || bitmap.height > ArticleImagePolicy.MAX_OUTPUT_SIDE ||
                                bitmap.width.toLong() * bitmap.height > ArticleImagePolicy.MAX_OUTPUT_PIXELS)
                                throw ArticleImageException(ArticleImageFailure.SOURCE_LIMIT)
                            ArticleImageResult.Ready(bitmap)
                        } finally { cancellation.cancel() }
                    }
                }
                currentCoroutineContext().ensureActive()
                delivered = true
                result
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: ArticleImageException) {
                currentCoroutineContext().ensureActive()
                ArticleImageResult.Unavailable(failure.reason)
            } catch (_: IOException) {
                currentCoroutineContext().ensureActive()
                ArticleImageResult.Unavailable(ArticleImageFailure.NETWORK)
            } catch (_: IllegalArgumentException) {
                currentCoroutineContext().ensureActive()
                ArticleImageResult.Unavailable(ArticleImageFailure.DECODE_FAILED)
            } finally {
                // Also covers prompt cancellation while withContext dispatches its Ready back to the caller.
                if (!delivered) ownedBitmap?.recycle()
            }
        }
    }
}
