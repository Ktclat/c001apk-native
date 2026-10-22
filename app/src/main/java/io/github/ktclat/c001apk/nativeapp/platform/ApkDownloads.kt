// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.platform

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import java.net.URI

/** Enqueue only after a user chooses Download. No cookies, API headers, or installation intent. */
fun enqueueApkDownload(context: Context, url: String, packageName: String, versionCode: Long, allowedHosts: Set<String>): Long {
    val uri = URI(url)
    require(uri.scheme == "https" && uri.host in allowedHosts && uri.userInfo == null && uri.port in setOf(-1, 443) && uri.fragment == null) { "下载地址必须是 HTTPS" }
    require(packageName.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) && versionCode >= 0) { "应用信息无效" }
    val request = DownloadManager.Request(Uri.parse(url))
        .setTitle("$packageName ($versionCode)")
        .setMimeType("application/vnd.android.package-archive")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        // Scoped destination works on API26+ without broad storage permission.
        .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "$packageName-$versionCode-${System.currentTimeMillis()}.apk")
    return (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
}

/** Defense-in-depth for a user-reviewed browser handoff, NOT a trusted CDN allowlist. */
fun isReviewableApkBrowserUrl(raw: String): Boolean = runCatching {
    if (raw.length > 8192) return@runCatching false
    val uri = URI(raw)
    val host = uri.host?.lowercase(java.util.Locale.ROOT)?.trimEnd('.') ?: return@runCatching false
    val labels = host.split('.')
    uri.scheme == "https" && uri.port in setOf(-1, 443) && uri.rawUserInfo == null && uri.rawFragment == null &&
        labels.size >= 2 && !host.contains(':') && !host.matches(Regex("[0-9.]+")) &&
        !labels.last().matches(Regex("(?:[0-9]+|0x[0-9a-f]+)")) &&
        labels.all { it.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) } &&
        labels.last() !in setOf("localhost", "local", "internal", "lan", "home", "test", "invalid", "onion", "arpa")
}.getOrDefault(false)
