package io.github.ktclat.c001apk.nativeapp.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PagedPostTextTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun hundredLinksDoNotCreateHundredInlineActionRows() {
        val html = (0..99).joinToString(" ") { "<a href='https://example.test/$it'>链接$it</a>" }
        compose.setContent { MaterialTheme { PagedPostText(html) {} } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("本段链接（100）").fetchSemanticsNodes().isNotEmpty() }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText((0..99).joinToString(" ") { "链接$it" }).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val text = layouts.single().layoutInput.text
        assertEquals((0..99).map { "https://example.test/$it" }, text.getLinkAnnotations(0, text.length).map { (it.item as androidx.compose.ui.text.LinkAnnotation.Clickable).tag })
        // Inline anchor semantics are not extra action rows. Exactly one menu button precedes the dialog.
        assertEquals(1, compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).fetchSemanticsNodes().size)
        compose.onNodeWithText("本段链接（100）").performClick()
        compose.onNodeWithText("本段链接").assertIsDisplayed()
        assertTrue(compose.onAllNodes(hasClickAction() and hasAnyAncestor(isDialog())).fetchSemanticsNodes().size < 20)
    }
    @Test fun linksOutsideCurrentTextSegmentStayOffThatSegment() {
        val html = "a".repeat(4800) + "<a href='https://example.test/tail'>尾部链接</a>"
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { PagedPostText(html) {} } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("阅读完整内容").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("本段链接", substring = true)).assertCountEquals(0)
        compose.onNodeWithText("阅读完整内容").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("下一段").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("本段链接", substring = true)).assertCountEquals(0)
        compose.onNodeWithText("下一段").performScrollTo().performClick()
        compose.onNodeWithText("本段链接（1）").assertExists()
    }
    @Test fun replyTargetsKeepFeedAndCommentKindsDistinct() {
        assertFalse(replyDraftForRoute("compose-reply:feed:42")!!.replyToReply)
        assertTrue(replyDraftForRoute("compose-reply:reply:42")!!.replyToReply)
        assertEquals("42", replyDraftForRoute("compose-reply:reply:42")!!.replyId)
        assertNull(replyDraftForRoute("compose-reply:unknown:42"))
        assertNull(replyDraftForRoute("compose-reply:reply:42/other"))
    }
}
