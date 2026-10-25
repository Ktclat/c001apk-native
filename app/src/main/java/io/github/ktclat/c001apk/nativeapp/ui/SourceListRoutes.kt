// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal data class SourceListRoute(val kind: String, val id: String) {
    val title: String get() = if (kind == "dyh") "订阅号" else id
    val tabs: List<Pair<String, String>> get() = if (kind == "dyh") listOf("all" to "精选", "square" to "广场")
        else listOf("recommend" to "精选", "hot" to "热门", "newest" to "最新")
    fun key(type: String) = "$kind-list:$type:$id"
}
internal fun sourceListRoute(route: String?): SourceListRoute? {
    if (route == null) return null
    val kind = route.substringBefore(':'); val id = route.substringAfter(':', "")
    if (kind !in setOf("dyh", "coolpic") || id.isBlank() || id.length > 4096 || id.any { it.isISOControl() }) return null
    if (kind == "dyh" && id.any { it !in '0'..'9' }) return null
    return SourceListRoute(kind, id)
}
internal fun sourceListRequest(key: String): Pair<SourceListRoute, String>? {
    val parts = key.split(':', limit = 3)
    if (parts.size != 3) return null
    if (parts[0] !in setOf("dyh-list", "coolpic-list")) return null
    val route = sourceListRoute("${parts[0].removeSuffix("-list")}:${parts[2]}") ?: return null
    return if (parts[1] in route.tabs.map { it.first }) route to parts[1] else null
}
/** Nested tab list bookmarks live as long as their owning navigation entry, not only its route string. */
internal fun retainedSourceListKeys(routes: List<String>): Set<String> = routes.flatMap { value ->
    sourceListRoute(value)?.let { route -> route.tabs.map { route.key(it.first) } }.orEmpty()
}.toSet()
@Composable internal fun SourceListTabs(route: SourceListRoute, refresh: (String) -> Unit, content: @Composable (String) -> Unit) {
    var selected by rememberSaveable(route) { mutableStateOf(route.tabs.first().first) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            route.tabs.forEach { (type, label) -> FilterChip(selected = selected == type, onClick = { selected = type },
                label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp).testTag("${route.kind}-tab:$type")) }
            TextButton(onClick = { refresh(route.key(selected)) }, modifier = Modifier.testTag("${route.kind}-refresh")) { Text("刷新") }
        }
        Box(Modifier.weight(1f)) { content(route.key(selected)) }
    }
}
