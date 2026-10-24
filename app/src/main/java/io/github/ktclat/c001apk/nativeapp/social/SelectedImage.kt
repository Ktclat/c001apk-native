// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import okio.ByteString.Companion.toByteString

/** App-private bounded spool of a user-selected content URI; never accepts file/http URIs. */
class SelectedImage internal constructor(internal val file: File, val mime: String, val width: Int,
    val height: Int, val md5Hex: String, val md5Base64: String, val name: String) : Closeable {
    val sizeBytes: Long get() = file.length()
    override fun close() { file.delete() }
    override fun toString() = "SelectedImage($mime, ${width}x$height)"
}

suspend fun stageSelectedImage(context: Context, uri: Uri): SelectedImage {
    var ownedFile: File? = null
    try { return withContext(Dispatchers.IO) {
    require(uri.scheme == "content") { "请选择系统文件选择器中的图片" }
    val directory = File(context.cacheDir, "native_tmp").apply { check(exists() || mkdirs()) }
    val target = File.createTempFile("selected-image-", ".tmp", directory).also { ownedFile = it }
    try {
        val digest = MessageDigest.getInstance("MD5")
        var total = 0L
        context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { output ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer); if (count < 0) break
                currentCoroutineContext().ensureActive()
                total += count; require(total <= MAX_IMAGE_BYTES) { "图片不能超过20MB" }
                output.write(buffer, 0, count); digest.update(buffer, 0, count)
            }
        } } ?: error("无法读取所选图片")
        require(total > 0) { "图片为空" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(target.path, bounds)
        require(bounds.outWidth in 1..16000 && bounds.outHeight in 1..16000 && bounds.outWidth.toLong() * bounds.outHeight <= 64_000_000) { "图片尺寸不支持" }
        val mime = bounds.outMimeType
        val suffix = when (mime) { "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"; "image/gif" -> "gif"; else -> error("仅支持JPEG、PNG、WebP和GIF图片") }
        val hash = digest.digest().toByteString()
        SelectedImage(target, mime, bounds.outWidth, bounds.outHeight, hash.hex(), hash.base64(), "${UUID.randomUUID()}.$suffix")
    } catch (e: Exception) { target.delete(); throw e }
    } } catch (e: Exception) {
        withContext(NonCancellable + Dispatchers.IO) { ownedFile?.delete() }
        throw e
    }
}
internal const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
