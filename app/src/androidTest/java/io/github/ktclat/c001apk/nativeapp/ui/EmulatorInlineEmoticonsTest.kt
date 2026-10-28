// SPDX-License-Identifier: AGPL-3.0-only
// Newly reconstructed actual-Android tests. Oracle asset bytes are independently hash-verified.
package io.github.ktclat.c001apk.nativeapp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class EmulatorInlineEmoticonsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val name = org.junit.rules.TestName()
    private lateinit var fixture: EmulatorReadOnlyFixture
    private val html = "<p>合成正文[受虐滑稽][受虐滑稽][受虐滑稽]，换行测试[笑哭][笑哭][笑哭]🎁。</p>" +
        "<p>特殊标记[图片][楼主][层主][置顶]，未知[QA未知表情]。</p>" +
        "<p><a href=\"/feed/9802\">合成链接[笑哭]</a>，代码<tt>[笑哭]</tt>保持文字。</p>" +
        "<p>本地图像<img src=\"coolapk_emotion_31_xiaoku.png\">。</p>"
    private val markdown get() = EmulatorEvidence.asset("oracles/copy_source.txt").toString(Charsets.UTF_8)
    private val formatted get() = EmulatorEvidence.asset("oracles/copy_expected.txt").toString(Charsets.UTF_8)
    private val ordinaryReply = "普通回复[受虐滑稽][笑哭]，换行之后🎁仍保留[QA未知表情]。"
    private val nestedReply = "嵌套回复[笑哭][受虐滑稽]，自己的原图与相邻文字。"
    @After fun close() { if (::fixture.isInitialized) fixture.close() }

    private fun feed(id: String, body: String, preview: Boolean = false) = buildJsonObject {
        put("id", id); put("entityType", "feed"); put("uid", "7"); put("username", "合成作者")
        put("message_title", "合成标题[受虐滑稽][笑哭]🎁"); put("message", body)
        put("replynum", 1); put("likenum", 0)
        if (preview) put("replyRows", buildJsonArray { add(buildJsonObject {
            put("id", "9891"); put("entityType", "feed_reply"); put("username", "合成热评")
            put("message", "热评引用[受虐滑稽][笑哭]🎁")
        }) })
    }
    private fun start(markdownBody: Boolean = false, dark: Boolean = false, scale: Float = 1f, detail: Boolean = false) {
        val body = if (markdownBody) markdown else html
        fixture = EmulatorReadOnlyFixture(compose, "emotes-${name.methodName}") { request -> when(request.url.encodedPath) {
            "/v6/main/indexV8" -> buildJsonObject { put("data", buildJsonArray { add(feed("9801", body, true)) }) }.toString()
            "/v6/feed/detail" -> when(request.url.queryParameter("id")) {
                "9801" -> buildJsonObject { put("data", feed("9801", body)) }.toString()
                "9802" -> buildJsonObject { put("data", feed("9802", "合成链接目标")) }.toString()
                else -> null
            }
            "/v6/feed/replyList" -> when(request.url.queryParameter("id")) {
                "9801" -> buildJsonObject { put("data", buildJsonArray { add(buildJsonObject {
                    put("id", "9811"); put("entityType", "feed_reply"); put("uid", "8"); put("username", "合成普通回复")
                    put("message", ordinaryReply); put("replynum", 1); put("likenum", 0)
                    put("replyRows", buildJsonArray { add(buildJsonObject {
                        put("id", "9812"); put("entityType", "feed_reply"); put("username", "合成嵌套回复"); put("message", nestedReply)
                    }) })
                }) }) }.toString()
                "9802" -> """{"data":[]}"""
                else -> null
            }
            "/v6/user/profile" -> """{"data":{"uid":"1","username":"合成测试账号"}}"""
            "/v6/notification/checkCount" -> """{"data":{"atme":0,"atcommentme":0,"feedlike":0,"contacts_follow":0,"message":0,"notification":0}}"""
            else -> null
        } }
        fixture.render(initialRoute = if (detail) "feed:9801" else null, dark = dark, scale = scale)
        if (detail) { fixture.awaitTag(if (markdownBody) "article-text-0" else "full-post-page"); fixture.awaitReplies("9801") }
        else fixture.awaitTag("feed-body:9801")
    }
    private fun bodyMatcher(detail: Boolean) = if (detail) hasTestTag("full-post-page") else
        hasText("本地图像[笑哭]。", substring = true) and hasAnyAncestor(hasTestTag("feed-body:9801"))
    private fun assertLocalImageBody(detail: Boolean, scrollToOccurrence: Boolean = false) {
        val matcher = bodyMatcher(detail)
        compose.waitUntil(30_000) { compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
        val body = compose.onNode(matcher, useUnmergedTree = true).performScrollTo()
        val text = body.fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        assertTrue(text.contains("本地图像[笑哭]。")); assertFalse(text.contains('\uFFFC'))
        assertTrue(text.contains("代码[笑哭]保持文字。")); assertTrue(text.contains("[QA未知表情]")); assertTrue(text.contains("🎁"))
        val smiles = compose.onAllNodes(hasTestTag("inline-emoticon:[笑哭]") and hasAnyAncestor(matcher), useUnmergedTree = true)
        smiles.assertCountEquals(5)
        val layouts = mutableListOf<TextLayoutResult>()
        body.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { check(it(layouts)) }
        val layout = layouts.single(); val start = text.indexOf("本地图像") + 4
        val placeholder = layout.layoutInput.placeholders.indexOfFirst { it.start == start && it.end == start + 4 }
        assertTrue(placeholder >= 0)
        val rect = requireNotNull(layout.placeholderRects[placeholder]); assertTrue(rect.width > 0 && rect.height > 0)
        if (scrollToOccurrence) smiles[4].performScrollTo()
        smiles[4].assertIsDisplayed()
        fixture.capture(if (detail) "reader-known-local-img-visible-after-scroll" else "preview-known-local-img-visible-after-scroll")
    }
    private fun openDetail(markdownBody: Boolean = false) {
        compose.onNodeWithTag("feed-body:9801").performScrollTo().performClick()
        fixture.awaitTag(if (markdownBody) "article-text-0" else "full-post-page"); fixture.awaitReplies("9801")
    }
    private fun assertReplies() {
        compose.onNodeWithTag("feed-detail-list").performScrollToKey("reply:feed_reply:9811")
        for ((message, stage) in listOf(ordinaryReply to "ordinary-reply", nestedReply to "nested-reply")) {
            fixture.awaitText(message)
            val matcher = hasText(message)
            compose.onNode(matcher).performScrollTo().assertIsDisplayed()
            compose.onNode(hasTestTag("inline-emoticon:[受虐滑稽]") and hasAnyAncestor(matcher), useUnmergedTree = true).assertIsDisplayed()
            compose.onNode(hasTestTag("inline-emoticon:[笑哭]") and hasAnyAncestor(matcher), useUnmergedTree = true).assertIsDisplayed()
            fixture.capture(stage)
        }
    }
    @Test fun actualFeedTitleBodyAndHotReplyUseBundledArt() = recordPrimaryTestFailure("actualFeedTitleBodyAndHotReplyUseBundledArt") {
        start(); fixture.awaitText("合成标题[受虐滑稽][笑哭]🎁")
        fixture.capture("title"); assertLocalImageBody(false, true)
        val hot = compose.onNodeWithText("合成热评：热评引用[受虐滑稽][笑哭]🎁")
        hot.performScrollTo().assertIsDisplayed(); fixture.capture("hot-reference")
        openDetail(); assertLocalImageBody(true, true); assertReplies()
    }
    @Test fun darkLargeFontHtmlLocalImageOccurrenceRemainsVisible() = recordPrimaryTestFailure("darkLargeFontHtmlLocalImageOccurrenceRemainsVisible") {
        start(dark = true, scale = 2.4f)
        fixture.capture("dark-title"); assertLocalImageBody(false, true)
        openDetail(); assertLocalImageBody(true, true); assertReplies()
    }
    @Test fun darkLargeFontFeedAndDetailRemainReadable() = recordPrimaryTestFailure("darkLargeFontFeedAndDetailRemainReadable") {
        start(markdownBody = true, dark = true, scale = 2.4f)
        fixture.capture("dark-markdown-preview"); openDetail(true)
        compose.onNodeWithTag("article-text-0").assertTextEquals(formatted)
        compose.onNodeWithTag("article-text-0").performScrollTo(); fixture.capture("dark-markdown-reader")
        assertReplies()
    }
    @Test fun directPointerOnLinkedEmoteDoesNotActivateParentFeedCard() = recordPrimaryTestFailure("directPointerOnLinkedEmoteDoesNotActivateParentFeedCard") {
        start()
        val matcher = bodyMatcher(false)
        compose.waitUntil(30_000) { compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
        val body = compose.onNode(matcher, useUnmergedTree = true).performScrollTo()
        val layouts = mutableListOf<TextLayoutResult>()
        body.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { check(it(layouts)) }
        val layout = layouts.single(); val text = layout.layoutInput.text.text
        val start = text.indexOf("合成链接") + 4
        val index = layout.layoutInput.placeholders.indexOfFirst { it.start == start && it.end == start + 4 }
        assertTrue(index >= 0); val glyph = requireNotNull(layout.placeholderRects[index])
        val node = body.fetchSemanticsNode(); val visible = node.boundsInRoot
        val raw = body.getUnclippedBoundsInRoot(); val density = compose.activity.resources.displayMetrics.density
        val absolute = glyph.translate(androidx.compose.ui.geometry.Offset(raw.left.value * density, raw.top.value * density))
        assertTrue(visible.contains(absolute.topLeft) && visible.contains(absolute.bottomRight - androidx.compose.ui.geometry.Offset(.1f, .1f)))
        fixture.capture("visible-linked-glyph")
        body.performTouchInput { click(glyph.center) }
        fixture.awaitText("合成链接目标"); fixture.awaitReplies("9802"); fixture.capture("linked-target")
        assertEquals(listOf("9802"), fixture.requests.filter { it.url.encodedPath == "/v6/feed/detail" }.map { it.url.queryParameter("id") })
    }
    @Test fun actualSettingsOffOnChangesRenderingWithoutChangingFeedSource() = recordPrimaryTestFailure("actualSettingsOffOnChangesRenderingWithoutChangingFeedSource") {
        start(); assertLocalImageBody(false, true)
        compose.onNode(hasText("我的") and isSelectable()).performClick(); fixture.awaitTag("my-root-list")
        compose.onNodeWithContentDescription("设置").performClick(); fixture.awaitTag("settings-category-list")
        compose.onNodeWithTag("settings-category-list").performScrollToNode(hasText("关注与内容"))
        compose.onNodeWithText("关注与内容").performClick(); fixture.awaitText("显示表情图片")
        val toggle = compose.onNode(isToggleable() and hasText("显示表情图片", substring = false))
        toggle.assertIsOn().performClick(); compose.waitUntil(30_000) { !fixture.settings.data.value.showEmoji }
        fixture.capture("memory-settings-off")
        compose.onNodeWithContentDescription("返回").performClick(); compose.onNodeWithContentDescription("返回").performClick()
        compose.onNode(hasText("首页") and isSelectable()).performClick(); fixture.awaitTag("feed-body:9801")
        compose.onAllNodesWithTag("inline-emoticon:[笑哭]", useUnmergedTree = true).assertCountEquals(0)
        fixture.capture("memory-off-literal")
        compose.onNode(hasText("我的") and isSelectable()).performClick(); compose.onNodeWithContentDescription("设置").performClick()
        fixture.awaitTag("settings-category-list"); compose.onNodeWithTag("settings-category-list").performScrollToNode(hasText("关注与内容"))
        compose.onNodeWithText("关注与内容").performClick(); fixture.awaitText("显示表情图片")
        toggle.assertIsOff().performClick(); compose.waitUntil(30_000) { fixture.settings.data.value.showEmoji }
        compose.onNodeWithContentDescription("返回").performClick(); compose.onNodeWithContentDescription("返回").performClick()
        compose.onNode(hasText("首页") and isSelectable()).performClick(); assertLocalImageBody(false, true)
    }
    @Test fun fullReaderRawAndActualCopyPreserveOriginalTokensAndCode() = recordPrimaryTestFailure("fullReaderRawAndActualCopyPreserveOriginalTokensAndCode") {
        assertEquals(97, formatted.length); assertEquals(117, markdown.length)
        assertEquals("a9ad7ccd576cb2959cda8141b71276b608f90a45b8007e90e4451b1aa0cd385c", sha(formatted))
        assertEquals("fc14b342f4089d5c62306d31546267f2a1dfeb932815cc3ff6cd10f3df1884f8", sha(markdown))
        start(markdownBody = true, detail = true)
        compose.onNodeWithTag("article-text-0").assertTextEquals(formatted)
        copyAll("article-text-0", formatted, "formatted")
        compose.onNodeWithTag("detail-source-menu").performClick()
        compose.onNodeWithTag("detail-source-toggle").performClick(); fixture.awaitTag("detail-source-page")
        compose.onNodeWithTag("detail-source-page").assertTextEquals(markdown)
        copyAll("detail-source-page", markdown, "raw")
    }
    private fun copyAll(tag: String, expected: String, stage: String) {
        val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        compose.runOnIdle { clipboard.setPrimaryClip(ClipData.newPlainText("synthetic-qa", "")); assertEquals("", clipboard.primaryClip!!.getItemAt(0).coerceToText(compose.activity).toString()) }
        val body = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>(); body.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { check(it(layouts)) }
        body.performTouchInput { longClick(layouts.single().getBoundingBox(0).center) }
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        toolbarAction(device, "(?i)(select all|全选)", "$stage-select-all").click()
        val before = SystemClock.uptimeMillis(); val clockBefore = compose.mainClock.currentTime
        compose.waitForIdle()
        EmulatorEvidence.text("copy-$stage-sync.txt", "beforeUptime=$before afterUptime=${SystemClock.uptimeMillis()} beforeCompose=$clockBefore afterCompose=${compose.mainClock.currentTime}")
        val copy = toolbarAction(device, "(?i)(copy|复制)", "$stage-copy")
        device.dumpWindowHierarchy(File(EmulatorEvidence.directory, "copy-$stage-toolbar.xml"))
        EmulatorEvidence.windowDump("copy-$stage-toolbar")
        EmulatorEvidence.capture("copy-$stage-toolbar-original")
        copy.click() // void API; returning normally is not itself successful clipboard activation.
        try {
            compose.waitUntil(10_000) { clipboard.primaryClip?.let { it.itemCount == 1 && it.getItemAt(0).coerceToText(compose.activity).toString() == expected } == true }
            val actual = clipboard.primaryClip!!.getItemAt(0).coerceToText(compose.activity).toString()
            assertEquals(expected, actual)
            EmulatorEvidence.text("copy-$stage-exact.txt", actual)
            EmulatorEvidence.text("copy-$stage-receipt.txt", "freshEmptyBefore=true\nexpectedUtf16=${expected.length}\nactualUtf16=${actual.length}\nsha256=${sha(actual)}\nCopyReturnedNormally=true\n")
        } catch (failure: Throwable) {
            runCatching { EmulatorEvidence.text("copy-$stage-primary-failure.txt", failure.stackTraceToString()) }
            runCatching { EmulatorEvidence.text("copy-$stage-post-failure-nonauthoritative.txt", clipboard.primaryClip?.getItemAt(0)?.coerceToText(compose.activity)?.toString().orEmpty()) }
            throw failure
        } finally { compose.runOnIdle { clipboard.setPrimaryClip(ClipData.newPlainText("synthetic-qa", "")) } }
    }
    private fun toolbarAction(device: UiDevice, regex: String, stage: String): UiObject2 {
        val target = device.wait(Until.findObject(By.desc(Pattern.compile(regex))), 10_000)
        if (target == null) {
            runCatching { device.dumpWindowHierarchy(File(EmulatorEvidence.directory, "copy-$stage-lookup-failed.xml")) }
            runCatching { EmulatorEvidence.windowDump("copy-$stage-lookup-failed") }
            runCatching { EmulatorEvidence.capture("copy-$stage-lookup-failed") }
            error("Current visible toolbar action absent: $regex")
        }
        assertTrue(target.isEnabled); assertTrue(target.isClickable); assertFalse(target.visibleBounds.isEmpty)
        EmulatorEvidence.text("copy-$stage-action.txt", "package=${target.applicationPackage}\ndescription=${target.contentDescription}\nbounds=${target.visibleBounds}\nenabled=${target.isEnabled}\nclickable=${target.isClickable}")
        return target
    }
    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}
