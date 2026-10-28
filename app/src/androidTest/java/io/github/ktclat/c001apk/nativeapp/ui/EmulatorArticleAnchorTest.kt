// SPDX-License-Identifier: AGPL-3.0-only
// New real-emulator regression from the persisted unit fixture; historical failure cause remains unknown.
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap

@RunWith(AndroidJUnit4::class)
class EmulatorArticleAnchorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var fixture: EmulatorReadOnlyFixture
    private val replies = ConcurrentHashMap<String, List<JsonObject>>()
    private val routeIds = (42..58).toList()
    private val pages = mutableListOf<String>()
    private val html = buildString {
        repeat(4) { page ->
            val prefix = "ORIGINAL_SEGMENT_${page + 1}_START"; val suffix = "ORIGINAL_SEGMENT_${page + 1}_END"
            val labels = routeIds.joinToString("") { "打开动态$it" }
            val prose = "这是离线合成阅读正文用于验证原生返回位置不来自真实账户"
            val needed = 4000 - prefix.length - labels.length - suffix.length
            val fill = prose.repeat((needed + prose.length - 1) / prose.length).take(needed)
            pages += prefix + fill + labels + suffix
            append(prefix); append(fill)
            routeIds.forEach { append("<a href='/feed/$it'>打开动态$it</a>") }; append(suffix)
        }
    }
    @After fun close() { if (::fixture.isInitialized) fixture.close() }
    private fun feed(id: String) = buildJsonObject {
        put("id", id); put("entityType", "feed"); put("uid", "9"); put("username", "合成文章锚点作者")
        put("message", "ARTICLE_PREVIEW_NOT_COMPLETE"); put("replynum", 0); put("likenum", 0)
        put("message_raw_output", buildJsonArray { add(buildJsonObject { put("type", "text"); put("message", html) }) }.toString())
    }
    private fun reply(id: Int) = buildJsonObject {
        put("id", id.toString()); put("entityType", "feed_reply"); put("uid", "9"); put("username", "合成回复$id")
        put("message", "ANCHOR_REPLY_$id" + if (id == 500) "<a href='/feed/43'>OPEN_FROM_REPLY</a>" else "")
        put("replynum", 0); put("likenum", 0)
    }
    private fun awaitPage(id: String, page: Int) {
        fixture.awaitTag("feed:$id"); fixture.awaitText(pages[page - 1])
        compose.onNodeWithTag("article-text-0").assertTextEquals(pages[page - 1])
        compose.onNodeWithTag("full-post-progress").assertTextEquals("第 $page/4 段")
        compose.onNodeWithTag("reader-preparing").assertDoesNotExist()
        compose.onNodeWithTag("full-post-pending").assertDoesNotExist()
    }
    @Test fun actualReply500AnchorSurvivesCachedPrependWhileChild43IsOpen() = recordPrimaryTestFailure("actualReply500AnchorSurvivesCachedPrependWhileChild43IsOpen") {
        assertTrue(pages.all { it.length == 4000 })
        val original = (496..510).map(::reply); replies["42"] = original
        fixture = EmulatorReadOnlyFixture(compose, "anchor") { request -> when (request.url.encodedPath) {
            "/v6/main/indexV8" -> """{"data":[]}"""
            "/v6/feed/detail" -> request.url.queryParameter("id")?.takeIf { it in setOf("42", "43") }?.let {
                buildJsonObject { put("data", feed(it)) }.toString()
            }
            "/v6/feed/replyList" -> request.url.queryParameter("id")?.takeIf { it in setOf("42", "43") }?.let {
                buildJsonObject { put("data", JsonArray(replies[it].orEmpty())) }.toString()
            }
            else -> null
        } }
        fixture.render(initialRoute = "feed:42", authenticated = false)
        awaitPage("42", 1); fixture.awaitReplies("42")
        for (page in 2..3) {
            compose.onNodeWithText("下一段").performScrollTo().assertIsDisplayed().performClick(); awaitPage("42", page)
        }
        compose.onNodeWithTag("feed-detail-list").performScrollToKey("reply:feed_reply:500")
        val exact = "ANCHOR_REPLY_500OPEN_FROM_REPLY"
        fixture.awaitText(exact)
        val before = compose.onNodeWithText(exact).assertIsDisplayed().getUnclippedBoundsInRoot()
        compose.onNodeWithText("本段链接（1）").assertIsDisplayed()
        fixture.capture("reply500-before")
        compose.onNodeWithText("本段链接（1）").performClick()
        compose.onNode(hasText("OPEN_FROM_REPLY") and hasAnyAncestor(isDialog())).assertIsDisplayed().performClick()
        awaitPage("43", 1); fixture.awaitReplies("43"); fixture.capture("child43")
        val key = "ordered:42:RECENT_REPLY:false"; val callsBefore = fixture.count("/v6/feed/replyList", "42")
        replies["42"] = listOf(reply(490), reply(491)) + original
        compose.runOnIdle { fixture.model.load(key, refresh = true) }
        compose.waitUntil(30_000) {
            compose.onRoot().fetchSemanticsNode()
            fixture.count("/v6/feed/replyList", "42") == callsBefore + 1 && fixture.model.states.value[key]?.let {
                it.loaded && !it.loading && it.items.firstOrNull()?.id == "490"
            } == true
        }
        val account = fixture.store.state.value
        val saved = fixture.model.readerPositions.controls(ReaderOwner(account.activeId, account.generation), "42")
        fixture.back(); fixture.awaitText(exact); compose.waitForIdle()
        // No scroll/reposition until current displayed target geometry is compared.
        val after = compose.onNodeWithText(exact).assertIsDisplayed().getUnclippedBoundsInRoot()
        assertEquals(before, after)
        val returned = fixture.model.readerPositions.controls(ReaderOwner(account.activeId, account.generation), "42")
        assertEquals("reply:feed_reply:500", saved?.rowKey)
        assertEquals(saved?.rowKey, returned?.rowKey)
        assertEquals(saved?.offset, returned?.offset)
        assertEquals(2, returned?.page)
        assertEquals(1, fixture.count("/v6/feed/detail", "42"))
        assertEquals(2, fixture.count("/v6/feed/replyList", "42"))
        EmulatorEvidence.text("anchor-retention.txt", "displayedBoundsBefore=$before\ndisplayedBoundsAfter=$after\n" +
            "savedBookmarkBeforeBack=$saved\nretainedBookmarkAfterBack=$returned\n" +
            "Bookmarks are disposal-saved values, not a fresh live first-visible-row observation. No corrective scroll before geometry assertion.")
        fixture.capture("reply500-after")
        compose.onNodeWithTag("feed-detail-list").performScrollToKey("post")
        awaitPage("42", 3); fixture.capture("page3-retained")
    }
}
