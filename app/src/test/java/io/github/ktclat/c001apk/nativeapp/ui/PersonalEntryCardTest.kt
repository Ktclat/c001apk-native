package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.ktclat.c001apk.nativeapp.data.decodePersonalPage
import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PersonalEntryCardTest {
    @get:Rule val compose = createComposeRule()
    private val routes = mutableListOf<String>()
    private val links = mutableListOf<String>()
    private var refreshes = 0
    private fun decoded(json: String) = decodePersonalPage(Json.parseToJsonElement("[$json]"), PageCursor()).items.single()
    private fun show(value: State<PersonalEntry>) {
        compose.setContent { MaterialTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                PersonalEntryCard(value.value, routes::add, links::add, { refreshes++ })
            }
        } }
    }
    @Test fun feedMainAndCommentsHonorExplicitDestinationAndExposeNoMutation() {
        val item = decoded("""{"id":"11","entityType":"feed","uid":"7","username":"作者","message":"正文","likenum":0,"userAction":{"like":1}}""")
        val state = mutableStateOf(item.copy(destination = PersonalDestination.Link("/feed/99"), likeAuthor = "喜欢者"))
        show(state)
        compose.onNodeWithText("喜欢者赞了这条内容").assertExists()
        compose.onNodeWithTag("feed-body:11").performClick()
        compose.onNodeWithTag("feed-comments:11").performScrollTo().performClick()
        assertEquals(listOf("/feed/99", "/feed/99"), links)
        assertTrue(routes.isEmpty())
        compose.onNodeWithTag("feed-like:11").assertIsNotEnabled()
        compose.onNodeWithTag("feed-more:11").assertDoesNotExist()
        compose.runOnIdle { state.value = item.copy(destination = PersonalDestination.Unsupported) }
        compose.onNodeWithTag("feed-body:11").assertHasNoClickAction()
        compose.onNodeWithTag("feed-comments:11").assertIsNotEnabled()
        compose.onNodeWithText("暂不支持打开此内容").assertExists()
    }
    @Test fun replyUrlAuthorityAndNestedThreadDoNotGainComposeOrLikeActions() {
        val state = mutableStateOf(decoded("""{"id":"12","entityType":"feed_reply","uid":"7","username":"回复者","message":"我的回复正文","url":"/feed/99","likeUserInfo":{"username":"喜欢者"},"replynum":2,"replyRows":[{"id":"13","entityType":"feed_reply","username":"楼中楼作者","message":"嵌套回复"}]}"""))
        show(state)
        compose.onNodeWithText("喜欢者赞了这条内容").assertExists()
        compose.onNodeWithText("嵌套回复").performClick()
        compose.onNodeWithTag("personal-reply-open:12").performScrollTo().performClick()
        assertEquals(listOf("/feed/99", "/feed/99"), links)
        assertTrue(routes.isEmpty())
        compose.onNodeWithText("回复", substring = false).assertDoesNotExist()
        compose.onNodeWithTag("reply-like:12").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(destination = PersonalDestination.Unsupported) }
        compose.onNodeWithTag("personal-reply-open:12").assertDoesNotExist()
        compose.onNodeWithText("2条回复").assertIsNotEnabled()
    }
    @Test fun allKnownTemplatesDispatchInsteadOfUnsupportedFallback() {
        fun card(template: String) = decoded("""{"id":"20","entityType":"card","entityTemplate":"$template","title":"模板标题","description":"模板说明","url":"/feed/99","entities":[]}""")
        val state = mutableStateOf(card("messageCard")); show(state)
        compose.onNodeWithText("模板说明").assertExists()
        for (template in HomeCardTemplate.entries.filterNot { it == HomeCardTemplate.END }) {
            compose.runOnIdle { state.value = card(template.wire) }
            compose.onNodeWithTag("home-template:20").assertExists()
            compose.onNodeWithText("此卡片的模板或嵌套内容暂不支持").assertDoesNotExist()
        }
        compose.runOnIdle { state.value = card("refreshCard") }
        compose.onNodeWithTag("home-refresh:20").performClick(); assertEquals(1, refreshes)
        compose.runOnIdle { state.value = card("titleCard") }
        compose.onNodeWithTag("home-title:20").performClick(); assertEquals(listOf("/feed/99"), links)
        compose.runOnIdle { state.value = card("iconLinkGridCard") }
        compose.onNodeWithText("暂无可显示的入口").assertExists()
        compose.onNodeWithText("此卡片的模板或嵌套内容暂不支持").assertDoesNotExist()
        compose.runOnIdle { state.value = card("future") }
        compose.onNodeWithText("此卡片的模板或嵌套内容暂不支持").assertExists()
    }
    @Test fun personalReplyUnknownZeroAndPositiveCountsRemainDistinct() {
        fun row(count: String) = decoded("""{"id":"12","entityType":"feed_reply","message":"正文","url":"/feed/99"$count,"replyRows":[{"id":"13","entityType":"feed_reply","message":"嵌套"}]}""")
        val state = mutableStateOf(row("")); show(state)
        compose.onNodeWithText("查看回复").assertExists()
        compose.onNodeWithText("0条回复").assertDoesNotExist()
        compose.onNodeWithContentDescription("赞 数量未知").assertIsNotEnabled()
        compose.runOnIdle { state.value = row(",\"replynum\":0,\"likenum\":0") }
        compose.onNodeWithText("查看回复").assertDoesNotExist()
        compose.onNodeWithText("0条回复").assertExists()
        compose.onNodeWithContentDescription("赞 0").assertIsNotEnabled()
        compose.runOnIdle { state.value = row(",\"replynum\":7") }
        compose.onNodeWithText("7条回复").assertExists()
    }
    @Test fun invalidAuthorDoesNotAcquireAUserNavigationAction() {
        show(mutableStateOf(decoded("""{"id":"11","entityType":"feed","uid":"0","username":"无效作者","message":"正文"}""")))
        compose.onNodeWithContentDescription("查看无效作者的主页").assertHasNoClickAction()
        assertTrue(routes.isEmpty())
    }
    @Test fun malformedFeedChildrenAndInvalidForwardRemainExplicitWithoutDeadClick() {
        val state = mutableStateOf(decoded("""{"id":"11","entityType":"feed","username":"作者","message":"正文","entities":[null],"forwardSourceType":"feed","forwardSourceFeed":{"id":"bad-id","username":"引用作者","message":"不可打开的引用"}}"""))
        show(state)
        compose.onNodeWithText("部分嵌套内容数据不完整或超出显示上限").performScrollTo().assertExists()
        compose.onNodeWithText("@引用作者：不可打开的引用").assertIsNotEnabled()
        assertTrue(routes.isEmpty())
    }
}
