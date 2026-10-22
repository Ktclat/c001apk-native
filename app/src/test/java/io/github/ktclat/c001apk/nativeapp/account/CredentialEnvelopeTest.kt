package io.github.ktclat.c001apk.nativeapp.account

import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test

/** Synthetic JCE keys exercise encryption integrity, NOT device AndroidKeyStore availability. */
class CredentialEnvelopeTest {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    @Test fun roundTripUsesFreshIvAndDoesNotExposePlaintext() {
        val key = key(); val bytes = "synthetic-secret-long-enough-not-to-match-random-data".toByteArray()
        val first = CredentialEnvelope.encrypt(bytes, key); val second = CredentialEnvelope.encrypt(bytes, key)
        assertFalse(first.contentEquals(second))
        assertFalse(first.decodeToString().contains(bytes.decodeToString()))
        assertArrayEquals(bytes, CredentialEnvelope.decrypt(first, key))
    }
    @Test fun corruptionAndWrongKeysCannotYieldCredentialData() {
        val key = key(); val blob = CredentialEnvelope.encrypt("synthetic".toByteArray(), key)
        val corrupt = blob.copyOf(); corrupt[corrupt.lastIndex] = (corrupt.last().toInt() xor 1).toByte()
        try { CredentialEnvelope.decrypt(corrupt, key); fail() } catch (_: java.security.GeneralSecurityException) { }
        try { CredentialEnvelope.decrypt(blob, key()); fail() } catch (_: java.security.GeneralSecurityException) { }
        try { CredentialEnvelope.decrypt(blob.copyOf(16), key); fail() } catch (_: IllegalArgumentException) { }
    }
}
