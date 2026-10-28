// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.feature.media

import coil.network.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Closed, export-safe values only. Never retain an exception, URL, body or account identifier. */
internal enum class ImageLoadFailureKind { HTTP, TIMEOUT, DNS, TLS, NETWORK, READ_OR_DECODE, DECODE, UNKNOWN }
internal data class ImageLoadFailure(val kind: ImageLoadFailureKind, val httpStatus: Int? = null) {
    init {
        require((kind == ImageLoadFailureKind.HTTP) == (httpStatus != null))
        require(httpStatus == null || httpStatus in 100..599)
    }
    fun displayText(): String = when (kind) {
        ImageLoadFailureKind.HTTP -> "服务器返回 HTTP $httpStatus"
        ImageLoadFailureKind.TIMEOUT -> "连接超时，请检查网络"
        ImageLoadFailureKind.DNS -> "域名解析失败，请检查网络"
        ImageLoadFailureKind.TLS -> "安全连接失败"
        ImageLoadFailureKind.NETWORK -> "网络读取失败"
        ImageLoadFailureKind.READ_OR_DECODE -> "图片读取或解码失败"
        ImageLoadFailureKind.DECODE -> "图片格式无法解码"
        ImageLoadFailureKind.UNKNOWN -> "图片加载失败"
    }
}

/** null phase means the UI cannot distinguish fetching IO from decoding IO. */
internal fun imageLoadFailure(error: Throwable, decoding: Boolean? = null): ImageLoadFailure {
    val causes = generateSequence(error) { it.cause }.take(8).toList()
    val status = causes.filterIsInstance<HttpException>().firstOrNull()?.response?.code
    val kind = when {
        status != null && status in 100..599 -> ImageLoadFailureKind.HTTP
        causes.any { it is UnknownHostException } -> ImageLoadFailureKind.DNS
        causes.any { it is SocketTimeoutException } -> ImageLoadFailureKind.TIMEOUT
        causes.any { it is SSLException } -> ImageLoadFailureKind.TLS
        decoding == true -> ImageLoadFailureKind.DECODE
        causes.any { it is IOException } -> if (decoding == false) ImageLoadFailureKind.NETWORK else ImageLoadFailureKind.READ_OR_DECODE
        else -> ImageLoadFailureKind.UNKNOWN
    }
    return ImageLoadFailure(kind, status.takeIf { kind == ImageLoadFailureKind.HTTP })
}
