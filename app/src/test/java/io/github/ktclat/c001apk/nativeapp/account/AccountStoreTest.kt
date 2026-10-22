package io.github.ktclat.c001apk.nativeapp.account

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class AccountStoreTest {
    private class Memory : CredentialPersistence {
        var value: ByteArray? = null
        var fail = false
        override fun read() = value?.copyOf()
        override fun write(bytes: ByteArray) { if (fail) error("disk full"); value = bytes.copyOf() }
        override fun reset() { value = null }
    }
    private val now = 1_700_000_000_000L
    private val api = "https://api.coolapk.com/v6/feed".toHttpUrl()
    private fun login(store: AccountStore, id: String) {
        val cookie = CookieScope.withExpiry(CookieScope.parse(api, "token=synthetic$id; Domain=coolapk.com; Path=/; Secure", now)!!, now + 7L * 86_400_000)
        assertTrue(store.saveAuthenticated(id, "Account $id", "local-device", listOf(cookie), store.snapshot()))
    }
    @Test fun switchingRejectsOldResponsesIncludingSwitchBack() {
        val store = AccountStore(Memory()) { now }; login(store, "1")
        val old = store.snapshot(); login(store, "2"); store.switchTo("1")
        assertFalse(store.isCurrent(old)); assertFalse(store.acceptCookies(old, api, listOf("SESSID=late; Secure")))
        assertFalse(store.logout(old)); assertEquals("1", store.state.value.activeId)
        store.useGuest(); assertEquals("", store.snapshot().cookieHeader(api, now))
        store.remove("2"); assertEquals(1, store.state.value.accounts.size)
    }
    @Test fun scopesCookiesByDomainPathExpiryAndScheme() {
        val store = AccountStore(Memory()) { now }; login(store, "1")
        store.acceptCookies(store.snapshot(), api, listOf("SESSID=onlyapi; Path=/v6/feed; Secure"))
        val s = store.snapshot()
        assertTrue(s.cookieHeader(api, now).contains("SESSID"))
        assertFalse(s.cookieHeader("https://api2.coolapk.com/v6/feed".toHttpUrl(), now).contains("SESSID"))
        assertFalse(s.cookieHeader("https://api.coolapk.com/v6/feedx".toHttpUrl(), now).contains("SESSID"))
        listOf("http://api.coolapk.com/v6/feed", "https://api.coolapk.com:444/v6/feed", "https://evil.coolapk.com/v6/feed", "https://github.com/").forEach {
            assertEquals("", s.cookieHeader(it.toHttpUrl(), now))
        }
        assertEquals("", s.cookieHeader(api, now + 8 * 86_400_000L))
        assertNull(CookieScope.parse(api, "bad=x; Domain=evil.com", now))
    }
    @Test fun revisionStopsOlderConcurrentCookieWrites() {
        val store = AccountStore(Memory()) { now }; login(store, "1")
        val first = store.snapshot(); val concurrent = store.snapshot()
        assertTrue(store.acceptCookies(first, api, listOf("SESSID=new; Path=/; Secure")))
        assertFalse(store.acceptCookies(concurrent, api, listOf("SESSID=old; Path=/; Secure")))
        assertTrue(store.isCurrent(first)) // Data is still current; only stale cookie mutation is refused.
    }
    @Test fun persistenceRestoresHostOnlyScopeAndLogout() {
        val memory = Memory(); val store = AccountStore(memory) { now }; login(store, "1")
        store.acceptCookies(store.snapshot(), api, listOf("SESSID=onlyapi; Path=/; Secure"))
        val restored = AccountStore(memory) { now }
        assertTrue(restored.snapshot().cookieHeader(api, now).contains("SESSID"))
        assertFalse(restored.snapshot().cookieHeader("https://api2.coolapk.com/".toHttpUrl(), now).contains("SESSID"))
        restored.logout(restored.snapshot())
        assertTrue(restored.state.value.accounts.single().needsLogin)
        assertNull(restored.state.value.activeId)
    }
    @Test fun corruptionRequiresExplicitResetAndDoesNotOverwrite() {
        val memory = Memory().apply { value = "broken".toByteArray() }; val store = AccountStore(memory)
        assertTrue(store.state.value.recoveryRequired)
        assertFalse(store.isCurrent(store.snapshot()))
        try { store.useGuest(); fail() } catch (_: CredentialRecoveryRequired) { }
        assertEquals("broken", memory.value!!.decodeToString())
        store.resetAfterRecovery(); assertFalse(store.state.value.recoveryRequired)
    }
    @Test fun failedCommitLeavesIdentityAndGenerationUnchanged() {
        val memory = Memory(); val store = AccountStore(memory) { now }; login(store, "1")
        val s = store.snapshot(); memory.fail = true
        try { store.useGuest(); fail() } catch (_: IllegalStateException) { }
        assertTrue(store.isCurrent(s)); assertEquals("1", store.state.value.activeId)
    }
    @Test fun snapshotsFromAnotherStoreCannotAuthorizeMutation() {
        val first = AccountStore(Memory()); val second = AccountStore(Memory())
        assertFalse(first.isCurrent(second.snapshot()))
    }
    @Test fun expiredTokensRequireReloginAcrossColdStart() {
        val memory = Memory(); val store = AccountStore(memory) { now }; login(store, "1")
        val restored = AccountStore(memory) { now + 8L * 86_400_000 }
        assertNull(restored.state.value.activeId)
        assertTrue(restored.state.value.accounts.single().needsLogin)
        assertNull(restored.snapshot().accountId)
        try { restored.switchTo("1"); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun emptyResponseDoesNotInvalidatePendingCookieWrite() {
        val store = AccountStore(Memory()) { now }; login(store, "1")
        val first = store.snapshot(); val concurrent = store.snapshot()
        assertTrue(store.acceptCookies(first, api, emptyList()))
        assertTrue(store.acceptCookies(concurrent, api, listOf("SESSID=new; Path=/; Secure")))
    }
    @Test fun acceptedValidationBindsSessionAndSurvivesOnlyColdRestart() {
        val memory = Memory(); val store = AccountStore(memory) { now }; login(store, "1")
        store.acceptCookies(store.snapshot(), api, listOf("SESSID=synthetic; Path=/; Secure"))
        val snapshot = store.snapshot()
        val challenge = HumanChallengeSpec("1234567890abcdef", "")
        assertTrue(store.saveAcceptedValidation(snapshot, challenge, "NEC:12345678:synthetic", snapshot.sessionHash()))
        val restored = AccountStore(memory) { now }
        assertTrue(restored.snapshot().cookieHeader(api, now).contains("validate="))
        assertFalse(restored.snapshot().cookieHeader("https://account.coolapk.com/".toHttpUrl(), now).contains("validate="))
        restored.useGuest(); restored.switchTo("1")
        assertFalse(restored.snapshot().cookieHeader(api, now).contains("validate="))
        assertFalse(restored.saveAcceptedValidation(snapshot, challenge, "NEC:12345678:synthetic", snapshot.sessionHash()))
    }
    @Test fun olderValidationResponseCannotOverwriteNewAcceptedProof() {
        val store = AccountStore(Memory()) { now }; login(store, "1")
        store.acceptCookies(store.snapshot(), api, listOf("SESSID=synthetic; Path=/; Secure"))
        val older = store.snapshot(); val newer = store.snapshot()
        val challenge = HumanChallengeSpec("1234567890abcdef", "")
        assertTrue(store.saveAcceptedValidation(newer, challenge, "NEC:12345678:new", newer.sessionHash()))
        assertFalse(store.saveAcceptedValidation(older, challenge, "NEC:12345678:old", older.sessionHash()))
        assertTrue(store.snapshot().cookieHeader(api, now).contains("validate=NEC:12345678:new"))
    }
    @Test fun ordinaryValidateCookieCannotBypassProofBinding() {
        val memory = Memory(); val store = AccountStore(memory) { now }; login(store, "1")
        assertNull(CookieScope.parse(api, "validate=unbound; Domain=.coolapk.com; Path=/; Secure", now))
        assertTrue(store.acceptCookies(store.snapshot(), api, listOf("SESSID=first; Path=/; Secure", "validate=unbound; Domain=.coolapk.com; Path=/; Secure")))
        val before = store.snapshot()
        assertFalse(before.cookieHeader(api, now).contains("validate="))
        assertTrue(store.saveAcceptedValidation(before, HumanChallengeSpec("1234567890abcdef", ""), "NEC:12345678:bound", before.sessionHash()))
        assertTrue(store.acceptCookies(store.snapshot(), api, listOf("SESSID=rotated; Path=/; Secure", "validate=unbound; Domain=.coolapk.com; Path=/; Secure")))
        val restored = AccountStore(memory) { now }
        assertFalse(restored.snapshot().cookieHeader(api, now).contains("validate="))
        assertFalse(restored.snapshot().cookieHeader("https://account.coolapk.com/".toHttpUrl(), now).contains("validate="))
    }
    @Test fun importedValidateCookieIsDiscardedAndCorruptReservedCookieFailsClosed() {
        val memory = Memory(); val store = AccountStore(memory) { now }
        val token = Cookie.parse(api, "token=synthetic; Domain=.coolapk.com; Path=/; Secure")!!
        val unbound = Cookie.parse(api, "validate=unbound; Domain=.coolapk.com; Path=/; Secure")!!
        assertTrue(store.saveAuthenticated("1", "Test", "device", listOf(token, unbound), store.snapshot()))
        assertFalse(store.snapshot().cookieHeader(api, now).contains("validate="))
        assertFalse(memory.value!!.decodeToString().contains("validate="))
        memory.value = memory.value!!.decodeToString().replace("token=synthetic", "validate=unbound").encodeToByteArray()
        val corrupted = AccountStore(memory) { now }
        assertTrue(corrupted.state.value.recoveryRequired)
        assertEquals("", corrupted.snapshot().cookieHeader(api, now))
    }

}
