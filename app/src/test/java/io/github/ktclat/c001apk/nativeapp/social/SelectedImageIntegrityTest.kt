// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

/** Immutable selected content checks; interceptors never open a socket. */
class SelectedImageIntegrityTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun image(): SelectedImage {
        val file = temporary.newFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val hash = MessageDigest.getInstance("MD5").digest(file.readBytes()).toByteString()
        return SelectedImage(file, "image/jpeg", 1, 1, hash.hex(), hash.base64(), "selected.jpg")
    }
    @Test fun verifiesOriginalAndRejectsSameLengthReplacementOrDeletion() = runBlocking {
        val image = image()
        assertTrue(verifySelectedImage(image))
        image.file.writeBytes(byteArrayOf(3, 2, 1))
        assertFalse(verifySelectedImage(image))
        image.close(); assertFalse(verifySelectedImage(image))
    }
    @Test fun rejectsIncorrectFrozenBase64EvenWithMatchingHex() = runBlocking {
        val image = image()
        val inconsistent = SelectedImage(image.file, image.mime, image.width, image.height, image.md5Hex, "wrong", image.name)
        assertFalse(verifySelectedImage(inconsistent))
    }
    @Test fun replacementBetweenPreflightAndPutBodyBecomesUncertainNotSuccess() = runBlocking {
        val image = image()
        val grant = OssGrant("synthetic-bucket", "oss-cn-hangzhou.aliyuncs.com", "feed/selected.jpg",
            "synthetic", "synthetic", "synthetic", System.currentTimeMillis() + 60_000)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            image.file.writeBytes(byteArrayOf(3, 2, 1))
            chain.request().body!!.writeTo(Buffer()) // Must throw on the frozen MD5 mismatch.
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("synthetic").body("".toResponseBody()).build()
        }.build()
        try {
            val result = OssImageUploader(client).upload(grant, image) { true }
            assertTrue(result is io.github.ktclat.c001apk.nativeapp.domain.ApiResult.Failure)
            assertEquals(io.github.ktclat.c001apk.nativeapp.domain.FailureKind.UNCERTAIN,
                (result as io.github.ktclat.c001apk.nativeapp.domain.ApiResult.Failure).kind)
        } finally { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
    }
}
