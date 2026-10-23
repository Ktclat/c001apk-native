// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

import io.github.ktclat.c001apk.nativeapp.domain.NotificationCounts
import kotlinx.serialization.json.*

/** Flutter 1020aff: message_controller.dart and data.g.dart. No coercion or local list counts. */
internal fun decodeNotificationCounts(value: JsonElement, expectedUid: String): NotificationCounts {
    val obj = value as? JsonObject ?: ApiDecoder.invalid()
    fun count(key: String): Long? {
        val primitive = obj[key] as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        val raw = primitive.content
        if (raw.length !in 1..19 || !raw.all { it in '0'..'9' } || (raw.length > 1 && raw[0] == '0')) return null
        return raw.toLongOrNull()
    }
    return NotificationCounts(expectedUid, count("atme"), count("atcommentme"), count("feedlike"),
        count("contacts_follow"), count("message"), count("notification"))
}
