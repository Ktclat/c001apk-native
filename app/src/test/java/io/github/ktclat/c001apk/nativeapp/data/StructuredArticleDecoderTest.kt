// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Source-schema fixtures only. No production responses, sockets, credentials or persistence. */
class StructuredArticleDecoderTest {
    private fun envelope(raw: JsonElement? = null, title: JsonElement? = null, cover: JsonElement? = null,
        message: JsonElement? = JsonPrimitive("ORDINARY_PREVIEW")): JsonObject = buildJsonObject {
        put("id", "42"); put("entityType", "feed")
        message?.let { put("message", it) }
        raw?.let { put("message_raw_output", it) }
        title?.let { put("title", it) }
        cover?.let { put("message_cover", it) }
        put("message_title", "ORDINARY_TITLE_NOT_ARTICLE_TITLE")
    }
    private fun decode(raw: String, title: String? = null, cover: String? = null): FullPostBody =
        requireNotNull(ApiDecoder.feedDetail(envelope(JsonPrimitive(raw), title?.let(::JsonPrimitive), cover?.let(::JsonPrimitive))).fullPostBody)
    private fun text(message: String = "body") = buildJsonObject { put("type", "text"); put("message", message) }
    private fun image(url: String? = "https://image.coolapk.com/a@640x480.jpg") = buildJsonObject {
        put("type", "image"); put("url", url?.let(::JsonPrimitive) ?: JsonNull); put("description", "caption")
    }
    private fun unavailable(expected: FullPostBodyLimit, raw: JsonElement, title: JsonElement? = null, cover: JsonElement? = null) {
        val item = ApiDecoder.feedDetail(envelope(raw, title, cover))
        assertEquals("Original preview must remain bounded, not be claimed complete", "ORDINARY_PREVIEW", item.message)
        assertEquals(expected, (item.fullPostBody as FullPostBody.Unavailable).reason)
    }
    private fun unavailable(expected: FullPostBodyLimit, raw: String) = unavailable(expected, JsonPrimitive(raw))

    @Test fun absentNullEmptyAndExactNullUseOrdinaryMessageButWhitespaceDoesNot() {
        for (raw in listOf<JsonElement?>(null, JsonNull, JsonPrimitive(""), JsonPrimitive("null"))) {
            assertEquals(FullPostBody.Available("ORDINARY_PREVIEW"), ApiDecoder.feedDetail(envelope(raw)).fullPostBody)
        }
        for (raw in listOf(" ", "\n\t", " null ", "\nnull", "NULL")) unavailable(FullPostBodyLimit.MALFORMED_ARTICLE, raw)
    }

    @Test fun genuinelyEmptyArrayKeepsOrdinaryBranchWithoutArticleDecorations() {
        for (raw in listOf("[]", " [ ] ", "\n[]\t")) {
            assertEquals(FullPostBody.Available("ORDINARY_PREVIEW"), decode(raw, "ARTICLE_TITLE", "https://image.coolapk.com/cover.jpg"))
        }
        val missing = ApiDecoder.feedDetail(envelope(JsonPrimitive("[]"), message = null)).fullPostBody
        assertEquals(FullPostBody.Unavailable(FullPostBodyLimit.MISSING_MESSAGE), missing)
    }

    @Test fun innerJsonMustBeAStringContainingAnArrayOfObjects() {
        for (outer in listOf<JsonElement>(JsonPrimitive(1), JsonPrimitive(true), buildJsonArray {}, buildJsonObject {})) {
            unavailable(FullPostBodyLimit.MALFORMED_ARTICLE, outer)
        }
        for (raw in listOf("{", "[", "[{}", "[{},]", "{}", "\"text\"", "1", "true", "[null]", "[1]", "[true]", "[\"text\"]", "[[]]")) {
            unavailable(FullPostBodyLimit.MALFORMED_ARTICLE, raw)
        }
    }

    @Test fun onlyTrueSourceKeysAreUsedAndUnusedKnownFieldsStillRequireNullableStrings() {
        val raw = """[{"type":"text","message":"TRUE_TEXT","text":"GUESSED_FIELD_NOT_DISPLAYED","extra":{"anything":true}},{"type":"image","url":"https://image.coolapk.com/synthetic.jpg","description":"PLAIN_CAPTION"},{"type":"shareUrl","url":"/feed/43","title":"PLAIN_SHARE_TITLE","subTitle":"NOT_RENDERED","logo":"NOT_RENDERED"}]"""
        val body = decode(raw, "TRUE_ARTICLE_TITLE", "https://image.coolapk.com/cover.jpg") as FullPostBody.Structured
        assertEquals(raw, body.rawOutput)
        assertEquals("TRUE_ARTICLE_TITLE", body.title)
        assertEquals("https://image.coolapk.com/cover.jpg", body.cover)
        assertEquals(3, body.blocks.size)
        assertEquals(ArticleBlock.Text("TRUE_TEXT"), body.blocks[0])
        assertEquals(ArticleBlock.Image("https://image.coolapk.com/synthetic.jpg", "PLAIN_CAPTION"), body.blocks[1])
        assertEquals(ArticleBlock.ShareUrl("/feed/43", "PLAIN_SHARE_TITLE"), body.blocks[2])
        val onlyGuessed = decode("""[{"type":"text","text":"DO_NOT_INVENT_A_MESSAGE"}]""") as FullPostBody.Structured
        assertEquals(listOf(ArticleBlock.Text("")), onlyGuessed.blocks)
    }

