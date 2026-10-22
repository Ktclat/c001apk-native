// SPDX-License-Identifier: AGPL-3.0-only
// Port of Ktclat/c001apk token_util.dart; protocol research and lookup table from
// daimiaopeng/coolapk-desktop 3ef2ad4 (MIT). See docs/licenses/coolapk-desktop-MIT.txt.
package io.github.ktclat.c001apk.nativeapp.data

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8
import org.mindrot.jbcrypt.BCrypt
import java.security.MessageDigest
import java.util.UUID

/** This is a local client identifier, NOT a vendor-issued identifier or attestation. */
class ClientProtocol(
    val deviceCode: String = encodeDevice("ca_${UUID.randomUUID().toString().replace("-", "")}; ; ; ; native; native; native; native; null"),
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val appCode: Int = 2604201,
    private val sdkInt: Int = 21,
) {
    fun headers(deviceOverride: String? = null): Map<String, String> = mapOf(
        "User-Agent" to "c001apk-native/0.1 (Android; independent client)",
        "X-Requested-With" to "XMLHttpRequest", "X-Sdk-Locale" to "zh-CN",
        "X-Sdk-Int" to sdkInt.toString(),
        "X-App-Id" to "com.coolapk.market", "X-App-Token" to token(deviceOverride ?: deviceCode, clockSeconds(), appCode),
        "X-App-Version" to "16.2.0", "X-App-Code" to appCode.toString(),
        "X-Api-Version" to "16", "X-App-Device" to (deviceOverride ?: deviceCode),
        "X-Dark-Mode" to "0", "X-App-Channel" to "coolapk", "X-App-Mode" to "universal",
        "X-App-Supported" to appCode.toString(),
    )
    companion object {
        fun encodeDevice(raw: String): String = raw.encodeUtf8().base64().reversed().replace("=", "")
        private fun base64(raw: String) = raw.encodeUtf8().base64().replace("=", "")
        private val authBlob by lazy { TOKEN_DATA.decodeBase64()!!.toByteArray() }
        private fun md5(raw: String): String {
            val bytes = MessageDigest.getInstance("MD5").digest(raw.toByteArray(Charsets.UTF_8))
            val hex = "0123456789abcdef"
            return buildString(32) { bytes.forEach { byte -> val value = byte.toInt() and 255; append(hex[value ushr 4]); append(hex[value and 15]) } }
        }
        fun token(device: String, timestamp: Long, appCode: Int = 2604201): String {
            require(timestamp >= 0 && appCode > 0)
            val blob = authBlob
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            for (offset in 0..15) {
                val time = timestamp + offset
                val index = (((time + appCode) % 100) * 4 + 0x80).toInt()
                val segment = String(blob.copyOfRange(index, index + 0x80), Charsets.US_ASCII).decodeBase64()!!.utf8()
                val plain = "com.coolapk.market&$segment&${md5(device)}&$time&$appCode"
                val password = md5(plain.encodeUtf8().base64())
                val source = base64("${time.toString(16)}/${md5(plain)}").take(22)
                val salt = source.take(21) + alphabet[(alphabet.indexOf(source[21]) - 5 + 64) % 64]
                if (!Regex("^[./A-Za-z0-9]{22}$").matches(salt)) continue
                val hash = BCrypt.hashpw(password, "\$2a\$04\$$salt").replaceRange(0, 3, "\$2y")
                return "v3${base64(hash)}"
            }
            error("Client signature unavailable")
        }
    }
}

private const val TOKEN_DATA = "VFRCVU9GUXNRMEVsTFVNa1dERWpRU0VzVXlFbUxETkFVeTFEUEZZdUl5MGlNVU01SXpCVUpGY3RNeWtoTVVRMUpTMURPU1V4TXpoUUxWTXdWMDBzSXloU01UUXNXVEZEUkZFd1EwUlZNU1FrVVMwaktTTXRKRGhZTFZRa1VqRXpLU1FzTTBCVkxUUXdVaTAwTlNNc00yQlhMVk1sSkMxRE5GbE5MQ1EwV1RFekxTUXhRMEVoTEVNbElqRXpLRmN3TXowbExpTkVWekVqUFNZdU5Ea2tMRk13Vml4VEtGa3RRemhYTEZNc1dDNGpMU1l0VTJCVFRUQTBMRkV1TXpoVExqTWhJakZETUZNc0kwQlZMQ1FrVUN3ekpTSXNJekJTTUVNOFVDeEVMU0l0STJCUUxVUW9VeTBqTEZNc0l5VWtNRlF3VmswdFJDeFlNVE1sSkM0ME5TRXdVMFJRTEZNaElpMGtOU013VTJCUUxqTmdVeXdqT0ZFc05DeFRMRU13V0N4VUxTRXRJeVJUTEROQkl5MDBKRlpOTVVNc1VEQkVKRmd0TkMwa0xEUWtXQzFETUZNeFJDa2pMVk0wVml4VE1GZ3hJeTBtTFZNeEpTMHpKRlF4TTJCUUxTUTRXVEJETUZNdUkwVWhUU3d6UEZndFJEQlJNRE5FVVMwak9GWXVKRGtsTVNRMFV6QXpOU013UkRra01UTWtWU3hVTUZRdFJDeFlNVE5GSXl4RUxGWXNJelVoTGlRMFYwMHhOQ3hWTENNMFZ5NHpKRlV4TkN4VExGUTRWeTRqWUZZeEpEVWpMVU13VXpGRFBGSXNNMEVsTERNc1ZEQTBPU011TkNrbUxFUWtWaXhFTVNKTkxUTW9VaXd6TEZjdFUwUlFMaVF3VlRGREpTVXdVeWtqTVVNaEl5MHpJU1VzSTBFak1UTXdVUzFVTkZjeFJERWxMVVF0SlM0a05GSXNJemhaVFRCVFlGRXdReVJZTFNNbElTNDBMRmdzVkRSWkxVUW9XVEJEUUZZdFJDeFdMak5FVkRCRE1Ga3RORFVsTEZRNFZTMVVKRmd3TXpSV0xEUTRVVTB3UXkwbUxVUTRXUzFUTVNNc1V5VWlNVU5BVVN4VUxGWXVJMFJVTGlNc1dERkVMRk14STJCUUxWTTBXUzRrT0Zjd00wUlhMVk1vV0MwaktGRXhMQ00wVml4VE5GQXRSRFJXTFVRbEpDMVRKRkF3TkN4Z1lB"
