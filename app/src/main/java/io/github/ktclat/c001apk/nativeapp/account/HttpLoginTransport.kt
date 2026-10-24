package io.github.ktclat.c001apk.nativeapp.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resumeWithException
import java.io.IOException
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** Account-host-only, ephemeral login flow. Never uses the active-account CookieJar.
 * A failed/ambiguous POST is surfaced; it is never automatically replayed.
 */
class HttpLoginTransport(private val client: OkHttpClient = OkHttpClient()) : LoginTransport {
    private val http = client.newBuilder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false)
        .followSslRedirects(false).retryOnConnectionFailure(false).callTimeout(20, TimeUnit.SECONDS).build()
    private val lock = Any()
    private var epoch = 0L
    private var cookies = emptyList<Cookie>()
    private var prepared: LoginPreparation? = null
    private val calls = mutableSetOf<Call>()
    override suspend fun prepare(): LoginPreparation = withContext(Dispatchers.IO) {
        val current = synchronized(lock) { epoch }
        execute("/auth/login?type=mobile", current)
        val html = execute("/auth/loginByCoolApk", current).decodeToString()
        val hash = extractRequestHash(html) ?: error("Login page omitted request hash")
        val result = LoginPreparation(hash, SecureRandom().nextDouble().toString().replace(".", "undefined"))
        synchronized(lock) { check(epoch == current); prepared = result }
        result
    }
    override suspend fun submit(preparation: LoginPreparation, login: String, password: CharArray, captcha: String, proof: String?): LoginReply = withContext(Dispatchers.IO) {
        val current = synchronized(lock) { check(prepared === preparation); epoch }
        // String allocation is required by OkHttp form encoding; never retained in coordinator/UI state or disk.
        val body = FormBody.Builder().add("submit", "1").add("randomNumber", preparation.randomNumber)
            .add("requestHash", preparation.requestHash).add("login", login).add("password", String(password))
            .add("captcha", captcha).add("code", "")
        val pending = synchronized(lock) { pendingChallenge }
        if (proof != null) {
            require(pending != null && pending.accepts(proof))
            if (pending.field.isNotEmpty()) body.add(pending.field, proof)
        }
        val bytes = execute("/auth/loginByCoolApk", current, body.build(), proof.takeIf { pending?.field == "" })
        val obj = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        if (obj["status"]?.jsonPrimitive?.intOrNull == 1) {
            val received = synchronized(lock) { check(epoch == current); cookies.toList() }
            val uid = received.lastOrNull { it.name == "uid" }?.value
            val name = received.lastOrNull { it.name == "username" }?.value
            val token = received.lastOrNull { it.name == "token" }?.value
            require(uid != null && uid.matches(Regex("[0-9]{1,30}")) && !name.isNullOrEmpty() && !token.isNullOrEmpty())
            return@withContext LoginReply.Authenticated(uid, name, received)
        }
        if (obj["messageStatus"]?.jsonPrimitive?.contentOrNull == "err_request_captcha_v2") {
            // API-default captcha IDs do not establish the account endpoint's protocol.
            // Only explicit account-provided configuration may enter the generic SDK flow.
            val extra = obj["messageExtra"] ?: obj["extra"] ?: obj
            val config = runCatching {
                if (extra is JsonPrimitive && extra.isString) Json.parseToJsonElement(extra.content).jsonObject else extra.jsonObject
            }.getOrNull() ?: return@withContext LoginReply.WebLoginRequired()
            val challenge = explicitAccountChallenge(config) ?: return@withContext LoginReply.WebLoginRequired()
            synchronized(lock) { check(epoch == current); pendingChallenge = challenge }
            return@withContext LoginReply.HumanChallenge(challenge)
        }
        val message = obj["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
        when {
            message.contains("图形验证码") -> LoginReply.GraphicCaptcha()
            message.contains("人机") -> LoginReply.WebLoginRequired()
            else -> LoginReply.Rejected()
        }
    }
    private var pendingChallenge: HumanChallengeSpec? = null
    suspend fun graphicCaptcha(): ByteArray = withContext(Dispatchers.IO) {
        execute("/auth/showCaptchaImage?${System.currentTimeMillis()}", synchronized(lock) { epoch })
    }
    override fun clear() = synchronized(lock) {
        epoch++; calls.forEach { it.cancel() }; calls.clear(); cookies = emptyList(); prepared = null; pendingChallenge = null
    }
    private suspend fun execute(path: String, expected: Long, body: RequestBody? = null, validation: String? = null): ByteArray {
        val url = ORIGIN.resolve(path) ?: error("Invalid login URL")
        require(url.host == ORIGIN.host && url.isHttps && url.port == 443)
        val header = synchronized(lock) {
            check(epoch == expected)
            cookies.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
                .joinToString("; ") { "${it.name}=${it.value}" }
        }
        val builder = Request.Builder().url(url).header("User-Agent", "c001apk-native/0.1 (Android; independent client)")
            .header("X-Requested-With", if (body == null) "com.coolapk.market" else "XMLHttpRequest")
            .header("Referer", "https://account.coolapk.com/auth/loginByCoolApk")
        val cookieHeader = listOf(header, "forward=https://www.coolapk.com", validation?.let { "validate=$it" }.orEmpty()).filter { it.isNotEmpty() }.joinToString("; ")
        builder.header("Cookie", cookieHeader)
        if (body != null) builder.post(body)
        val call = http.newCall(builder.build())
        synchronized(lock) { check(epoch == expected); calls.add(call) }
        try {
            return await(call).use { response ->
                check(response.isSuccessful) { "Login request failed" }
                val responseBody = response.body ?: error("Empty login response")
                require(responseBody.contentLength() <= MAX_BYTES)
                val source = responseBody.source()
                source.request(MAX_BYTES + 1)
                require(source.buffer.size <= MAX_BYTES)
                val result = source.readByteArray()
                synchronized(lock) {
                    check(epoch == expected)
                    response.headers("Set-Cookie").forEach { value ->
                        CookieScope.parse(url, value, System.currentTimeMillis())?.let { incoming ->
                            cookies = cookies.filterNot { it.name == incoming.name && it.domain == incoming.domain && it.path == incoming.path } +
                                listOf(incoming).filter { it.expiresAt > System.currentTimeMillis() && it.value.isNotEmpty() }
                        }
                    }
                    require(cookies.size <= 100)
                }
                result
            }
        } finally { synchronized(lock) { calls.remove(call) } }
    }
    private suspend fun await(call: Call): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response, onCancellation = { _, value, _ -> value.close() })
            }
        })
    }
    companion object {
        private val ORIGIN = "https://account.coolapk.com/".toHttpUrl()
        private const val MAX_BYTES = 2L * 1024 * 1024
        internal fun explicitAccountChallenge(config: JsonObject): HumanChallengeSpec? = runCatching {
            val id = config["captchaId"]?.jsonPrimitive?.contentOrNull ?: return null
            val field = config["captchaField"]?.jsonPrimitive?.contentOrNull?.trim() ?: return null
            if (field.isBlank()) return null // Account cookie-proof contract is not established.
            HumanChallengeSpec(id, field)
        }.getOrNull()
        internal fun extractRequestHash(html: String): String? {
            // Only a quoted attribute on BODY is accepted; never execute server HTML or JavaScript.
            val body = Regex("<body\\b[^>]*>", RegexOption.IGNORE_CASE).find(html)?.value ?: return null
            val value = Regex("\\bdata-request-hash\\s*=\\s*([\"'])([^\"']+)\\1", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(2) ?: return null
            return value.takeIf { it.length <= 4096 && it.none { c -> c.isWhitespace() || c.code < 32 } }
        }
    }
}
