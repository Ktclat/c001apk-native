// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import io.github.ktclat.c001apk.nativeapp.data.RequestSession
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ReachableReadinessDiagnosticsTest {
    @Test fun sessionObservationDelegatesExactlyOnceWithoutProbingOrChangingOtherCallbacks() {
        var currentCalls = 0
        var sessionCalls = 0
        var readCalls = 0
        var acceptedCalls = 0
        val raw = RequestSession(cookieFor = { null },
            isCurrent = { currentCalls++; true },
            isSessionCurrent = { sessionCalls++; false },
            isReadSessionCurrent = { readCalls++; true },
            acceptCookies = { _, _ -> acceptedCalls++ })
        val observed = ReachableReadinessDiagnostics().session(raw)
        assertEquals(listOf(0, 0, 0, 0), listOf(currentCalls, sessionCalls, readCalls, acceptedCalls))
        assertSame(raw.cookieFor, observed.cookieFor)
        assertSame(raw.acceptChallengeCookies, observed.acceptChallengeCookies)
        assertTrue(observed.isCurrent())
        assertFalse(observed.isSessionCurrent!!.invoke())
        assertTrue(observed.isReadSessionCurrent!!.invoke())
        observed.acceptCookies("https://api.coolapk.com/v6/user/followList".toHttpUrl(), emptyList())
        assertEquals(listOf(1, 1, 1, 1), listOf(currentCalls, sessionCalls, readCalls, acceptedCalls))
    }

    @Test fun absentCallbacksRemainAbsentAndCallbackFailuresAreNotReplaced() {
        val original = CancellationException("synthetic account changed")
        val acceptFailure = IllegalStateException("synthetic acceptance failure")
        var acceptedCalls = 0
        val raw = RequestSession(cookieFor = { null }, isCurrent = { throw original },
            acceptCookies = { _, _ -> acceptedCalls++; throw acceptFailure })
        val observed = ReachableReadinessDiagnostics().session(raw)
        assertNull(observed.isSessionCurrent)
        assertNull(observed.isReadSessionCurrent)
        try {
            observed.isCurrent()
            fail("Expected original cancellation")
        } catch (actual: CancellationException) {
            assertSame(original, actual)
        }
        try {
            observed.acceptCookies("https://api.coolapk.com/v6/user/followList".toHttpUrl(), emptyList())
            fail("Expected original acceptance failure")
        } catch (actual: IllegalStateException) {
            assertSame(acceptFailure, actual)
        }
        assertEquals(1, acceptedCalls)
    }

    @Test fun responseObservationReturnsOriginalUnreadSourceAndMetadata() {
        val text = """{"data":[{"username":"开源观察员"}]}"""
        val original = text.toResponseBody("application/json".toMediaType())
        val originalSource = original.source()
        val observed = ReachableReadinessDiagnostics().body("/v6/user/followList", original)
        assertEquals(original.contentType(), observed.contentType())
        assertEquals(original.contentLength(), observed.contentLength())
        assertSame(originalSource, observed.source())
        assertSame(originalSource, observed.source())
        assertEquals(text, observed.string())
    }
}
