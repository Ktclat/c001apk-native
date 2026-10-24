// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

import java.util.UUID

/** Non-unique system/model metadata only. Never hardware IDs or vendor attestation.
 * Source: c001apk Flutter storage_util.dart fullSetUserAgent/fullSetXAppDevice;
 * Android getProtocolDeviceProfile uses Build.DISPLAY (not ID or FINGERPRINT).
 * Existing account-bound device codes MUST NOT be regenerated from this profile.
 */
data class ClientDeviceProfile(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val buildNumber: String,
    val androidVersion: String,
) {
    init {
        require(listOf(manufacturer, brand, model, buildNumber, androidVersion).all { value ->
            value.isNotBlank() && value.length <= 256 && value.all { it in ' '..'~' && it != ';' }
        }) { "Unsupported system profile format" }
    }

    companion object {
        /** OS properties on customized ROMs may be empty or invalid HTTP header text. */
        fun fromSystemProperties(manufacturer: String, brand: String, model: String, buildNumber: String, androidVersion: String): ClientDeviceProfile? =
            try { ClientDeviceProfile(manufacturer, brand, model, buildNumber, androidVersion) }
            catch (_: IllegalArgumentException) { null }
    }

    fun userAgent(appCode: Int = 2604201): String {
        require(appCode > 0)
        return "Dalvik/2.1.0 (Linux; U; Android $androidVersion; $model $buildNumber) " +
            "(#Build; $brand; $model; $buildNumber; $androidVersion) +CoolMarket/16.2.0-$appCode-universal"
    }

    /** Only for a new installation, not refresh/error recovery/account switching. */
    fun newLocalDeviceCode(): String = encodeLocalDevice("ca_${UUID.randomUUID().toString().replace("-", "").uppercase()}")

    internal fun encodeLocalDevice(installationId: String): String {
        require(Regex("ca_[A-Fa-f0-9]{32}").matches(installationId))
        return ClientProtocol.encodeDevice("$installationId; ; ; ; $manufacturer; $brand; $model; $buildNumber; null")
    }
}
