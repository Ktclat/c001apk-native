// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import io.github.ktclat.c001apk.nativeapp.domain.ContentItem

internal fun contentListIdentity(item: ContentItem): String? =
    (item.sourceEntityId?.takeIf(String::isNotBlank) ?: item.id.takeIf(String::isNotBlank))
        ?.let { id ->
            val marker = if (item.kind == "card" && item.entityTemplate == "listCard" && item.childrenIncomplete) "incomplete-list|" else "entity|"
            marker + listOf(item.kind, id, item.title).joinToString("") { "${it.length}:$it" }
        }

internal fun contentListItems(source: List<ContentItem>): List<ContentItem> = distinctContentItems(expandSupportedListCards(source))

/** Raw wrapper identities often repeat between pages while their child entities change. */
internal fun mergeRawContentPages(source: List<ContentItem>): List<ContentItem> {
    val seen = mutableSetOf<String>()
    return source.filter { item ->
        (item.kind == "card" && item.entityTemplate == "listCard") || (contentListIdentity(item)?.let(seen::add) ?: true)
    }
}

internal fun distinctContentItems(source: List<ContentItem>): List<ContentItem> {
    val seen = mutableSetOf<String>()
    // Anonymous siblings have no identity to deduplicate; retain their distinct source positions.
    return source.filter { item -> contentListIdentity(item)?.let(seen::add) ?: true }
}

/** Flutter NetworkRepo._expandListCards' exact container contract, at presentation time.
 * Keep the decoded tree, raw record IDs and recursive blacklist filtering untouched.
 * Never promote arbitrary entities, forwarded quotes or replies to sibling posts.
 */
internal fun expandSupportedListCards(source: List<ContentItem>): List<ContentItem> {
    val result = ArrayList<ContentItem>()
    var visited = 0
    var limited = false
    fun append(items: List<ContentItem>, depth: Int) {
        for (item in items) {
            if (++visited > 10_000 || result.size >= 2_000) { limited = true; return }
            if (item.kind == "card" && item.entityTemplate == "listCard" && depth < 4) {
                append(item.children, depth + 1)
                // A malformed/truncated container must not silently look complete.
                if (item.childrenIncomplete) {
                    if (result.size < 2_000) result += item.copy(children = emptyList()) else limited = true
                }
            } else result += item
            if (limited) return
        }
    }
    append(source, 0)
    if (limited) result += ContentItem("list-display-limit", "card", message = "本次列表超过显示上限，请缩小范围后重试", childrenIncomplete = true)
    return result
}
