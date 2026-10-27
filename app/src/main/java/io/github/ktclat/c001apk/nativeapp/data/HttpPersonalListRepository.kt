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
  if (item.kind == "card" && item.entityTemplate == "listCard" && depth < 4) {
   // ApiDecoder uses this same bounded object sequence, skipping malformed non-object children.
   val children = (row["entities"] as? JsonArray).orEmpty().take(100).filterIsInstance<JsonObject>()
   children.zip(item.children).forEach { (child, decoded) -> collect(child, decoded, depth + 1) }
   if (item.childrenIncomplete) pairs += row to item.copy(children = emptyList())
  } else pairs += row to item
 }
 rows.forEachIndexed { index, element -> collect(element as? JsonObject ?: ApiDecoder.invalid(), content[index], 0) }
 val entries = pairs.filterNot { isPersonalEndCard(it.second) }.map { (row, item) ->
  fun text(key:String) = (row[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
  fun nested(key:String, field:String) = ((row[key] as? JsonObject)?.get(field) as? JsonPrimitive)?.contentOrNull.orEmpty()
  fun route(prefix:String, id:String): PersonalDestination = if(id.matches(Regex("[0-9]+"))) PersonalDestination.Route("$prefix:$id") else PersonalDestination.Unsupported
  val destination = when(item.kind) {
   "contacts" -> route("user", item.userId)
   "collection" -> route("mycollection", item.id)
   "feed" -> route("feed", item.id)
   "feed_reply" -> if(text("url").isNotBlank()) PersonalDestination.Link(text("url")) else route("thread", item.id)
   "recentHistory", "user", "topic", "product", "apk" -> if(text("url").isNotBlank()) PersonalDestination.Link(text("url")) else PersonalDestination.Unsupported
   else -> PersonalDestination.Unsupported
  }
  val label = when(item.kind) {
   "contacts" -> item.userName
   "recentHistory" -> listOf(item.entityCard?.targetTypeTitle.orEmpty(), item.title).filter { it.isNotBlank() }.joinToString("：")
   else -> item.title.ifBlank { item.userName }
  }.ifBlank { "内容信息未提供" }
  val detail = if(item.kind == "collection") item.entityCard?.metrics.orEmpty().joinToString(" · ") else item.message
  PersonalEntry(text("id"), item, label, detail, destination, nested("likeUserInfo","username").takeIf { it.isNotBlank() })
 }
 val boundary = listCardCursorBoundary(content)
 val last = boundary.lastId
 val next = if(!boundary.complete || pairs.any { isPersonalEndCard(it.second) } || last == null || last == cursor.lastItem) null
  else PageCursor(cursor.page + 1, cursor.firstItem ?: boundary.firstId, last)
 return Page(entries, next)
}

/** Flutter uses both the legacy entity and canonical card/template terminal marker. */
private fun isPersonalEndCard(item: ContentItem): Boolean = item.kind == "noMoreDataCard" ||
 item.kind == "card" && item.entityTemplate == "noMoreDataCard"
