// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.feature.media

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Local TLS terminator only, never an upstream proxy. New test key; default hostname checks. */
internal class PublicMediaTlsFixture : Closeable {
    val server = MockWebServer()
    private val clients = mutableListOf<OkHttpClient>()
    // Fresh per fixture, short-lived and memory-only. Never serialize certificate/key payloads.
    private val certificate = HeldCertificate.Builder().commonName("LOCAL TEST ONLY rebuilt media")
        .addSubjectAlternativeName("image.coolapk.com")
        .addSubjectAlternativeName("avatar.coolapk.com")
        .addSubjectAlternativeName("static.coolapk.com")
        .addSubjectAlternativeName("outside.test")
        .duration(1, TimeUnit.DAYS).build()
    private val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    private val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
    init {
        server.protocols = listOf(Protocol.HTTP_1_1)
        server.useHttps(serverTls.sslSocketFactory(), true)
        server.start(InetAddress.getLoopbackAddress(), 0)
    }
    fun client(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
        .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(InetAddress.getLoopbackAddress(), server.port)))
        .dns(object : Dns { override fun lookup(hostname: String): List<InetAddress> = throw UnknownHostException("External DNS forbidden in local fixture") })
        .protocols(listOf(Protocol.HTTP_1_1)).callTimeout(5, TimeUnit.SECONDS).build().also { clients += it }
    fun tunnel() { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.UPGRADE_TO_SSL_AT_END)) }
    fun nextGet(): RecordedRequest {
        repeat(4) {
            val request = checkNotNull(server.takeRequest(3, TimeUnit.SECONDS)) { "No bounded fixture request" }
            if (request.method == "CONNECT") {
                assertEquals(anonymousMediaUserAgent, request.getHeader("User-Agent"))
                assertNoSecrets(request)
            } else {
                assertEquals("GET", request.method)
                return request
            }
        }
        error("No GET after bounded tunnel observations")
    }
    override fun close() {
        clients.forEach { it.dispatcher.cancelAll(); it.connectionPool.evictAll(); it.dispatcher.executorService.shutdownNow() }
        server.shutdown()
    }
    companion object {
        fun assertNoSecrets(request: RecordedRequest) {
            for (name in request.headers.names()) {
                assertFalse("Unexpected sensitive header name", name.lowercase() in setOf("cookie", "cookie2", "authorization", "proxy-authorization", "referer", "x-requested-with"))
                assertFalse(name.startsWith("X-App-", true) || name.startsWith("X-Sdk-", true))
            }
        }
    }
}
