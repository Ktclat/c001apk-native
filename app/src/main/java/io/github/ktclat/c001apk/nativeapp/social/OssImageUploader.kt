// SPDX-License-Identifier: AGPL-3.0-only
// Adapted from Ktclat/c001apk chat_image_uploader.dart and utils/oss (AGPL-3.0).
package io.github.ktclat.c001apk.nativeapp.social

import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink
import okio.source
import okio.ByteString.Companion.toByteString
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** No data-class generated toString/copy exposing temporary credentials. Never persist this object. */
internal class OssGrant(val bucket: String, val endpoint: String, val objectName: String,
    val accessId: String, val accessSecret: String, val securityToken: String, val expiresAt: Long) {
    override fun toString() = "OssGrant(redacted)"
    val destination: HttpUrl get() = "https://$bucket.$endpoint/".toHttpUrl().newBuilder().addPathSegments(objectName).build()
}
internal fun parseOssGrant(value: JsonElement, image: SelectedImage, now: Long): OssGrant? = runCatching {
    val data = value.jsonObject
    val info = data["uploadPrepareInfo"]!!.jsonObject
    val files = data["fileInfo"]!!.jsonArray
    require(files.size == 1)
    val file = files.single().jsonObject
    require(file.text("name") == image.name && file.text("md5").equals(image.md5Hex, ignoreCase = true))
    val endpoint = info.text("endPoint").removePrefix("https://")
    require(Regex("oss-[a-z0-9-]+\\.aliyuncs\\.com").matches(endpoint))
    val bucket = info.text("bucket")
    require(Regex("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]").matches(bucket))
    val objectName = file.text("uploadFileName")
    require(objectName.startsWith("message/") && objectName.length <= 1024)
    require(objectName.split('/').none { it.isEmpty() || it == "." || it == ".." })
    require(Regex("[A-Za-z0-9/_.,@+-]+").matches(objectName))
    val expiration = info.text("expiration")
    val expires = parseOssExpiration(expiration) ?: error("Missing expiry")
    require(expires > now + 5000 && expires <= now + 24L * 60 * 60 * 1000)
    fun secret(key: String): String = info.text(key).also { require(it.length in 1..8192 && it.none { c -> c.code < 32 || c.code == 127 }) }
    OssGrant(bucket, endpoint, objectName, secret("accessKeyId"), secret("accessKeySecret"), secret("securityToken"), expires)
}.getOrNull()
internal fun parseOssExpiration(value: String): Long? {
    if (!Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z").matches(value)) return null
    return runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { isLenient = false; timeZone = TimeZone.getTimeZone("UTC") }.parse(value)?.time }.getOrNull()
}
internal fun ossHeaders(grant: OssGrant, image: SelectedImage, now: Long): Map<String, String> {
    val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.format(Date(now))
    val headers = linkedMapOf("Content-Type" to image.mime, "Content-MD5" to image.md5Base64, "Date" to date,
        "x-oss-security-token" to grant.securityToken, "x-oss-callback-var" to "eyJ4OnZhcjEiOiJmYWxzZSJ9", "x-oss-callback" to OSS_CALLBACK)
    val canonical = headers.filterKeys { it.startsWith("x-oss-") }.toSortedMap().entries.joinToString("\n") { "${it.key}:${it.value}" }
    val toSign = "PUT\n${image.md5Base64}\n${image.mime}\n$date\n$canonical\n/${grant.bucket}/${grant.objectName}"
    val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(grant.accessSecret.toByteArray(Charsets.UTF_8), "HmacSHA1")) }
    headers["Authorization"] = "OSS ${grant.accessId}:${mac.doFinal(toSign.toByteArray(Charsets.UTF_8)).toByteString().base64()}"
    return headers
}

/** Dedicated credential-free client. API cookies/signatures are never attached to OSS/CDN requests. */
class OssImageUploader {
    private val client = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false)
        .followSslRedirects(false).retryOnConnectionFailure(false).callTimeout(60, TimeUnit.SECONDS).build()
    internal suspend fun upload(grant: OssGrant, image: SelectedImage, isCurrent: () -> Boolean): ApiResult<String> = withContext(Dispatchers.IO) {
        if (!isCurrent()) throw CancellationException("Session changed")
        if (System.currentTimeMillis() >= grant.expiresAt - 5000 || image.sizeBytes !in 1..MAX_IMAGE_BYTES)
            return@withContext ApiResult.Failure(FailureKind.INVALID_ARGUMENT, "图片或上传授权已失效，请重新选择")
        val body = object : RequestBody() {
            override fun contentType() = image.mime.toMediaType()
            override fun contentLength() = image.sizeBytes
            override fun writeTo(sink: BufferedSink) {
                image.file.source().use { source ->
                    var sent = 0L
                    while (true) {
                        if (!isCurrent()) throw java.io.IOException("Session changed")
                        val count = source.read(sink.buffer, 32 * 1024L); if (count < 0) break
                        sent += count; if (sent > MAX_IMAGE_BYTES) throw java.io.IOException("Image exceeds limit")
                        sink.emitCompleteSegments()
                    }
                }
            }
        }
        val request = Request.Builder().url(grant.destination).put(body).apply {
            ossHeaders(grant, image, System.currentTimeMillis()).forEach { (key, value) -> header(key, value) }
        }.build()
        try {
            val response = suspendCancellableCoroutine<Response> { continuation ->
                val call = client.newCall(request); continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: java.io.IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                    override fun onResponse(call: Call, response: Response) { continuation.resume(response) { _, result, _ -> result.close() } }
                })
            }
            response.use {
                if (!isCurrent()) throw CancellationException("Session changed")
                if (it.isSuccessful) ApiResult.Success("/${grant.objectName}")
                else ApiResult.Failure(FailureKind.UNCERTAIN, "图片上传结果未确认；消息尚未发送")
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { ApiResult.Failure(FailureKind.UNCERTAIN, "图片上传结果未确认；消息尚未发送") }
    }
}
// Fixed source-compatible callback. The server response cannot redirect the token or callback to another site.
private const val OSS_CALLBACK = "eyJjYWxsYmFja0JvZHlUeXBlIjoiYXBwbGljYXRpb25cL2pzb24iLCJjYWxsYmFja0hvc3QiOiJhcGkuY29vbGFway5jb20iLCJjYWxsYmFja1VybCI6Imh0dHBzOlwvXC9hcGkuY29vbGFway5jb21cL3Y2XC9jYWxsYmFja1wvbW9iaWxlT3NzVXBsb2FkU3VjY2Vzc0NhbGxiYWNrP2NoZWNrQXJ0aWNsZUNvdmVyUmVzb2x1dGlvbj0wJnZlcnNpb25Db2RlPTIxMDIwMzEiLCJjYWxsYmFja0JvZHkiOiJ7XCJidWNrZXRcIjoke2J1Y2tldH0sXCJvYmplY3RcIjoke29iamVjdH0sXCJoYXNQcm9jZXNzXCI6JHt4OnZhcjF9fSJ9"
