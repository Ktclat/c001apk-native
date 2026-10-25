package io.github.ktclat.c001apk.nativeapp.account

import org.junit.Assert.*
import org.junit.Test

class HumanVerificationTest {
    @Test fun singleUseNonceAndExpiryBoundProof() {
        var now = 0L
        val gate = ChallengeGate(HumanChallengeSpec("1234567890abcdef", "")) { now }
        assertNull(gate.accept("wrong", "synthetic-proof"))
        assertNull(gate.accept(gate.nonce, "cookie;token=bad"))
        assertEquals("NEC:12345678:synthetic-proof", gate.accept(gate.nonce, "synthetic-proof"))
        assertNull(gate.accept(gate.nonce, "synthetic-proof"))
        val expired = ChallengeGate(HumanChallengeSpec("1234567890abcdef", "")) { now }
        now += 300_001
        assertNull(expired.accept(expired.nonce, "synthetic-proof"))
        val cancelled = ChallengeGate(HumanChallengeSpec("1234567890abcdef", "")) { now }
        cancelled.cancel(); assertNull(cancelled.accept(cancelled.nonce, "synthetic-proof"))
    }
    @Test fun vendorResourceOriginsAreBounded() {
        assertTrue(ChallengeOriginPolicy.resourceAllowed("https://cstaticdun.126.net/load.min.js"))
        listOf("http://cstaticdun.126.net/load.min.js", "https://cstaticdun.126.net:444/a", "https://126.net.evil.example/a", "https://account.coolapk.com/a", "file:///tmp/a", "https://user@cstaticdun.126.net/a").forEach {
            assertFalse(ChallengeOriginPolicy.resourceAllowed(it))
        }
    }
    @Test fun productionHtmlAllowsOnlyProviderAudioAndBlobWithoutRelaxingScriptPolicy() {
        val method = Class.forName("io.github.ktclat.c001apk.nativeapp.account.HumanVerificationKt")
            .getDeclaredMethod("challengeHtml", HumanChallengeSpec::class.java, String::class.java).apply { isAccessible = true }
        val html = method.invoke(null, HumanChallengeSpec("abcdefghijklmnop", ""), "0".repeat(48)) as String
        assertTrue(html.contains("media-src blob: https://*.126.net https://*.127.net https://*.163.com https://*.163yun.com;"))
        assertTrue(html.contains("default-src 'none'")); assertTrue(html.contains("frame-src 'none'"))
        assertTrue(html.contains("script-src 'nonce-${"0".repeat(48)}' https://*.126.net"))
        assertFalse(html.contains("unsafe-eval")); assertFalse(html.contains("media-src *")); assertFalse(html.contains("coolapk.com"))
        assertTrue(html.contains("typeof data.validate==='string'"))
    }
}
