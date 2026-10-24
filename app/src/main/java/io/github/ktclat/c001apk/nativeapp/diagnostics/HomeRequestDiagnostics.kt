// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.diagnostics

import io.github.ktclat.c001apk.nativeapp.domain.FailureKind

/** Closed, non-identifying vocabulary. Never add arbitrary request/response strings here. */
enum class HomeRequestTab { DYNAMIC, HOT, TOPICS, PRODUCTS, PICTURES }
enum class HomeRequestEndpoint { INDEX_V8, PAGE_DATA_LIST, PRODUCT_CATEGORIES }
enum class HomeRequestHost { API, API2, LOCAL_TEST, UNAVAILABLE }
enum class HomeRequestMode { GUEST, AUTHENTICATED, UNKNOWN }
enum class HomeContentType { JSON, HTML, OTHER, UNAVAILABLE }
data class HomeProtocolInfo(val appCode: Int, val sdkInt: Int, val systemProfileValid: Boolean) {
    fun text() = "app=16.2.0; code=$appCode; api=16; sdk=$sdkInt; profile=${if (systemProfileValid) "valid" else "fallback"}; identityProvenance=unknown"
}
data class HomeRequestEvent(
    val tab: HomeRequestTab, val endpoint: HomeRequestEndpoint, val host: HomeRequestHost,
    val mode: HomeRequestMode, val hasSessid: Boolean, val protocol: HomeProtocolInfo,
    val httpStatus: Int?, val contentType: HomeContentType, val failure: FailureKind?, val elapsedMillis: Long,
) {
    fun text() = "tab=$tab; endpoint=$endpoint; host=$host; method=GET; mode=$mode; SESSID-present=$hasSessid; ${protocol.text()}; HTTP=${httpStatus ?: "unavailable"}; contentType=$contentType; transportResult=${failure?.name ?: "SUCCESS"}; elapsedMs=$elapsedMillis"
}

/** AccountStore's monitor serializes generation changes, append and explicit export. No disk/network.
 * Snapshots and exports clear old events even if the new account has not made a Home request.
 * Generation is only a local ownership guard and is never exported.
 */
class HomeRequestDiagnostics(private val generationLock: Any, private val currentGeneration: () -> Long) {
    private var generation: Long? = null
    private val events = ArrayDeque<HomeRequestEvent>()
    private fun refresh() { val current = currentGeneration(); if (generation != current) { events.clear(); generation = current } }
    fun append(expectedGeneration: Long?, event: HomeRequestEvent) = synchronized(generationLock) {
        refresh()
        if (expectedGeneration != null && expectedGeneration == generation) {
            if (events.size == 12) events.removeFirst()
            events.addLast(event.copy(httpStatus = event.httpStatus?.takeIf { it in 100..599 }, elapsedMillis = event.elapsedMillis.coerceIn(0, 120_000)))
        }
    }
    fun snapshot(): List<HomeRequestEvent> = synchronized(generationLock) { refresh(); events.toList() }
    /** The caller writes the clipboard in this callback, under the same generation guard. */
    fun export(consume: (List<HomeRequestEvent>) -> Unit) = synchronized(generationLock) { refresh(); consume(events.toList()) }
}

internal fun homeRequestRoute(path: String, params: Map<String, String>): Pair<HomeRequestTab, HomeRequestEndpoint>? = when (path) {
    "v6/main/indexV8" -> HomeRequestTab.DYNAMIC to HomeRequestEndpoint.INDEX_V8
    "v6/product/categoryList" -> HomeRequestTab.PRODUCTS to HomeRequestEndpoint.PRODUCT_CATEGORIES
    "v6/page/dataList" -> when (params["url"]) {
        "/page?url=V9_HOME_TAB_RANKING" -> HomeRequestTab.HOT
        "V11_VERTICAL_TOPIC" -> HomeRequestTab.TOPICS
        "/page?url=V11_FIND_COOLPIC" -> HomeRequestTab.PICTURES
        else -> null
    }?.let { it to HomeRequestEndpoint.PAGE_DATA_LIST }
    else -> null
}
internal fun homeContentType(header: String?): HomeContentType = when (header?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT)) {
    null, "" -> HomeContentType.UNAVAILABLE
    "application/json" -> HomeContentType.JSON
    "text/html", "application/xhtml+xml" -> HomeContentType.HTML
    else -> HomeContentType.OTHER
}
