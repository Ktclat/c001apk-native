// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.feature.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Dedicated anonymous client. Never attach account cookies or Coolapk API headers. */
class MediaTransfer internal constructor(private val context: Context, private val client: OkHttpClient) {
    constructor(context: Context) : this(context, mediaTransferClient())
    data class Download(val file: File, val mime: String)
    suspend fun fetch(url: String): Download {
        var ownedFile: File? = null
        try { return withContext(Dispatchers.IO) {
        val uri = java.net.URI(url)
        require(uri.scheme == "https" && uri.host != null && uri.userInfo == null) { "仅支持安全的 HTTPS 图片地址" }
        val call = client.newCall(Request.Builder().url(url).build())
        // Keep cancellation attached through response-body streaming, not just until headers arrive.
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
        try { call.awaitMediaResponse().use { response ->
            check(response.isSuccessful) { "图片下载失败 (${response.code})" }
            val body = checkNotNull(response.body)
            val mime = body.contentType()?.let { "${it.type}/${it.subtype}" }.orEmpty()
            val extension = mapOf("image/jpeg" to "jpg", "image/png" to "png", "image/gif" to "gif", "image/webp" to "webp", "image/avif" to "avif")[mime]
                ?: error("不支持此图片格式")
            check(body.contentLength() <= MAX_BYTES) { "图片超过 30 MB" }
            val folder = File(context.cacheDir, "shared-media").apply { mkdirs() }
            // A day-long grant window avoids deleting a file while another app is opening it.
            folder.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 86_400_000 }?.forEach { it.delete() }
            val file = File(folder, "c001apk-${UUID.randomUUID()}.$extension").also { ownedFile = it }
            val coroutine = currentCoroutineContext()
            try {
                body.byteStream().use { input -> file.outputStream().use { out ->
                    copyBoundedImage(input, out, MAX_BYTES) { coroutine.ensureActive() }
                } }
                check(file.length() > 0) { "图片为空" }; Download(file, mime)
            } catch (error: Exception) { file.delete(); throw error }
        } } finally { cancellation.cancel() }
        } } catch (error: Exception) {
            // Includes cancellation during withContext return dispatch, after all bytes were written.
            withContext(NonCancellable + Dispatchers.IO) { ownedFile?.delete() }
            throw error
        }
    }
    suspend fun save(download: Download, target: Uri) = withContext(Dispatchers.IO) {
        require(target.scheme == "content") { "只能保存到系统文件选择器提供的位置" }
        val coroutine = currentCoroutineContext()
        context.contentResolver.openOutputStream(target, "w").use { output ->
            checkNotNull(output) { "无法写入文件" }
            download.file.inputStream().use { copyBoundedImage(it, output, MAX_BYTES) { coroutine.ensureActive() } }
        }
    }
    suspend fun discard(download: Download) = withContext(NonCancellable + Dispatchers.IO) { download.file.delete(); Unit }
    fun share(download: Download) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.media", download.file)
        val intent = Intent(Intent.ACTION_SEND).setType(download.mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).also { it.clipData = android.content.ClipData.newRawUri("图片", uri) }
        context.startActivity(Intent.createChooser(intent, "分享图片"))
    }
    companion object { const val MAX_BYTES = 30L * 1024 * 1024 }
}

/** Shared by cache writes; a missing/false Content-Length cannot bypass the cap. */
internal fun copyBoundedImage(input: java.io.InputStream, output: java.io.OutputStream, limit: Long, checkpoint: () -> Unit = {}): Long {
    val buffer = ByteArray(8192); var total = 0L
    while (true) {
        checkpoint()
        val read = input.read(buffer)
        checkpoint()
        if (read < 0) break
        total += read
        check(total <= limit) { "图片超过大小限制" }
        output.write(buffer, 0, read)
    }
    return total
}

private suspend fun Call.awaitMediaResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) { if (continuation.isActive) continuation.resumeWithException(error) }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}

/** Public gallery transfer only; preserve the existing streaming and timeout policies. */
internal fun mediaTransferClient(): OkHttpClient = anonymousMediaClientBuilder()
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build()
