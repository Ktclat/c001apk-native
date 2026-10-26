// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI

/**
 * Coolapk still returns HTTP image/avatar/logo fields, although its public CDNs serve HTTPS.
 * Upgrade only these exact public origins before a model reaches Coil. Never enable Android
 * cleartext, downgrade HTTPS, change CDN hosts, or attach account credentials to media requests.
 */
internal fun publicImageUrl(raw: String): String? {
    if (raw.isEmpty() || raw.length > 12_000 || raw.any { it <= ' ' || it == '\\' }) return null
    val relative = raw.startsWith("//")
    val candidate = if (relative) "https:$raw" else raw
    val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
    if (uri.rawUserInfo != null || uri.rawFragment != null) return null
    val url = candidate.toHttpUrlOrNull() ?: return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    if (relative && url.host !in legacyPublicImageHosts) return null
    return when {
        url.isHttps && url.port == 443 -> url.toString()
        url.scheme == "http" && url.port == 80 && url.host in legacyPublicImageHosts ->
            url.newBuilder().scheme("https").port(443).build().toString()
        else -> null
    }?.takeIf { it.length <= 12_000 }
}

private val legacyPublicImageHosts = setOf("image.coolapk.com", "avatar.coolapk.com", "static.coolapk.com")
