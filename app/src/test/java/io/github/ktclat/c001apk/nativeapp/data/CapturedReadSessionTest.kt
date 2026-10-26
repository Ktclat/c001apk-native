// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

import org.junit.Assert.*
import org.junit.Test

/** Source capture only; no API, provider or mutable cookie jar is consulted by the accessor. */
class CapturedReadSessionTest {
    private fun session(device: String) = RequestSession(
        cookieFor = { null }, isCurrent = { true }, acceptCookies = { _, _ -> },
        deviceCode = device, isSessionCurrent = { true }, isReadSessionCurrent = { true },
    )
    @Test fun snapshotAccessorNeverConsultsSharedSupplierAgain() {
        val original = session("synthetic-original")
        var selected = original
        var captures = 0
        val shared = CoolapkHttpTransport(requestSession = { captures++; selected })
        val operation = shared.forSingleOperation()
        assertEquals(1, captures)
        selected = session("synthetic-replacement")
        repeat(3) { assertSame(original, operation.capturedReadSession()) }
        assertEquals(1, captures)
    }
    @Test fun sharedTransportAccessorFailsBeforeReadingItsMutableSupplier() {
        var captures = 0
        val shared = CoolapkHttpTransport(requestSession = { captures++; session("synthetic") })
        try { shared.capturedReadSession(); fail("Uncaptured transport must not expose a session") }
        catch (_: IllegalStateException) { }
        assertEquals(0, captures)
    }
    @Test fun publicOperationFlagCannotMasqueradeAsAnImmutableFactoryCapture() {
        var captures = 0
        val direct = CoolapkHttpTransport(operationScoped = true, requestSession = { captures++; session("synthetic-mutable") })
        try { direct.capturedReadSession(); fail("A flag must not authorize recapture") }
        catch (_: IllegalStateException) { }
        assertEquals(0, captures)
    }
    @Test fun explicitlySuppliedSessionIsReturnedWithoutCreatingAnotherScope() {
        val original = session("synthetic-explicit")
        val operation = CoolapkHttpTransport().forRequestSession(original)
        assertSame(original, operation.capturedReadSession())
    }
}
