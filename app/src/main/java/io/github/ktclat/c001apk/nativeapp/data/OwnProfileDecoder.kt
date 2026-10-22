// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLDecoder

/** Flutter: message_controller.dart/getProfile and feed/datum.g.dart. Transport bounds the envelope. */
internal fun decodeOwnProfile(value: JsonElement, expectedUid: String): OwnProfile {
    val obj = value as? JsonObject ?: ApiDecoder.invalid()
    fun text(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull
    if (text("uid") != expectedUid) ApiDecoder.invalid()
    fun count(key: String): Long? = text(key)?.takeIf { it.length <= 19 && it.all { c -> c in '0'..'9' } }
        ?.toLongOrNull()?.takeIf { it >= 0 }
    val name = text("username")?.take(512)?.takeIf { it.isNotBlank() }?.let {
        // URI component decoding, unlike form decoding, preserves literal plus signs.
        runCatching { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrDefault(it)
    }
    val avatar = text("userAvatar")?.takeIf { it.length <= 4096 }?.toHttpUrlOrNull()?.takeIf {
        it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.port == 443 &&
            (it.host == "coolapk.com" || it.host.endsWith(".coolapk.com") ||
             it.host == "coolapkmarket.com" || it.host.endsWith(".coolapkmarket.com"))
    }?.toString()
    return OwnProfile(expectedUid, name, avatar, count("level"), count("experience"), count("next_level_experience"),
        count("feed"), count("follow"), count("fans"))
}
