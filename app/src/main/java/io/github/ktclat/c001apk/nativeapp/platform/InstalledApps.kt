// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.platform

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledApp(val packageName: String, val label: String, val versionName: String = "", val versionCode: Long = 0, val lastUpdateTime: Long = 0)
enum class AppSort { NAME, UPDATED }
fun sortInstalledApps(apps: List<InstalledApp>, sort: AppSort): List<InstalledApp> = when (sort) {
    AppSort.NAME -> apps.sortedWith(compareBy<InstalledApp> { it.label.lowercase(java.util.Locale.ROOT) }.thenBy { it.packageName })
    AppSort.UPDATED -> apps.sortedWith(compareByDescending<InstalledApp> { it.lastUpdateTime }.thenBy { it.packageName })
}

/** Intentionally bounded to launcher-visible non-system apps, not QUERY_ALL_PACKAGES. */
suspend fun installedLauncherApps(context: Context): List<InstalledApp> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    @Suppress("DEPRECATION")
    val activities = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
    val apps = activities.mapNotNull { activity ->
        val info = activity.activityInfo.applicationInfo
        if ((info.flags and ApplicationInfo.FLAG_SYSTEM) != 0) return@mapNotNull null
        try {
            @Suppress("DEPRECATION") val pkg = pm.getPackageInfo(info.packageName, 0)
            @Suppress("DEPRECATION") val version = if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else pkg.versionCode.toLong()
            InstalledApp(info.packageName, info.loadLabel(pm).toString(), pkg.versionName.orEmpty(), version, pkg.lastUpdateTime)
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) { null }
    }.distinctBy { it.packageName }
    sortInstalledApps(apps, AppSort.NAME)
}
