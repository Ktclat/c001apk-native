// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class ResponseBodyCancellationTest {
    @Test fun cancellationClosesCallEvenAfterHeadersWhileBodyIsBlocked() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            // Deliberately incomplete body without sleeping server workers or teardown timing races.
            server.enqueue(MockResponse().setBody("{\"data\":[]}").setHeader("Content-Length", "10000"))
            val reading = CompletableDeferred<Unit>()
            val cancelled = AtomicBoolean(false)
            val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun responseBodyStart(call: Call) { reading.complete(Unit) }
                override fun canceled(call: Call) { cancelled.set(true) }
            }).build()
            val transport = CoolapkHttpTransport(client, ApiEndpoints(server.url("/"),server.url("/")), ClientProtocol("synthetic", {1770000000}))
            val job = async { transport.get("v6/feed/detail",mapOf("id" to "1")) }
            withTimeout(3000) { reading.await() }
            withTimeout(3000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            assertTrue(cancelled.get())
            assertEquals(1,server.requestCount)
        }
    }
}
