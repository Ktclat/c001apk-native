// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.data.*
import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test

/** Loopback only, synthetic session and records. Never authenticates to a real service. */
class NotificationDeleteRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var accounts: AccountStore
    private lateinit var repo: SocialRepository
    @Before fun setUp() {
        server = MockWebServer(); server.start()
        accounts = AccountStore(object : CredentialPersistence {
            private var bytes: ByteArray? = null
            override fun read() = bytes?.copyOf()
            override fun write(bytes: ByteArray) { this.bytes = bytes.copyOf() }
            override fun reset() { bytes = null }
        })
        accounts.saveAuthenticated("1", "synthetic", "synthetic-device", listOf(Cookie.Builder()
            .name("token").value("synthetic-not-a-real-token").domain("coolapk.com").path("/").secure()
            .expiresAt(System.currentTimeMillis() + 3_600_000).build()), accounts.snapshot())
        repo = SocialRepository(accounts) { captured -> CoolapkHttpTransport(
            endpoints = ApiEndpoints(server.url("/"), server.url("/")),
            requestSession = { RequestSession(cookieFor = { null }, isCurrent = { accounts.isCurrent(captured) }, acceptCookies = { _, _ -> }, deviceCode = captured.deviceId) }) }
    }
    @After fun tearDown() { server.shutdown() }
    private suspend fun mixedRows(): List<SocialRow> {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":123,"entityType":"feed"},{"id":123,"entityType":"feed_reply"},{"id":123,"entityType":"notification","fromuid":999}]}"""))
        return (repo.notices(NoticeCategory.SYSTEM) as ApiResult.Success).value.items
    }
    @Test fun onlyExactNotificationEntityUsesNotificationEndpoint() = runBlocking {
        val rows = mixedRows(); assertEquals(3, rows.size)
        assertTrue(repo.deleteNotification(rows[0]) is WriteOutcome.Rejected)
        assertTrue(repo.deleteNotification(rows[1]) is WriteOutcome.Rejected)
        assertTrue(repo.delete(DeleteTarget.NOTIFICATION, "123") is WriteOutcome.Rejected)
        assertEquals(1, server.requestCount)
        server.takeRequest()
        server.enqueue(MockResponse().setBody("{\"data\":1}"))
        assertTrue(repo.deleteNotification(rows[2]) is WriteOutcome.Confirmed)
        val request = server.takeRequest()
        assertEquals("POST", request.method); assertEquals("/v6/notification/delete?id=123", request.path)
        assertEquals(2, server.requestCount)
    }
    @Test fun changedIdentityUnknownProvenanceAndChangedRowCannotDelete() = runBlocking {
        val row = mixedRows().last()
        assertTrue(repo.deleteNotification(row.copy(id = "456")) is WriteOutcome.Rejected)
        assertTrue(repo.deleteNotification(row.copy(entityType = "feed")) is WriteOutcome.Rejected)
        assertTrue(repo.deleteNotification(row.copy(notificationScope = null)) is WriteOutcome.Rejected)
        val foreignRepository = SocialRepository(accounts) { error("Foreign provenance must not create transport") }
        assertTrue(foreignRepository.deleteNotification(row) is WriteOutcome.Rejected)
        accounts.useGuest(); accounts.switchTo("1")
        assertTrue(repo.deleteNotification(row) is WriteOutcome.Rejected)
        assertEquals(1, server.requestCount)
    }
    @Test fun messageEndpointCannotMintNotificationDeletionScope() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":123,"entityType":"notification"}]}"""))
        val row = (repo.chat("1_2") as ApiResult.Success).value.items.single()
        assertNull(row.notificationScope); assertTrue(repo.deleteNotification(row) is WriteOutcome.Rejected)
        assertEquals(1, server.requestCount)
    }
}