    @Test fun nullableDisplayFieldsPreserveEmptyBlocksInsteadOfSilentlyDroppingThem() {
        val raw = """[{"type":"text","message":null},{"type":"image","url":null,"description":null},{"type":"shareUrl","url":null,"title":null,"subTitle":null,"logo":null}]"""
        val body = decode(raw) as FullPostBody.Structured
        assertEquals(listOf(ArticleBlock.Text(""), ArticleBlock.Image(null, ""), ArticleBlock.ShareUrl(null, "")), body.blocks)
        assertNull(body.title); assertNull(body.cover)
    }

    @Test fun wrongTypesInEveryKnownFieldAreMalformedEvenWhenFieldIsUnusedByThatBlock() {
        val fields = listOf("type", "message", "url", "description", "title", "subTitle", "logo")
        val wrong = listOf<JsonElement>(JsonPrimitive(7), JsonPrimitive(false), buildJsonArray {}, buildJsonObject {})
        for (field in fields) for (value in wrong) {
            val block = JsonObject(text() + (field to value))
            unavailable(FullPostBodyLimit.MALFORMED_ARTICLE, JsonArray(listOf(block)).toString())
        }
        for (value in wrong) {
            unavailable(FullPostBodyLimit.MALFORMED_ARTICLE, JsonPrimitive(JsonArray(listOf(text())).toString()), title = value)
            unavailable(FullPostBodyLimit.MALFORMED_ARTICLE, JsonPrimitive(JsonArray(listOf(text())).toString()), cover = value)
        }
    }

    @Test fun unknownMissingAndSourceTitleTypesRejectTheWholeArticleNotJustOneBlock() {
        for (unknown in listOf<JsonObject>(buildJsonObject { put("message", "missing type") },
            buildJsonObject { put("type", JsonNull) }, buildJsonObject { put("type", "") },
            buildJsonObject { put("type", "title") }, buildJsonObject { put("type", "shareurl") },
            buildJsonObject { put("type", "video"); put("message", "unknown") })) {
            for (blocks in listOf(listOf(unknown), listOf(text("HEAD"), unknown, text("TAIL")))) {
                unavailable(FullPostBodyLimit.UNSUPPORTED_ARTICLE, JsonArray(blocks).toString())
            }
        }
    }

    @Test fun duplicateImagesRemainOrderedOccurrencesAndSourceBlocksAreNotDecoratedTwice() {
        val url = "https://image.coolapk.com/repeated@640x480.jpg"
        val raw = JsonArray(listOf(image(url), text("BETWEEN"), image(url))).toString()
        val body = decode(raw, "TITLE", url) as FullPostBody.Structured
        assertEquals(listOf(ArticleBlock.Image(url, "caption"), ArticleBlock.Text("BETWEEN"), ArticleBlock.Image(url, "caption")), body.blocks)
        assertEquals(url, body.cover)
    }

    @Test fun blockAndImageLimitsIncludeAllOccurrencesAndTheCover() {
        assertEquals(128, (decode(JsonArray(List(128) { text("") }).toString()) as FullPostBody.Structured).blocks.size)
        unavailable(FullPostBodyLimit.ARTICLE_COMPLEXITY, JsonArray(List(129) { text("") }).toString())
        assertEquals(18, (decode(JsonArray(List(18) { image() }).toString()) as FullPostBody.Structured).blocks.size)
        unavailable(FullPostBodyLimit.ARTICLE_COMPLEXITY, JsonArray(List(19) { image() }).toString())
        assertTrue(decode(JsonArray(List(17) { image() }).toString(), cover = "https://image.coolapk.com/cover.jpg") is FullPostBody.Structured)
        unavailable(FullPostBodyLimit.ARTICLE_COMPLEXITY, JsonPrimitive(JsonArray(List(18) { image() }).toString()),
            cover = JsonPrimitive("https://image.coolapk.com/cover.jpg"))
    }

    @Test fun urlLengthBoundIsExactAndDoesNotCoerceOrTruncate() {
        val url = "https://image.coolapk.com/".let { it + "x".repeat(4096 - it.length) }
        assertEquals(url, ((decode(JsonArray(listOf(image(url))).toString()) as FullPostBody.Structured).blocks.single() as ArticleBlock.Image).url)
        unavailable(FullPostBodyLimit.LINK_COMPLEXITY, JsonArray(listOf(image(url + "x"))).toString())
        val share = buildJsonObject { put("type", "shareUrl"); put("url", url); put("title", "label") }
        assertTrue(decode(JsonArray(listOf(share)).toString()) is FullPostBody.Structured)
        unavailable(FullPostBodyLimit.LINK_COMPLEXITY, JsonArray(listOf(JsonObject(share + ("url" to JsonPrimitive(url + "x"))))).toString())
    }

