package io.github.ktclat.c001apk.nativeapp.account

import org.junit.Assert.*
import org.junit.Test

class OfficialWebLoginPolicyTest {
    private val cookies = listOf("uid=123; Domain=.coolapk.com; Path=/; Secure", "username=synthetic; Domain=.coolapk.com; Path=/; Secure", "token=synthetic; Domain=.coolapk.com; Path=/; Secure")
    @Test fun exactRootAndExplicitResourceOriginsOnly() {
        assertTrue(OfficialWebLoginPolicy.isCompletion("https://www.coolapk.com/"))
        for (bad in listOf("http://www.coolapk.com/", "https://www.coolapk.com:444/", "https://evil.example/", "https://www.coolapk.com.evil.example/", "https://user@www.coolapk.com/", "https://www.coolapk.com/path", "https://www.coolapk.com/?next=x")) assertFalse(OfficialWebLoginPolicy.isCompletion(bad))
        assertTrue(OfficialWebLoginPolicy.allowsNavigation(OfficialWebLoginPolicy.LOGIN))
        assertFalse(OfficialWebLoginPolicy.allowsNavigation("intent://account.coolapk.com/auth/login"))
        assertFalse(OfficialWebLoginPolicy.allowsResource("https://126.net.evil.example/"))
        assertFalse(OfficialWebLoginPolicy.allowsResource("file:///private"))
    }
    @Test fun scopeIsNotWidenedAndDuplicateIdentityIsRejected() {
        assertNotNull(OfficialWebLoginPolicy.credentials(cookies, 1_000L))
        assertNull(OfficialWebLoginPolicy.credentials(cookies.map { it.replace("Domain=.coolapk.com; ", "") }, 1_000L))
        assertNull(OfficialWebLoginPolicy.credentials(cookies + cookies.first(), 1_000L))
        assertNull(OfficialWebLoginPolicy.credentials(cookies.dropLast(1), 1_000L))
        assertNull(OfficialWebLoginPolicy.credentials(cookies.map { it.replace("Path=/", "Path=/auth") }, 1_000L))
    }
    @Test fun credentialsReleasedOnlyAfterCleanupAndOnlyOnce() {
        val gate = OfficialWebLoginGate { 0L }
        assertNull(gate.cleanupFinished(gate.nonce, true, true))
        assertTrue(gate.beginCompletion(gate.nonce, OfficialWebLoginPolicy.COMPLETE, cookies, 1_000L))
        assertFalse(gate.beginCompletion(gate.nonce, OfficialWebLoginPolicy.COMPLETE, cookies, 1_000L))
        assertNotNull(gate.cleanupFinished(gate.nonce, true, true))
        assertNull(gate.cleanupFinished(gate.nonce, true, true))
    }
    @Test fun cancelFailureStaleSessionAndWrongNonceNeverReleaseCredentials() {
        for (mode in listOf("cancel", "cleanup_failure", "stale_session", "nonce")) {
            val gate = OfficialWebLoginGate { 0L }
            assertTrue(gate.beginCompletion(gate.nonce, OfficialWebLoginPolicy.COMPLETE, cookies, 1_000L))
            if (mode == "cancel") gate.cancel()
            assertNull(gate.cleanupFinished(if (mode == "nonce") "stale" else gate.nonce, mode != "cleanup_failure", mode != "stale_session"))
            gate.cancel()
            assertNull(gate.cleanupFinished(gate.nonce, true, true))
        }
    }
    @Test fun expiredAttemptCannotImportAfterCleanup() {
        var elapsed = 0L
        val gate = OfficialWebLoginGate { elapsed }
        assertTrue(gate.beginCompletion(gate.nonce, OfficialWebLoginPolicy.COMPLETE, cookies, 1_000L))
        elapsed = 21L * 60 * 1_000_000_000
        assertNull(gate.cleanupFinished(gate.nonce, true, true))
    }
    @Test fun cancelAfterCleanupButBeforeIoImportBlocksCommit() {
        val gate = OfficialWebLoginGate { 0L }
        assertTrue(gate.beginCompletion(gate.nonce, OfficialWebLoginPolicy.COMPLETE, cookies, 1_000L))
        assertNotNull(gate.cleanupFinished(gate.nonce, true, true))
        gate.cancel()
        assertFalse(gate.claimImport())
    }

}
