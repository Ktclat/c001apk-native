package io.github.ktclat.c001apk.nativeapp.account

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

/** Synthetic fixtures only. No network or real account credentials. */
class GuestSessionStoreTest {
    private class Memory : CredentialPersistence {
        var bytes: ByteArray? = null
        var fail = false
        override fun read() = bytes?.copyOf()
        override fun write(bytes: ByteArray) { if (fail) error("Synthetic disk failure"); this.bytes = bytes.copyOf() }
        override fun reset() { bytes = null }
    }
    private val now = 1_700_000_000_000L
    private val api = "https://api.coolapk.com/v6/feed/detail".toHttpUrl()
    private fun store(memory: Memory = Memory(), device: String = "synthetic-anonymous-device") =
        AccountStore(memory, guestDeviceId = device) { now }
    private fun login(store: AccountStore) {
        val token = CookieScope.parse(api, "token=synthetic-member; Domain=coolapk.com; Path=/; Secure", now)!!
        assertTrue(store.saveAuthenticated("1", "Synthetic", "synthetic-member-device", listOf(token), store.snapshot()))
    }
    @Test fun roundTripUsesSeparateJarAndStableAnonymousDevice() {
        val memory = Memory(); val store = store(memory)
        assertTrue(store.acceptCookies(store.snapshot(), api, listOf("SESSID=synthetic-guest; Path=/; Secure")))
        val restored = store(memory)
        assertNull(restored.state.value.activeId)
        assertTrue(restored.state.value.accounts.isEmpty())
        assertEquals("synthetic-anonymous-device", restored.snapshot().deviceId)
        assertEquals("SESSID=synthetic-guest", restored.snapshot().cookieHeader(api, now))
        assertEquals("", store(memory, "synthetic-changed-device").snapshot().cookieHeader(api, now))
        assertFalse(restored.snapshot().toString().contains("synthetic-guest"))
    }
    @Test fun honorsDomainPathSecureExpiryAndApiOriginAllowlist() {
        val store = store()
        assertTrue(store.acceptCookies(store.snapshot(), api, listOf("SESSID=synthetic-scoped; Path=/v6/feed; Secure")))
        val snapshot = store.snapshot()
        assertEquals("SESSID=synthetic-scoped", snapshot.cookieHeader(api, now))
        listOf("https://api2.coolapk.com/v6/feed/detail", "https://api.coolapk.com/v6/feedx",
            "http://api.coolapk.com/v6/feed", "https://api.coolapk.com:444/v6/feed",
            "https://account.coolapk.com/v6/feed", "https://evil.coolapk.com/v6/feed").forEach {
            assertEquals("", snapshot.cookieHeader(it.toHttpUrl(), now))
        }
        assertEquals("", snapshot.cookieHeader(api, now + 7L * 86_400_000))
        assertFalse(store.acceptCookies(store.snapshot(), "https://account.coolapk.com/".toHttpUrl(), listOf("SESSID=synthetic-injected; Domain=coolapk.com; Path=/")))
        assertTrue(store.acceptCookies(store.snapshot(), api, listOf("SESSID=synthetic-domain; Domain=coolapk.com; Path=/; Secure")))
        assertEquals("SESSID=synthetic-domain", store.snapshot().cookieHeader("https://api2.coolapk.com/".toHttpUrl(), now))
    }
    @Test fun ignoresCredentialsProofsMalformedValuesAndForeignDomains() {
        val store = store()
        assertTrue(store.acceptCookies(store.snapshot(), api, listOf("uid=123; Path=/", "token=synthetic; Path=/",
            "username=Synthetic; Path=/", "validate=synthetic; Path=/", "SESSID=synthetic; Domain=evil.com; Path=/",
            "SESSID=synthetic:invalid; Path=/")))
        assertEquals("", store.snapshot().cookieHeader(api, now))
        assertNull(store.acceptChallengeCookies(store.snapshot(), api, listOf("SESSID=synthetic-proof; Path=/")))
    }
    @Test fun deletionAndExpiryDoNotReviveOnRestart() {
        val memory = Memory(); val store = store(memory)
        store.acceptCookies(store.snapshot(), api, listOf("SESSID=synthetic-old; Path=/"))
        store.acceptCookies(store.snapshot(), api, listOf("SESSID=; Max-Age=0; Path=/"))
        assertEquals("", store(memory).snapshot().cookieHeader(api, now))
        store.acceptCookies(store.snapshot(), api, listOf("SESSID=synthetic-expired; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/"))
        assertEquals("", store(memory).snapshot().cookieHeader(api, now))
    }
    @Test fun loginAndGuestTransitionsNeverBlendJarsAndRejectDelayedResponses() {
        val memory = Memory(); val store = store(memory)
        store.acceptCookies(store.snapshot(), api, listOf("SESSID=synthetic-guest; Path=/"))
        val guest = store.snapshot(); login(store)
        assertFalse(store.acceptCookies(guest, api, listOf("SESSID=synthetic-late; Path=/")))
        assertFalse(store.snapshot().cookieHeader(api, now).contains("synthetic-guest"))
        assertEquals("synthetic-member-device", store.snapshot().deviceId)
        val member = store.snapshot(); store.useGuest()
        assertEquals("", store.snapshot().cookieHeader(api, now))
        assertFalse(store.acceptCookies(member, api, listOf("SESSID=synthetic-late-member; Path=/")))
        assertFalse(store.acceptCookies(guest, api, listOf("SESSID=synthetic-late-guest; Path=/")))
        assertEquals("", store(memory).snapshot().cookieHeader(api, now))
    }
    @Test fun revisionRejectsConcurrentOverwriteButEmptyResponseDoesNotInvalidate() {
        val store = store(); val first = store.snapshot(); val second = store.snapshot()
        assertTrue(store.acceptCookies(first, api, emptyList()))
        assertTrue(store.acceptCookies(second, api, listOf("SESSID=synthetic-new; Path=/")))
        assertFalse(store.acceptCookies(first, api, listOf("SESSID=synthetic-old; Path=/")))
        assertEquals("SESSID=synthetic-new", store.snapshot().cookieHeader(api, now))
    }
    @Test fun failedPersistenceDoesNotPublishGuestCookieOrAdvanceRevision() {
        val memory = Memory(); val store = store(memory); val captured = store.snapshot()
        memory.fail = true
        try { store.acceptCookies(captured, api, listOf("SESSID=synthetic; Path=/")); fail() }
        catch (_: IllegalStateException) { }
        assertEquals("", store.snapshot().cookieHeader(api, now))
        memory.fail = false
        assertTrue(store.acceptCookies(captured, api, listOf("SESSID=synthetic; Path=/")))
    }
    @Test fun resetAndUnboundStoreCannotReuseGuestSession() {
        val memory = Memory(); val store = store(memory)
        store.acceptCookies(store.snapshot(), api, listOf("SESSID=synthetic; Path=/"))
        assertEquals("", AccountStore(memory) { now }.snapshot().cookieHeader(api, now))
        val old = store.snapshot(); store.resetAfterRecovery()
        assertFalse(store.acceptCookies(old, api, listOf("SESSID=synthetic-late; Path=/")))
        assertEquals("", store.snapshot().cookieHeader(api, now))
    }
    @Test fun oldVaultLoadsAndNextWriteHasExplicitVersionTwo() {
        // The original encoder omitted default version/accounts entirely for an empty v1 vault.
        for (legacy in listOf("{}", """{"version":1,"accounts":[]}""")) {
        val memory = Memory().apply { bytes = legacy.encodeToByteArray() }
        val store = store(memory)
        assertFalse(store.state.value.recoveryRequired)
        assertTrue(store.acceptCookies(store.snapshot(), api, listOf("SESSID=synthetic; Path=/")))
        assertTrue(memory.bytes!!.decodeToString().contains("\"version\":2"))
        assertEquals("SESSID=synthetic", store(memory).snapshot().cookieHeader(api, now))
        }
    }
    @Test fun deviceMismatchDropsGuestOnlyAndRetainsAccountRecords() {
        val memory = Memory(); val store = store(memory); login(store); store.useGuest()
        store.acceptCookies(store.snapshot(), api, listOf("SESSID=synthetic-guest; Path=/"))
        val changed = store(memory, "synthetic-other-device")
        assertEquals("", changed.snapshot().cookieHeader(api, now))
        assertEquals(1, changed.state.value.accounts.size)
        changed.switchTo("1")
        assertEquals("synthetic-member-device", changed.snapshot().deviceId)
        assertTrue(changed.snapshot().cookieHeader(api, now).contains("token=synthetic-member"))
    }
    @Test fun corruptGuestCredentialFailsClosedWithoutOverwritingVault() {
        val memory = Memory(); val store = store(memory)
        store.acceptCookies(store.snapshot(), api, listOf("SESSID=synthetic; Path=/"))
        memory.bytes = memory.bytes!!.decodeToString().replace("SESSID=synthetic", "token=synthetic").encodeToByteArray()
        val corrupted = memory.bytes!!.copyOf(); val restored = store(memory)
        assertTrue(restored.state.value.recoveryRequired)
        assertEquals("", restored.snapshot().cookieHeader(api, now))
        assertArrayEquals(corrupted, memory.bytes)
    }

}
