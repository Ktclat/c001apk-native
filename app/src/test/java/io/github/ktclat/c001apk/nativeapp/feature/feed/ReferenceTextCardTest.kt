// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.feature.feed

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.ktclat.c001apk.nativeapp.domain.FeedReferenceKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual card, offline only. Classification separately proves the no-background-path contract. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-420dpi")
class ReferenceTextCardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val hotText = "适中的字号和行距，连续阅读更舒服。"
    private val actions = FeedCardActions({}, null, {}, {}, {})
    private fun card() = FeedCardModel(
        id = "card", author = "作者", authorId = "author",
        forward = FeedReference("forward", "转发作者", body = "转发正文😀"),
        hotReply = FeedReference("hot", "热评作者", body = hotText, kind = FeedReferenceKind.HOT_REPLY, parentFeedId = "card"),
    )

    @Test fun exactHotFixtureAndForwardHaveNoPendingPlaceholder() {
        assertEquals(ReferenceText.Immediate(hotText), classifyReferenceText(hotText, true))
        compose.setContent { MaterialTheme { NativeFeedCard(card(), actions) } }
        // No parse polling or timeout rescue for eligible references.
        compose.onNodeWithText("热评作者：$hotText").assertExists()
        compose.onNodeWithText("@转发作者：转发正文😀").assertExists()
        compose.onNodeWithText("正在排版…").assertDoesNotExist()
    }

    @Test fun changedBodySameReferenceAndChangedReferenceUseCurrentPlainText() {
        val model = mutableStateOf(card())
        compose.setContent { MaterialTheme { NativeFeedCard(model.value, actions) } }
        compose.onNodeWithText("热评作者：$hotText").assertExists()
        compose.runOnIdle { model.value = model.value.copy(hotReply = model.value.hotReply!!.copy(body = "更新正文👍")) }
        compose.onNodeWithText("热评作者：更新正文👍").assertExists()
        compose.onNodeWithText("热评作者：$hotText").assertDoesNotExist()
        compose.runOnIdle { model.value = model.value.copy(hotReply = model.value.hotReply!!.copy(id = "new-hot", author = "另一作者", body = "另一热评")) }
        compose.onNodeWithText("另一作者：另一热评").assertExists()
        compose.onNodeWithText("热评作者：更新正文👍").assertDoesNotExist()
        compose.onNodeWithText("正在排版…").assertDoesNotExist()
        compose.runOnIdle { model.value = model.value.copy(hotReply = model.value.hotReply!!.copy(body = "")) }
        compose.onNodeWithText("另一作者：").assertExists()
        compose.onNodeWithText("正在排版…").assertDoesNotExist()
    }

    @Test fun formattedReferencesAndPlainHtmlTransitionsKeepParserOutput() {
        val model = mutableStateOf(card())
        compose.setContent { MaterialTheme { NativeFeedCard(model.value, actions) } }
        compose.runOnIdle {
            model.value = model.value.copy(
                forward = model.value.forward!!.copy(body = "<b>转发</b>&amp;内容"),
                hotReply = model.value.hotReply!!.copy(body = "<p>热评&nbsp;内容</p>"),
            )
        }
        awaitText("@转发作者：转发&内容")
        awaitText("热评作者：热评\u00a0内容")
        compose.onNodeWithText("热评作者：$hotText").assertDoesNotExist()
        compose.runOnIdle { model.value = model.value.copy(hotReply = model.value.hotReply!!.copy(body = "<i>替换</i>&lt;正文&gt;")) }
        awaitText("热评作者：替换<正文>")
        compose.onNodeWithText("热评作者：热评\u00a0内容").assertDoesNotExist()
        compose.runOnIdle { model.value = model.value.copy(hotReply = model.value.hotReply!!.copy(body = hotText), forward = null) }
        compose.onNodeWithText("热评作者：$hotText").assertExists()
        compose.onNodeWithText("热评作者：替换<正文>").assertDoesNotExist()
        compose.onNodeWithText("正在排版…").assertDoesNotExist()
    }

    @Test fun repeatedHtmlPlainHtmlReplacementNeverRestoresPreviousReference() {
        val model = mutableStateOf(card().copy(forward = null))
        compose.setContent { MaterialTheme { NativeFeedCard(model.value, actions) } }
        repeat(3) { index ->
            compose.runOnIdle {
                model.value = model.value.copy(hotReply = model.value.hotReply!!.copy(
                    id = "html-$index", author = "作者$index", body = "<b>内容$index</b>&amp;正文",
                ))
            }
            awaitText("作者$index：内容$index&正文")
            compose.runOnIdle {
                model.value = model.value.copy(hotReply = model.value.hotReply!!.copy(
                    id = "plain-$index", author = "新作者$index", body = hotText,
                ))
            }
            compose.onNodeWithText("新作者$index：$hotText").assertExists()
            compose.onNodeWithText("作者$index：内容$index&正文").assertDoesNotExist()
            compose.onNodeWithText("正在排版…").assertDoesNotExist()
        }
    }

    @Test fun hotAndForwardUseTheirOriginalLimitsAndDeletedReferenceStaysDeleted() {
        val reference = FeedReference("ref", "引用作者", body = "中".repeat(1200) + "<b>尾部</b>")
        val model = mutableStateOf(card().copy(forward = reference, hotReply = reference.copy(kind = FeedReferenceKind.HOT_REPLY, parentFeedId = "card")))
        compose.setContent { MaterialTheme { NativeFeedCard(model.value, actions) } }
        compose.onNodeWithText("引用作者：" + "中".repeat(1200)).assertExists()
        awaitText("@引用作者：" + "中".repeat(1200) + "尾部")
        compose.runOnIdle { model.value = model.value.copy(forward = reference.copy(deleted = true), hotReply = null) }
        compose.onNodeWithText("原动态已删除").assertExists()
        compose.onNodeWithText("@引用作者：" + "中".repeat(1200) + "尾部").assertDoesNotExist()
        compose.runOnIdle { model.value = model.value.copy(forward = reference.copy(body = "恢复正文")) }
        compose.onNodeWithText("@引用作者：恢复正文").assertExists()
        compose.onNodeWithText("原动态已删除").assertDoesNotExist()
        compose.onNodeWithText("正在排版…").assertDoesNotExist()
    }

    private fun awaitText(text: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).assertExists()
    }
}
