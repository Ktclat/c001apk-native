// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

import okio.ByteString.Companion.decodeBase64
import org.junit.Assert.*
import org.junit.Test

class ClientProfileContractTest {
    private val profile = ClientDeviceProfile("Fixture", "fixture", "Fixture Phone", "TEST.001", "15")
    private val install = "ca_0123456789ABCDEF0123456789ABCDEF"
    @Test fun sourceUserAgentAndLocalDeviceGrammar() {
        val code = profile.encodeLocalDevice(install)
        assertEquals("$install; ; ; ; Fixture; fixture; Fixture Phone; TEST.001; null", code.reversed().decodeBase64()!!.utf8())
        val headers = ClientProtocol(code, { 1770000000 }, sdkInt = 35, deviceProfile = profile).headers()
        assertEquals("Dalvik/2.1.0 (Linux; U; Android 15; Fixture Phone TEST.001) (#Build; fixture; Fixture Phone; TEST.001; 15) +CoolMarket/16.2.0-2604201-universal", headers["User-Agent"])
        assertEquals("35", headers["X-Sdk-Int"])
        assertEquals(code, headers["X-App-Device"])
        assertEquals(ClientProtocol.token(code, 1770000000), headers["X-App-Token"])
        assertFalse(headers.containsKey("Cookie"))
    }
    @Test fun existingAccountDeviceIsNeverRewritten() {
        val original = ClientProtocol.encodeDevice("ca_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA; ; ; ; native; native; native; native; null")
        val protocol = ClientProtocol(profile.encodeLocalDevice(install), { 1770000000 }, sdkInt = 35, deviceProfile = profile)
        val headers = protocol.headers(original)
        assertEquals(original, headers["X-App-Device"])
        assertEquals(ClientProtocol.token(original, 1770000000), headers["X-App-Token"])
    }
    @Test fun rejectHeaderInjectionAndFieldInjection() {
        for (value in listOf("x\r\nCookie: secret", "x\u0000", "x; injected", "机型", "x".repeat(257), " ")) {
            assertThrows(IllegalArgumentException::class.java) { ClientDeviceProfile("fixture", "fixture", value, "build", "15") }
            assertNull(ClientDeviceProfile.fromSystemProperties("fixture", "fixture", value, "build", "15"))
        }
    }
    @Test fun existingGuestUpgradeKeepsDeviceBytesButUsesSourceUserAgent() {
        val original = ClientProtocol.encodeDevice("ca_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA; ; ; ; native; native; native; native; null")
        val headers = ClientProtocol(original, { 1770000000 }, sdkInt = 35, deviceProfile = profile).headers()
        assertEquals(original, headers["X-App-Device"])
        assertEquals(profile.userAgent(), headers["User-Agent"])
        assertFalse(headers.containsKey("Cookie"))
    }
    @Test fun sourceGoldenTokenRemainsUnchanged() {
        assertEquals("v3JDJ5JDA0JE5qazRNREJsT0RBdk16VXhZemxsTnVOeS96WDhRWDE5NXNheklDN05mbE1ZRy9EaDN4cEhP", ClientProtocol.token("synthetic-test-device", 1770000000))
    }
    @Test fun generatedInstallationIdentityIsLocalAndWellFormed() {
        val raw = profile.newLocalDeviceCode().reversed().decodeBase64()!!.utf8()
        assertTrue(raw.substringBefore(';').matches(Regex("ca_[A-F0-9]{32}")))
        assertEquals(9, raw.split(';').size)
    }
}
