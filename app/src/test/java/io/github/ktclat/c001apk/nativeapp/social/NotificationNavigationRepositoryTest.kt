// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.data.*
import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import okhttp3.Cookie
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Synthetic loopback only. A rich navigation projection never mints a different write identity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationNavigationRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var accounts: AccountStore
    private lateinit var repository: SocialRepository
    @Before fun setup() {
        server = MockWebServer(); server.start()
        accounts = AccountStore(object : CredentialPersistence {
            private var bytes: ByteArray? = null
            override fun read() = bytes?.copyOf()
            override fun write(bytes: ByteArray) { this.bytes = bytes.copyOf() }
            override fun reset() { bytes = null }
        })
        accounts.saveAuthenticated("1","synthetic","synthetic-device",listOf(Cookie.Builder().name("token").value("synthetic-token")
            .domain("coolapk.com").path("/").secure().expiresAt(System.currentTimeMillis()+3_600_000).build()),accounts.snapshot())
        repository = SocialRepository(accounts) { snapshot -> CoolapkHttpTransport(endpoints = ApiEndpoints(server.url("/"),server.url("/")),
            requestSession = { RequestSession(cookieFor = { null },isCurrent = { accounts.isCurrent(snapshot) },acceptCookies = { _,_ -> },deviceCode = snapshot.deviceId,
                isSessionCurrent = { accounts.isCurrentRead(snapshot) }, isReadSessionCurrent = { accounts.isCurrentRead(snapshot) }) }) }
    }
    @After fun close() { server.shutdown() }
    @Test fun enrichedRowsKeepRecordDeletionIdentityAndTerminalTemplateStopsPaging() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[
            {"id":123,"entityType":"feed_reply","url":"/feed/900?rid=123","likeUserInfo":{"uid":44,"username":"liker"}},
            {"id":123,"entityType":"notification","fromuid":55,"note":"<a href='/feed/900'>related post</a>"},
            {"id":123,"entityType":"notification","fromuid":66,"note":"<a href='/feed/901'>another occurrence</a>"},
            {"entityType":"card","entityTemplate":"noMoreDataCard"}]}"""))
        val page = (repository.notices(NoticeCategory.MENTIONS) as ApiResult.Success).value
        assertEquals(3,page.items.size); assertNull(page.nextCursor)
        assertEquals(listOf("123","123","123"),page.items.map { it.id })
        assertEquals(NotificationDestination.Feed("900"),page.items[1].notification!!.destination)
        assertFalse(canDeleteNotification(page.items[0])); assertTrue(canDeleteNotification(page.items[1])); assertTrue(canDeleteNotification(page.items[2]))
        assertTrue(repository.deleteNotification(page.items[1].copy(id="900")) is WriteOutcome.Rejected)
        assertTrue(repository.deleteNotification(page.items[0]) is WriteOutcome.Rejected)
        server.takeRequest(); server.enqueue(MockResponse().setBody("{\"data\":1}"))
        assertTrue(repository.deleteNotification(page.items[1]) is WriteOutcome.Confirmed)
        assertEquals("/v6/notification/delete?id=123",server.takeRequest().path)
        assertEquals(2,server.requestCount)
    }
    @Test fun staleExpectedGenerationCannotDispatchNotificationRead() = runBlocking {
        val old = accounts.state.value.generation
        accounts.useGuest(); accounts.switchTo("1")
        try { repository.notices(NoticeCategory.LIKES,expectedGeneration=old); fail("stale read should cancel") }
        catch (_: CancellationException) { }
        assertEquals(0,server.requestCount)
    }
    @Test fun repeatedRawBoundaryIdsPreservePageNumberProgressionUntilExplicitTerminal() = runBlocking {
        var cursor = PageCursor()
        for (page in 1..3) {
            val ending = if (page == 3) ",{\"entityType\":\"card\",\"entityTemplate\":\"noMoreDataCard\"}" else ""
            server.enqueue(MockResponse().setBody("""{"data":[{"id":606,"entityType":"feed_reply","url":"/feed/707?rid=606","likeUserInfo":{"uid":${10+page},"username":"actor$page"}}$ending]}"""))
            val result = (repository.notices(NoticeCategory.LIKES,cursor) as ApiResult.Success).value
            assertEquals((10+page).toString(),result.items.single().notification!!.actorUid)
            val request = server.takeRequest()
            assertEquals(page.toString(),request.requestUrl!!.queryParameter("page"))
            if (page > 1) {
                assertEquals("606",request.requestUrl!!.queryParameter("firstItem")); assertEquals("606",request.requestUrl!!.queryParameter("lastItem"))
            }
            if (page == 3) assertNull(result.nextCursor)
            else { assertEquals(page+1,result.nextCursor!!.page); cursor=result.nextCursor!! }
        }
    }
    @Test fun notificationPageOverflowAndInvalidCursorNeverWrapOrDispatchInvalidPage() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) { PageCursor(0) }
        assertEquals(0,server.requestCount)
        server.enqueue(MockResponse().setBody("""{"data":[{"id":606,"entityType":"feed_reply","url":"/feed/707?rid=606"}]}"""))
        val page = (repository.notices(NoticeCategory.LIKES,PageCursor(Int.MAX_VALUE,"606","606")) as ApiResult.Success).value
        assertNull(page.nextCursor); assertEquals(Int.MAX_VALUE.toString(),server.takeRequest().requestUrl!!.queryParameter("page"))
    }
    @Test fun nonMessageRetainedRecordsAreBoundedAndCannotCarryConversationPayload() = runBlocking {
        val row = buildJsonObject {
            put("id",123); put("entityType","notification"); put("message","x".repeat(20_000)); put("fromusername","n".repeat(500)); put("fromuid","u".repeat(500))
            put("ukey","k".repeat(20_000)); put("is_top",1); put("message_pic","https://example.invalid/"+"x".repeat(10_000))
        }
        server.enqueue(MockResponse().setBody(buildJsonObject { put("data",buildJsonArray { add(row) }) }.toString()))
        val result = (repository.notices(NoticeCategory.SYSTEM) as ApiResult.Success).value.items.single()
        assertEquals(4000,result.message.length); assertEquals(200,result.userName.length); assertEquals(128,result.uid.length)
        assertNull(result.conversationKey); assertFalse(result.pinned); assertNull(result.imageUrl); assertFalse(result.hasImage)
        assertTrue(result.notification!!.text.length <=4000)
        server.enqueue(MockResponse().setBody("""{"data":[{"id":123,"entityType":"${"x".repeat(65)}"}]}"""))
        assertTrue(repository.notices(NoticeCategory.SYSTEM) is ApiResult.Failure)
    }
}
