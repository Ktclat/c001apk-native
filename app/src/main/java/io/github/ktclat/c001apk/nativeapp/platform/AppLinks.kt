// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.platform

import java.net.URI
import java.net.URLDecoder

sealed interface AppLink {
    data class Internal(val route: String) : AppLink
    data class External(val url: String) : AppLink
    data object Unsupported : AppLink
}

/** Exact authorities only: coolapk.com.evil and user-info never enter native routes. */
fun parseAppLink(raw: String): AppLink = try {
    val uri = URI(raw.trim())
    val trusted = setOf("coolapk.com", "www.coolapk.com")
    val local = uri.scheme == null && raw.startsWith("/") && !raw.startsWith("//")
    val official = uri.scheme in setOf("https", "http") && uri.host?.lowercase() in trusted && uri.rawUserInfo == null && uri.port == -1
    val custom = uri.scheme == "coolmarket" && uri.rawUserInfo == null && uri.port == -1
    if (!local && !official && !custom) {
        if (uri.scheme in setOf("https", "http") && uri.host != null && uri.rawUserInfo == null) AppLink.External(uri.toASCIIString()) else AppLink.Unsupported
    } else {
        val path = if (custom && uri.host !in trusted) "/${uri.host.orEmpty()}${uri.rawPath.orEmpty()}" else uri.rawPath.orEmpty()
        val parts = path.trim('/').split('/').map { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }
        val id = parts.getOrNull(1).orEmpty()
        val numeric = id.matches(Regex("[0-9]+"))
        val query = uri.rawQuery.orEmpty().split('&').mapNotNull { pair -> pair.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to URLDecoder.decode(it[1], "UTF-8") } }.toMap()
        val route = if (parts.size == 2 && id.isNotBlank() && !id.contains('/')) when (parts[0]) {
            "feed" -> if (numeric) "feed:$id" else null
            "event" -> if (numeric) "event:$id" else null
            "u" -> "user:$id"
            "apk", "game" -> "app:$id"
            "t" -> if (query["type"] == "8") "coolpic:$id" else "topic:$id"
            "product" -> "product:$id"
            "dyh" -> if (numeric) "dyh:$id" else null
            else -> null
        } else if (path == "/page" && !query["url"].isNullOrBlank()) "page:$path?${uri.rawQuery}" else null
        route?.let { AppLink.Internal(it) } ?: if (official) AppLink.External(uri.toASCIIString()) else AppLink.Unsupported
    }
} catch (_: Exception) { AppLink.Unsupported }
