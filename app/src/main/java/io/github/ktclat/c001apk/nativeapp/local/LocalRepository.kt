// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.local

import android.content.Context
import io.github.ktclat.c001apk.nativeapp.domain.ContentItem
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable data class LocalFeedEntry(val id: String, val userId: String = "", val userName: String = "", val avatarUrl: String? = null, val summary: String = "", val savedAtMillis: Long = 0)
@Serializable data class LocalFollowUser(val uid: String, val name: String = "", val avatar: String? = null)
@Serializable data class Blacklist(val version: Int = 1, val userBlackList: List<String> = emptyList(), val topicBlackList: List<String> = emptyList())
@Serializable data class LocalState(val version: Int = 1, val favorites: List<LocalFeedEntry> = emptyList(), val history: List<LocalFeedEntry> = emptyList(), val follows: List<LocalFollowUser> = emptyList(), val blacklist: Blacklist = Blacklist(), val searchHistory: List<String> = emptyList())
enum class LibraryKind { FAVORITES, HISTORY }

object BlacklistJson {
    const val MAX_BYTES = 1_048_576
    const val MAX_ITEMS = 5000
    private val json = Json { encodeDefaults = true }
    fun validate(value: Blacklist): Blacklist {
        require(value.version == 1) { "不支持的黑名单版本" }
        require(value.userBlackList.size <= MAX_ITEMS && value.topicBlackList.size <= MAX_ITEMS)
        require(value.userBlackList.all { validUid(it) }) { "用户 ID 格式错误" }
        require(value.topicBlackList.all { it.isNotBlank() && it.length <= 200 && !it.any(Char::isISOControl) }) { "话题格式错误" }
        return value.copy(userBlackList = value.userBlackList.distinct(), topicBlackList = value.topicBlackList.map(String::trim).distinct())
    }
    fun decode(text: String, legacyUserList: Boolean? = null): Blacklist {
        require(text.length <= MAX_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "文件超过 1 MB" }
        // Reject excessive nesting before parsing to avoid adversarial parser stack exhaustion.
        var depth = 0; var quoted = false; var escape = false
        text.forEach { c ->
            if (quoted) { if (escape) escape = false else if (c == '\\') escape = true else if (c == '"') quoted = false }
            else when (c) { '"' -> quoted = true; '{', '[' -> { depth++; require(depth <= 4) }; '}', ']' -> depth-- }
        }
        val parsed = json.parseToJsonElement(text)
        if (parsed is JsonArray) {
            require(legacyUserList != null) { "旧版列表需要先选择用户或话题" }
            require(parsed.size <= MAX_ITEMS)
            val values = parsed.map { element ->
                val primitive = element as? JsonPrimitive ?: error("黑名单必须是字符串列表")
                require(primitive.isString || (legacyUserList && primitive.content.all { it in '0'..'9' }))
                primitive.content
            }
            return validate(if (legacyUserList) Blacklist(userBlackList = values) else Blacklist(topicBlackList = values))
        }
        val obj = parsed.jsonObject
        require(obj.keys.all { it in setOf("version", "userBlackList", "topicBlackList") }) { "黑名单包含未知字段" }
        require("userBlackList" in obj || "topicBlackList" in obj)
        return validate(json.decodeFromJsonElement<Blacklist>(obj)) // accepts source legacy unversioned blacklist
    }
    fun encode(value: Blacklist): String = json.encodeToString(validate(value))
    fun merge(old: Blacklist, incoming: Blacklist) = validate(Blacklist(userBlackList = (old.userBlackList + incoming.userBlackList).distinct(), topicBlackList = (old.topicBlackList + incoming.topicBlackList).distinct()))
}
fun validUid(uid: String): Boolean = uid.length in 1..32 && uid.all { it in '0'..'9' } && uid.any { it != '0' }

