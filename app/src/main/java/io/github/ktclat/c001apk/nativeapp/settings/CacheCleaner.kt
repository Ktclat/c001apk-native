// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.settings

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Only allowlisted disposable directories inside cacheDir; never databases, files or preferences. */
class CacheCleaner(private val cacheDir: File) {
    private val names = listOf("image_cache", "native_tmp")
    data class Result(val removedBytes: Long, val failedFiles: Int)
    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) { files().sumOf { it.length() } }
    suspend fun clear(): Result = withContext(Dispatchers.IO) {
        var bytes = 0L; var failed = 0
        files().forEach { file -> val size = file.length(); if (file.delete()) bytes += size else failed++ }
        Result(bytes, failed)
    }
    private fun files(): List<File> {
        val cachePath = cacheDir.canonicalFile
        val result = mutableListOf<File>()
        fun visit(file: File, root: File, depth: Int) {
            if (depth > 16) return
            // Reject symlinks and paths escaping the specific disposable root.
            val canonical = file.canonicalFile
            if (canonical != file.absoluteFile || !(canonical.path == root.path || canonical.path.startsWith(root.path + File.separator))) return
            if (file.isDirectory) file.listFiles()?.forEach { visit(it, root, depth + 1) }
            else if (file.isFile) result += file
        }
        names.forEach { name -> val root = File(cachePath,name); if (root.canonicalFile == root.absoluteFile) visit(root,root,0) }
        return result
    }
    companion object { fun create(context: Context) = CacheCleaner(context.applicationContext.cacheDir) }
}
