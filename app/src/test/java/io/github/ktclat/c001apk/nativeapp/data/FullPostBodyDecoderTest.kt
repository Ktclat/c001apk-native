package io.github.ktclat.c001apk.nativeapp.data

import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** All generated locally. No production content, credentials or endpoint calls. */
class FullPostBodyDecoderTest {
    private fun fixture(message: String, article: JsonElement? = null): JsonObject = buildJsonObject {
        put("id", "42"); put("entityType", "feed"); put("message", message)
        article?.let { put("message_raw_output", it) }
    }
    @Test fun listStaysBoundedWhileDetailRetainsActualTail() {
        val html = "前".repeat(12_000) + "<a href='https://example.test/tail'>真实尾部</a>"
        val list = ApiDecoder.item(fixture(html))
        assertEquals(12_000, list.message.length)
        assertTrue(list.bodyTruncated)
        assertNull(list.fullPostBody)
        val detail = ApiDecoder.feedDetail(fixture(html))
        assertEquals(list.message, detail.message)
        assertTrue(detail.bodyTruncated) // Explicitly the preview, not the separate complete source.
        assertEquals(html, (detail.fullPostBody as FullPostBody.Available).html)
    }
    @Test fun exactBoundariesAndOversizeNeverProduceSilentSubstring() {
        for (size in listOf(0, 12_000, 12_001, FullPostBodyBounds.MAX_HTML_UNITS)) {
            val body = "x".repeat(size)
            val item = ApiDecoder.feedDetail(fixture(body))
            assertEquals(body, (item.fullPostBody as FullPostBody.Available).html)
            assertEquals(size > 12_000, item.bodyTruncated)
        }
        val size = FullPostBodyBounds.MAX_HTML_UNITS + 1
        assertEquals(FullPostBody.Unavailable(FullPostBodyLimit.BODY_TOO_LARGE, size),
            ApiDecoder.feedDetail(fixture("x".repeat(size))).fullPostBody)
    }
    @Test fun structuredArticleUsesTheSourceSchemaAndEmptyListRemainsOrdinary() {
        val raw = """[{"type":"text","message":"article tail"},{"type":"image","url":"https://example.test/image"}]"""
        val detail = ApiDecoder.feedDetail(fixture("short summary", JsonPrimitive(raw)))
        assertEquals(raw, (detail.fullPostBody as FullPostBody.Structured).rawOutput)
        assertEquals("short summary", detail.message)
        for (article in listOf<JsonElement>(JsonPrimitive("malformed"), buildJsonArray { })) {
            assertEquals(FullPostBody.Unavailable(FullPostBodyLimit.MALFORMED_ARTICLE), ApiDecoder.feedDetail(fixture("summary", article)).fullPostBody)
        }
        for (article in listOf(JsonNull, JsonPrimitive("null"), JsonPrimitive(""), JsonPrimitive("[]"))) {
            assertEquals(FullPostBody.Available("message"), ApiDecoder.feedDetail(fixture("message", article)).fullPostBody)
        }
    }
    @Test fun absentOrWrongMessageDoesNotPromoteDescriptionToFullBody() {
        for (message in listOf<JsonElement?>(null, JsonNull, JsonPrimitive(123), buildJsonObject { })) {
            val data = buildJsonObject {
                put("id", "42"); put("entityType", "feed"); put("description", "summary")
                message?.let { put("message", it) }
            }
            assertEquals(FullPostBody.Unavailable(FullPostBodyLimit.MISSING_MESSAGE), ApiDecoder.feedDetail(data).fullPostBody)
        }
    }
    @Test fun previewBoundaryDoesNotSplitSurrogateOrLoseFullSource() {
        val html = "x".repeat(11_999) + "😀尾"
        val item = ApiDecoder.feedDetail(fixture(html))
        assertEquals(11_999, item.message.length)
        assertTrue(item.bodyTruncated)
        assertEquals(html, (item.fullPostBody as FullPostBody.Available).html)
    }
    @Test fun childrenRemainBoundedAndDoNotAcquireDetailBodies() {
        val child = fixture("x".repeat(12_001))
        val root = JsonObject(fixture("root") + ("entities" to JsonArray(listOf(child))))
        val item = ApiDecoder.feedDetail(root)
        assertNull(item.children.single().fullPostBody)
        assertEquals(12_000, item.children.single().message.length)
    }
    @Test fun jsonPreflightBoundsDepthNodesAndIgnoresQuotedDelimiters() {
        assertTrue(boundedJsonEnvelope("[".repeat(64) + "0" + "]".repeat(64)))
        assertFalse(boundedJsonEnvelope("[".repeat(65) + "0" + "]".repeat(65)))
        assertTrue(boundedJsonEnvelope("[0,0]", maxNodes = 3))
        assertFalse(boundedJsonEnvelope("[0,0,0]", maxNodes = 3))
        assertTrue(boundedJsonEnvelope(buildJsonObject { put("text", "[".repeat(1000) + "\\\"}") }.toString()))
        assertFalse(boundedJsonEnvelope("{\"unterminated"))
        try { ApiDecoder.envelope("[".repeat(65) + "0" + "]".repeat(65)); fail() }
        catch (problem: ApiProblem) { assertTrue(problem.failure.message.contains("安全读取上限")) }
    }
    @Test fun quotedCaptchaExtraCannotBypassTheNestedJsonBound() {
        val input = buildJsonObject {
            put("messageStatus", "err_request_captcha_v2")
            put("messageExtra", "[".repeat(65) + "0" + "]".repeat(65))
        }.toString()
        try { ApiDecoder.envelope(input); fail("Expected challenge") }
        catch (problem: ApiProblem) {
            assertEquals(FailureKind.CAPTCHA, problem.failure.kind)
            assertTrue(problem.failure.challenge is ApiChallenge.Netease)
        }
    }
}
