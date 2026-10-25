// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.*
import org.junit.Assert.*

class LinkedContentRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: HttpCoolapkRepository
    @Before fun setup() {
        server = MockWebServer().apply { start() }
        repository = HttpCoolapkRepository(endpoints = ApiEndpoints(server.url("/"), server.url("/")), protocol = ClientProtocol("synthetic-linked-device", { 1770000000 }))
    }
    @After fun close() { server.shutdown() }
    private fun enqueue(body: String) = server.enqueue(MockResponse().setBody(body))
    @Test fun eventUsesItsOwnEndpointAndFullSourceFieldsNotFeedSummary() = runBlocking {
        val content = "正文".repeat(7_000) + "EVENT_FULL_TAIL"
        val event = buildJsonObject {
            put("id", 1086); put("title", "离线众测活动"); put("message", "NOT_EVENT_BODY")
            put("description", "说明 <保留文字>"); put("notice_rule", "产品数量：8\n等级要求：3 级以上")
            put("content", content); put("time_reg_end", 1792116000)
            put("action_url", "https://example.com/synthetic-registration"); put("pic", "https://fixture.invalid/icon-1.png")
        }
        enqueue(buildJsonObject { put("data", event) }.toString())
        val item = (repository.eventDetail("1086") as ApiResult.Success).value
        val request = server.takeRequest().requestUrl!!
        assertEquals("/v6/event/detail", request.encodedPath)
        assertEquals(setOf("id"), request.queryParameterNames)
        assertEquals("1086", request.queryParameter("id"))
        assertEquals("event", item.kind)
        assertEquals("", item.message)
        assertEquals(content, (item.event!!.sections.last().body as FullPostBody.Available).html)
        assertEquals("说明 <保留文字>", (item.event.sections.first().body as FullPostBody.Available).html)
        assertTrue(item.event.sections.first().literal)
        assertFalse(item.event.sections.last().literal)
        assertEquals(1792116000L, item.event.deadlineSeconds)
        assertEquals(listOf("https://fixture.invalid/icon-1.png"), item.imageUrls)
    }
    @Test fun eventNullDataHasExplicitMissingContentAndNoSuccessPlaceholder() = runBlocking {
        enqueue("""{"data":null}""")
        val result = repository.eventDetail("1086") as ApiResult.Failure
        assertEquals(FailureKind.BUSINESS, result.kind)
        assertEquals("未找到活动，可能已下架", result.message)
    }
    @Test fun malformedOrMismatchedEventNeverBecomesSuccess() = runBlocking {
        for (body in listOf("[]", "{\"title\":12}", "{\"description\":\"missing title\"}", "{\"id\":1087,\"title\":\"wrong event\"}")) {
            enqueue("{\"data\":$body}")
            assertEquals(FailureKind.INVALID_RESPONSE, (repository.eventDetail("1086") as ApiResult.Failure).kind)
        }
    }
    @Test fun unsafeActionAndExtremeDeadlineAreNotExposed() {
        for (url in listOf("javascript:alert(1)", "file:///private", "https://user:secret@example.com/registration")) {
            val data = buildJsonObject { put("title", "活动"); put("action_url", url); put("time_reg_end", Long.MAX_VALUE) }
            val item = decodeEventDetail(data, "1")
            assertNull(item.event!!.actionUrl); assertNull(item.event.deadlineSeconds)
        }
    }
    @Test fun eventLeadingZeroRouteAcceptsSameNumericServerIdentityWithoutOverflow() {
        assertEquals("001086", decodeEventDetail(Json.parseToJsonElement("""{"id":1086,"title":"活动"}"""), "001086").id)
        val longId = "9".repeat(80)
        assertEquals("00$longId", decodeEventDetail(buildJsonObject { put("id", longId); put("title", "活动") }, "00$longId").id)
    }
    @Test fun aggregateEventLimitsRejectInsteadOfTruncating() = runBlocking {
        enqueue(buildJsonObject { put("data", buildJsonObject { put("title", "活动"); put("content", "x".repeat(262_145)) }) }.toString())
        assertEquals(FailureKind.INVALID_RESPONSE, (repository.eventDetail("1") as ApiResult.Failure).kind)
        enqueue(buildJsonObject { put("data", buildJsonObject { put("title", "x".repeat(4001)) }) }.toString())
        assertEquals(FailureKind.INVALID_RESPONSE, (repository.eventDetail("1") as ApiResult.Failure).kind)
    }
    @Test fun dyhAndPictureTypesPreserveSourceQueryAndPagination() = runBlocking {
        enqueue("""{"data":[]}""")
        repository.dyhArticles("55", "square", PageCursor(2, "first", "last"))
        val dyh = server.takeRequest().requestUrl!!
        assertEquals("/v6/dyhArticle/list", dyh.encodedPath)
        assertEquals("55", dyh.queryParameter("dyhId")); assertEquals("square", dyh.queryParameter("type"))
        assertEquals("2", dyh.queryParameter("page")); assertEquals("first", dyh.queryParameter("firstItem")); assertEquals("last", dyh.queryParameter("lastItem"))
        enqueue("""{"data":[]}""")
        repository.pictures("星空:2026 &月亮", "newest", PageCursor(3, "a", "b"))
        val pic = server.takeRequest().requestUrl!!
        assertEquals("/v6/picture/list", pic.encodedPath)
        assertEquals("星空:2026 &月亮", pic.queryParameter("tag")); assertEquals("newest", pic.queryParameter("type"))
        assertEquals("3", pic.queryParameter("page")); assertEquals("a", pic.queryParameter("firstItem")); assertEquals("b", pic.queryParameter("lastItem"))
    }
    @Test fun invalidDirectIdsAndTypesNeverDispatch() = runBlocking {
        assertEquals(FailureKind.INVALID_ARGUMENT, (repository.eventDetail("../1") as ApiResult.Failure).kind)
        assertEquals(FailureKind.INVALID_ARGUMENT, (repository.dyhArticles("55", "invented") as ApiResult.Failure).kind)
        assertEquals(FailureKind.INVALID_ARGUMENT, (repository.dyhArticles("not-id", "all") as ApiResult.Failure).kind)
        for (tag in listOf("", " ", "a\nb", "x".repeat(4097))) assertEquals(FailureKind.INVALID_ARGUMENT, (repository.pictures(tag, "hot") as ApiResult.Failure).kind)
        assertEquals(FailureKind.INVALID_ARGUMENT, (repository.pictures("摄影", "invented") as ApiResult.Failure).kind)
        assertEquals(0, server.requestCount)
    }
    @Test fun eventReadsCaptureTheSupplierOnlyOnce() = runBlocking {
        var captures = 0
        val scoped = HttpCoolapkRepository(endpoints = ApiEndpoints(server.url("/"), server.url("/")), requestSession = {
            captures++
            RequestSession({ null }, { true }, { _, _ -> }, deviceCode = "synthetic-event")
        })
        enqueue("""{"data":{"id":1,"title":"活动"}}""")
        assertTrue(scoped.eventDetail("1") is ApiResult.Success)
        assertEquals(1, captures)
    }
    @Test fun linkedListsAndExistingHomeCaptureSupplierOnlyOncePerRead() = runBlocking {
        var captures = 0
        val scoped = HttpCoolapkRepository(endpoints = ApiEndpoints(server.url("/"), server.url("/")), requestSession = {
            captures++
            RequestSession({ null }, { true }, { _, _ -> }, deviceCode = "synthetic-linked")
        })
        repeat(3) { enqueue("""{"data":[]}""") }
        assertTrue(scoped.dyhArticles("1", "all") is ApiResult.Success)
        assertEquals(1, captures)
        assertTrue(scoped.pictures("摄影", "recommend") is ApiResult.Success)
        assertEquals(2, captures)
        assertTrue(scoped.home() is ApiResult.Success)
        assertEquals(3, captures)
    }
}
