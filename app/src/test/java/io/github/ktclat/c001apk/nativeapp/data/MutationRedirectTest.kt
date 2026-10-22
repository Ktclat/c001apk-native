// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class MutationRedirectTest {
    @Test fun mutationRedirectsAndServerRequestTimeoutRemainUncertainWithoutFollowing() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val transport = CoolapkHttpTransport(endpoints = ApiEndpoints(server.url("/"),server.url("/")), protocol = ClientProtocol("synthetic", {1770000000}))
            for (status in listOf(301,302,303,307,308,408)) {
                server.enqueue(MockResponse().setResponseCode(status).setHeader("Location",server.url("/must-not-follow")))
                val result = transport.post("v6/feed/like",mapOf("id" to "1")) as ApiResult.Failure
                assertEquals(FailureKind.UNCERTAIN,result.kind)
                assertEquals(status,result.httpStatus)
            }
            assertEquals(6,server.requestCount)
        }
    }
}
