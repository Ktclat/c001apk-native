// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

import io.github.ktclat.c001apk.nativeapp.domain.ContentItem

internal data class ListCursorBoundary(val firstId: String?, val lastId: String?, val complete: Boolean)

/** Flutter expands exact listCard wrappers before CommonController reads first/last IDs.
 * Other templates stay a single row. The original tree/record IDs are never mutated.
 * Decoder depth/node limits remain authoritative; incomplete wrappers cannot invent a cursor.
 */
internal fun listCardCursorBoundary(items: List<ContentItem>): ListCursorBoundary {
    var first: ContentItem? = null
    var last: ContentItem? = null
    var visited = 0
    var complete = true
    fun visit(rows: List<ContentItem>, depth: Int) {
        for (item in rows) {
            if (++visited > 500) { complete = false; return }
            if (item.kind == "card" && item.entityTemplate == "listCard") {
                if (item.childrenIncomplete || depth >= 4) complete = false
                else visit(item.children, depth + 1)
            } else {
                if (first == null) first = item
                last = item
            }
        }
    }
    visit(items, 0)
    return ListCursorBoundary(first?.id?.takeIf(String::isNotBlank), last?.id?.takeIf(String::isNotBlank), complete)
}
