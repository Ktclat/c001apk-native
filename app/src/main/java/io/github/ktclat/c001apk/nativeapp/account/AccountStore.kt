package io.github.ktclat.c001apk.nativeapp.account

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.HttpUrl

/** Public state deliberately excludes all cookies and device credentials. */
data class AccountSummary(val id: String, val name: String, val needsLogin: Boolean)
data class AccountState(val accounts: List<AccountSummary> = emptyList(), val activeId: String? = null,
    val generation: Long = 0, val recoveryRequired: Boolean = false)

@Serializable
internal class AccountRecord(val id: String, val name: String, val deviceId: String,
    val cookies: List<String>, val needsLogin: Boolean = false, val validation: AcceptedValidation? = null)
@Serializable
internal class AcceptedValidation(val proof: String, val sessionHash: String, val expiresAt: Long)
@Serializable
internal class GuestRecord(val deviceId: String, val cookies: List<String>)
@Serializable
internal class Vault(val version: Int = 2, val accounts: List<AccountRecord> = emptyList(), val activeId: String? = null,
    val guest: GuestRecord? = null)

/** Implementations must encrypt before writing. A failure must not replace the existing vault. */
interface CredentialPersistence { fun read(): ByteArray?; fun write(bytes: ByteArray); fun reset() }
class CredentialRecoveryRequired : IllegalStateException("Credential storage unavailable; explicit reset and re-login required")

/** Not a data class: accidental logging never prints credentials. */
class SessionSnapshot internal constructor(val accountId: String?, val generation: Long,
    val deviceId: String?, internal val cookies: List<Cookie>, internal val owner: Any, internal val revision: Long, private val validation: AcceptedValidation?) {
    fun cookieHeader(url: HttpUrl, now: Long = System.currentTimeMillis()): String =
        if (!CookieScope.allows(url) || (accountId == null && !CookieScope.allowsGuest(url))) "" else cookies.filter { it.name != "validate" && it.expiresAt > now && it.matches(url) }
            .sortedByDescending { it.path.length }.joinToString("; ") { "${it.name}=${it.value}" }.let { header ->
                val accepted = validation?.takeIf { url.host in setOf("api.coolapk.com", "api2.coolapk.com") && it.expiresAt > now && it.sessionHash == sessionHash() && cookies.any { c -> c.name == "SESSID" && c.expiresAt > now && c.matches(url) } }
                if (accepted == null) header else "$header; validate=${accepted.proof}"
            }
    /** Exact authenticated identity/session, including cookie scope and expiry. No secret is exposed. */
    fun sameIdentityAndSession(other: SessionSnapshot): Boolean = sameIdentityAndCredentials(other) &&
        cookies.filter { it.name == "SESSID" }.toSet() == other.cookies.filter { it.name == "SESSID" }.toSet()
    internal fun sameIdentityAndCredentials(other: SessionSnapshot): Boolean = owner === other.owner &&
        accountId != null && accountId == other.accountId && generation == other.generation && deviceId == other.deviceId &&
        cookies.filter { it.name in setOf("uid", "token") }.toSet() == other.cookies.filter { it.name in setOf("uid", "token") }.toSet()
    fun sessionHash(): String = sessionFingerprint(cookies)
    override fun toString() = "SessionSnapshot(generation=$generation, authenticated=${accountId != null})"
}

object CookieScope {
    private val hosts = setOf("api.coolapk.com", "api2.coolapk.com", "account.coolapk.com")
    fun allows(url: HttpUrl) = url.isHttps && url.port == 443 && url.host in hosts
    fun allowsGuest(url: HttpUrl) = allows(url) && url.host in setOf("api.coolapk.com", "api2.coolapk.com")
    fun parse(url: HttpUrl, header: String, now: Long): Cookie? {
        if (!allows(url) || header.length > 8192) return null
        val cookie = Cookie.parse(url, header) ?: return null
        if (cookie.name == "validate") return null // Reserved for session-bound, server-accepted proof only.
        if (cookie.domain != "coolapk.com" && cookie.domain != url.host) return null
        if (cookie.path.length > 256 || cookie.value.length > 4096) return null
        // Even poorly scoped server cookies are never sent over cleartext or to non-API hosts.
        val cappedExpiry = if (cookie.name == "SESSID") minOf(cookie.expiresAt, now + 7L * 24 * 60 * 60 * 1000) else cookie.expiresAt
        return withExpiry(cookie, cappedExpiry, forceSecure = true)
    }
    internal fun withExpiry(cookie: Cookie, expiresAt: Long, forceSecure: Boolean = false): Cookie = Cookie.Builder()
        .name(cookie.name).value(cookie.value).path(cookie.path).expiresAt(expiresAt).apply {
            if (cookie.hostOnly) hostOnlyDomain(cookie.domain) else domain(cookie.domain)
            if (cookie.secure || forceSecure) secure()
            if (cookie.httpOnly) httpOnly()
        }.build()
}

