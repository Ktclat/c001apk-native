// SPDX-License-Identifier: AGPL-3.0-only
// New 14th app scenario, not covered by the historical 13-case approval.
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ktclat.c001apk.nativeapp.local.*
import io.github.ktclat.c001apk.nativeapp.settings.fixedColorScheme
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmulatorLiteralSummaryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var fixture: EmulatorReadOnlyFixture
    private val message = "消息[受虐滑稽][笑哭][未知]<b>原样</b>"
    private val summary = "[笑哭]" + "正文".repeat(1200)
    private val notice = "合成通知[笑哭][未知]"
    private val privateRow = "<a href='/feed/99'>私信[笑哭]</a>[未知]"
    private val chatRow = "会话正文[受虐滑稽][笑哭]，只读，不发送。"
    @After fun close() { if (::fixture.isInitialized) fixture.close() }
    private fun row(id:String, text:String, extras:JsonObjectBuilder.()->Unit = {}) = buildJsonObject {
        put("id",id);put("entityType","feed");put("uid","42");put("username","合成作者");put("message",text);extras()
    }
    private fun data(vararg rows:JsonObject) = buildJsonObject { put("data",JsonArray(rows.toList())) }.toString()
    private fun layout(text:String, expectedArt:Int):TextLayoutResult {
        val node=compose.onNodeWithText(text).assertIsDisplayed();node.assertTextEquals(text)
        val layouts=mutableListOf<TextLayoutResult>();node.performSemanticsAction(SemanticsActions.GetTextLayoutResult){check(it(layouts))}
        val result=layouts.single();assertEquals(expectedArt,result.layoutInput.placeholders.size)
        assertTrue(result.layoutInput.text.getLinkAnnotations(0,text.length).isEmpty())
        for(token in listOf("[笑哭]","[受虐滑稽]")) if(text.contains(token))
            compose.onNode(hasTestTag("inline-emoticon:$token") and hasAnyAncestor(hasText(text)),useUnmergedTree=true).assertIsDisplayed()
        return result
    }
    @Test fun fourLiteralSummaryPathsKeepArtOverflowParentRoutesAndReadOnlyMessages() = recordPrimaryTestFailure("literal-summary") {
        val end=buildJsonObject{put("entityType","card");put("entityTemplate","noMoreDataCard")}
        fixture=EmulatorReadOnlyFixture(compose,"literal-summary"){request->when(request.url.encodedPath){
            "/v6/main/indexV8"->data(buildJsonObject{put("id","9900");put("entityType","card");put("entityTemplate","messageCard");put("description",message)},end)
            "/v6/user/feedList"->if(request.url.queryParameter("uid")=="42"&&request.url.queryParameter("isIncludeTop")=="0")
                data(row("7",summary){put("message_title","标题[受虐滑稽]")},end) else null
            "/v6/feed/detail"->if(request.url.queryParameter("id")=="7")buildJsonObject{put("data",row("7","合成本地关注目标"))}.toString()else null
            "/v6/feed/replyList"->if(request.url.queryParameter("id")=="7")data()else null
            "/v6/user/profile"->"""{"data":{"uid":"1","username":"合成测试账号"}}"""
            "/v6/notification/checkCount"->"""{"data":{"atme":1,"atcommentme":0,"feedlike":0,"contacts_follow":0,"message":1,"notification":0}}"""
            "/v6/notification/atMeList"->data(row("9911",notice){put("entityType","unsupportedSynthetic")},end)
            "/v6/message/list"->data(row("9912",privateRow){put("ukey","synthetic-conversation")},end)
            "/v6/message/chat"->if(request.url.queryParameter("ukey")=="synthetic-conversation")data(row("9913",chatRow),end)else null
            else->null
        }}
        fixture.render(localState=LocalState(follows=listOf(LocalFollowUser("42"))))
        fixture.awaitText(message)
        val messageLayout=layout(message,2);assertEquals(fixedColorScheme(0,false).onSecondaryContainer,messageLayout.layoutInput.style.color)
        val initial=fixture.requests.size;compose.onNodeWithText(message).performTouchInput{click(center)}
        compose.waitForIdle();assertEquals(initial,fixture.requests.size);fixture.capture("home-message-literal")
        compose.onNode(hasText("关注") and isSelectable()).performClick();fixture.awaitText(summary)
        compose.onNodeWithText(summary).performScrollTo();val body=layout(summary,1)
        assertEquals(8,body.layoutInput.maxLines);assertEquals(TextOverflow.Ellipsis,body.layoutInput.overflow)
        assertEquals(8,body.lineCount);assertTrue(body.isLineEllipsized(7))
        fixture.capture("local-summary-eight-line-ellipsis")
        compose.onNodeWithText(summary).performTouchInput{click(requireNotNull(body.placeholderRects.single()).center)}
        fixture.awaitText("合成本地关注目标");fixture.awaitReplies("7");fixture.capture("local-parent-feed7")
        assertEquals(1,fixture.count("/v6/feed/detail","7"));fixture.back();fixture.awaitTag("local-follow-list")
        compose.onNode(hasText("我的") and isSelectable()).performClick();fixture.awaitTag("my-root-list")
        compose.onNodeWithTag("my-notice-MENTIONS").performScrollTo().performClick();fixture.awaitText(notice);layout(notice,1)
        val beforeNotice=fixture.requests.size;compose.onNodeWithText(notice).assertHasNoClickAction().performTouchInput{click(center)}
        compose.waitForIdle();assertEquals(beforeNotice,fixture.requests.size);fixture.capture("unsupported-notice-no-navigation")
        compose.onNodeWithText("@我的动态").performClick();compose.onNodeWithText("私信").performClick();fixture.awaitText(privateRow);layout(privateRow,1)
        val beforePrivate=fixture.requests.size;compose.onNodeWithText(privateRow).performTouchInput{click(center)}
        compose.waitForIdle();assertEquals(beforePrivate,fixture.requests.size);fixture.capture("private-summary-literal-html")
        compose.onNodeWithText("打开会话").assertIsDisplayed().performClick();fixture.awaitText(chatRow);layout(chatRow,2)
        compose.onNodeWithText("确认发送").assertIsNotEnabled();fixture.capture("conversation-read-only")
        fixture.back();fixture.awaitText(privateRow);fixture.capture("conversation-back")
        assertEquals(1,fixture.count("/v6/message/list"));assertEquals(1,fixture.count("/v6/message/chat"))
        assertFalse(fixture.requests.any{it.url.queryParameter("id")=="99"})
        assertTrue(fixture.requests.all{it.method=="GET"});assertTrue(fixture.denied.isEmpty());assertTrue(fixture.browserIntents.isEmpty())
    }
}
