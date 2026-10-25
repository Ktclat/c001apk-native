package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PersonalListUiTest {
 @get:Rule val compose = createComposeRule()
 @Test fun allSixRealDashboardCallbacksReachTypedRequests() {
  var route = ""
  val account = AccountState(listOf(AccountSummary("123","测试",false)),"123")
  compose.setContent { MaterialTheme { MyDashboard(account,rememberScrollState(),80.dp,{},onPersonalList = { route = personalListRoute(it) }) } }
  listOf("关注" to PersonalListKind.FOLLOW,"粉丝" to PersonalListKind.FAN,"我的常去" to PersonalListKind.RECENT,"我的收藏" to PersonalListKind.COLLECTION,"我的赞" to PersonalListKind.LIKE,"我的回复" to PersonalListKind.REPLY).forEach { (label,kind) ->
   compose.onNodeWithText(label).performScrollTo().performClick()
   compose.runOnIdle { assertEquals(PersonalListRequest(kind),parsePersonalListRoute(route)) }
  }
 }
 @Test fun invalidActiveSummaryAndRecoveryAreNotAuthenticated() {
  assertNull(personalListAccountId(AccountState(activeId = "123")))
  assertNull(personalListAccountId(AccountState(listOf(AccountSummary("123","expired",true)),"123")))
  assertNull(personalListAccountId(AccountState(listOf(AccountSummary("123","valid",false)),"123",recoveryRequired = true)))
  assertEquals("123",personalListAccountId(AccountState(listOf(AccountSummary("123","valid",false)),"123")))
 }
 @Test fun guestScreenNeverLoadsAndOffersLogin() {
  var route = ""
  val repo = PersonalListRepository { _, _, _ -> error("guest fetched") }
  compose.setContent { MaterialTheme { PersonalListScreen(repo,PersonalListRequest(PersonalListKind.COLLECTION),null,0,{}, { route = it },{}) } }
  compose.onNodeWithText("登录").performClick(); compose.runOnIdle { assertEquals("login",route) }
 }
 @Test fun realRowCallbackOpensCollectionAndPaginationUsesCursor() {
  val calls = mutableListOf<PageCursor>(); var route = ""
  val repo = PersonalListRepository { _, _, cursor ->
   calls += cursor
   ApiResult.Success(Page(if(cursor.page == 1) listOf(PersonalEntry("8",ContentItem("8","collection"),"真实合集","",PersonalDestination.Route("mycollection:8"))) else emptyList(),if(cursor.page == 1) PageCursor(2,"8","8") else null))
  }
  compose.setContent { MaterialTheme { PersonalListScreen(repo,PersonalListRequest(PersonalListKind.COLLECTION),"123",0,{}, { route = it },{}) } }
  compose.onNodeWithTag("entity-card:collection:8", useUnmergedTree = true).performClick(); compose.runOnIdle { assertEquals(PersonalListRequest(PersonalListKind.COLLECTION_ITEMS,"8"),parsePersonalListRoute(route)) }
  compose.onNodeWithText("加载更多").performScrollTo().performClick()
  compose.runOnIdle { assertEquals(listOf(PageCursor(),PageCursor(2,"8","8")),calls) }
 }
 @Test fun followingAndReplyTabsInvokeExactTypedRequests() {
  val kinds = mutableListOf<PersonalListKind>()
  var request by mutableStateOf(PersonalListRequest(PersonalListKind.FOLLOW))
  val repo = PersonalListRepository { req,_,_ -> kinds += req.kind; ApiResult.Success(Page(emptyList(),null)) }
  compose.setContent { MaterialTheme { PersonalListScreen(repo,request,"123",0,{}, {},{}) } }
  listOf("话题" to PersonalListKind.TOPICS,"数码" to PersonalListKind.PRODUCTS,"应用" to PersonalListKind.APPS).forEach { (label,kind) ->
   compose.onNodeWithText(label).performClick(); compose.runOnIdle { assertEquals(kind,kinds.last()) }
  }
  compose.runOnIdle { request = PersonalListRequest(PersonalListKind.REPLY) }
  compose.onNodeWithText("我收到的回复").performClick()
  compose.runOnIdle { assertEquals(PersonalListKind.REPLY_ME,kinds.last()) }
 }
 @Test fun generationChangeRemovesOldRowsAndLateCompletionCannotReturnThem() {
  val pending = CompletableDeferred<ApiResult<Page<PersonalEntry>>>()
  var generation by mutableLongStateOf(0)
  var uid by mutableStateOf("123")
  val repo = PersonalListRepository { _, account, _ -> if(account == "123") pending.await() else ApiResult.Success(Page(listOf(PersonalEntry("2",ContentItem("2","user"),"新账号内容","",PersonalDestination.Unsupported)),null)) }
  compose.setContent { MaterialTheme { PersonalListScreen(repo,PersonalListRequest(PersonalListKind.FAN),uid,generation,{}, {},{}) } }
  compose.runOnIdle { uid = "456"; generation++ }
  compose.onNodeWithText("新账号内容").assertExists()
  compose.runOnIdle { pending.complete(ApiResult.Success(Page(listOf(PersonalEntry("1",ContentItem("1","user"),"旧账号内容","",PersonalDestination.Unsupported)),null))) }
  compose.onNodeWithText("旧账号内容").assertDoesNotExist()
 }
 @Test fun sameGenerationCancellationReleasesSpinnerForManualRefresh() {
  var attempts = 0
  val repo = PersonalListRepository { _,_,_ -> attempts++; if(attempts == 1) throw kotlinx.coroutines.CancellationException("session changed") else ApiResult.Success(Page(emptyList(),null)) }
  compose.setContent { MaterialTheme { PersonalListScreen(repo,PersonalListRequest(PersonalListKind.FAN),"123",0,{}, {},{}) } }
  compose.onNodeWithTag("personal-loading").assertDoesNotExist()
  compose.onNodeWithText("刷新").assertIsEnabled().performClick()
  compose.onNodeWithText("暂无内容").assertExists()
  compose.runOnIdle { assertEquals(2,attempts) }
 }
 @Test fun failuresStayVisibleAndRetryDoesNotPretendEmptySuccess() {
  var attempts = 0
  val repo = PersonalListRepository { _,_,_ -> attempts++; ApiResult.Failure(FailureKind.RISK_CONTROL,"请求受限") }
  compose.setContent { MaterialTheme { PersonalListScreen(repo,PersonalListRequest(PersonalListKind.LIKE),"123",0,{}, {},{}) } }
  compose.onNodeWithText("请求受限").assertExists(); compose.onNodeWithText("暂无内容").assertDoesNotExist()
  compose.onNodeWithText("重试").performClick(); compose.runOnIdle { assertEquals(2,attempts) }
 }
 @Test fun explicitUnsupportedCollectionCannotFallBackToPublicRoute() {
  var route = ""
  val repo = PersonalListRepository { _,_,_ -> ApiResult.Success(Page(listOf(PersonalEntry("8",ContentItem("8","collection"),"禁止推断的收藏","",PersonalDestination.Unsupported)),null)) }
  compose.setContent { MaterialTheme { PersonalListScreen(repo,PersonalListRequest(PersonalListKind.COLLECTION),"123",0,{}, { route = it },{ route = it }) } }
  compose.onNodeWithText("禁止推断的收藏").assertExists()
  compose.onNodeWithTag("entity-card:collection:8", useUnmergedTree = true).assertHasNoClickAction()
  compose.onNodeWithText("未提供可打开的内容地址").assertExists()
  compose.runOnIdle { assertEquals("",route) }
 }
 @Test fun sourceAwareIdentityRetainsDistinctEntitiesMarkersAndContactDuplicates() {
  var kind by mutableStateOf(PersonalListKind.COLLECTION)
  val repo = PersonalListRepository { request,_,_ ->
   val raw = if (request.kind == PersonalListKind.FOLLOW) """[
    {"id":"8","entityType":"contacts","fUserInfo":{"uid":1002,"username":"重复联系人"}},
    {"id":"8","entityType":"contacts","fUserInfo":{"uid":1002,"username":"重复联系人"}}
   ]""" else """[
    {"id":"0","entityId":"11","entityType":"collection","title":"同名收藏"},
    {"id":"0","entityId":"12","entityType":"collection","title":"同名收藏"},
    {"id":"20","entityType":"card","entityTemplate":"listCard","title":"同名容器","entities":[null,{"id":"20","entityType":"card","title":"同名容器"}]}
   ]"""
   ApiResult.Success(io.github.ktclat.c001apk.nativeapp.data.decodePersonalPage(kotlinx.serialization.json.Json.parseToJsonElement(raw),PageCursor()))
  }
  compose.setContent { MaterialTheme { PersonalListScreen(repo,PersonalListRequest(kind),"123",0,{}, {},{}) } }
  compose.onAllNodesWithText("同名收藏").assertCountEquals(2)
  compose.onNodeWithTag("personal-list").performScrollToIndex(4)
  compose.onAllNodesWithText("同名容器").assertCountEquals(2)
  compose.onAllNodesWithText("此卡片的模板或嵌套内容暂不支持").assertCountEquals(2)
  compose.runOnIdle { kind = PersonalListKind.FOLLOW }
  compose.onAllNodesWithText("重复联系人").assertCountEquals(2)
 }


}
