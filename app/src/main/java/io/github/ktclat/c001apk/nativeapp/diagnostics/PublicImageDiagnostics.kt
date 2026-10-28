// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.diagnostics

import coil.EventListener
import coil.decode.DataSource
import coil.decode.Decoder
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.network.HttpException
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.Options
import coil.request.SuccessResult
import io.github.ktclat.c001apk.nativeapp.feature.media.ImageLoadFailure
import io.github.ktclat.c001apk.nativeapp.feature.media.imageLoadFailure
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal enum class PublicImageHost { IMAGE_CDN, AVATAR_CDN, STATIC_CDN, OTHER_HTTPS }
internal enum class PublicImageType { IMAGE, HTML, OTHER, UNKNOWN }
internal enum class PublicImageSource { NETWORK, DISK, MEMORY, UNKNOWN }
internal data class PublicImageObservation(
    val host: PublicImageHost,
    val failure: ImageLoadFailure?,
    val contentType: PublicImageType,
    val source: PublicImageSource,
) {
    fun text() = "host=$host; result=${failure?.kind ?: "SUCCESS"}; HTTP=${failure?.httpStatus ?: "unavailable"}; contentType=$contentType; source=$source"
}

/** Bounded process-memory aggregates, not a request log. No URLs, identifiers or timestamps. */
internal class PublicImageCounters {
    private val counts = linkedMapOf<PublicImageObservation, Int>()
    @Synchronized fun record(event: PublicImageObservation) {
        val count = counts.remove(event) ?: 0
        if (counts.size >= 16) counts.remove(counts.keys.first())
        counts[event] = (count + 1).coerceAtMost(9999)
    }
    @Synchronized fun snapshot(): List<Pair<PublicImageObservation, Int>> = counts.entries.map { it.key to it.value }
}

internal object PublicImageDiagnostics {
    val counters = PublicImageCounters()

}

internal fun publicImageType(value: String?): PublicImageType = when {
    value.isNullOrBlank() -> PublicImageType.UNKNOWN
    value.substringBefore(';').trim().startsWith("image/", ignoreCase = true) -> PublicImageType.IMAGE
    value.substringBefore(';').trim().lowercase() in setOf("text/html", "application/xhtml+xml") -> PublicImageType.HTML
    else -> PublicImageType.OTHER
}

/** Observes the real Coil pipeline; never changes a request or retries it. */
internal class PublicImageEventListenerFactory(private val counters: PublicImageCounters = PublicImageDiagnostics.counters) : EventListener.Factory {
    override fun create(request: ImageRequest): EventListener {
        val url = (request.data as? String)?.toHttpUrlOrNull()?.takeIf { it.isHttps } ?: return EventListener.NONE
        val host = when (url.host) {
            "image.coolapk.com" -> PublicImageHost.IMAGE_CDN
            "avatar.coolapk.com" -> PublicImageHost.AVATAR_CDN
            "static.coolapk.com" -> PublicImageHost.STATIC_CDN
            else -> PublicImageHost.OTHER_HTTPS
        }
        return object : EventListener {
            private var type = PublicImageType.UNKNOWN
            private var source = PublicImageSource.UNKNOWN
            private var decoding = false
            override fun fetchEnd(request: ImageRequest, fetcher: Fetcher, options: Options, result: FetchResult?) {
                type = publicImageType((result as? SourceResult)?.mimeType)
                source = (result as? SourceResult)?.dataSource?.let(::publicImageSource) ?: PublicImageSource.UNKNOWN
            }
            override fun decodeStart(request: ImageRequest, decoder: Decoder, options: Options) { decoding = true }
            override fun onError(request: ImageRequest, result: ErrorResult) {
                val http = generateSequence(result.throwable) { it.cause }.take(8).filterIsInstance<HttpException>().firstOrNull()
                counters.record(PublicImageObservation(host, imageLoadFailure(result.throwable, decoding),
                    http?.response?.header("Content-Type")?.let(::publicImageType) ?: type, source))
            }
            override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                counters.record(PublicImageObservation(host, null, type, publicImageSource(result.dataSource)))
            }
        }
    }
}

private fun publicImageSource(source: DataSource): PublicImageSource = when (source) {
    DataSource.NETWORK -> PublicImageSource.NETWORK
    DataSource.DISK -> PublicImageSource.DISK
    DataSource.MEMORY, DataSource.MEMORY_CACHE -> PublicImageSource.MEMORY
}
