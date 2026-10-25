// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

import okhttp3.Cookie
import kotlinx.coroutines.CancellationException
import java.net.URLEncoder

/** Accepted read validation + SESSID only. No account credentials, WebView jar or disk access.
 * Continuations remain one-shot; only an accepted blank-field validate cookie can serve later
 * public API GETs, matching Flutter's 24h maximum but deliberately remaining memory-only.
 */
internal class ReadSessionMemory(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    internal class Capture internal constructor(val raw: RequestSession, internal var version: Long) {
        lateinit var session: RequestSession
        internal var validationRevoked = false
    }
    private class Entry(val raw: RequestSession, val cookies: List<Cookie>, val validation: String?, val deadline: Long, val validationExpiry: Long)
    private var version = 0L
    private var binding: RequestSession? = null
    private var entry: Entry? = null
    private fun strict(raw: RequestSession) = raw.isCurrent() && (raw.isReadSessionCurrent ?: raw.isSessionCurrent)?.invoke() == true
    private fun sameOwner(a: RequestSession, b: RequestSession) = a.readOwner != null && a.readOwner === b.readOwner &&
        a.diagnosticGeneration != null && a.diagnosticGeneration == b.diagnosticGeneration && a.deviceCode == b.deviceCode && a.isGuest == b.isGuest
    private fun drop() { entry = null; binding = null; version++ }
    private fun expire() {
        val existing = entry ?: return
        if (clock() >= existing.deadline || !strict(existing.raw)) drop()
        else if (existing.validation != null && wallClock() >= existing.validationExpiry) {
            // Keep expired-cookie tombstones, but never send validation for a different/expired session.
            entry = Entry(existing.raw, existing.cookies, null, existing.deadline, Long.MAX_VALUE); version++
        }
    }
    @Synchronized fun capture(raw: RequestSession): Capture {
        expire()
        if (binding?.let { !strict(it) || (it.readOwner != null && !sameOwner(it, raw)) } == true) drop()
        binding = raw
        val captured = Capture(raw, version)
        val accepted = entry?.takeIf { sameOwner(it.raw, raw) }
        val cookies = accepted?.cookies.orEmpty()
        fun validation(): String? = if (!captured.validationRevoked && current(raw, captured.version)) accepted?.validation else null
        captured.session = raw.copy(
            cookieFor = { url -> synchronized(this) {
                if (accepted == null) raw.cookieFor(url)
                else {
                    if (!current(raw, captured.version)) throw CancellationException("Read session changed")
                    ReadCookiePolicy.header(raw, url, cookies, validation())
                }
            } },
            readSessionCookies = { ReadCookiePolicy.merge(raw.readSessionCookies?.invoke().orEmpty(), cookies) },
            readValidationCookie = { synchronized(this) { validation() } },
            // Ordinary read publication is generation-bound, not invalidated by its own Set-Cookie.
            isCurrent = raw.isCurrent,
            isReadSessionCurrent = { synchronized(this) { current(raw, captured.version) } },
            acceptCookies = { url, headers -> synchronized(this) {
                if (current(raw, captured.version)) {
                    if (accepted == null) raw.acceptCookies(url, headers)
                    else entry?.let { old ->
                        val updated = ReadCookiePolicy.update(old.cookies, url, headers)
                        // A changed server session cannot keep an old validation. Rotation does not extend TTL.
                        if (updated != old.cookies) { entry = Entry(raw, updated, null, old.deadline, Long.MAX_VALUE); version++ }
                    }
                }
            } },
        )
        return captured
    }
    private fun current(raw: RequestSession, expected: Long): Boolean {
        expire()
        return version == expected && strict(raw)
    }
    /** Only this exact rejected read advances; late challenges cannot erase newer accepted state. */
    @Synchronized fun revokeOnChallenge(capture: Capture): Boolean {
        if (!current(capture.raw, capture.version)) return false
        entry = null; version++
        capture.version = version; capture.validationRevoked = true
        return true
    }
    @Synchronized fun publish(capture: Capture, cookies: List<Cookie>, proof: String? = null): Boolean {
        if (!current(capture.raw, capture.version)) return false
        // Ownerless adapters may verify one read; they never gain reusable session state.
        if (capture.raw.readOwner == null || capture.raw.diagnosticGeneration == null || capture.raw.deviceCode.isNullOrBlank()) return true
        val validation = proof?.let {
            if (it.length !in 14..8205 || !it.startsWith("NEC:") || it.any { c -> c.isWhitespace() || c.code < 32 || c.code == 127 }) return false
            URLEncoder.encode(it, "UTF-8")
        }
        if (cookies.isEmpty() && validation == null) return true
        val prior = entry?.takeIf { sameOwner(it.raw, capture.raw) }?.cookies.orEmpty()
        val merged = ReadCookiePolicy.merge(prior, cookies)
        val validationExpiry = merged.minOfOrNull { it.expiresAt } ?: Long.MAX_VALUE
        entry = Entry(capture.raw, merged, validation?.takeIf { wallClock() < validationExpiry }, clock() + 86_400_000, validationExpiry)
        binding = capture.raw; version++
        return true
    }
}
