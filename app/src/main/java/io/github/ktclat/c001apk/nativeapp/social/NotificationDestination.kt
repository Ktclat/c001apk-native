// SPDX-License-Identifier: AGPL-3.0-only
// Read destinations follow Flutter NotificationCard, FeedReplyCard and LikeCard.
package io.github.ktclat.c001apk.nativeapp.social

import android.text.Html
import java.net.URI
import kotlinx.serialization.json.*

/** Typed internal destinations only. This never authorizes a browser, intent, or social mutation. */
sealed interface NotificationDestination {
    data class Feed(val id: String) : NotificationDestination
    data class User(val uid: String) : NotificationDestination
    data class Unavailable(val reason: NotificationTargetIssue) : NotificationDestination
}
enum class NotificationTargetIssue { MISSING, UNSUPPORTED, TOO_LONG }
data class NotificationContent(val text: String, val actorName: String, val actorUid: String?,
    val destination: NotificationDestination, val context: String? = null, val truncated: Boolean = false)

internal fun notificationNumericId(value: String) = value.length in 1..32 && value.all { it in '0'..'9' } && value.any { it != '0' }
internal fun NotificationDestination.route(): String? = when(this) {
    is NotificationDestination.Feed -> id.takeIf(::notificationNumericId)?.let { "feed:$it" }
    is NotificationDestination.User -> uid.takeIf(::notificationNumericId)?.let { "user:$it" }
    is NotificationDestination.Unavailable -> null
}

/** Strict path/authority validation precedes ignoring harmless share metadata. In particular rid
 * belongs to a comment, not to feed/detail. Flutter opens the root /feed/<id> and does not jump to rid.
 * Unknown query keys are deliberately unsupported, rather than silently choosing a misleading ID. */
internal fun notificationLink(raw: String): NotificationDestination {
    fun unavailable(issue: NotificationTargetIssue = NotificationTargetIssue.UNSUPPORTED) = NotificationDestination.Unavailable(issue)
    if (raw.isBlank()) return unavailable(NotificationTargetIssue.MISSING)
    if (raw.length > 2048) return unavailable(NotificationTargetIssue.TOO_LONG)
    if (raw != raw.trim() || raw.any { it.isISOControl() } || '\\' in raw || '%' in raw) return unavailable()
    return try {
        val uri = URI(raw)
        val hosts = setOf("coolapk.com", "www.coolapk.com", "m.coolapk.com", "coolapk1s.com", "www.coolapk1s.com")
        if (uri.rawUserInfo != null || uri.port != -1) return unavailable()
        val path = when {
            uri.scheme == null && uri.rawAuthority == null && raw.startsWith('/') && !raw.startsWith("//") -> uri.rawPath
            uri.scheme in setOf("https", "http") && uri.host?.lowercase() in hosts -> uri.rawPath
            uri.scheme == "coolmarket" && uri.host?.lowercase() in hosts + "com.coolapk.market" -> uri.rawPath
            uri.scheme == "coolmarket" && uri.host in setOf("feed", "u") -> "/${uri.host}${uri.rawPath}"
            else -> return unavailable()
        }
        val target = Regex("^/(feed|u)/([0-9]+)/?$").matchEntire(path ?: "") ?: return unavailable()
        val id = target.groupValues[2]
        if (!notificationNumericId(id)) return unavailable()
        if (uri.rawFragment != null && !uri.rawFragment.matches(Regex("[A-Za-z0-9_-]{1,64}"))) return unavailable()
        val query = uri.rawQuery
        if (query != null) {
            val seen = mutableSetOf<String>()
            for (part in query.split('&')) {
                val fields = part.split('=', limit = 2)
                if (fields.size != 2 || !seen.add(fields[0])) return unavailable()
                val valid = when(fields[0]) {
                    "rid", "shareUid", "shareTime" -> notificationNumericId(fields[1])
                    "shareFrom", "shareKey" -> fields[1].matches(Regex("[A-Za-z0-9_.-]{1,128}"))
                    else -> false
                }
                if (!valid) return unavailable()
            }
        }
        if (target.groupValues[1] == "feed") NotificationDestination.Feed(id) else NotificationDestination.User(id)
    } catch (_: Exception) { unavailable() }
}

