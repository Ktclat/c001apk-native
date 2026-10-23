// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.feature.media

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

internal enum class ArticleImageFailure {
    BLOCKED_URL, BLOCKED_ADDRESS, UNSUPPORTED_PROXY, ENCODED_LIMIT, SOURCE_LIMIT,
    UNSUPPORTED_FORMAT, DECODE_FAILED, NETWORK,
}
internal class ArticleImageException(val reason: ArticleImageFailure) : IOException(reason.name)

/** These limits are native-reader safety policy, not server protocol limits. */
internal object ArticleImagePolicy {
    const val MAX_URL = 4096
    const val MAX_BYTES = 8 * 1024 * 1024
    const val MAX_SOURCE_SIDE = 32768
    const val MAX_SOURCE_PIXELS = 40_000_000L
    const val MAX_OUTPUT_SIDE = 2048
    const val MAX_OUTPUT_PIXELS = 4_000_000L
    const val MAX_OCCURRENCES = 18

    fun safeUrl(raw: String): String? {
        if (raw.length > MAX_URL || raw.isEmpty() || raw.any { it <= ' ' }) return null
        val uri = try { URI(raw) } catch (_: Exception) { return null }
        if (uri.rawUserInfo != null || uri.rawFragment != null) return null
        return raw.toHttpUrlOrNull()?.takeIf {
            it.isHttps && it.host == "image.coolapk.com" && it.port == 443 &&
                it.username.isEmpty() && it.password.isEmpty() && it.fragment == null
        }?.toString()?.takeIf { it.length <= MAX_URL }
    }

