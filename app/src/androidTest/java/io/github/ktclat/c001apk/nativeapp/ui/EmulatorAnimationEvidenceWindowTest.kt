// SPDX-License-Identifier: AGPL-3.0-only
// Six newly reconstructed measurement controls; no app-scenario or runtime PASS claim.
package io.github.ktclat.c001apk.nativeapp.ui

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Random
import org.junit.Assert.*
import org.junit.Test

class EmulatorAnimationEvidenceWindowTest {
    private val identity = FrameIdentity(720, 1280, 7, PixelBounds(0, 376, 720, 856))
    private fun sample(gate: AnimationEvidenceWindow, time: Long, art: String, valid: Boolean = true,
        id: FrameIdentity? = identity, outside: String = "still") = gate.observe(time, valid, id, FrameDigests(art, outside))

    @Test fun staticImageInitialFadeNeverQualifiesAfterSettling() {
        val gate = AnimationEvidenceWindow()
        sample(gate, 0, "fadeA"); sample(gate, 200, "fadeB"); sample(gate, 400, "static")
        for (time in 1_500L..12_000L step 500) assertFalse(sample(gate, time, "static"))
        assertEquals(setOf("static"), gate.distinctImageHashes)
    }
    @Test fun delayedLoadedImageFadeCannotBorrowTimeFromEarlierLoading() {
        val gate = AnimationEvidenceWindow()
        for (time in 0L..1_800L step 200) assertFalse(sample(gate, time, "loading", valid = false))
        sample(gate, 2_000, "fadeA"); sample(gate, 2_200, "fadeB"); sample(gate, 2_500, "static")
        for (time in 3_500L..12_000L step 500) assertFalse(sample(gate, time, "static"))
        assertEquals(2_000L, gate.qualifiedOrigin)
        assertEquals(setOf("static"), gate.distinctImageHashes)
    }
    @Test fun actualPostSettleAnimationRequiresThreeStableFrames() {
        val gate = AnimationEvidenceWindow()
        assertFalse(sample(gate, 100, "A"))
        assertFalse(sample(gate, 1_599, "B"))
        assertFalse(sample(gate, 1_600, "A"))
        assertFalse(sample(gate, 1_600, "B")) // same acquired frame cannot increase count
        assertEquals(1, gate.postSettleSamples)
        assertFalse(sample(gate, 1_800, "B"))
        assertTrue(sample(gate, 2_000, "A"))
        assertEquals(3, gate.postSettleSamples)
    }
    @Test fun foregroundBoundsOrOutsideMotionResetTheWindow() {
        val changedFrames = listOf<FrameIdentity?>(null, identity.copy(windowId = 8), identity.copy(width = 721),
            identity.copy(height = 1281), identity.copy(image = PixelBounds(1, 376, 720, 856)))
        for (changed in changedFrames) {
            val gate = AnimationEvidenceWindow()
            sample(gate, 0, "A"); sample(gate, 1_500, "A"); sample(gate, 1_700, "B")
            assertFalse(sample(gate, 1_900, "A", id = changed))
            assertFalse(gate.qualifies())
        }
        val gate = AnimationEvidenceWindow()
        sample(gate, 0, "A"); sample(gate, 1_500, "A"); sample(gate, 1_700, "B")
        assertFalse(sample(gate, 1_900, "A", outside = "moved"))
        assertEquals(1_900L, gate.qualifiedOrigin)
        assertFalse(sample(gate, 2_000, "B", valid = false))
        assertNull(gate.qualifiedOrigin)
        sample(gate, 2_100, "B")
        assertFalse(sample(gate, 2_000, "A"))
        assertEquals(2_000L, gate.qualifiedOrigin)
    }
    @Test fun bulkPixelDigestsMatchNaiveRowOrderAndClockExclusion() {
        val random = Random(0x534b414449L)
        repeat(80) {
            val width = 1 + random.nextInt(71); val height = 1 + random.nextInt(57)
            val pixels = IntArray(width * height) { random.nextInt() }
            val left = random.nextInt(width + 1); val top = random.nextInt(height + 1)
            val roi = PixelBounds(left, top, left + random.nextInt(width + 2), top + random.nextInt(height + 2))
            val clock = PixelBounds(width - 85, 0, maxOf(width - 16, width - 85), 48)
            assertEquals(naive(pixels, width, height, roi, clock), BulkFrameDigests.compute(pixels, width, height, roi, clock))
        }
        val pixels = IntArray(11 * 7) { it * 1234567 }
        for (roi in listOf(PixelBounds(0, 0, 11, 7), PixelBounds(10, 6, 11, 7), PixelBounds(2, 1, 8, 3), PixelBounds(4, 2, 4, 2))) {
            val clock = PixelBounds(1, 0, 5, 3)
            assertEquals(naive(pixels, 11, 7, roi, clock), BulkFrameDigests.compute(pixels, 11, 7, roi, clock))
        }
    }
    @Test fun bulkDigestsDistinguishArtworkOutsideMutationAndDimensions() {
        val pixels = IntArray(11 * 7) { it }; val roi = PixelBounds(2, 2, 8, 5); val clock = PixelBounds(8, 0, 11, 2)
        val original = BulkFrameDigests.compute(pixels, 11, 7, roi, clock)
        val art = pixels.clone().also { it[3 * 11 + 3] = -1 }
        val artDigest = BulkFrameDigests.compute(art, 11, 7, roi, clock)
        assertNotEquals(original.image, artDigest.image); assertEquals(original.outside, artDigest.outside)
        val outside = pixels.clone().also { it[6 * 11] = -1 }
        val outsideDigest = BulkFrameDigests.compute(outside, 11, 7, roi, clock)
        assertEquals(original.image, outsideDigest.image); assertNotEquals(original.outside, outsideDigest.outside)
        val clockOnly = pixels.clone().also { it[10] = -1 }
        assertEquals(original, BulkFrameDigests.compute(clockOnly, 11, 7, roi, clock))
        assertNotEquals(original, BulkFrameDigests.compute(pixels, 7, 11, roi, clock))
    }
    private fun naive(pixels: IntArray, width: Int, height: Int, image: PixelBounds, clock: PixelBounds): FrameDigests {
        val art = MessageDigest.getInstance("SHA-256"); val outside = MessageDigest.getInstance("SHA-256")
        for (y in 0 until height) for (x in 0 until width) {
            val bytes = ByteBuffer.allocate(4).putInt(pixels[y * width + x]).array()
            if (image.contains(x, y)) art.update(bytes)
            if (!image.contains(x, y) && !clock.contains(x, y)) outside.update(bytes)
        }
        fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        return FrameDigests(hex(art.digest()), hex(outside.digest()))
    }
}