private const val MAX_NOTE = 12_000
/** A deliberately strict, bounded HTML-token reader. It recognizes quoted/unquoted/boolean
 * attributes, comments and raw-text elements before considering anchors; it is not a renderer.
 * Malformed structures fail closed instead of guessing which apparent link a browser might use. */
internal fun firstNotificationLink(note: String): NotificationDestination {
    fun unsupported() = NotificationDestination.Unavailable(NotificationTargetIssue.UNSUPPORTED)
    fun missing() = NotificationDestination.Unavailable(NotificationTargetIssue.MISSING)
    if (note.length > MAX_NOTE) return NotificationDestination.Unavailable(NotificationTargetIssue.TOO_LONG)
    var offset = 0
    var tokens = 0
    val rawText = setOf("script", "style", "textarea", "title", "xmp", "iframe", "noembed", "noframes")
    fun space(c: Char) = c in " \t\n\u000C\r"
    val treeRepairElements = setOf("table", "tbody", "thead", "tfoot", "tr", "td", "th", "caption", "colgroup", "col", "select", "option", "optgroup", "template", "svg", "math", "frameset", "frame")
    fun nameChar(c: Char) = c.isLetterOrDigit() && c.code < 128 || c in "_:-."
    while (offset < note.length) {
        val start = note.indexOf('<', offset)
        if (start < 0) return missing()
        if (++tokens > 512) return NotificationDestination.Unavailable(NotificationTargetIssue.TOO_LONG)
        if (note.startsWith("<!--", start)) {
            val end = note.indexOf("-->", start + 4)
            if (end < 0) return unsupported()
            offset = end + 3
            continue
        }
        var i = start + 1
        if (i >= note.length) return missing()
        if (note[i] == '!' || note[i] == '?') return unsupported()
        val closing = note[i] == '/'
        if (closing) i++
        if (i >= note.length || note[i] !in 'a'..'z' && note[i] !in 'A'..'Z') {
            // A literal comparison such as "2 < 3" is not an element.
            offset = start + 1
            continue
        }
        val nameStart = i
        while (i < note.length && nameChar(note[i])) i++
        val name = note.substring(nameStart, i).lowercase()
        var firstHref: String? = null
        var hasHref = false
        var count = 0
        var ended = false
        while (i < note.length) {
            while (i < note.length && space(note[i])) i++
            if (i >= note.length) break
            if (note[i] == '>') { i++; ended = true; break }
            if (note[i] == '/' && i + 1 < note.length && note[i + 1] == '>') { i += 2; ended = true; break }
            if (closing || !nameChar(note[i]) || ++count > 128) return unsupported()
            val attrStart = i
            while (i < note.length && nameChar(note[i])) i++
            val attribute = note.substring(attrStart, i).lowercase()
            while (i < note.length && space(note[i])) i++
            var value = ""
            if (i < note.length && note[i] == '=') {
                i++
                while (i < note.length && space(note[i])) i++
                if (i >= note.length) return unsupported()
                if (note[i] == '\'' || note[i] == '"') {
                    val quote = note[i++]
                    val valueStart = i
                    while (i < note.length && note[i] != quote) i++
                    if (i >= note.length) return unsupported()
                    value = note.substring(valueStart, i++)
                    if (i < note.length && !space(note[i]) && note[i] !in "/>" ) return unsupported()
                } else {
                    val valueStart = i
                    while (i < note.length && !space(note[i]) && note[i] != '>') {
                        if (note[i] in "\"'<=`") return unsupported()
                        i++
                    }
                    value = note.substring(valueStart, i)
                }
            }
            if (attribute == "href") {
                if (hasHref) return unsupported()
                hasHref = true; firstHref = value
            }
        }
        if (!ended) return unsupported()
        offset = i
        if (closing) continue
        // Tree-repair, inert-template and foreign-content semantics can reorder/ignore apparent
        // anchors. This bounded extractor intentionally declines these rather than guessing DOM.
        if (name in treeRepairElements) return unsupported()
        if (name == "plaintext") return missing()
        if (name in rawText) {
            // Anything resembling an anchor inside raw text is text, not a DOM link.
            var end = note.indexOf("</$name", offset, ignoreCase = true)
            while (end >= 0) {
                val boundary = end + name.length + 2
                if (boundary == note.length || space(note[boundary]) || note[boundary] == '>') break
                end = note.indexOf("</$name", boundary, ignoreCase = true)
            }
            if (end < 0) return missing()
            offset = end
            continue
        }
        if (name == "a" && hasHref) {
            // Decode character references exactly once, with literal angle brackets escaped so
            // this helper cannot interpret attribute text as another element or fetch an image.
            val decoded = Html.fromHtml(firstHref.orEmpty().replace("<", "&lt;").replace(">", "&gt;"), Html.FROM_HTML_MODE_COMPACT).toString()
            return notificationLink(decoded)
        }
    }
    return missing()
}
private fun noticePlain(value: String, limit: Int): String {
    val bounded = value.take(MAX_NOTE)
    return (if ('<' !in bounded && '&' !in bounded) bounded else Html.fromHtml(bounded, Html.FROM_HTML_MODE_COMPACT).toString()).trim().take(limit)
}

