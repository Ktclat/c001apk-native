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
class SocialRepositoryTest {
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
            .expiresAt(System.currentTimeMillis() + 60_000).build()), accounts.snapshot())
        repo = SocialRepository(accounts) { captured -> CoolapkHttpTransport(
            endpoints = ApiEndpoints(server.url("/"), server.url("/")),
            requestSession = { RequestSession(cookieFor = { null }, isCurrent = { accounts.isCurrent(captured) }, acceptCookies = { _, _ -> }, deviceCode = captured.deviceId) }) }
    }
    @After fun tearDown() { server.shutdown() }
    @Test fun likeUsesQueryAndUrlencodedTrace() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"data\":{\"likenum\":1}}"))
        assertTrue(repo.like("42", reply = false, targetLiked = true) is WriteOutcome.Confirmed)
        val request = server.takeRequest()
        assertEquals("POST", request.method); assertEquals("/v6/feed/like?id=42&detail=0", request.path)
        assertEquals("trace=", request.body.readUtf8())
        assertNull(request.getHeader("Cookie"))
    }
    @Test fun chatRequiresMessageAcknowledgementAndUsesRecipientUid() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"data\":{\"count\":1}}"))
        assertTrue(repo.sendText("2", "hello") is WriteOutcome.Uncertain)
        val request = server.takeRequest()
        assertEquals("/v6/message/send?uid=2", request.path)
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("name=\"message\"")); assertTrue(body.contains("hello"))
        assertEquals(1, server.requestCount)
    }
    @Test fun serverFailureIsUncertainWithoutRetry() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(repo.sendText("2", "hello") is WriteOutcome.Uncertain)
        assertEquals(1, server.requestCount)
    }
    @Test fun pinUsesConversationKeyAndCannotConfirmUnrelatedId() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"data\":{\"id\":5}}"))
        assertTrue(repo.conversationPinned("1_2", true) is WriteOutcome.Uncertain)
        val request = server.takeRequest()
        assertEquals("GET", request.method); assertEquals("/v6/message/addTop?ukey=1_2", request.path)
    }
    @Test fun selfOnlyUsesPublishStatusNotNegativeStatus() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"data\":{\"id\":77}}"))
        assertEquals(WriteOutcome.Confirmed("77"), repo.publish(ComposerDraft("test", selfOnly = true)))
        val request = server.takeRequest(); val body = request.body.readUtf8()
        assertEquals("/v6/feed/createFeed", request.path)
        assertTrue(body.contains("publish_status=1")); assertTrue(body.contains("status=1")); assertFalse(body.contains("status=-1"))
    }
    @Test fun replyToReplyUsesExactFlutterEnumName() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"data\":{\"id\":78}}"))
        repo.publish(ComposerDraft("test", replyId = "77", replyToReply = true, replyAndForward = true))
        val request = server.takeRequest()
        assertEquals("/v6/feed/reply?id=77&type=reply", request.path)
        assertTrue(request.body.readUtf8().contains("replyAndForward=1"))
    }
    @Test fun guestCannotWriteOrLoadPrivateMessages() = runBlocking {
        accounts.useGuest()
        assertTrue(repo.sendText("2", "hello") is WriteOutcome.Rejected)
        assertTrue(repo.notices(NoticeCategory.MESSAGES) is ApiResult.Failure)
        assertEquals(0, server.requestCount)
    }
    @Test fun oversizedNoticeArrayIsRejected() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"data\":[" + (1..501).joinToString { "{\"id\":$it}" } + "]}"))
        assertTrue(repo.notices(NoticeCategory.SYSTEM) is ApiResult.Failure)
    }
    @Test fun sessionSwitchWhileSendingCannotCommitOldAccountSuccess() = runBlocking {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                accounts.useGuest()
                return MockResponse().setBody("{\"data\":{\"entityType\":\"message\",\"id\":8}}")
            }
        }
        try { repo.sendText("2", "hello"); fail("stale response must cancel") }
        catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(1, server.requestCount)
    }
    @Test fun conversationFieldsUsePeerNotSenderAndRespectWirePinKey() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"data\":[{\"id\":1,\"ukey\":\"1_2\",\"messageUid\":2,\"fromuid\":1,\"messageUsername\":\"peer\",\"is_top\":1}]}"))
        val result = repo.notices(NoticeCategory.MESSAGES) as ApiResult.Success
        assertEquals("2", result.value.items.single().uid)
        assertEquals("peer", result.value.items.single().userName)
        assertTrue(result.value.items.single().pinned)
    }

}
