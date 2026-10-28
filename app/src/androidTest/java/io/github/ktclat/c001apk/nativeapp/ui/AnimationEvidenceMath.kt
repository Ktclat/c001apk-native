// SPDX-License-Identifier: AGPL-3.0-only
// Newly reconstructed measurement code, not recovered historical source.
package io.github.ktclat.c001apk.nativeapp.ui

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

internal data class PixelBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init { require(right >= left && bottom >= top) }
    fun contains(x: Int, y: Int) = x >= left && x < right && y >= top && y < bottom
    fun clamp(width: Int, height: Int) = PixelBounds(left.coerceIn(0, width), top.coerceIn(0, height),
        right.coerceIn(0, width), bottom.coerceIn(0, height))
}

internal data class FrameIdentity(val width: Int, val height: Int, val windowId: Int, val image: PixelBounds)
internal data class FrameDigests(val image: String, val outside: String)

/** Digest exact row-major ARGB ints in big-endian order, excluding only the supplied two rectangles. */
internal object BulkFrameDigests {
    fun compute(pixels: IntArray, width: Int, height: Int, image: PixelBounds, clock: PixelBounds, onTimingNanos: ((Long, Long) -> Unit)? = null): FrameDigests {
        require(width > 0 && height > 0 && width.toLong() * height == pixels.size.toLong())
        val roi = image.clamp(width, height)
        val excludedClock = clock.clamp(width, height)
        val bytes = ByteBuffer.allocate(width * 4).order(ByteOrder.BIG_ENDIAN)
        val imageDigest = MessageDigest.getInstance("SHA-256")
        val outsideDigest = MessageDigest.getInstance("SHA-256")
        fun append(digest: MessageDigest, y: Int, left: Int, right: Int) {
            if (right <= left) return
            bytes.clear()
            bytes.asIntBuffer().put(pixels, y * width + left, right - left)
            digest.update(bytes.array(), 0, (right - left) * 4)
        }
        val imageStart = System.nanoTime()
        for (y in roi.top until roi.bottom) append(imageDigest, y, roi.left, roi.right)
        val imageHash = hex(imageDigest.digest())
        val outsideStart = System.nanoTime()
        for (y in 0 until height) {
            val intervals = listOf(roi, excludedClock).filter { y >= it.top && y < it.bottom && it.right > it.left }
                .map { it.left to it.right }.sortedBy { it.first }
            var cursor = 0
            for ((left, right) in intervals) {
                if (left > cursor) append(outsideDigest, y, cursor, left)
                cursor = maxOf(cursor, right)
            }
            append(outsideDigest, y, cursor, width)
        }
        val outsideHash = hex(outsideDigest.digest())
        onTimingNanos?.invoke(outsideStart - imageStart, System.nanoTime() - outsideStart)
        return FrameDigests(imageHash, outsideHash)
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}

/**
 * Acquisition time, never PNG/hash completion time. This accumulator does not establish
 * loaded state: the caller must supply a fresh positively qualified owned image occurrence.
 */
internal class AnimationEvidenceWindow(private val settleMillis: Long = 1_500) {
    var qualifiedOrigin: Long? = null; private set
    var postSettleSamples = 0; private set
    val distinctImageHashes: Set<String> get() = hashes.toSet()
    var resetReason = "not-started"; private set
    private var identity: FrameIdentity? = null
    private var outside: String? = null
    private var lastAcquisition: Long? = null
    private val hashes = linkedSetOf<String>()

    fun observe(acquiredAt: Long, qualified: Boolean, frame: FrameIdentity?, digests: FrameDigests?): Boolean {
        require(acquiredAt >= 0)
        if (!qualified || frame == null || digests == null || frame.width <= 0 || frame.height <= 0 || frame.windowId < 0 ||
            frame.image.right <= frame.image.left || frame.image.bottom <= frame.image.top ||
            frame.image != frame.image.clamp(frame.width, frame.height)) {
            reset("unqualified")
            return false
        }
        val previousTime = lastAcquisition
        val changed = identity != frame || outside != digests.outside
        if (qualifiedOrigin == null || changed || (previousTime != null && acquiredAt < previousTime)) {
            reset(if (qualifiedOrigin == null) "first-qualified" else if (changed) "identity-or-outside-changed" else "clock-regressed")
            identity = frame
            outside = digests.outside
            qualifiedOrigin = acquiredAt
        }
        // Reusing a single acquired frame is not three temporal samples.
        if (lastAcquisition == acquiredAt) return qualifies()
        lastAcquisition = acquiredAt
        if (acquiredAt - requireNotNull(qualifiedOrigin) >= settleMillis) {
            postSettleSamples++
            hashes += digests.image
        }
        return qualifies()
    }
    fun qualifies() = postSettleSamples >= 3 && hashes.size >= 2
    private fun reset(reason: String) {
        qualifiedOrigin = null; postSettleSamples = 0; identity = null; outside = null
        lastAcquisition = null; hashes.clear(); resetReason = reason
    }
}