class LocalRepository(private val store: JsonStateStore<LocalState>, private val clock: () -> Long = System::currentTimeMillis) {
    val state = store.data.map(::validateState)
    suspend fun favorite(item: ContentItem) = store.update { value ->
        val entries = value.favorites.filterNot { it.id == item.id }
        require(entries.size < MAX_ENTRIES) { "本地收藏已达上限" }
        value.copy(favorites = listOf(entry(item)) + entries)
    }
    suspend fun record(item: ContentItem, enabled: Boolean) {
        if (!enabled) return
        store.update { value -> if (value.history.any { it.id == item.id }) value else value.copy(history = (listOf(entry(item)) + value.history).take(MAX_ENTRIES)) }
    }
    suspend fun delete(kind: LibraryKind, id: String) = store.update { if (kind == LibraryKind.FAVORITES) it.copy(favorites = it.favorites.filterNot { e -> e.id == id }) else it.copy(history = it.history.filterNot { e -> e.id == id }) }
    suspend fun clear(kind: LibraryKind) = store.update { if (kind == LibraryKind.FAVORITES) it.copy(favorites = emptyList()) else it.copy(history = emptyList()) }
    suspend fun follow(user: LocalFollowUser) {
        require(validUid(user.uid) && user.name.length <= 200 && (user.avatar?.length ?: 0) <= 2048)
        store.update { val rest = it.follows.filterNot { u -> u.uid == user.uid }; require(rest.size < MAX_FOLLOWS); it.copy(follows = listOf(user) + rest) }
    }
    suspend fun unfollow(uid: String) = store.update { it.copy(follows = it.follows.filterNot { u -> u.uid == uid }) }
    suspend fun importBlacklist(text: String, legacyUserList: Boolean? = null) { val incoming = withContext(Dispatchers.IO) { BlacklistJson.decode(text, legacyUserList) }; store.update { it.copy(blacklist = BlacklistJson.merge(it.blacklist, incoming)) } }
    suspend fun addBlockedUser(uid: String) = store.update { it.copy(blacklist = BlacklistJson.merge(it.blacklist, Blacklist(userBlackList = listOf(uid.trim())))) }
    suspend fun addBlockedTopic(topic: String) = store.update { it.copy(blacklist = BlacklistJson.merge(it.blacklist, Blacklist(topicBlackList = listOf(topic.trim())))) }
    suspend fun removeBlockedUser(uid: String) = store.update { it.copy(blacklist = it.blacklist.copy(userBlackList = it.blacklist.userBlackList - uid)) }
    suspend fun removeBlockedTopic(topic: String) = store.update { it.copy(blacklist = it.blacklist.copy(topicBlackList = it.blacklist.topicBlackList - topic)) }
    suspend fun clearBlacklist() = store.update { it.copy(blacklist = Blacklist()) }
    suspend fun rememberSearch(query: String) { val clean = query.trim(); require(clean.isNotEmpty() && clean.length <= 200); store.update { it.copy(searchHistory = (listOf(clean) + it.searchHistory.filterNot { q -> q == clean }).take(50)) } }
    suspend fun forgetSearch(query: String) = store.update { it.copy(searchHistory = it.searchHistory - query) }
    private fun entry(item: ContentItem): LocalFeedEntry {
        require(item.id.isNotBlank() && item.id.length <= 128)
        val text = item.message.ifBlank { item.title }
        val end = text.offsetByCodePoints(0, minOf(150, text.codePointCount(0, text.length)))
        return LocalFeedEntry(item.id, item.userId.take(128), item.userName.take(200), item.avatarUrl?.take(2048), text.substring(0, end), clock())
    }
    companion object {
        const val MAX_ENTRIES = 10000
        const val MAX_FOLLOWS = 500
        fun validateState(state: LocalState): LocalState {
            require(state.version == 1)
            require(state.favorites.size <= MAX_ENTRIES && state.history.size <= MAX_ENTRIES && state.follows.size <= MAX_FOLLOWS)
            require(state.follows.all { validUid(it.uid) })
            BlacklistJson.validate(state.blacklist)
            return state
        }
        fun create(context: Context) = LocalRepository(PreferenceJsonStore.create(context, "library", LocalState.serializer(), LocalState(), ::validateState))
    }
}
/** Match extracted source tags, topic title, and first relation title, not arbitrary feed body text. */
fun Blacklist.blocks(userId: String, tags: List<String>, topicTitle: String = "", firstRelationTitle: String = ""): Boolean =
    userId in userBlackList || topicBlackList.any { word -> (listOf(tags.joinToString(","), topicTitle, firstRelationTitle)).any { word in it } }
