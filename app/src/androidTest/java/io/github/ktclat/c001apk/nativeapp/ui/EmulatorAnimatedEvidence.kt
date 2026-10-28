// SPDX-License-Identifier: AGPL-3.0-only
// New acquisition implementation. No historical screenshot or execution record is recreated.
package io.github.ktclat.c001apk.nativeapp.ui

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** loadedCheckpoint must use the current UiAutomation root; never synchronizing Compose queries. */
internal object EmulatorAnimatedEvidence {
    data class LoadedOccurrence(val identity: FrameIdentity, val originalUrl: String, val sourceReceipt: String)
    fun capture(name: String, expectedOriginalUrl: String, sourceFrameCount: Int,
        loadedCheckpoint: () -> LoadedOccurrence?): List<File> {
        require(sourceFrameCount > 1) { "Independent source metadata must establish multiple frames" }
        require(Regex("[A-Za-z0-9_.-]+").matches(name))
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val gate = AnimationEvidenceWindow()
        val frames = mutableListOf<File>()
        val trace = JSONArray()
        val start = SystemClock.uptimeMillis()
        var number = 0
        fun saveTrace() = EmulatorEvidence.text("$name-temporal-trace.json", JSONObject()
            .put("scope", "Original compositor frames; synthetic decoder only, not live CDN; acquisition timestamps, not processing end")
            .put("samplingBudgetMs", 12_000).put("settleMs", 1_500).put("sourceFrameCount", sourceFrameCount)
            .put("qualifiedOrigin", gate.qualifiedOrigin).put("postSettleSamples", gate.postSettleSamples)
            .put("imageHashes", JSONArray(gate.distinctImageHashes.toList())).put("frames", trace).toString(2))
        try {
            while (SystemClock.uptimeMillis() - start < 12_000) {
                val before = loadedCheckpoint()
                val acquireBefore = SystemClock.uptimeMillis()
                val bitmap = automation.takeScreenshot()
                val acquired = SystemClock.uptimeMillis()
                val after = loadedCheckpoint()
                val row = JSONObject().put("index", number).put("acquisitionBeforeMs", acquireBefore - start)
                    .put("acquiredMs", acquired - start).put("screenshotMs", acquired - acquireBefore)
                if (bitmap == null) {
                    gate.observe(acquired - start, false, null, null)
                    trace.put(row.put("screenshot", "NULL")); saveTrace()
                    SystemClock.sleep(100); continue
                }
                try {
                    val file = File(EmulatorEvidence.directory, "$name-original-${number.toString().padStart(2, '0')}.png")
                    val pngStart = SystemClock.uptimeMillis()
                    file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    val pngEnd = SystemClock.uptimeMillis()
                    frames += file
                    val identity = before?.identity
                    val qualified = before != null && before == after && before.originalUrl == expectedOriginalUrl &&
                        bitmap.width < bitmap.height && identity!!.width == bitmap.width && identity.height == bitmap.height
                    val pixelsStart = SystemClock.uptimeMillis()
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    val pixelsEnd = SystemClock.uptimeMillis()
                    val digestStart = SystemClock.uptimeMillis()
                    // This experiment is portrait-only. Bounds are the observed API26 clock at 720px.
                    val clock = PixelBounds(bitmap.width - 85, 0, bitmap.width - 16, 48)
                    var roiNanos = 0L; var outsideNanos = 0L
                    val digests = if (qualified) BulkFrameDigests.compute(pixels, bitmap.width, bitmap.height,
                        requireNotNull(identity).image, clock) { roi, outside -> roiNanos = roi; outsideNanos = outside } else null
                    val digestEnd = SystemClock.uptimeMillis()
                    val accepted = gate.observe(acquired - start, qualified, identity, digests)
                    row.put("file", file.name).put("width", bitmap.width).put("height", bitmap.height)
                        .put("qualified", qualified).put("before", before?.toString()).put("after", after?.toString())
                        .put("pngMs", pngEnd - pngStart).put("getPixelsMs", pixelsEnd - pixelsStart)
                        .put("bulkDigestMs", digestEnd - digestStart).put("roiDigestNanos", roiNanos)
                        .put("outsideDigestNanos", outsideNanos).put("imageHash", digests?.image)
                        .put("outsideHash", digests?.outside).put("qualifiedOrigin", gate.qualifiedOrigin)
                        .put("postSettleSamples", gate.postSettleSamples).put("accepted", accepted)
                    trace.put(row); saveTrace(); number++
                    if (accepted) return frames
                } finally { bitmap.recycle() }
                SystemClock.sleep(200)
            }
            error("Animation not qualified in sampling window; original frames/trace retained (not decoder failure by itself)")
        } catch (failure: Throwable) {
            // Plain-file primary failure before any teardown/UI diagnostics; no masking.
            runCatching { EmulatorEvidence.text("$name-primary-failure.txt", failure.stackTraceToString()) }
            runCatching { saveTrace() }
            throw failure
        }
    }
}
