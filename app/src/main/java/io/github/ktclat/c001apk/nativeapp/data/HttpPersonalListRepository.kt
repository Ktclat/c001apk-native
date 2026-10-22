// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.serialization.json.*

/** Uses the existing signed/session-scoped transport. No alternate client or mutable account cache. */
class HttpPersonalListRepository(private val fetch: suspend (String, Map<String,String>) -> ApiResult<JsonElement>) : PersonalListRepository {
 override suspend fun load(request: PersonalListRequest, uid: String, cursor: PageCursor): ApiResult<Page<PersonalEntry>> {
  if (!uid.matches(Regex("[0-9]+"))) return failure(FailureKind.AUTHENTICATION)
  val kind = request.kind
  if (kind == PersonalListKind.COLLECTION_ITEMS && request.collectionId?.matches(Regex("[0-9]+")) != true) return failure(FailureKind.INVALID_ARGUMENT)
  val params = mutableMapOf("page" to cursor.page.toString())
  if (kind !in setOf(PersonalListKind.COLLECTION, PersonalListKind.TOPICS, PersonalListKind.PRODUCTS)) params["uid"] = uid
  if (kind == PersonalListKind.FEED) { params["showAnonymous"] = "0"; params["isIncludeTop"] = "1" }
  if (kind == PersonalListKind.COLLECTION) params["showDefault"] = "0"
  if (kind == PersonalListKind.COLLECTION_ITEMS) params["id"] = request.collectionId!!
  if (kind in setOf(PersonalListKind.TOPICS, PersonalListKind.PRODUCTS)) {
   params["url"] = if (kind == PersonalListKind.TOPICS) "#/topic/userFollowTagList" else "#/product/followProductList"
   params["title"] = if (kind == PersonalListKind.TOPICS) "我关注的话题" else "我关注的数码吧"
   params["subTitle"] = ""
  }
  cursor.firstItem?.let { params["firstItem"] = it }; cursor.lastItem?.let { params["lastItem"] = it }
  return when(val result = fetch(kind.path, params)) {
   is ApiResult.Failure -> result
   is ApiResult.Success -> try { ApiResult.Success(decodePersonalPage(result.value, cursor)) } catch(e: ApiProblem) { e.failure }
  }
 }
}
internal fun decodePersonalPage(value: JsonElement, cursor: PageCursor): Page<PersonalEntry> {
 val rows = value as? JsonArray ?: ApiDecoder.invalid()
 if(rows.size > 500) ApiDecoder.invalid()
 val content = ApiDecoder.items(rows)
 val pairs = mutableListOf<Pair<JsonObject, ContentItem>>()
 fun collect(row: JsonObject, item: ContentItem, depth: Int) {
  val children = row["entities"] as? JsonArray
  if (children != null && item.children.isNotEmpty() && item.kind !in setOf("feed", "feed_reply") && depth < 3) {
   children.take(item.children.size).forEachIndexed { index, child -> collect(child as? JsonObject ?: ApiDecoder.invalid(), item.children[index], depth + 1) }
  } else pairs += row to item
 }
 rows.forEachIndexed { index, element -> collect(element as? JsonObject ?: ApiDecoder.invalid(), content[index], 0) }
 val entries = pairs.filterNot { it.second.kind == "noMoreDataCard" }.map { (row, item) ->
  fun text(key:String) = (row[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
  fun nested(key:String, field:String) = ((row[key] as? JsonObject)?.get(field) as? JsonPrimitive)?.contentOrNull.orEmpty()
  fun route(prefix:String, id:String): PersonalDestination = if(id.matches(Regex("[0-9]+"))) PersonalDestination.Route("$prefix:$id") else PersonalDestination.Unsupported
  val destination = when(item.kind) {
   "contacts" -> route("user", nested("userInfo", "uid").ifBlank { nested("fUserInfo", "uid") })
   "collection" -> route("mycollection", item.id)
   "feed" -> route("feed", item.id)
   "feed_reply" -> if(text("url").isNotBlank()) PersonalDestination.Link(text("url")) else route("thread", item.id)
   "recentHistory", "user", "topic", "product", "apk" -> if(text("url").isNotBlank()) PersonalDestination.Link(text("url")) else PersonalDestination.Unsupported
   else -> PersonalDestination.Unsupported
  }
  val label = when(item.kind) {
   "contacts" -> nested("userInfo","username").ifBlank { nested("fUserInfo","username") }
   "recentHistory" -> listOf(text("targetTypeTitle"), item.title).filter { it.isNotBlank() }.joinToString("：")
   else -> item.title.ifBlank { item.userName }
  }.ifBlank { "内容信息未提供" }
  val detail = if(item.kind == "collection") listOf(text("isOpenTitle"), text("followNum").takeIf { it.isNotEmpty() }?.let { "${it}人关注" }.orEmpty(), text("itemNum").takeIf { it.isNotEmpty() }?.let { "${it}个内容" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ") else item.message
  PersonalEntry(text("id"), item, label, detail, destination, nested("likeUserInfo","username").takeIf { it.isNotBlank() })
 }
 fun rawId(element: JsonElement?) = ((element as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
 val last = rawId(rows.lastOrNull())
 val next = if(content.any { it.kind == "noMoreDataCard" } || pairs.any { it.second.kind == "noMoreDataCard" } || last == null || last == cursor.lastItem) null else PageCursor(cursor.page + 1, cursor.firstItem ?: rawId(rows.firstOrNull()), last)
 return Page(entries, next)
}
