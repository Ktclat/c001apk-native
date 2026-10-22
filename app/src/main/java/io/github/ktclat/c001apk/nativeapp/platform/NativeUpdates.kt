// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.platform

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Stable release ordering; build metadata does not change precedence. */
data class ReleaseVersion(val major: Long, val minor: Long, val patch: Long) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    override fun toString() = "$major.$minor.$patch"
    companion object {
        fun parse(raw: String): ReleaseVersion? {
            val match = Regex("^v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-release)?(?:\\+[0-9]+)?$").matchEntire(raw) ?: return null
            val parts = match.groupValues.drop(1).map { it.toLongOrNull() ?: return null }
            return ReleaseVersion(parts[0], parts[1], parts[2])
        }
    }
}
data class NativeRelease(val version: ReleaseVersion, val page: String, val notes: String, val downloads: Map<String, String>)
const val NATIVE_RELEASES = "https://github.com/Ktclat/c001apk-native/releases"
fun parseNativeRelease(json: JsonObject): NativeRelease {
    fun field(name: String) = (json[name] as? JsonPrimitive)?.contentOrNull
    require((json["draft"] as? JsonPrimitive)?.booleanOrNull == false && (json["prerelease"] as? JsonPrimitive)?.booleanOrNull == false)
    val tag = requireNotNull(field("tag_name"))
    val version = requireNotNull(ReleaseVersion.parse(tag))
    fun trusted(url: String, suffix: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host == "github.com" && uri.rawUserInfo == null && uri.port == -1 && uri.rawQuery == null && uri.rawFragment == null && uri.rawPath == "/Ktclat/c001apk-native/releases/$suffix"
    }.getOrDefault(false)
    val page = requireNotNull(field("html_url")); require(trusted(page, "tag/$tag"))
    val downloads = linkedMapOf<String, String>()
    for (element in requireNotNull(json["assets"] as? JsonArray)) {
        val asset = element as? JsonObject ?: continue
        val name = (asset["name"] as? JsonPrimitive)?.contentOrNull ?: continue
        val url = (asset["browser_download_url"] as? JsonPrimitive)?.contentOrNull ?: continue
        if (!name.endsWith(".apk") || name.contains('/') || (asset["state"] as? JsonPrimitive)?.contentOrNull != "uploaded" || ((asset["size"] as? JsonPrimitive)?.longOrNull ?: 0) <= 0) continue
        if (trusted(url, "download/$tag/$name")) downloads[name] = url
    }
    return NativeRelease(version, page, field("body")?.takeIf { it.isNotBlank() } ?: "此版本未提供更新说明，请查看发布页面。", downloads)
}
sealed interface NativeUpdateResult {
    data class Available(val release: NativeRelease) : NativeUpdateResult
    data class Current(val release: NativeRelease) : NativeUpdateResult
    data object NoRelease : NativeUpdateResult
    data object Suppressed : NativeUpdateResult
    data object Busy : NativeUpdateResult
    data class Failed(val message: String) : NativeUpdateResult
}

/** Separate client and preference namespace: never sends Coolapk credentials to GitHub. */
class NativeUpdateService(context: Context) {
    private val preferences = context.getSharedPreferences("native-release-updates", Context.MODE_PRIVATE)
    private val checking = AtomicBoolean(false)
    private val checkedAutomatically = AtomicBoolean(false)
    private val client = OkHttpClient.Builder().followRedirects(false).connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    var enabled: Boolean
        get() = preferences.getBoolean("enabled", true)
        set(value) { preferences.edit().putBoolean("enabled", value).apply() }
    fun skip(version: ReleaseVersion) { preferences.edit().putString("skipped", version.toString()).apply() }
    suspend fun check(currentVersion: String, automatic: Boolean = false): NativeUpdateResult {
        if (automatic && (!enabled || checkedAutomatically.get())) return NativeUpdateResult.Suppressed
        if (!checking.compareAndSet(false, true)) return NativeUpdateResult.Busy
        if (automatic) checkedAutomatically.set(true)
        try {
            return withContext(Dispatchers.IO) {
                val request = Request.Builder().url("https://api.github.com/repos/Ktclat/c001apk-native/releases/latest")
                    .header("Accept", "application/vnd.github+json").header("User-Agent", "c001apk-native-update").build()
                client.newCall(request).execute().use { response ->
                    if (response.code == 404) return@withContext NativeUpdateResult.NoRelease
                    if (response.code == 403 || response.code == 429) return@withContext NativeUpdateResult.Failed("检查更新请求过于频繁，请稍后重试")
                    check(response.isSuccessful)
                    val body = checkNotNull(response.body)
                    check(body.contentLength() <= 1_048_576)
                    val source = body.source(); source.request(1_048_577); check(source.buffer.size <= 1_048_576)
                    val release = parseNativeRelease(Json.parseToJsonElement(source.readUtf8()).jsonObject)
                    if (automatic && (!enabled || preferences.getString("skipped", null) == release.version.toString())) return@withContext NativeUpdateResult.Suppressed
                    // Native dev builds can discover their first stable release without pretending to be stable.
                    val current = ReleaseVersion.parse(currentVersion) ?: ReleaseVersion.parse(currentVersion.substringBefore('-'))
                    if (current == null) return@withContext NativeUpdateResult.Failed("无法识别当前版本，请手动查看发布页面")
                    if (release.version > current || currentVersion.contains("-dev") && release.version == current) NativeUpdateResult.Available(release) else NativeUpdateResult.Current(release)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return NativeUpdateResult.Failed("检查更新失败，请检查网络或查看发布页面") }
        finally { checking.set(false) }
    }
}
