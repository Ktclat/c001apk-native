// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.serialization.json.*
import java.net.URLDecoder

/** Flutter user_controller.dart / UserInfoCard use /user/space, not the own-account profile API. */
internal fun decodeUserSpace(value: JsonElement, expectedUid: String, nowSeconds: Long = System.currentTimeMillis() / 1000): ContentItem {
    val obj = value as? JsonObject ?: ApiDecoder.invalid()
    fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    // Do not label one person's profile with another route's UID, including malformed/missing IDs.
    if (obj.text("uid") != expectedUid) ApiDecoder.invalid()
    val nested = obj["userInfo"] as? JsonObject
    if (!nested?.text("uid").isNullOrBlank() && nested?.text("uid") != expectedUid) ApiDecoder.invalid()
    fun count(key: String): Long? = obj.text(key).takeIf { it.length in 1..19 && it.all { c -> c in '0'..'9' } }
        ?.toLongOrNull()?.takeIf { it >= 0 }
    // Flutter logintime/regdate are Unix seconds, not milliseconds. Missing/future values stay unknown.
    fun timestamp(key: String) = count(key)?.takeIf { it in 1..nowSeconds }
    val rawName = nested?.text("username").orEmpty().ifBlank { obj.text("username") }.take(512)
    val name = runCatching { URLDecoder.decode(rawName.replace("+", "%2B"), "UTF-8") }.getOrDefault(rawName)
        .filterNot(Char::isISOControl).take(512)
    fun image(raw: String) = raw.takeIf { it.length in 1..4096 }?.let(::publicImageUrl)
    return ContentItem(id = expectedUid, kind = "user", userId = expectedUid, userName = name,
        avatarUrl = image(obj.text("userAvatar").ifBlank { nested?.text("userAvatar").orEmpty() }),
        userProfile = UserSpaceProfile(image(obj.text("cover")), count("level"), count("feed"),
            count("be_like_num"), count("follow"), count("fans"),
            when (obj.text("gender")) { "0" -> 0; "1" -> 1; else -> null }, timestamp("regdate"), timestamp("logintime")))
}
