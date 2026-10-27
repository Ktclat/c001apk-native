// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.local

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Document access only after an explicit system-picker result. No broad storage permission. */
interface BlacklistDocumentAccess {
    suspend fun read(uri: Uri): String
    suspend fun write(uri: Uri, text: String)
}

internal class ResolverBlacklistDocuments(private val resolver: ContentResolver) : BlacklistDocumentAccess {
    override suspend fun read(uri: Uri): String = withContext(Dispatchers.IO) {
        resolver.openInputStream(uri)?.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size() + count <= BlacklistJson.MAX_BYTES)
                output.write(buffer, 0, count)
            }
            output.toString("UTF-8")
        } ?: error("无法打开文件")
    }

    override suspend fun write(uri: Uri, text: String) = withContext(Dispatchers.IO) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        // Validate before opening/truncating the selected output. Oversized files are not backups.
        require(bytes.size <= BlacklistJson.MAX_BYTES)
        resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("无法写入文件")
    }
}