/** Response record identity is deliberately absent: navigation/actor projections cannot rewrite
 * SocialRow.id/entityType, the page cursor, or the repository-owned deletion capability. */
internal fun decodeNotificationContent(row: JsonObject): NotificationContent {
    val kind = row.text("entityType")
    val liker = row["likeUserInfo"] as? JsonObject
    val hasLiker = row["likeUserInfo"] != null && row["likeUserInfo"] != JsonNull
    // Null-only fallback follows Flutter; an explicitly malformed/empty sender cannot become
    // the recipient or original author. Names and identifiers follow their own source fields.
    fun JsonObject.nullableText(key: String): String? = get(key)?.takeUnless { it == JsonNull }?.let { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
    val actor = when {
        hasLiker && kind == "feed_reply" -> liker?.text("uid").orEmpty() to liker?.text("username").orEmpty()
        kind == "notification" -> (row.nullableText("fromuid") ?: row.text("uid")) to row.text("fromusername")
        kind == "contacts" -> {
            val user = row["userInfo"] as? JsonObject
            val followed = row["fUserInfo"] as? JsonObject
            (user?.nullableText("uid") ?: followed?.text("uid").orEmpty()) to
                (user?.nullableText("username") ?: followed?.text("username").orEmpty())
        }
        else -> row.text("uid") to row.text("username").ifBlank { (row["userInfo"] as? JsonObject)?.text("username").orEmpty() }
    }
    val body = if(kind == "notification") row.text("note").ifBlank { row.text("message") } else row.text("message")
    fun missing() = NotificationDestination.Unavailable(NotificationTargetIssue.MISSING)
    val destination = when(kind) {
        "feed" -> row.text("id").takeIf(::notificationNumericId)?.let(NotificationDestination::Feed) ?: missing()
        "feed_reply" -> notificationLink(row.text("url")).let { if (it is NotificationDestination.User) NotificationDestination.Unavailable(NotificationTargetIssue.UNSUPPORTED) else it }
        "notification" -> if (row.text("type") == "contacts_follow") notificationLink(row.text("url")).let {
            if (it is NotificationDestination.Feed) NotificationDestination.Unavailable(NotificationTargetIssue.UNSUPPORTED) else it
        } else firstNotificationLink(row.text("note"))
        "user", "contacts" -> actor.first.takeIf(::notificationNumericId)?.let(NotificationDestination::User) ?: missing()
        else -> NotificationDestination.Unavailable(NotificationTargetIssue.UNSUPPORTED)
    }
    return NotificationContent(noticePlain(body, 4000), noticePlain(actor.second, 200), actor.first.takeIf(::notificationNumericId), destination,
        context = if (hasLiker && kind == "feed_reply") "赞了你的${noticePlain(row.text("infoHtml"), 80).ifBlank { "内容" }}" else if (kind == "feed_reply") "评论节选 · 打开相关动态查看上下文" else null,
        truncated = body.length > 4000)
}
