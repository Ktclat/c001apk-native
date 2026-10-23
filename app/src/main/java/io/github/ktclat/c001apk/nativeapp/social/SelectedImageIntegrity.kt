// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.toByteString
import java.security.MessageDigest

/** The selected private spool is immutable intent, not a path whose current bytes may be substituted. */
internal suspend fun verifySelectedImage(image: SelectedImage): Boolean = withContext(Dispatchers.IO) {
    try {
        if (!image.file.isFile || image.sizeBytes !in 1..MAX_IMAGE_BYTES) return@withContext false
        val expectedSize = image.sizeBytes
        val digest = MessageDigest.getInstance("MD5")
        var count = 0L
        image.file.inputStream().use { input ->
            val bytes = ByteArray(32 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(bytes)
                if (read < 0) break
                count += read
                if (count > MAX_IMAGE_BYTES || count > expectedSize) return@withContext false
                digest.update(bytes, 0, read)
            }
        }
        val actual = digest.digest().toByteString()
        count == expectedSize && image.sizeBytes == expectedSize &&
            actual.hex() == image.md5Hex && actual.base64() == image.md5Base64
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }
}

internal suspend fun verifyImageBatch(images: List<SelectedImage>): Boolean {
    if (!validImageBatch(images)) return false
    for (image in images) if (!verifySelectedImage(image)) return false
    return true
}