/** Serialize all mutations and disk commits under one monitor. Call on an IO dispatcher. */
class AccountStore(private val persistence: CredentialPersistence, private val guestDeviceId: String? = null, private val clock: () -> Long = System::currentTimeMillis) {
    private val owner = Any()
    private var vault = Vault()
    private var revision = 0L
    private val mutableState = MutableStateFlow(AccountState())
    val state: StateFlow<AccountState> = mutableState.asStateFlow()
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    init {
        try {
            persistence.read()?.let { bytes ->
                try {
                    val restored = json.decodeFromString<Vault>(bytes.decodeToString())
                    require(restored.version in 1..2 && restored.accounts.size <= 100)
                    require(restored.accounts.map { it.id }.distinct().size == restored.accounts.size)
                    restored.accounts.forEach { validate(it) }
                    restored.guest?.let(::validateGuest)
                    require(restored.activeId == null || restored.guest == null)
                    require(restored.activeId == null || restored.accounts.any { it.id == restored.activeId && !it.needsLogin })
                    // A device change cannot reuse an earlier anonymous session. Never rotate account devices.
                    vault = if (restored.guest?.deviceId != guestDeviceId) Vault(accounts = restored.accounts, activeId = restored.activeId) else restored
                } finally { bytes.fill(0) }
            }
            publish()
            expireTokens()
        } catch (_: Exception) { mutableState.value = AccountState(recoveryRequired = true) }
    }
    @Synchronized fun snapshot(): SessionSnapshot {
        if (!state.value.recoveryRequired) expireTokens()
        val record = if (state.value.recoveryRequired) null else vault.accounts.firstOrNull { it.id == vault.activeId }
        val guest = vault.guest?.takeIf { record == null && !state.value.recoveryRequired && it.deviceId == guestDeviceId }
        return SessionSnapshot(record?.id, state.value.generation, record?.deviceId ?: guestDeviceId?.takeUnless { state.value.recoveryRequired },
            (record?.cookies ?: guest?.cookies)?.mapNotNull { restoreCookie(it) } ?: emptyList(), owner, revision, record?.validation)
    }
    @Synchronized fun isCurrent(snapshot: SessionSnapshot): Boolean {
        if (!state.value.recoveryRequired) expireTokens()
        return snapshot.owner === owner && snapshot.generation == state.value.generation && snapshot.accountId == vault.activeId && !state.value.recoveryRequired
    }

