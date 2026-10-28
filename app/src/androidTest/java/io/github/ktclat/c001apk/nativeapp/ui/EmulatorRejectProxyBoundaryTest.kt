// SPDX-License-Identifier: AGPL-3.0-only
// Infrastructure preflight, NOT an app scenario / live-CDN result. No Activity is launched.
package io.github.ktclat.c001apk.nativeapp.ui

import io.github.ktclat.c001apk.nativeapp.feature.media.publicImageClient
import java.io.IOException
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class EmulatorRejectProxyBoundaryTest {
    // Fixed TEST-NET literals avoid external DNS and cannot select a real CDN.
    // Host must separately require THIS probe's matching closed proxy counter to increase.
    @Test fun defaultHttpsIsRejectedBeforeTls() = recordPrimaryTestFailure("boundary-default-https") {
        val connection = URL("https://198.51.100.17/").openConnection() as HttpsURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.instanceFollowRedirects = false
        var rejected = false
        try {
            connection.inputStream.use { it.read() }
        } catch (_: IOException) {
            rejected = true
        } finally {
            connection.disconnect()
        }
        assertTrue("Default HTTPS unexpectedly reached a response", rejected)
        EmulatorEvidence.text("boundary-default-https.txt", "stack=default HttpsURLConnection\nrejected=true\nHTTP status not claimed; host closed-counter proof also required\n")
    }
    @Test fun productionMediaIsRejectedBeforeTls() = recordPrimaryTestFailure("boundary-production-media") {
        val client = publicImageClient()
        try {
            val call = client.newCall(Request.Builder().url("https://198.51.100.18/").build())
            // Per-call budget only; no new DNS, proxy, interceptor, UA, TLS, or factory policy.
            call.timeout().timeout(8, TimeUnit.SECONDS)
            var rejected = false
            try { call.execute().use { } } catch (_: IOException) { rejected = true }
            assertTrue("Production media client unexpectedly reached a response", rejected)
            EmulatorEvidence.text("boundary-production-media.txt", "stack=production publicImageClient\nrejected=true\nHTTP status not claimed; host closed-counter proof also required\n")
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
