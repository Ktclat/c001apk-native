// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import java.security.MessageDigest

/** Exact typed layout values, not a hash of arbitrary style toString output. */
internal data class PreviewGeometryKey(
    val bodyDigest: String,
    val constraints: Constraints,
    val density: Float,
    val fontScale: Float,
    val direction: LayoutDirection,
    val localeTags: String,
    val fontWeightAdjustment: Int,
    val readingStyle: TextStyle,
    val smallStyle: TextStyle,
    val buttonStyle: TextStyle,
    val ambientStyle: TextStyle,
    val minimumInteractiveSize: Dp,
    val expanded: Boolean,
    val page: Int,
    val sourceIncomplete: Boolean,
)

/** Full raw UTF-16 identity, bounded before hashing. Oversize direct inputs never use a prefix key. */
internal fun previewBodyIdentity(raw: String): String? {
    if (raw.length > 12_000) return null
    val digest = MessageDigest.getInstance("SHA-256")
    raw.forEach { char -> digest.update((char.code ushr 8).toByte()); digest.update(char.code.toByte()) }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Memory-only, owner-scoped geometry; no HTML, ParsedPost, bitmap, account data or SavedState. */
internal class PreviewGeometryStore {
    private val heights = LinkedHashMap<PreviewGeometryKey, Int>(64, .75f, true)
    private var closed = false
    @Synchronized fun height(key: PreviewGeometryKey): Int? = if (closed) null else heights[key]
    @Synchronized fun put(key: PreviewGeometryKey, heightPx: Int) {
        if (closed || !key.constraints.hasBoundedWidth || key.constraints.maxWidth <= 0 ||
            !key.density.isFinite() || key.density <= 0f || !key.fontScale.isFinite() || key.fontScale <= 0f ||
            heightPx !in 1..131_072 || heightPx / key.density > 8192f) return
        heights[key] = heightPx
        while (heights.size > 64) heights.remove(heights.keys.first())
    }
    @Synchronized fun close() { closed = true; heights.clear() }
    @Synchronized internal fun size(): Int = heights.size
}

internal val LocalPreviewGeometryStore = staticCompositionLocalOf<PreviewGeometryStore?> { null }
