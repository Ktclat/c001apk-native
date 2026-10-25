// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

import io.github.ktclat.c001apk.nativeapp.account.*
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

/** Synthetic read-only cache tests. No provider, network, persistent credentials or real device. */
class ReadSessionMemoryTest {
    private val url = "https://api.coolapk.com/v6/main/indexV8".toHttpUrl()
    private val proof = "NEC:abcdefgh:synthetic-memory-proof"
    private fun cookie(value: String = "accepted", expires: Long = Long.MAX_VALUE) = Cookie.Builder()
        .name("SESSID").value(value).domain("coolapk.com").path("/").secure().expiresAt(expires).build()
    private fun raw(owner: Any = Any(), current: () -> Boolean = { true }, revision: () -> Boolean = { true },
                    generation: Long = 1, device: String = "synthetic-device", guest: Boolean = true) = RequestSession(
        cookieFor = { "uid=999; token=unrelated; SESSID=original" }, isCurrent = current,
        acceptCookies = { _, _ -> error("Accepted read cache must not persist cookies") }, deviceCode = device,
        isReadSessionCurrent = revision, readOwner = owner, diagnosticGeneration = generation, isGuest = guest,
        readSessionCookies = { listOf(cookie("original")) })
    private fun accept(memory: ReadSessionMemory, raw: RequestSession, cookie: Cookie = cookie()): ReadSessionMemory.Capture {
        val capture = memory.capture(raw); assertTrue(memory.revokeOnChallenge(capture))
        assertTrue(memory.publish(capture, listOf(cookie), proof)); return capture
    }
    @Test fun acceptedCookieIsReadOnlyMemoryAndReusedAcrossNextReads() {
        val memory = ReadSessionMemory(); val raw = raw(); accept(memory, raw)
        repeat(3) {
            val next = memory.capture(raw)
            assertEquals("SESSID=accepted; validate=NEC%3Aabcdefgh%3Asynthetic-memory-proof", next.session.cookieFor(url))
            assertEquals("NEC%3Aabcdefgh%3Asynthetic-memory-proof", next.session.readValidationCookie!!.invoke())
        }
        assertEquals("uid=999; token=unrelated; SESSID=original", raw.cookieFor(url))
        assertNull(memory.capture(raw).session.cookieFor("https://account.coolapk.com/login".toHttpUrl()))
        assertNull(memory.capture(raw).session.cookieFor("https://untrusted.invalid/".toHttpUrl()))
    }
    @Test fun exactNewChallengeRevokesAndStaleRepliesCannotEraseOrOverwriteNewerAcceptance() {
        val memory = ReadSessionMemory(); val raw = raw(); val initial = accept(memory, raw)
        val first = memory.capture(raw); val concurrent = memory.capture(raw)
        assertTrue(memory.revokeOnChallenge(first)); assertNull(first.session.readValidationCookie!!.invoke())
        assertFalse(first.session.cookieFor(url)!!.contains("validate="))
        assertFalse(memory.revokeOnChallenge(concurrent)); assertFalse(memory.publish(initial, listOf(cookie("stale")), proof))
        assertTrue(memory.publish(first, listOf(cookie("new")), proof))
        assertFalse(memory.revokeOnChallenge(concurrent)); assertFalse(memory.publish(concurrent, listOf(cookie("late")), proof))
        concurrent.session.acceptCookies(url, listOf("SESSID=late-rotation; Domain=coolapk.com; Path=/; Secure"))
        assertTrue(memory.capture(raw).session.cookieFor(url)!!.startsWith("SESSID=new; validate="))
    }
    @Test fun ordinarySessionRotationAndDeletionRevokeValidateWithoutVaultWrites() {
        val memory = ReadSessionMemory(); val raw = raw(); accept(memory, raw)
        memory.capture(raw).session.acceptCookies(url, listOf("SESSID=rotated; Domain=coolapk.com; Path=/; Secure", "uid=111; Path=/"))
        val rotated = memory.capture(raw); assertEquals("SESSID=rotated", rotated.session.cookieFor(url))
        assertNull(rotated.session.readValidationCookie!!.invoke())
        rotated.session.acceptCookies(url, listOf("SESSID=; Domain=coolapk.com; Path=/; Secure; Max-Age=0"))
        assertNull(memory.capture(raw).session.cookieFor(url)) // deletion suppresses old raw SESSID
    }
    @Test fun validationExpiresWithAcceptedServerSessionAndDoesNotSlidePast24Hours() {
        var monotonic = 0L; var wall = System.currentTimeMillis()
        val memory = ReadSessionMemory({ monotonic }, { wall }); val raw = raw()
        accept(memory, raw, cookie(expires = wall + 1000))
        wall += 1001
        val expiredSession = memory.capture(raw)
        assertNull(expiredSession.session.readValidationCookie!!.invoke())
        assertFalse(expiredSession.session.cookieFor(url).orEmpty().contains("validate="))
        accept(memory, raw)
        monotonic = 86_399_999; assertNotNull(memory.capture(raw).session.readValidationCookie!!.invoke())
        monotonic = 86_400_000; assertNull(memory.capture(raw).session.readValidationCookie!!.invoke())
    }
    @Test fun ordinaryRotationDoesNotExtendOriginalMemoryLifetime() {
        var now = 0L; val memory = ReadSessionMemory({ now }); val raw = raw(); accept(memory, raw)
        now = 86_399_900
        memory.capture(raw).session.acceptCookies(url, listOf("SESSID=rotated; Domain=coolapk.com; Path=/; Secure"))
        assertEquals("SESSID=rotated", memory.capture(raw).session.cookieFor(url))
        now = 86_400_000
        assertFalse(memory.capture(raw).session.cookieFor(url).orEmpty().contains("rotated"))
    }
    @Test fun ownerGenerationDeviceGuestAndRawCookieRevisionAllRevoke() {
        val owner = Any()
        listOf<(RequestSession) -> RequestSession>(
            { it.copy(readOwner = Any()) }, { it.copy(diagnosticGeneration = 2) },
            { it.copy(deviceCode = "another-synthetic-device") }, { it.copy(isGuest = false) },
        ).forEach { changed ->
            val memory = ReadSessionMemory(); val raw = raw(owner); accept(memory, raw)
            assertNull(memory.capture(changed(raw)).session.readValidationCookie!!.invoke())
            assertNull(memory.capture(raw).session.readValidationCookie!!.invoke())
        }
        var currentRevision = true
        val memory = ReadSessionMemory(); val old = raw(owner, revision = { currentRevision }); accept(memory, old)
        currentRevision = false
        assertNull(memory.capture(raw(owner)).session.readValidationCookie!!.invoke())
    }
    @Test fun actualStoreSwitchAndRawCookieRevisionCannotReuseGuestReadProof() {
        var writes = 0
        val store = AccountStore(object : CredentialPersistence {
            override fun read(): ByteArray? = null
            override fun write(bytes: ByteArray) { writes++ }
            override fun reset() = Unit
        }, "synthetic-memory-device")
        val memory = ReadSessionMemory(); val baseline = writes
        accept(memory, store.captureRequestSession()); assertEquals(baseline, writes)
        val auth = listOf("uid=123", "token=synthetic-auth").map { Cookie.parse(url, "$it; Domain=coolapk.com; Path=/; Secure")!! }
        assertTrue(store.saveAuthenticated("123", "Synthetic", "synthetic-memory-device", auth, store.snapshot()))
        assertNull(memory.capture(store.captureRequestSession()).session.readValidationCookie!!.invoke())
        accept(memory, store.captureRequestSession())
        assertTrue(store.acceptCookies(store.snapshot(), url, listOf("SESSID=changed; Domain=coolapk.com; Path=/; Secure")))
        assertNull(memory.capture(store.captureRequestSession()).session.readValidationCookie!!.invoke())
    }
    @Test fun ownerlessReadNeverCreatesReusableStateAndFieldProofIsNotCached() {
        val memory = ReadSessionMemory(); val noOwner = raw().copy(readOwner = null)
        accept(memory, noOwner); assertNull(memory.capture(noOwner).session.readValidationCookie!!.invoke())
        val raw = raw(); val capture = memory.capture(raw)
        assertTrue(memory.revokeOnChallenge(capture)); assertTrue(memory.publish(capture, listOf(cookie())))
        assertEquals("SESSID=accepted", memory.capture(raw).session.cookieFor(url))
        assertNull(memory.capture(raw).session.readValidationCookie!!.invoke())
    }
}