    /** Only call following a server-confirmed login; not a synthetic success or an entered username. */
    @Synchronized fun saveAuthenticated(id: String, name: String, deviceId: String, cookies: List<Cookie>, expected: SessionSnapshot): Boolean {
        if (!isCurrent(expected)) return false
        val bounded = cookies.filter { it.name != "validate" && it.expiresAt > clock() }.map {
            CookieScope.withExpiry(it, if (it.name == "SESSID") minOf(it.expiresAt, clock() + 7L * 86_400_000) else it.expiresAt)
        }
        val record = AccountRecord(id, name, deviceId, bounded.map { encodeCookie(it) })
        validate(record)
        require(bounded.any { usableApiToken(it, clock()) }) { "Login token is not usable by the API" }
        commit(Vault(accounts = vault.accounts.filterNot { it.id == id } + record, activeId = id), true)
        return true
    }
    @Synchronized fun switchTo(id: String) {
        expireTokens()
        require(vault.accounts.any { it.id == id && !it.needsLogin }) { "Account requires login" }
        if (vault.activeId != id) commit(Vault(accounts = vault.accounts, activeId = id), true)
    }
    @Synchronized fun useGuest() { commit(Vault(accounts = vault.accounts), true) }
    @Synchronized fun remove(id: String) { commit(Vault(accounts = vault.accounts.filterNot { it.id == id },
        activeId = vault.activeId.takeUnless { it == id }, guest = vault.guest), vault.activeId == id) }
    /** Local logout forgets this account's credentials, retaining a re-login entry. No network call. */
    @Synchronized fun logout(expected: SessionSnapshot): Boolean {
        if (!isCurrent(expected)) return false
        commit(Vault(accounts = vault.accounts.map { if (it.id == expected.accountId)
            AccountRecord(it.id, it.name, it.deviceId, emptyList(), true) else it }), true)
        return true
    }
    @Synchronized fun acceptCookies(expected: SessionSnapshot, url: HttpUrl, headers: List<String>): Boolean {
        if (!isCurrent(expected) || expected.revision != revision || !CookieScope.allows(url)) return false
        if (expected.accountId == null) return acceptGuestCookies(url, headers)
        val current = vault.accounts.first { it.id == expected.accountId }
        val cookies = current.cookies.mapNotNull { restoreCookie(it) }.toMutableList()
        headers.forEach { header -> CookieScope.parse(url, header, clock())?.let { incoming ->
            cookies.removeAll { it.name == incoming.name && it.domain == incoming.domain && it.path == incoming.path }
            if (incoming.expiresAt > clock() && incoming.value.isNotEmpty()) cookies.add(incoming)
        } }
        val updated = AccountRecord(current.id, current.name, current.deviceId,
            cookies.filter { it.expiresAt > clock() }.map { encodeCookie(it) }, current.needsLogin, current.validation?.takeIf { it.sessionHash == sessionFingerprint(cookies) })
        if (updated.cookies == current.cookies) return true
        commit(Vault(accounts = vault.accounts.map { if (it.id == current.id) updated else it }, activeId = vault.activeId), false)
        return true
    }
    /** Anonymous API sessions are a separate jar, never promoted to authenticated credentials. */
    private fun acceptGuestCookies(url: HttpUrl, headers: List<String>): Boolean {
        val device = guestDeviceId?.takeIf { it.isNotBlank() && it.length <= 8192 } ?: return false
        if (!CookieScope.allowsGuest(url)) return false
        val now = clock()
        val current = vault.guest?.takeIf { it.deviceId == device }?.cookies.orEmpty()
        val cookies = current.mapNotNull(::restoreCookie).filter { it.expiresAt > now }.toMutableList()
        headers.forEach { header -> CookieScope.parse(url, header, now)?.takeIf {
            it.name == "SESSID" && (it.value.isEmpty() || it.expiresAt <= now || guestValue.matches(it.value))
        }?.let { incoming ->
            cookies.removeAll { it.name == incoming.name && it.domain == incoming.domain && it.path == incoming.path }
            if (incoming.expiresAt > now && incoming.value.isNotEmpty()) cookies.add(incoming)
        } }
        if (cookies.size > 100) return false
        val encoded = cookies.map(::encodeCookie)
        if (encoded == current) return true
        commit(Vault(accounts = vault.accounts, activeId = vault.activeId,
            guest = encoded.takeIf { it.isNotEmpty() }?.let { GuestRecord(device, it) }), false)
        return true
    }
    /** Only the transport's source-known challenge/image/validation response may use this handoff.
     * Revision gate and capture share one monitor: unrelated responses cannot be silently adopted. */
    @Synchronized fun acceptChallengeCookies(expected: SessionSnapshot, url: HttpUrl, headers: List<String>): SessionSnapshot? {
        if (!isCurrent(expected) || expected.accountId == null || expected.revision != revision ||
            url.host !in setOf("api.coolapk.com", "api2.coolapk.com") || !CookieScope.allows(url)) return null
        val before = snapshot()
        // A challenge is never permission to change or renew account credentials.
        val credentialUpdates = headers.mapNotNull { CookieScope.parse(url, it, clock()) }.filter { it.name in setOf("uid", "token") }
        if (credentialUpdates.any { incoming -> incoming !in before.cookies }) return null
        if (!acceptCookies(expected, url, headers)) return null
        val after = snapshot()
        return after.takeIf { before.sameIdentityAndCredentials(it) }
    }
    /** Store only after the server accepted this proof; never on mere challenge completion. */
    @Synchronized fun saveAcceptedValidation(expected: SessionSnapshot, challenge: HumanChallengeSpec, proof: String, acceptedSessionHash: String): Boolean {
        if (!isCurrent(expected) || expected.accountId == null || expected.revision != revision || !challenge.accepts(proof)) return false
        val current = vault.accounts.first { it.id == expected.accountId }
        val cookies = current.cookies.mapNotNull(::restoreCookie)
        if (cookies.none { it.name == "SESSID" && it.expiresAt > clock() } || acceptedSessionHash != sessionFingerprint(cookies)) return false
        val accepted = AcceptedValidation(proof, acceptedSessionHash, clock() + 86_400_000)
        val updated = AccountRecord(current.id, current.name, current.deviceId, current.cookies, current.needsLogin, accepted)
        commit(Vault(accounts = vault.accounts.map { if (it.id == current.id) updated else it }, activeId = vault.activeId), false)
        return true
    }
    /** Explicit user-confirmed recovery. Never called automatically on key loss/corruption. */
    @Synchronized fun resetAfterRecovery() {
        persistence.reset(); vault = Vault()
        mutableState.value = AccountState(generation = state.value.generation + 1)
    }
    private fun expireTokens() {
        val changed = vault.accounts.map { record ->
            if (!record.needsLogin && record.cookies.mapNotNull(::restoreCookie).none {
                    usableApiToken(it, clock())
                }) AccountRecord(record.id, record.name, record.deviceId, emptyList(), true) else record
        }
        if (changed == vault.accounts) return
        val active = vault.activeId.takeIf { id -> changed.any { it.id == id && !it.needsLogin } }
        commit(Vault(accounts = changed, activeId = active, guest = vault.guest), active != vault.activeId)
    }
    private fun validateGuest(record: GuestRecord) {
        require(record.deviceId.isNotBlank() && record.deviceId.length <= 8192 && record.cookies.size <= 100)
        record.cookies.forEach { serialized ->
            val cookie = restoreCookie(serialized) ?: error("Malformed guest session")
            require(cookie.name == "SESSID" && guestValue.matches(cookie.value))
            require(cookie.domain in setOf("coolapk.com", "api.coolapk.com", "api2.coolapk.com"))
            require(cookie.secure && cookie.path.length <= 256)
        }
    }
    private fun validate(record: AccountRecord) {
        require(record.id.matches(Regex("[0-9]{1,30}")) && record.name.length <= 1024)
        require(record.deviceId.isNotBlank() && record.deviceId.length <= 8192 && record.cookies.size <= 100)
        record.validation?.let { require(it.proof.length <= 8205 && it.proof.none { c -> c.isWhitespace() || c.code < 32 || c.code >= 127 || c in ";,\"\\" }) }
        record.cookies.forEach { serialized ->
            val cookie = restoreCookie(serialized) ?: error("Malformed cookie")
            require(cookie.domain in setOf("coolapk.com", "api.coolapk.com", "api2.coolapk.com", "account.coolapk.com"))
            require(cookie.secure && cookie.path.length <= 256 && cookie.value.length <= 4096)
        }
    }
    private fun commit(proposed: Vault, invalidate: Boolean) {
        val next = if (invalidate) Vault(accounts = proposed.accounts.map { record ->
            if (record.validation == null) record else AccountRecord(record.id, record.name, record.deviceId, record.cookies, record.needsLogin)
        }, activeId = proposed.activeId) else proposed
        if (state.value.recoveryRequired) throw CredentialRecoveryRequired()
        require(next.accounts.size <= 100)
        next.accounts.forEach(::validate)
        next.guest?.let(::validateGuest)
        require(next.activeId == null || next.guest == null)
        val bytes = json.encodeToString(Vault.serializer(), next).encodeToByteArray()
        try { require(bytes.size <= 1024 * 1024); persistence.write(bytes) } finally { bytes.fill(0) }
        vault = next; revision++; publish(if (invalidate) 1 else 0)
    }
    private fun publish(increment: Long = 0) { mutableState.value = AccountState(
        vault.accounts.map { AccountSummary(it.id, it.name, it.needsLogin) }, vault.activeId, state.value.generation + increment) }
    companion object {
        private val guestValue = Regex("^[A-Za-z0-9,._~%+/-]{1,512}$")
        private val apiAuthUrls = listOf("api.coolapk.com", "api2.coolapk.com").map {
            okhttp3.HttpUrl.Builder().scheme("https").host(it).addPathSegment("v6").addPathSegment("").build()
        }
        private fun usableApiToken(cookie: Cookie, now: Long): Boolean = cookie.name == "token" &&
            cookie.value.isNotBlank() && cookie.expiresAt > now && apiAuthUrls.any(cookie::matches)
        private fun encodeCookie(cookie: Cookie): String = "${cookie.domain}|$cookie"
        private fun restoreCookie(value: String): Cookie? {
            val host = value.substringBefore('|')
            if (host !in setOf("coolapk.com", "account.coolapk.com", "api.coolapk.com", "api2.coolapk.com")) return null
            return Cookie.parse(okhttp3.HttpUrl.Builder().scheme("https").host(host).build(), value.substringAfter('|'))?.takeUnless { it.name == "validate" }
        }
    }
}

private fun sessionFingerprint(cookies: List<Cookie>): String {
    val canonical = cookies.filter { it.name == "SESSID" }.map { "${it.domain}|${it.path}|${it.hostOnly}|${it.value}" }.sorted().joinToString("\n")
    return java.security.MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
}
