// SPDX-License-Identifier: AGPL-3.0-only
// Feed upload fields adapted from Ktclat/c001apk reply_dialog.dart (AGPL-3.0).
package io.github.ktclat.c001apk.nativeapp.social

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal const val MAX_FEED_IMAGES = 9
internal const val MAX_FEED_IMAGE_BYTES = 60L * 1024 * 1024
internal class FeedImageGrant(val upload: OssGrant, val publicationUrl: String) {
    override fun toString() = "FeedImageGrant(redacted)"
}
internal fun validImageBatch(images: List<SelectedImage>): Boolean = images.size in 1..MAX_FEED_IMAGES &&
    images.map { it.name }.distinct().size == images.size && images.all { it.sizeBytes in 1..MAX_IMAGE_BYTES } &&
    images.sumOf { it.sizeBytes } <= MAX_FEED_IMAGE_BYTES

internal fun feedPrepareForm(images: List<SelectedImage>): Map<String, String> = mapOf(
    "uploadBucket" to "image", "uploadDir" to "feed", "is_anonymous" to "0",
    "uploadFileList" to buildJsonArray { images.forEach { image -> add(buildJsonObject {
        put("name", image.name); put("resolution", "${image.width}x${image.height}"); put("md5", image.md5Hex)
    }) } }.toString(),
)
internal fun parseFeedImageGrants(value: JsonElement, images: List<SelectedImage>, now: Long): List<FeedImageGrant>? = runCatching {
    require(validImageBatch(images))
    val data = value.jsonObject
    val info = data["uploadPrepareInfo"]!!.jsonObject
    val prefix = info.text("uploadImagePrefix").toHttpUrlOrNull() ?: error("Missing image prefix")
    require(prefix.isHttps && prefix.host == "image.coolapk.com" && prefix.port == 443 && prefix.username.isEmpty() && prefix.password.isEmpty())
    require(prefix.encodedPath == "/" && prefix.query == null && prefix.fragment == null)
    val files = data["fileInfo"]!!.jsonArray.map { it.jsonObject }
    require(files.size == images.size && files.map { it.text("name") }.distinct().size == files.size)
    require(files.map { it.text("name") }.toSet() == images.map { it.name }.toSet())
    val grants = images.map { image ->
        val file = files.single { it.text("name") == image.name }
        val single = buildJsonObject { put("uploadPrepareInfo", info); put("fileInfo", buildJsonArray { add(file) }) }
        val grant = parseOssGrant(single, image, now, "feed") ?: error("Invalid feed grant")
        require(',' !in grant.objectName)
        FeedImageGrant(grant, prefix.newBuilder().addPathSegments(grant.objectName).build().toString())
    }
    require(grants.map { it.upload.objectName }.distinct().size == grants.size)
    require(grants.map { it.publicationUrl }.distinct().size == grants.size)
    grants
}.getOrNull()
