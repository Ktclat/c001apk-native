package io.github.ktclat.c001apk.nativeapp.account

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLDecoder
import java.util.UUID

/** Only the source-known official completion root may initiate credential extraction. */
internal object OfficialWebLoginPolicy {
    const val LOGIN = "https://account.coolapk.com/auth/login?type=mobile"
    const val COMPLETE = "https://www.coolapk.com/"
    private val root = COMPLETE.toHttpUrl()
    private val api = "https://api.coolapk.com/".toHttpUrl()
    fun isCompletion(value: String): Boolean = value.toHttpUrlOrNull()?.let {
        it.isHttps && it.host == root.host && it.port == 443 && it.username.isEmpty() && it.password.isEmpty() &&
            it.encodedPath == "/" && it.query == null && it.fragment == null
    } == true
    fun allowsNavigation(value: String): Boolean = value.toHttpUrlOrNull()?.let {
        it.isHttps && it.port == 443 && it.username.isEmpty() && it.password.isEmpty() &&
            ((it.host == "account.coolapk.com" && it.encodedPath.startsWith("/auth/")) || isCompletion(value))
    } == true
    fun allowsResource(value: String): Boolean = value.toHttpUrlOrNull()?.let { url ->
        url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
            listOf("coolapk.com", "126.net", "127.net", "163.com", "163yun.com").any { url.host == it || url.host.endsWith(".$it") }
    } == true
    /** Attributes come from profile-specific GET_COOKIE_INFO, never lossy CookieManager.getCookie.
     * Do not widen www host-only cookies or import vendor cookies / validate proofs. */
    fun credentials(headers: List<String>, now: Long): LoginReply.Authenticated? = runCatching {
        require(headers.size <= 100 && headers.all { it.length <= 8192 })
        val known = headers.mapNotNull { Cookie.parse(root, it) }.filter { it.name in setOf("uid", "username", "token") }
        require(known.size == 3 && known.map { it.name }.toSet().size == 3)
        require(known.all { it.matches(root) && it.matches(api) && it.domain == "coolapk.com" && !it.hostOnly && it.path == "/" && it.expiresAt > now && it.value.length <= 4096 })
        val id = known.single { it.name == "uid" }.value
        require(id.matches(Regex("[0-9]{1,30}")))
        require(known.single { it.name == "token" }.value.isNotBlank())
        val encodedName = known.single { it.name == "username" }.value
        val name = URLDecoder.decode(encodedName.replace("+", "%2B"), "UTF-8")
        require(name.isNotBlank() && name.length <= 1024 && name.none { it.code < 32 })
        val cookies = known.map { CookieScope.withExpiry(it, if (it.persistent) it.expiresAt else now + 7L * 86_400_000, forceSecure = true) }
        LoginReply.Authenticated(id, name, cookies)
    }.getOrNull()
}

/** Pure one-shot gate. No credentials can leave it before successful scoped cleanup.
 * Host must cancel on disposal, account change, navigation error, timeout, and user cancellation. */
internal class OfficialWebLoginGate(private val elapsedNanos: () -> Long = System::nanoTime) {
    val nonce: String = UUID.randomUUID().toString()
    private val created = elapsedNanos()
    private enum class Stage { ACTIVE, CLEANING, CONSUMED, IMPORTING, CANCELLED }
    private var stage = Stage.ACTIVE
    private var pending: LoginReply.Authenticated? = null
    @Synchronized fun beginCompletion(callbackNonce: String, url: String, cookieInfo: List<String>, now: Long): Boolean {
        if (callbackNonce != nonce || stage != Stage.ACTIVE || expired() || !OfficialWebLoginPolicy.isCompletion(url)) return false
        val credentials = OfficialWebLoginPolicy.credentials(cookieInfo, now) ?: return false
        pending = credentials; stage = Stage.CLEANING
        return true
    }
    @Synchronized fun cleanupFinished(callbackNonce: String, successful: Boolean, sessionCurrent: Boolean): LoginReply.Authenticated? {
        if (callbackNonce != nonce || stage != Stage.CLEANING) return null
        if (!successful || !sessionCurrent || expired()) { cancel(); return null }
        stage = Stage.CONSUMED
        return pending.also { pending = null }
    }
    /** Linearization point immediately before the atomic vault transaction, not before IO dispatch. */
    @Synchronized fun claimImport(): Boolean {
        if (stage != Stage.CONSUMED || expired()) return false
        stage = Stage.IMPORTING
        return true
    }
    @Synchronized fun cancel() { stage = Stage.CANCELLED; pending = null }
    @Synchronized fun isActive() = stage == Stage.ACTIVE && !expired()
    private fun expired() = elapsedNanos() - created > 20L * 60 * 1_000_000_000
    override fun toString() = "OfficialWebLoginGate(redacted)"
}
