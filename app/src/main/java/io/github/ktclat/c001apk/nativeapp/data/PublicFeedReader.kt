// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonElement
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The official numeric share representation only. Never carries or accepts account cookies.
 * Redirects and arbitrary URLs are deliberately unsupported; this is not a scraping/browser client.
 */
internal class PublicFeedReader(client: OkHttpClient, private val base: HttpUrl, private val protocol: ClientProtocol) {
    private val client = client.newBuilder().apply { interceptors().clear(); networkInterceptors().clear() }
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).cache(null).cookieJar(CookieJar.NO_COOKIES).callTimeout(25, TimeUnit.SECONDS).build()

    suspend fun read(id: String, session: RequestSession?): ApiResult<JsonElement> = withContext(Dispatchers.IO) {
        require(id.isNotEmpty() && id.all { it in '0'..'9' })
        require(base.username.isEmpty() && base.password.isEmpty())
        require((base.isHttps && base.port == 443 && base.host == "www.coolapk.com") || base.host in setOf("localhost", "127.0.0.1", "::1"))
        val context = currentCoroutineContext()
        fun active() { context.ensureActive(); if (session?.isCurrent?.invoke() == false) throw CancellationException("Session changed") }
        active()
        val url = base.newBuilder().encodedPath("/feed/$id").query(null).fragment(null).build()
        val request = Request.Builder().url(url).get().header("Accept", "application/json").apply {
            protocol.headers(session?.deviceCode).forEach { (name, value) -> header(name, value) }
        }.build()
        val call = client.newCall(request)
        val consuming = AtomicBoolean(true)
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { if (consuming.get()) call.cancel() }
        }
        try {
            val response = suspendCancellableCoroutine<Response> { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                    override fun onResponse(call: Call, response: Response) { continuation.resume(response) { _, value, _ -> value.close() } }
                })
            }
            response.use {
                active()
                if (!it.isSuccessful) return@withContext failure(FailureKind.HTTP, it.code)
                val body = it.body ?: return@withContext failure(FailureKind.INVALID_RESPONSE)
                val source = body.source()
                if (source.request(4L * 1024 * 1024 + 1)) return@withContext failure(FailureKind.INVALID_RESPONSE).copy(message = "公开分享页超过 4 MiB 安全上限，未读取完整内容")
                val text = source.readUtf8()
                active()
                // An app-only landing page is not a detail body. No HTML summary extraction.
                if (text.trimStart().startsWith('<')) return@withContext failure(FailureKind.INVALID_RESPONSE).copy(message = "公开分享页暂不提供可读取的正文")
                ApiResult.Success(ApiDecoder.envelope(text))
            }
        } catch (e: ApiProblem) { e.failure }
        catch (e: IOException) { currentCoroutineContext().ensureActive(); failure(FailureKind.NETWORK) }
        finally { consuming.set(false); watcher.cancel() }
    }
}
