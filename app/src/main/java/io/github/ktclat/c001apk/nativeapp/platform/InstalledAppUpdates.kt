// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.platform

import io.github.ktclat.c001apk.nativeapp.data.CoolapkHttpTransport
import io.github.ktclat.c001apk.nativeapp.domain.ApiResult
import io.github.ktclat.c001apk.nativeapp.domain.FailureKind
import kotlinx.serialization.json.*
import java.util.Base64

/** No labels, timestamps, icons, or unrelated device inventory in the request body. */
fun encodeSelectedPackages(selected: List<InstalledApp>): String {
    require(selected.isNotEmpty() && selected.size <= 1000)
    require(selected.map { it.packageName }.distinct().size == selected.size)
    val json = buildJsonObject {
        selected.sortedBy { it.packageName }.forEach { app ->
            require(app.packageName.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) && app.versionCode >= 0)
            put(app.packageName, "0,${app.versionCode},0")
        }
    }
    return Base64.getEncoder().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
}

data class InstalledAppUpdate(val id: String, val packageName: String, val title: String, val installedVersion: String,
    val installedVersionCode: Long, val versionName: String, val versionCode: Long, val size: String, val changelog: String)

fun interface PackageUpdateTransport { suspend fun check(encodedPackages: String): ApiResult<JsonElement> }
class InstalledAppUpdatesRepository(
    private val transport: PackageUpdateTransport,
    private val location: (suspend (InstalledAppUpdate) -> ApiResult<String>)? = null,
) {
    constructor(transport: CoolapkHttpTransport) : this(PackageUpdateTransport { encoded ->
        transport.post("v6/apk/checkUpdate", mapOf("pkgs" to encoded), query = mapOf("coolmarket_beta" to "0"), multipart = true)
    }, { update -> transport.getApkDownloadLocation(update.packageName, update.id, update.versionCode) })
    suspend fun downloadLocation(update: InstalledAppUpdate): ApiResult<String> {
        val result = location?.invoke(update) ?: return ApiResult.Failure(FailureKind.INVALID_ARGUMENT, "此环境未配置下载地址解析")
        return if (result is ApiResult.Success && !isReviewableApkBrowserUrl(result.value))
            ApiResult.Failure(FailureKind.INVALID_RESPONSE, "下载地址不符合安全要求，未打开") else result
    }
    /** Caller must obtain reviewable user opt-in for this exact selected inventory before invoking. */
    suspend fun check(selected: List<InstalledApp>): ApiResult<List<InstalledAppUpdate>> {
        val encoded = try { encodeSelectedPackages(selected) } catch (_: IllegalArgumentException) { return invalid(FailureKind.INVALID_ARGUMENT) }
        return when (val response = transport.check(encoded)) {
            is ApiResult.Failure -> response
            is ApiResult.Success -> try {
                val array = response.value as? JsonArray ?: throw IllegalArgumentException()
                require(array.size <= 1000)
                val installed = selected.associateBy { it.packageName }
                val updates = array.map { element ->
                    val obj = element as? JsonObject ?: throw IllegalArgumentException()
                    fun text(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    val pkg = text("packageName")
                    val local = requireNotNull(installed[pkg])
                    val id = text("id"); require(id.matches(Regex("[0-9]+")))
                    val code = requireNotNull(text("apkversioncode").toLongOrNull()); require(code >= 0)
                    InstalledAppUpdate(id, pkg, text("title").ifBlank { local.label }, local.versionName, local.versionCode,
                        text("apkversionname"), code, text("apksize"), text("changelog").ifBlank { "暂无更新说明" })
                }.filter { it.versionCode > it.installedVersionCode }.distinctBy { it.packageName }
                ApiResult.Success(updates)
            } catch (_: IllegalArgumentException) { invalid(FailureKind.INVALID_RESPONSE) }
        }
    }
    private fun invalid(kind: FailureKind) = ApiResult.Failure(kind, if (kind == FailureKind.INVALID_ARGUMENT) "请选择有效的应用列表" else "应用更新响应格式异常，未显示不可信结果")
}
