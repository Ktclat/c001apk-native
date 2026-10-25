// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

import java.util.Locale

/** Flutter-compatible proof-field syntax, never permission to change captured request controls.
 * Nonblank proof is an additional query value on the same rejected GET, not a header/cookie.
 * Protected aliases are normalized so _UID, _first_item and _X_App_Device cannot evade checks.
 * This guards known request controls, not unknown server-side interpretations of new field names.
 */
internal object ReadProofFieldPolicy {
    private val syntax = Regex("^_[A-Za-z0-9_]{1,63}$")
    private fun normalized(value: String) = value.filter { it.isLetterOrDigit() }.lowercase(Locale.ROOT)
    private val protected = setOf(
        "id", "ids", "uid", "userid", "account", "accountid", "username", "password", "ukey",
        "page", "firstitem", "lastitem", "url", "uri", "path", "route", "endpoint", "method", "target", "action", "type",
        "redirect", "redirecturl", "redirecturi", "returnurl", "callback", "scheme", "port",
        "auth", "authorization", "authentication", "token", "accesstoken", "refreshtoken", "cookie", "setcookie",
        "credential", "credentials", "passwd", "secret", "session", "sessionid", "sessid", "validate", "sign", "signature", "requesthash", "apikey", "appkey",
        "device", "deviceid", "devicecode", "androidid", "oaid", "imei", "imsi", "nonce", "timestamp",
        "xappdevice", "xapptoken", "xappuid", "xappid", "xappversion", "xappversioncode", "xappchannel",
        "xappcode", "xapiversion", "xsdkint", "xsdklocale", "xrequestedwith", "xdarkmode", "xappmode", "xappsupported",
        "useragent", "referer", "origin", "host", "contenttype", "contentlength",
    )
    fun allows(field: String, originalKeys: Collection<String>): Boolean {
        if (field.isEmpty()) return true // The dedicated, bounded validate-cookie protocol only.
        if (!syntax.matches(field)) return false
        val key = normalized(field)
        return key.isNotEmpty() && key !in protected && originalKeys.none { normalized(it) == key }
    }
}