    @Test fun escapedInnerJsonCannotBypassDepthAndNodePreflight() {
        // The outer string is shallow, while its ignored extra field contains a deep JSON subtree.
        val tooDeep = "[{\"type\":\"text\",\"message\":\"body\",\"extra\":" + "[".repeat(63) + "0" + "]".repeat(63) + "}]"
        assertTrue(boundedJsonEnvelope(envelope(JsonPrimitive(tooDeep)).toString()))
        unavailable(FullPostBodyLimit.ARTICLE_COMPLEXITY, tooDeep)
        val acceptedDepth = "[{\"type\":\"text\",\"message\":\"body\",\"extra\":" + "[".repeat(62) + "0" + "]".repeat(62) + "}]"
        assertTrue(decode(acceptedDepth) is FullPostBody.Structured)
        val manyNodes = "[{\"type\":\"text\",\"extra\":[" + List(20_000) { "0" }.joinToString(",") + "]}]"
        assertTrue(boundedJsonEnvelope(envelope(JsonPrimitive(manyNodes)).toString()))
        unavailable(FullPostBodyLimit.ARTICLE_COMPLEXITY, manyNodes)
    }

    @Test fun rawBudgetIncludesExactSourceTitleAndCoverAndNeverReturnsATruncatedArticle() {
        val prefix = "[{\"type\":\"text\",\"message\":\""
        val suffix = "\"}]"
        val raw = prefix + "x".repeat(262_144 - prefix.length - suffix.length) + suffix
        assertEquals(262_144, raw.length)
        assertEquals(raw, (decode(raw) as FullPostBody.Structured).rawOutput)
        for (data in listOf(envelope(JsonPrimitive(raw + " ")), envelope(JsonPrimitive(raw), JsonPrimitive("x")),
            envelope(JsonPrimitive(raw), cover = JsonPrimitive("x")))) {
            val result = ApiDecoder.feedDetail(data).fullPostBody
            assertEquals(FullPostBody.Unavailable(FullPostBodyLimit.BODY_TOO_LARGE, 262_145), result)
        }
    }

    @Test fun decodingChecksCancellationInsideLargeQuotedInputAndDoesNotSwallowIt() {
        val raw = JsonArray(listOf(text("x".repeat(100_000)))).toString()
        var totalChecks = 0
        ApiDecoder.feedDetail(envelope(JsonPrimitive(raw))) { totalChecks++ }
        assertTrue("Must checkpoint inside the quoted source, not just before and after parsing", totalChecks > 20)
        for (stopAt in listOf(1, 3, totalChecks / 2, totalChecks)) {
            val cancellation = CancellationException("synthetic article decode $stopAt")
            var checks = 0
            try {
                ApiDecoder.feedDetail(envelope(JsonPrimitive(raw))) { if (++checks == stopAt) throw cancellation }
                fail("Cancellation at checkpoint $stopAt must propagate")
            } catch (actual: CancellationException) { assertSame(cancellation, actual) }
        }
    }

    @Test fun listAndNestedChildrenDoNotAcquireArticleBodiesOrUnboundedRawSource() {
        val raw = JsonArray(listOf(text("ARTICLE_ONLY_DETAIL"))).toString()
        val root = envelope(JsonPrimitive(raw))
        assertNull(ApiDecoder.item(root).fullPostBody)
        val parent = JsonObject(root + ("entities" to JsonArray(listOf(root))))
        val decoded = ApiDecoder.feedDetail(parent)
        assertTrue(decoded.fullPostBody is FullPostBody.Structured)
        assertNull(decoded.children.single().fullPostBody)
    }
    @Test fun innerNodeBoundaryIsExactEvenForAnIgnoredExtraField() {
        // Array, object, type key/value, extra key, nested array contribute six lexical nodes.
        fun source(nodes: Int) = "[{\"type\":\"text\",\"extra\":[" + List(nodes - 6) { "0" }.joinToString(",") + "]}]"
        assertTrue(decode(source(20_000)) is FullPostBody.Structured)
        unavailable(FullPostBodyLimit.ARTICLE_COMPLEXITY, source(20_001))
    }

    @Test fun titleAndCoverArePreservedAsAbsentVersusExplicitEmptyForIdentity() {
        val raw = JsonArray(listOf(text())).toString()
        val absent = decode(raw) as FullPostBody.Structured
        val empty = decode(raw, "", "") as FullPostBody.Structured
        assertNull(absent.title); assertNull(absent.cover)
        assertEquals("", empty.title); assertEquals("", empty.cover)
        val cover = "https://image.coolapk.com/".let { it + "x".repeat(4096 - it.length) }
        assertEquals(cover, (decode(raw, cover = cover) as FullPostBody.Structured).cover)
        unavailable(FullPostBodyLimit.LINK_COMPLEXITY, JsonPrimitive(raw), cover = JsonPrimitive(cover + "x"))
    }

}
