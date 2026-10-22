// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Shared API-only transport. No retries of writes; account scope is supplied explicitly. */
class CoolapkHttpTransport(
    client: OkHttpClient = OkHttpClient(),
    private val endpoints: ApiEndpoints = ApiEndpoints(),
    private val protocol: ClientProtocol = ClientProtocol(),
    private val requestSession: (() -> RequestSession)? = null,
) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).cookieJar(CookieJar.NO_COOKIES)
        .callTimeout(25, TimeUnit.SECONDS).build()
    suspend fun get(path: String, params: Map<String, String> = emptyMap(), api2: Boolean = false, mutation: Boolean = false): ApiResult<JsonElement> = request(path, params, api2, false, emptyMap(), false, mutation)
    suspend fun post(path: String, form: Map<String, String>, query: Map<String, String> = emptyMap(), api2: Boolean = false, multipart: Boolean = false): ApiResult<JsonElement> = request(path, form, api2, true, query, multipart, true)
    /** Resolve only the source's signed image endpoint; returned URL is fetched without app headers. */
    suspend fun getImageLocation(id: String): ApiResult<String> {
        if (id.isBlank() || !id.all { it in '0'..'9' }) return failure(FailureKind.INVALID_ARGUMENT)
        return when (val result = request("v6/message/showImage", mapOf("id" to id, "type" to "s"), false, false, emptyMap(), false, false, true)) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> ApiResult.Success((result.value as JsonPrimitive).content)
        }
    }
    private suspend fun request(path: String, params: Map<String, String>, api2: Boolean, post: Boolean, query: Map<String, String>, multipart: Boolean, mutation: Boolean, imageRedirect: Boolean = false): ApiResult<JsonElement> = withContext(Dispatchers.IO) {
        try {
            require(Regex("^v6/[A-Za-z0-9]+(?:/[A-Za-z0-9]+)*$").matches(path))
            val base = if (api2) endpoints.api2 else endpoints.api
            require((base.isHttps && base.port == 443 && base.host in setOf("api.coolapk.com", "api2.coolapk.com")) || base.host in setOf("localhost", "127.0.0.1", "::1"))
            val session = requestSession?.invoke()
            if (session?.isCurrent?.invoke() == false) throw CancellationException("Session changed")
            val url = base.newBuilder().addPathSegments(path).apply { (if (post) query else params).forEach { (k, v) -> addQueryParameter(k, v) } }.build()
            val builder = Request.Builder().url(url).header("Accept", "application/json")
            if (post) {
                val body: RequestBody = if (multipart) MultipartBody.Builder().setType(MultipartBody.FORM).apply { params.forEach { (key, value) -> addFormDataPart(key, value) } }.build()
                    else FormBody.Builder().apply { params.forEach { (key, value) -> add(key, value) } }.build()
                builder.post(body)
            } else builder.get()
            val cookie = session?.cookieFor?.invoke(url)?.takeIf { it.isNotBlank() }
            // Account cookies must never be paired with the global anonymous device signature.
            if (cookie != null && session?.deviceCode.isNullOrBlank()) return@withContext failure(FailureKind.INVALID_ARGUMENT)
            protocol.headers(session?.deviceCode).forEach { (key, value) -> builder.header(key, value) }
            cookie?.let { builder.header("Cookie", it) }
            client.newCall(builder.build()).await().use { response ->
                if (session?.isCurrent?.invoke() == false) throw CancellationException("Session changed")
                if (imageRedirect && response.code == 302) {
                    val imageUrl = response.header("Location")?.toHttpUrlOrNull()
                    if (imageUrl == null || !imageUrl.isHttps || imageUrl.port != 443 ||
                        imageUrl.host != "image.coolapk.com" || imageUrl.username.isNotEmpty() || imageUrl.password.isNotEmpty() || imageUrl.fragment != null) {
                        return@withContext failure(FailureKind.INVALID_RESPONSE)
                    }
                    return@withContext ApiResult.Success(JsonPrimitive(imageUrl.toString()))
                }
                if (imageRedirect && response.isSuccessful) return@withContext failure(FailureKind.INVALID_RESPONSE)
                if (!response.isSuccessful) {
                    val kind = when (response.code) { 401 -> FailureKind.AUTHENTICATION; 429 -> FailureKind.RATE_LIMIT; else -> if (mutation && response.code >= 500) FailureKind.UNCERTAIN else FailureKind.HTTP }
                    return@withContext failure(kind, response.code)
                }
                val body = response.body ?: return@withContext failure(if (mutation) FailureKind.UNCERTAIN else FailureKind.INVALID_RESPONSE)
                // Bound allocation; giant/unexpected server responses cannot exhaust app memory.
                val source = body.source()
                if (source.request(MAX_BODY + 1)) return@withContext failure(if (mutation) FailureKind.UNCERTAIN else FailureKind.INVALID_RESPONSE)
                val decoded = ApiDecoder.envelope(source.readUtf8())
                if (session?.isCurrent?.invoke() == false) throw CancellationException("Session changed")
                session?.acceptCookies?.invoke(url, response.headers.values("Set-Cookie"))
                ApiResult.Success(decoded)
            }
        } catch (e: ApiProblem) { if (mutation && e.failure.kind == FailureKind.INVALID_RESPONSE) failure(FailureKind.UNCERTAIN) else e.failure }
        catch (e: IOException) { failure(if (mutation) FailureKind.UNCERTAIN else FailureKind.NETWORK) }
        catch (e: IllegalArgumentException) { failure(FailureKind.INVALID_RESPONSE) }
        // CancellationException intentionally propagates to prevent stale screen updates.
    }
    private companion object { const val MAX_BODY = 4L * 1024 * 1024 }
}
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}
