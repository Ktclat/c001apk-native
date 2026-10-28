// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.ktclat.c001apk.nativeapp.domain.FullPostBody
import io.github.ktclat.c001apk.nativeapp.domain.FeedReferenceKind
import io.github.ktclat.c001apk.nativeapp.feature.feed.*
import io.github.ktclat.c001apk.nativeapp.settings.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** New recovery gate on actual production composables. Not an emulator approval receipt. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReconstructedRichTextUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val result = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(result)) }
        return result.single()
    }

    @Test fun actualFeedTitleBodyAndHotReferenceUseArtAndLinkTapDoesNotOpenParent() {
        var posts = 0; var references = 0
        val links = mutableListOf<String>()
        val card = FeedCardModel(id = "emote", author = "作者", authorId = "author", title = "标题[受虐滑稽][笑哭]",
            body = "正文[受虐滑稽]<a href='/feed/42'>[笑哭]</a>",
            hotReply = FeedReference("hot", "热评作者", body = "热评[受虐滑稽][笑哭]", kind = FeedReferenceKind.HOT_REPLY, parentFeedId = "emote"))
        compose.setContent { MaterialTheme { NativeFeedCard(card, FeedCardActions({ posts++ }, null, {},
            { links += it }, { references++ })) } }
        // Read exact Text leaves, not the clickable parent with merged title/body actions.
        compose.waitUntil(10_000) { compose.onAllNodesWithText("正文[受虐滑稽][笑哭]", useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
        for (raw in listOf("标题[受虐滑稽][笑哭]", "热评作者：热评[受虐滑稽][笑哭]")) {
            val text = compose.onNodeWithText(raw, useUnmergedTree = true).assertTextEquals(raw)
            val textLayout = layout(text)
            assertEquals(raw, textLayout.layoutInput.text.text)
            assertEquals(2, textLayout.layoutInput.placeholders.size)
        }
        val node = compose.onNodeWithText("正文[受虐滑稽][笑哭]", useUnmergedTree = true)
            .assertTextEquals("正文[受虐滑稽][笑哭]")
        val value = layout(node)
        assertEquals("正文[受虐滑稽][笑哭]", value.layoutInput.text.text)
        assertEquals(2, value.layoutInput.placeholders.size)
        val link = value.layoutInput.text.getLinkAnnotations(0, value.layoutInput.text.length).single()
        assertEquals("[笑哭]", value.layoutInput.text.subSequence(link.start, link.end).text)
        assertEquals("/feed/42", (link.item as LinkAnnotation.Clickable).tag)
        val index = value.layoutInput.placeholders.indexOfFirst { it.start == link.start }
        val box = requireNotNull(value.placeholderRects[index])
        assertTrue(box.width > 0 && box.height > 0)
        node.performTouchInput { click(box.center) }
        compose.runOnIdle { assertEquals(listOf("/feed/42"), links); assertEquals(0, posts); assertEquals(0, references) }
    }

    @Test fun repeatedUrlOccurrencesRemainClickableAndTheMenuDeduplicates() {
        val urls = mutableListOf<String>()
        compose.setContent { MaterialTheme { FullPostText(FullPostBody.Available(
            "<a href='/feed/42'>[笑哭]</a>中<a href='/feed/42'>再看[受虐滑稽]</a>"), "", { urls += it }, null) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("full-post-page").fetchSemanticsNodes().size == 1 }
        val node = compose.onNodeWithTag("full-post-page")
        val value = layout(node)
        assertEquals(2, value.layoutInput.text.getLinkAnnotations(0, value.layoutInput.text.length).size)
        value.layoutInput.text.getLinkAnnotations(0, value.layoutInput.text.length).forEach { link ->
            node.performTouchInput { click(value.getBoundingBox(link.start).center) }
        }
        compose.onNodeWithText("本段链接（1）").performClick()
        compose.onNode(hasText("[笑哭]") and hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle { assertEquals(listOf("/feed/42", "/feed/42", "/feed/42"), urls) }
    }

    @Test fun retainedImmutableLinksCannotActAfterReplacementOrDisposalButLiveCallbackUpdates() {
        var destination by mutableStateOf("/feed/42")
        var generation by mutableIntStateOf(1)
        var shown by mutableStateOf(true)
        val opened = mutableListOf<String>()
        compose.setContent { MaterialTheme { if (shown) {
            val text = decodePostHtml("<a href='$destination'>[笑哭]链接</a>")
            val captured = generation
            NativeRichText(text.richText, Modifier.testTag("owned"), MaterialTheme.typography.bodyLarge,
                text.links, { opened += "$captured:$it" })
        } } }
        fun listener() = layout(compose.onNodeWithTag("owned")).layoutInput.text.let {
            it.getLinkAnnotations(0, it.length).single().item as LinkAnnotation.Clickable
        }
        val first = listener()
        compose.runOnIdle { generation = 2 }
        compose.runOnIdle { first.linkInteractionListener!!.onClick(first); assertEquals(listOf("2:/feed/42"), opened) }
        compose.runOnIdle { destination = "/feed/43" }
        val current = listener()
        compose.runOnIdle {
            first.linkInteractionListener!!.onClick(first)
            current.linkInteractionListener!!.onClick(current)
            assertEquals(listOf("2:/feed/42", "2:/feed/43"), opened)
            shown = false
        }
        compose.onNodeWithTag("owned").assertDoesNotExist()
        compose.runOnIdle { current.linkInteractionListener!!.onClick(current); assertEquals(2, opened.size) }
    }

    @Test fun wholeRolesAndOriginalTokensSurviveNarrowLargeFontLayout() {
        val raw = "前[楼主][层主][置顶][图片][受虐滑稽][笑哭]后[未知]🎁"
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2.4f)) {
            NativeRichText(AnnotatedString(raw), Modifier.width(220.dp).testTag("large"), MaterialTheme.typography.bodyLarge)
        } } }
        compose.onNodeWithTag("large").assertTextEquals(raw)
        val value = layout(compose.onNodeWithTag("large"))
        assertEquals(6, value.placeholderRects.size)
        assertTrue(value.placeholderRects.all { it != null && it.width > 0 && it.height > 0 })
        listOf("[楼主]", "[层主]", "[置顶]").forEach {
            compose.onNodeWithTag("inline-emoticon:$it", useUnmergedTree = true).assertExists()
        }
    }


    @Test fun anchorsAfterTheInlineCapRemainAvailableThroughTheLazyMenu() {
        val opened = mutableListOf<String>()
        val html = (0..149).joinToString(" ") { "<a href='/feed/${it + 1}'>$it</a>" }
        compose.setContent { MaterialTheme { PagedPostText(html) { opened += it } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("本段链接（150）").fetchSemanticsNodes().size == 1 }
        val value = layout(compose.onNodeWithText((0..149).joinToString(" ")))
        assertEquals(128, value.layoutInput.text.getLinkAnnotations(0, value.layoutInput.text.length).size)
        compose.onNodeWithText("本段链接（150）").performClick()
        compose.onNode(hasScrollAction() and hasAnyAncestor(isDialog())).performScrollToNode(hasText("149"))
        compose.onNode(hasText("149") and hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle { assertEquals(listOf("/feed/150"), opened) }
    }

    @Test fun structuredDuplicateAnchorsKeepOccurrencesButOneMenuDestination() {
        val html = "<a href='/feed/42'>[笑哭]</a>和<a href='/feed/42'>另一个[笑哭]</a>"
        val body = FullPostBody.Structured("[]", null, null, listOf(io.github.ktclat.c001apk.nativeapp.domain.ArticleBlock.Text(html)))
        compose.setContent { MaterialTheme { FullPostText(body, "", {}, null) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("article-text-0").fetchSemanticsNodes().size == 1 }
        val value = layout(compose.onNodeWithTag("article-text-0"))
        assertEquals(2, value.layoutInput.text.getLinkAnnotations(0, value.layoutInput.text.length).size)
        compose.onNodeWithText("本段链接（1）").performClick()
        compose.onNode(hasText("[笑哭]") and hasAnyAncestor(isDialog())).assertExists()
    }

    @Test fun realSettingsSwitchPersistsExistingShowEmojiPreference() {
        val repository = SettingsRepository.create(compose.activity)
        runBlocking { repository.update { it.copy(showEmoji = true) } }
        var settings by mutableStateOf(runBlocking { repository.settings.first() })
        compose.setContent { MaterialTheme { SettingsScreen(settings, { next ->
            runBlocking { repository.update { next } }; settings = next
        }, {}, {}, {}, {}, {}) } }
        compose.onNodeWithTag("settings-category-list").performScrollToNode(hasText("关注与内容"))
        compose.onNodeWithText("关注与内容").performClick()
        compose.onNodeWithTag("settings-detail-list").performScrollToNode(hasText("显示表情图片"))
        val switch = compose.onNode(hasText("显示表情图片") and isToggleable())
        switch.assertIsOn().performClick()
        switch.assertIsOff()
        assertFalse(runBlocking { SettingsRepository.create(compose.activity).settings.first().showEmoji })
        switch.performClick().assertIsOn()
        assertTrue(runBlocking { SettingsRepository.create(compose.activity).settings.first().showEmoji })
    }
}
