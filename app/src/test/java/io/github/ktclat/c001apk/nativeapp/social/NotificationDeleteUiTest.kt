// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.data.*
import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.runBlocking
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import okhttp3.Cookie
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test

/** Loopback only, synthetic session and records. Never authenticates to a real service. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class NotificationDeleteUiTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createComposeRule()
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
            requestSession = { RequestSession(cookieFor = { null }, isCurrent = { accounts.isCurrent(captured) }, acceptCookies = { _, _ -> }, deviceCode = captured.deviceId,
                isSessionCurrent = { accounts.isCurrentRead(captured) }, isReadSessionCurrent = { accounts.isCurrentRead(captured) }) }) }
    }
    @After fun tearDown() { server.shutdown() }
    @Test fun mixedRowsExposeOnlyOneNotificationDeleteAndCancellationDoesNotWrite() {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":11,"entityType":"feed","message":"feed row"},{"id":12,"entityType":"feed_reply","message":"reply row"},{"id":13,"entityType":"notification","message":"notification row","fromuid":999}]}"""))
        val generation = accounts.state.value.generation
        compose.setContent { MaterialTheme { NotificationScreen(repo, generation) } }
        try {
            compose.waitUntil(5_000) { compose.onAllNodesWithText("feed row").fetchSemanticsNodes().size == 1 }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Loopback requests=${server.requestCount}; UI=${compose.onRoot().printToString()}", e)
        }
        // Feed/reply rows are visible before scrolling and must never acquire notification deletion.
        compose.onAllNodesWithText("删除").assertCountEquals(0)
        // Rich actor/destination rows are taller: inspect the real scrollable third row,
        // rather than assuming all three records fit the default320×470 test viewport.
        compose.onNodeWithTag("notification-list").performScrollToNode(hasText("notification row"))
        compose.onNodeWithText("notification row").assertIsDisplayed()
        compose.onAllNodesWithText("删除").assertCountEquals(1)
        compose.onNodeWithText("删除").performClick()
        compose.onNodeWithText("确认删除？").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        assertEquals(1, server.requestCount)
    }
}
