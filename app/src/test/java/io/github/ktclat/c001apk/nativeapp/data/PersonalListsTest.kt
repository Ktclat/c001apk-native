package io.github.ktclat.c001apk.nativeapp.data

import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PersonalListsTest {
 @Test fun exactRoutesAndCursorContracts() = runBlocking {
  val captured = mutableListOf<Pair<String,Map<String,String>>>()
  val repo = HttpPersonalListRepository { path, params -> captured += path to params; ApiResult.Success(JsonArray(emptyList())) }
  PersonalListKind.entries.forEach { kind ->
   repo.load(PersonalListRequest(kind, "91"), "123", PageCursor(3,"11","22"))
   val (path, params) = captured.last()
   assertEquals(kind.path,path); assertEquals("3",params["page"]); assertEquals("11",params["firstItem"]); assertEquals("22",params["lastItem"])
   if(kind in setOf(PersonalListKind.COLLECTION, PersonalListKind.TOPICS, PersonalListKind.PRODUCTS)) assertFalse(params.containsKey("uid")) else assertEquals("123",params["uid"])
   assertEquals(if(kind == PersonalListKind.COLLECTION) "0" else null, params["showDefault"])
   assertEquals(if(kind == PersonalListKind.COLLECTION_ITEMS) "91" else null, params["id"])
  }
  assertEquals("#/product/followProductList",captured.first { it.second["url"] == "#/product/followProductList" }.second["url"])
 }
 @Test fun destinationsNeverMistakeRecordIdentityForEntityIdentity() {
  val page = decodePersonalPage(Json.parseToJsonElement("""[
   {"id":"100","entityType":"contacts","uid":"wrong","fUserInfo":{"uid":"27","username":"联系人"}},
   {"id":"101","entityType":"recentHistory","title":"话题","url":"/t/test"},
   {"id":"102","entityType":"collection","title":"合集"},
   {"id":"103","entityType":"feed_reply","message":"回复"},
   {"id":"104","entityType":"feed","message":"动态"}
  ]"""),PageCursor())
  assertEquals(PersonalDestination.Route("user:27"),page.items[0].destination)
  assertEquals(PersonalDestination.Link("/t/test"),page.items[1].destination)
  assertEquals(PersonalDestination.Route("mycollection:102"),page.items[2].destination)
  assertEquals(PersonalDestination.Route("thread:103"),page.items[3].destination)
  assertEquals(PersonalDestination.Route("feed:104"),page.items[4].destination)
  assertEquals(PageCursor(2,"100","104"),page.nextCursor)
  assertEquals("",page.items[2].detail)
 }
 @Test fun rawContactsDuplicatesAndUnknownKindsRemainVisible() {
  val page = decodePersonalPage(Json.parseToJsonElement("""[{"id":"1","entityType":"contacts"},{"id":"1","entityType":"contacts"},{"id":"2","entityType":"futureEntity"}]"""),PageCursor())
  assertEquals(3,page.items.size); assertEquals(PersonalDestination.Unsupported,page.items.last().destination)
  assertNull(decodePersonalPage(JsonArray(emptyList()),PageCursor()).nextCursor)
  assertNull(decodePersonalPage(Json.parseToJsonElement("""[{"id":"2","entityType":"feed"}]"""),PageCursor(2,"1","2")).nextCursor)
 }
 @Test fun nestedContainerKeepsParentCursorAndNestedTerminalStops() {
  val value = Json.parseToJsonElement("""[{"id":"20","entityType":"imageTextCard","entities":[{"id":"91","entityType":"topic","title":"主题","url":"/t/test"}]}]""")
  val page = decodePersonalPage(value,PageCursor())
  assertEquals("91",page.items.single().recordId); assertEquals(PageCursor(2,"20","20"),page.nextCursor)
  val terminal = decodePersonalPage(Json.parseToJsonElement("""[{"id":"21","entityType":"imageTextCard","entities":[{"entityType":"noMoreDataCard"}]}]"""),PageCursor())
  assertTrue(terminal.items.isEmpty()); assertNull(terminal.nextCursor)
 }
 @Test fun errorsAndCancellationAreNotConvertedToEmptyLists() = runBlocking {
  val error = ApiResult.Failure(FailureKind.RISK_CONTROL,"请求受限")
  assertEquals(error,HttpPersonalListRepository { _, _ -> error }.load(PersonalListRequest(PersonalListKind.LIKE),"123",PageCursor()))
  try { HttpPersonalListRepository { _, _ -> throw CancellationException("switched") }.load(PersonalListRequest(PersonalListKind.LIKE),"123",PageCursor()); fail("cancellation swallowed") } catch(_: CancellationException) { }
 }
 @Test fun invalidIdentityDoesNotFetch() = runBlocking {
  val repo = HttpPersonalListRepository { _, _ -> error("must not fetch") }
  assertTrue(repo.load(PersonalListRequest(PersonalListKind.FAN),"",PageCursor()) is ApiResult.Failure)
  assertTrue(repo.load(PersonalListRequest(PersonalListKind.COLLECTION_ITEMS,"../x"),"123",PageCursor()) is ApiResult.Failure)
 }
}
