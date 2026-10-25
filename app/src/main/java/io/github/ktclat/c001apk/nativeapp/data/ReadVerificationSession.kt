// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

import okhttp3.Cookie
import okhttp3.HttpUrl

/** A single read's private cookie overlay, not a WebView/global/account cookie jar.
 * Only the source API's SESSID can advance. uid/token/validate from a challenge, provider
 * or another WebView are never imported. Proof is attached by the one-shot transport only.
 */
internal class ReadVerificationSession private constructor(private val source: RequestSession) {
    private var closed = false
    private val cookies = mutableListOf<Cookie>()
    private fun owned() = !closed && source.isCurrent() &&
        (source.isReadSessionCurrent ?: source.isSessionCurrent)?.invoke() == true
    val session: RequestSession = source.copy(
        cookieFor = { url -> header(url) },
        isCurrent = { synchronized(this) { owned() } },
        isSessionCurrent = { synchronized(this) { owned() } },
        acceptCookies = { url, headers -> accept(url, headers) },
        acceptChallengeCookies = { url, headers -> if (accept(url, headers)) sessionValue() else null },
    )
    private fun sessionValue(): RequestSession = session
    @Synchronized private fun header(url: HttpUrl): String? =
        if (!owned()) null else ReadCookiePolicy.header(source, url, cookies)
    @Synchronized private fun accept(url: HttpUrl, headers: List<String>): Boolean {
        if (!owned() || !ReadCookiePolicy.allowed(url)) return false
        val updated = ReadCookiePolicy.update(cookies, url, headers)
        cookies.clear(); cookies.addAll(updated)
        return true
    }
    @Synchronized fun snapshotCookies(): List<Cookie> = if (owned()) ReadCookiePolicy.merge(source.readSessionCookies?.invoke().orEmpty(), cookies) else emptyList()
    @Synchronized fun close() { closed = true; cookies.clear() }
    companion object {
        fun capture(source: RequestSession): ReadVerificationSession? {
            if (source.deviceCode.isNullOrBlank() || !source.isCurrent() ||
                (source.isReadSessionCurrent ?: source.isSessionCurrent)?.invoke() != true) return null
            return ReadVerificationSession(source)
        }
    }
}

/** One allowlisted cookie policy for private verification and the accepted short-lived read session. */
internal object ReadCookiePolicy {
    fun allowed(url: HttpUrl) = (url.isHttps && url.port == 443 && url.host in setOf("api.coolapk.com", "api2.coolapk.com")) ||
        url.host in setOf("localhost", "127.0.0.1", "::1") // Injected loopback tests only.
    fun header(source: RequestSession, url: HttpUrl, cookies: List<Cookie>, validation: String? = null): String? {
        if (!allowed(url)) return null
        val names = if (source.isGuest) setOf("SESSID") else setOf("uid", "token", "username", "SESSID")
        val base = source.cookieFor(url).orEmpty().split(';').map { it.trim() }
            .filter { it.substringBefore('=') in names && '=' in it && it.none { c -> c.code < 32 || c.code == 127 } }
        val overrides = cookies.filter { it.matches(url) }
        val active = overrides.filter { it.expiresAt > System.currentTimeMillis() && it.value.isNotEmpty() }
        return (base.filterNot { part -> overrides.any { it.name == part.substringBefore('=') } } +
            active.map { "${it.name}=${it.value}" } + listOfNotNull(validation?.let { "validate=$it" })).joinToString("; ").ifBlank { null }
    }
    fun update(current: List<Cookie>, url: HttpUrl, headers: List<String>): List<Cookie> {
        if (!allowed(url)) return current
        val cookies = current.toMutableList()
        headers.take(100).forEach { header ->
            if (header.length > 8192) return@forEach
            val cookie = Cookie.parse(url, header) ?: return@forEach
            if (cookie.name != "SESSID" || cookie.value.length > 4096 || cookie.path.length > 256 ||
                cookie.domain !in setOf(url.host, "coolapk.com") || cookie.value.any { it.isWhitespace() || it.code < 32 || it.code == 127 }) return@forEach
            cookies.removeAll { it.domain == cookie.domain && it.path == cookie.path }
            if (cookies.size < 100) cookies += cookie
        }
        return cookies
    }
    fun merge(current: List<Cookie>, accepted: List<Cookie>): List<Cookie> {
        val cookies = current.toMutableList()
        accepted.filter { it.name == "SESSID" }.take(100).forEach { incoming ->
            cookies.removeAll { it.domain == incoming.domain && it.path == incoming.path }
            if (cookies.size < 100) cookies += incoming
        }
        return cookies
    }
}