    /** Conservative public-address allowlist; the returned DNS addresses are the ones OkHttp uses. */
    fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address
        if (bytes.size == 16 && bytes.take(10).all { it == 0.toByte() } &&
            bytes[10] == (-1).toByte() && bytes[11] == (-1).toByte()) {
            return isPublicAddress(InetAddress.getByAddress(bytes.copyOfRange(12, 16)))
        }
        val a = bytes.map { it.toInt() and 255 }
        if (a.size == 4) {
            if (a[0] == 0 || a[0] == 10 || a[0] == 127 || a[0] >= 224) return false
            if (a[0] == 100 && a[1] in 64..127) return false
            if (a[0] == 169 && a[1] == 254 || a[0] == 172 && a[1] in 16..31) return false
            if (a[0] == 192 && (a[1] == 168 || a[1] == 0 && a[2] in setOf(0, 2))) return false
            if (a[0] == 192 && a[1] == 88 && a[2] == 99) return false
            if (a[0] == 198 && (a[1] in 18..19 || a[1] == 51 && a[2] == 100)) return false
            return !(a[0] == 203 && a[1] == 0 && a[2] == 113)
        }
        if (a.size != 16 || (a[0] and 0xe0) != 0x20) return false // Only global unicast 2000::/3.
        if (a[0] == 0x20 && a[1] == 0x01 && (a[2] < 2 || a[2] == 0x0d && a[3] == 0xb8)) return false
        if (a[0] == 0x20 && a[1] == 0x02) return false // No 6to4 tunnelling.
        return !(a[0] == 0x3f && a[1] == 0xff && (a[2] and 0xf0) == 0) // Documentation /20.
    }

    fun sampleSize(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0 || width > MAX_SOURCE_SIDE || height > MAX_SOURCE_SIDE ||
            width.toLong() * height > MAX_SOURCE_PIXELS) throw ArticleImageException(ArticleImageFailure.SOURCE_LIMIT)
        var sample = 1
        while (true) {
            // Ceil, not floor: do not underestimate an odd-sized decoder output.
            val w = (width.toLong() + sample - 1) / sample
            val h = (height.toLong() + sample - 1) / sample
            if (w <= MAX_OUTPUT_SIDE && h <= MAX_OUTPUT_SIDE && w * h <= MAX_OUTPUT_PIXELS) return sample
            sample *= 2
        }
    }

    fun validateFormat(bytes: ByteArray, contentType: String?, checkActive: () -> Unit = {}): String {
        checkActive()
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) throw ArticleImageException(ArticleImageFailure.ENCODED_LIMIT)
        val mime = contentType?.substringBefore(';')?.trim()?.lowercase()
        fun unsupported(): Nothing = throw ArticleImageException(ArticleImageFailure.UNSUPPORTED_FORMAT)
        fun magic(offset: Int, vararg expected: Int): Boolean = offset >= 0 && offset + expected.size <= bytes.size &&
            expected.indices.all { (bytes[offset + it].toInt() and 255) == expected[it] }
        fun tag(offset: Int, value: String): Boolean = offset >= 0 && offset + 4 <= bytes.size &&
            value.indices.all { bytes[offset + it].toInt() == value[it].code }
        fun uint32(offset: Int, little: Boolean): Long {
            if (offset < 0 || offset + 4 > bytes.size) unsupported()
            var result = 0L
            for (i in 0..3) result = result or ((bytes[offset + i].toLong() and 255) shl (8 * if (little) i else 3 - i))
            return result
        }
        if (magic(0, 0xff, 0xd8, 0xff)) {
            if (mime != "image/jpeg") unsupported()
            return mime
        }
        if (magic(0, 137, 80, 78, 71, 13, 10, 26, 10)) {
            if (mime != "image/png") unsupported()
            var offset = 8
            var first = true
            while (offset.toLong() + 12 <= bytes.size) {
                checkActive()
                val length = uint32(offset, false)
                val end = offset.toLong() + 12 + length
                if (end > bytes.size || first && (!tag(offset + 4, "IHDR") || length != 13L)) unsupported()
                first = false
                if (tag(offset + 4, "acTL") || tag(offset + 4, "fcTL") || tag(offset + 4, "fdAT")) unsupported()
                if (tag(offset + 4, "IEND")) {
                    if (length != 0L || end != bytes.size.toLong()) unsupported()
                    return mime
                }
                offset = end.toInt()
            }
            unsupported()
        }
        if (tag(0, "RIFF") && tag(8, "WEBP")) {
            if (mime != "image/webp" || uint32(4, true) + 8 != bytes.size.toLong()) unsupported()
            var offset = 12
            var payload = false
            while (offset.toLong() + 8 <= bytes.size) {
                checkActive()
                val length = uint32(offset + 4, true)
                val end = offset.toLong() + 8 + length + (length and 1)
                if (end > bytes.size) unsupported()
                if (tag(offset, "ANIM") || tag(offset, "ANMF")) unsupported()
                if (tag(offset, "VP8X") && (length != 10L || (bytes[offset + 8].toInt() and 2) != 0)) unsupported()
                if (tag(offset, "VP8 ") || tag(offset, "VP8L")) payload = true
                offset = end.toInt()
            }
            if (offset != bytes.size || !payload) unsupported()
            return mime
        }
        unsupported() // Includes GIF, AVIF and spoofed/absent MIME or magic.
    }
}

internal class ArticlePublicDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname != "image.coolapk.com") throw ArticleImageException(ArticleImageFailure.BLOCKED_URL)
        val addresses = delegate.lookup(hostname)
        if (addresses.isEmpty() || addresses.any { !ArticleImagePolicy.isPublicAddress(it) })
            throw ArticleImageException(ArticleImageFailure.BLOCKED_ADDRESS)
        return addresses
    }
}

/** Consult the current OS selector on the actual route-selection path. Never bypass a configured proxy. */
internal class ArticleProxySelector(private val current: () -> ProxySelector? = { ProxySelector.getDefault() }) : ProxySelector() {
    override fun select(uri: URI): List<Proxy> {
        return try {
            val selector = current()
            if (selector == null) listOf(Proxy.NO_PROXY) else {
                if (selector === this) throw ArticleImageException(ArticleImageFailure.UNSUPPORTED_PROXY)
                val proxies: List<Proxy>? = selector.select(uri)
                if (proxies.isNullOrEmpty() || proxies.any { it.type() != Proxy.Type.DIRECT })
                    throw ArticleImageException(ArticleImageFailure.UNSUPPORTED_PROXY)
                proxies
            }
        } catch (_: Exception) { throw ArticleImageException(ArticleImageFailure.UNSUPPORTED_PROXY) }
    }
    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
}
