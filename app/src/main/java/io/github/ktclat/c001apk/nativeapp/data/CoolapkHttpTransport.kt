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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import okio.ByteString.Companion.toByteString
import okio.ByteString.Companion.decodeBase64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Shared API-only transport. No retries of writes; account scope is supplied explicitly. */
class CoolapkHttpTransport(
    client: OkHttpClient = OkHttpClient(),
    private val endpoints: ApiEndpoints = ApiEndpoints(),
    private val protocol: ClientProtocol = ClientProtocol(),
    private val requestSession: (() -> RequestSession)? = null,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val operationScoped: Boolean = false,
) {
    private data class Operation(val path: String, val params: Map<String,String>, val api2: Boolean, val post: Boolean, val query: Map<String,String>, val multipart: Boolean, val mutation: Boolean)
    private class Rejected(val operation: Operation, val challenge: ApiChallenge, @Volatile var session: RequestSession?, @Volatile var scope: String, val expires: Long, val originalUrl: HttpUrl) {
        val graphicVerified = AtomicBoolean(false)
        val replayGranted = AtomicBoolean(false)
    }
    private class Replay(val rejected: Rejected, val proof: String?) { val used = AtomicBoolean(false) }
    private val rejected = AtomicReference<Rejected?>(null)
    private var replay: Replay? = null
    private var continuationSession = AtomicReference<RequestSession?>(null)
    private fun activeSession() = (if (operationScoped) continuationSession.get() else null) ?: requestSession?.invoke()
    /** Read-only scoped adapter: use exactly the caller's captured session, never recapture another account. */
    internal fun forRequestSession(session: RequestSession): CoolapkHttpTransport =
        CoolapkHttpTransport(client, endpoints, protocol, { session }, monotonicMillis, true)

    /** Capture once for one social action/pipeline, not a shared browsing repository. */
    fun forSingleOperation(): CoolapkHttpTransport {
        val captured = activeSession()
        return CoolapkHttpTransport(client, endpoints, protocol, captured?.let { value -> { value } }, monotonicMillis, true)
    }
    fun isRequestScopeCurrent(): Boolean = activeSession().let { it == null || (it.isCurrent() && it.isSessionCurrent?.invoke() != false) }
    fun isCaptchaContinuationCurrent(challenge: ApiChallenge): Boolean = rejected.get()?.let { it.challenge == challenge && eligible(it) && !it.replayGranted.get() } ?: false
    /** Advance only from the exact response under the account store revision lock, never by recapturing an arbitrary session. */
    private fun acceptChallengeResponse(session: RequestSession?, url: HttpUrl, headers: List<String>, record: Rejected?): RequestSession? {
        fun advance(): RequestSession? {
            if (record != null && record.session !== session) throw CancellationException("Challenge session changed")
            val next = if (session?.acceptChallengeCookies != null) {
                session.acceptChallengeCookies.invoke(url, headers) ?: throw CancellationException("Challenge session changed")
            } else { session?.acceptCookies?.invoke(url, headers); session }
            if (next != null && (!next.isCurrent() || next.isSessionCurrent?.invoke() != true)) throw CancellationException("Challenge session changed")
            if (record != null) { record.session = next; record.scope = scope(next, record.originalUrl) }
            continuationSession.set(next)
            return next
        }
        return if (record == null) advance() else synchronized(record) { advance() }
    }
    private fun now() = monotonicMillis()
    private fun scope(session: RequestSession?, url: HttpUrl): String =
        "${session?.deviceCode ?: protocol.deviceCode}|${session?.cookieFor?.invoke(url).orEmpty().split(';').map { it.trim() }.filter { it.substringBefore('=') in setOf("uid", "token", "SESSID") }.sorted().joinToString(";")}".toByteArray().toByteString().sha256().hex()
    private fun eligible(record: Rejected): Boolean {
        val session = record.session
        return now() <= record.expires && (session == null || (session.isCurrent() && session.isSessionCurrent?.invoke() == true))
    }
    /** Explicit caller-controlled replay, bound to this transport's last known rejected operation. */
    fun withCaptchaProof(challenge: ApiChallenge, proof: String): CoolapkHttpTransport {
        require(challenge is ApiChallenge.Netease && challenge.acceptsProof(proof))
        return replayTransport(challenge, proof, false)
    }
    fun withGraphicVerification(challenge: ApiChallenge): CoolapkHttpTransport {
        require(challenge is ApiChallenge.Graphic)
        return replayTransport(challenge, null, true)
    }
    private fun replayTransport(challenge: ApiChallenge, proof: String?, graphic: Boolean): CoolapkHttpTransport {
        val record = requireNotNull(rejected.get()) { "No pending challenge" }
        require(record.challenge == challenge && eligible(record) && (!graphic || record.graphicVerified.get())) { "Challenge scope changed" }
        require(record.replayGranted.compareAndSet(false, true)) { "Challenge already consumed" }
        return CoolapkHttpTransport(client, endpoints, protocol, requestSession, monotonicMillis, operationScoped).also { it.replay = Replay(record, proof); it.continuationSession = continuationSession }
    }
    suspend fun getCaptchaImage(): ApiResult<ByteArray> {
        val record = rejected.get()
        if (record == null || record.challenge !is ApiChallenge.Graphic || !eligible(record)) return failure(FailureKind.INVALID_ARGUMENT)
        return when (val result = request("v6/account/captchaImage", mapOf("_" to System.currentTimeMillis().toString(), "w" to "270", "h" to "113"), false, false, emptyMap(), false, false, captchaImage = true, challengeRecord = record)) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> ApiResult.Success((result.value as JsonPrimitive).content.decodeBase64()!!.toByteArray())
        }
    }
    suspend fun validateGraphicCaptcha(code: String): ApiResult<Unit> {
        val record = rejected.get()
        if (record == null || record.challenge !is ApiChallenge.Graphic || !eligible(record) || !Regex("^[A-Za-z0-9]{4}$").matches(code)) return failure(FailureKind.INVALID_ARGUMENT)
        return when (val result = request("v6/account/requestValidate", mapOf("type" to "err_request_captcha", "code" to code), false, true, emptyMap(), true, true, challengeRecord = record)) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> if ((result.value as? JsonPrimitive)?.content == "验证通过" && eligible(record)) {
                record.graphicVerified.set(true); ApiResult.Success(Unit)
            } else failure(FailureKind.BUSINESS)
        }
    }
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
    /** Preview only. Caller must ask before opening externally; never fetch this URL with this client. */
    suspend fun getApkDownloadLocation(packageName: String, id: String, versionCode: Long): ApiResult<String> {
        if (!Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$").matches(packageName) ||
            id.isBlank() || !id.all { it in '0'..'9' } || versionCode < 0) return failure(FailureKind.INVALID_ARGUMENT)
        return when (val result = request("v6/apk/download", mapOf("pn" to packageName, "aid" to id, "vc" to versionCode.toString(), "extra" to ""), false, false, emptyMap(), false, false, browserRedirect = true)) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> ApiResult.Success((result.value as JsonPrimitive).content)
        }
    }
    private fun publicBrowserLocation(raw: String?): HttpUrl? {
        if (raw == null || raw.length > 8192) return null
        val url = raw.toHttpUrlOrNull() ?: return null
        if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) return null
        val labels = url.host.split('.')
        // Reject all IP literals and local/reserved hostnames. This is a browser preview, not an in-app CDN allowlist.
        if (url.host.length > 253 || labels.size < 2 || !labels.last().matches(Regex("^[a-z]{2,63}$")) ||
            labels.any { !it.matches(Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")) } ||
            labels.last() in setOf("localhost", "local", "internal", "lan", "home", "test", "invalid", "example", "onion", "arpa")) return null
        return url
    }
    private suspend fun request(path: String, params: Map<String, String>, api2: Boolean, post: Boolean, query: Map<String, String>, multipart: Boolean, mutation: Boolean, imageRedirect: Boolean = false, captchaImage: Boolean = false, challengeRecord: Rejected? = null, browserRedirect: Boolean = false): ApiResult<JsonElement> = withContext(Dispatchers.IO) {
        var dispatched = false
        try {
            require(Regex("^v6/[A-Za-z0-9]+(?:/[A-Za-z0-9]+)*$").matches(path))
            val base = if (api2) endpoints.api2 else endpoints.api
            require((base.isHttps && base.port == 443 && base.host in setOf("api.coolapk.com", "api2.coolapk.com")) || base.host in setOf("localhost", "127.0.0.1", "::1"))
            val operation = Operation(path, params.toMap(), api2, post, query.toMap(), multipart, mutation)
            val boundRecord = replay?.rejected ?: challengeRecord
            val session = boundRecord?.session ?: activeSession()
            if (session?.isCurrent?.invoke() == false || (operationScoped && session?.isSessionCurrent?.invoke() == false)) throw CancellationException("Session changed")
            val originalUrl = base.newBuilder().addPathSegments(path).build()
            val attempt = replay
            if (boundRecord != null && (!eligible(boundRecord) || boundRecord.scope != scope(session, boundRecord.originalUrl))) return@withContext failure(FailureKind.INVALID_ARGUMENT)
            if (attempt != null && (!attempt.used.compareAndSet(false, true) || attempt.rejected.operation != operation || !eligible(attempt.rejected) || attempt.rejected.scope != scope(session, attempt.rejected.originalUrl))) return@withContext failure(FailureKind.INVALID_ARGUMENT)
            val fieldProof = attempt?.proof?.let { value -> (attempt.rejected.challenge as? ApiChallenge.Netease)?.field?.takeIf { it.isNotEmpty() }?.let { it to value } }
            val effectiveParams = if (fieldProof != null) params + fieldProof else params
            val url = base.newBuilder().addPathSegments(path).apply { (if (post) query else effectiveParams).forEach { (k, v) -> addQueryParameter(k, v) } }.build()
            val builder = Request.Builder().url(url).header("Accept", "application/json")
            if (post) {
                val body: RequestBody = if (multipart) MultipartBody.Builder().setType(MultipartBody.FORM).apply { effectiveParams.forEach { (key, value) -> addFormDataPart(key, value) } }.build()
                    else FormBody.Builder().apply { effectiveParams.forEach { (key, value) -> add(key, value) } }.build()
                builder.post(body)
            } else builder.get()
            val baseCookie = session?.cookieFor?.invoke(url)?.takeIf { it.isNotBlank() }
            val cookieProof = attempt?.proof?.takeIf { (attempt.rejected.challenge as? ApiChallenge.Netease)?.field == "" }
            val cookie = if (cookieProof == null) baseCookie else {
                val clean = baseCookie.orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() && it.substringBefore('=') != "validate" }
                (clean + ("validate=" + java.net.URLEncoder.encode(cookieProof, "UTF-8"))).joinToString("; ")
            }
            // Account cookies must never be paired with the global anonymous device signature.
            if (baseCookie != null && session?.deviceCode.isNullOrBlank()) return@withContext failure(FailureKind.INVALID_ARGUMENT)
            protocol.headers(session?.deviceCode).forEach { (key, value) -> builder.header(key, value) }
            cookie?.let { builder.header("Cookie", it) }
            val call = client.newCall(builder.build())
            dispatched = true
            // Keep cancellation connected while consuming the blocking Okio body, not only until headers.
            val consumingResponse = AtomicBoolean(true)
            val cancellationWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { if (consumingResponse.get()) call.cancel() }
            }
            try { call.await().use { response ->
                if (boundRecord != null && !eligible(boundRecord)) {
                    if (mutation && now() > boundRecord.expires && session?.isCurrent?.invoke() != false && session?.isSessionCurrent?.invoke() != false) return@withContext failure(FailureKind.UNCERTAIN)
                    throw CancellationException("Challenge session changed")
                }
                if (session?.isCurrent?.invoke() == false || (operationScoped && session?.isSessionCurrent?.invoke() == false)) throw CancellationException("Session changed")
                if (browserRedirect && response.code == 302) {
                    val preview = publicBrowserLocation(response.header("Location")) ?: return@withContext failure(FailureKind.INVALID_RESPONSE)
                    return@withContext ApiResult.Success(JsonPrimitive(preview.toString()))
                }
                if (imageRedirect && response.code == 302) {
                    val imageUrl = response.header("Location")?.toHttpUrlOrNull()
                    if (imageUrl == null || !imageUrl.isHttps || imageUrl.port != 443 ||
                        imageUrl.host != "image.coolapk.com" || imageUrl.username.isNotEmpty() || imageUrl.password.isNotEmpty() || imageUrl.fragment != null) {
                        return@withContext failure(FailureKind.INVALID_RESPONSE)
                    }
                    return@withContext ApiResult.Success(JsonPrimitive(imageUrl.toString()))
                }
                if ((imageRedirect || browserRedirect) && response.isSuccessful) return@withContext failure(FailureKind.INVALID_RESPONSE)
                if (!response.isSuccessful) {
                    val kind = when (response.code) { 401 -> FailureKind.AUTHENTICATION; 429 -> FailureKind.RATE_LIMIT; else -> if (mutation && (response.code >= 500 || response.code in 300..399 || response.code == 408)) FailureKind.UNCERTAIN else FailureKind.HTTP }
                    return@withContext failure(kind, response.code)
                }
                val body = response.body ?: return@withContext failure(if (mutation) FailureKind.UNCERTAIN else FailureKind.INVALID_RESPONSE)
                // Bound allocation; giant/unexpected server responses cannot exhaust app memory.
                val source = body.source()
                if (source.request((if (captchaImage) MAX_IMAGE else MAX_BODY) + 1)) return@withContext failure(if (mutation) FailureKind.UNCERTAIN else FailureKind.INVALID_RESPONSE)
                if (captchaImage) {
                    if (body.contentType()?.toString()?.substringBefore(';') !in setOf("image/png", "image/jpeg", "image/webp", "image/gif")) return@withContext failure(FailureKind.INVALID_RESPONSE)
                    val bytes = source.readByteArray()
                    if (bytes.isEmpty()) return@withContext failure(FailureKind.INVALID_RESPONSE)
                    if (session?.isCurrent?.invoke() == false || (operationScoped && session?.isSessionCurrent?.invoke() == false)) throw CancellationException("Session changed")
                    acceptChallengeResponse(session, url, response.headers.values("Set-Cookie"), boundRecord)
                    return@withContext ApiResult.Success(JsonPrimitive(bytes.toByteString().base64()))
                }
                val decoded = try { ApiDecoder.envelope(source.readUtf8()) } catch (problem: ApiProblem) {
                    val challenge = problem.failure.challenge
                    if (challenge != null && replay == null && path !in setOf("v6/account/requestValidate", "v6/account/captchaImage")) {
                        if (session?.isCurrent?.invoke() == false || (operationScoped && session?.isSessionCurrent?.invoke() == false)) throw CancellationException("Session changed")
                        // Guest or adapter without continuation support still receives a visible CAPTCHA failure.
                        if (session != null && (session.isSessionCurrent == null || session.deviceCode.isNullOrBlank() || session.cookieFor(url).isNullOrBlank())) throw problem
                        val successor = acceptChallengeResponse(session, url, response.headers.values("Set-Cookie"), null)
                        val operationId = UUID.randomUUID().toString()
                        val bound = when (challenge) {
                            is ApiChallenge.Graphic -> challenge.copy(operationId = operationId)
                            is ApiChallenge.Netease -> challenge.copy(operationId = operationId)
                        }
                        rejected.set(Rejected(operation, bound, successor, scope(successor, originalUrl), now() + 300_000, originalUrl))
                        throw ApiProblem(problem.failure.copy(challenge = bound))
                    }
                    throw problem
                }
                if (session?.isCurrent?.invoke() == false || (operationScoped && session?.isSessionCurrent?.invoke() == false)) throw CancellationException("Session changed")
                if (boundRecord == null) session?.acceptCookies?.invoke(url, response.headers.values("Set-Cookie"))
                else acceptChallengeResponse(session, url, response.headers.values("Set-Cookie"), boundRecord)
                ApiResult.Success(decoded)
            } } finally { consumingResponse.set(false); cancellationWatcher.cancel() }
        } catch (e: ApiProblem) { if (mutation && e.failure.kind == FailureKind.INVALID_RESPONSE) failure(FailureKind.UNCERTAIN) else e.failure }
        catch (e: IOException) { failure(if (mutation) FailureKind.UNCERTAIN else FailureKind.NETWORK) }
        catch (e: IllegalArgumentException) { failure(if (mutation && dispatched) FailureKind.UNCERTAIN else FailureKind.INVALID_RESPONSE) }
        // CancellationException intentionally propagates to prevent stale screen updates.
    }
    private companion object { const val MAX_BODY = 4L * 1024 * 1024; const val MAX_IMAGE = 1024L * 1024 }
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
