// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.feature.media

internal data class SourceImageSize(val width: Float = 1f, val height: Float = 1f) {
    val ratio: Float get() = width / height
    val isLong: Boolean get() = height / width >= 22f / 9f
}
internal fun sourceImageSize(url: String): SourceImageSize {
    val match = Regex("@([0-9]+(?:\\.[0-9]+)?)x([0-9]+(?:\\.[0-9]+)?)\\.[^/?#]+(?:[?#].*)?$").find(url) ?: return SourceImageSize()
    val width = match.groupValues[1].toFloatOrNull() ?: return SourceImageSize()
    val height = match.groupValues[2].toFloatOrNull() ?: return SourceImageSize()
    return if (width > 0 && height > 0 && width.isFinite() && height.isFinite() && (width / height).isFinite() && (height / width).isFinite()) SourceImageSize(width, height) else SourceImageSize()
}
internal data class MediaGridLayout(val width: Float, val height: Float, val columns: Int)
/** Flutter components/imageview.dart and NineGridType.weChat: 5dp gaps, 4 images form a 2x2 grid. */
internal fun mediaGridLayout(maxWidth: Float, count: Int, first: SourceImageSize): MediaGridLayout {
    val cell = ((maxWidth - 10f) / 3f).coerceAtLeast(0f)
    if (count == 1) {
        val width = when { first.ratio > 1.5f -> maxWidth; first.ratio >= 1f || 1f / first.ratio < 1.5f -> cell * 2; else -> cell }
        return MediaGridLayout(width, width * minOf(1f / first.ratio, 22f / 9f), 1)
    }
    return MediaGridLayout(cell, cell, if (count == 4) 2 else 3)
}
internal fun mediaBadge(url: String): String? = when {
    url.substringBefore('?').substringBefore('#').endsWith(".gif", ignoreCase = true) -> "GIF"
    sourceImageSize(url).isLong -> "长图"
    else -> null
}
internal fun thumbnailUrl(url: String): String = runCatching {
    val uri = java.net.URI(url)
    if (uri.scheme == "https" && uri.host.equals("image.coolapk.com", ignoreCase = true) && uri.rawQuery == null && uri.rawFragment == null) "$url.s.jpg" else url
}.getOrDefault(url)
