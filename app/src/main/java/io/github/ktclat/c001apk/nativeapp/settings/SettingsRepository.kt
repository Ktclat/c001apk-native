// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.settings

import android.content.Context
import io.github.ktclat.c001apk.nativeapp.local.JsonStateStore
import io.github.ktclat.c001apk.nativeapp.local.PreferenceJsonStore
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

@Serializable enum class ThemeMode { SYSTEM, LIGHT, DARK }
@Serializable enum class FontChoice { SYSTEM, MATERIAL, NOTO_SANS }
@Serializable enum class ReadingPreset { COMFORTABLE, ORIGINAL }
@Serializable data class AppSettings(
    val schemaVersion: Int = 2,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val themeColor: Int = 0,
    val font: FontChoice = FontChoice.NOTO_SANS,
    val fontScale: Float = 1f,
    val readingPreset: ReadingPreset = ReadingPreset.COMFORTABLE,
    val floatingNavigation: Boolean = false,
    val secretFollow: Boolean = false,
    val recordHistory: Boolean = true,
    val showEmoji: Boolean = true,
    val openInBrowser: Boolean = false,
    val checkAppUpdates: Boolean = true,
    val followType: Int = 0,
) {
    fun validated(): AppSettings {
        require(schemaVersion in 1..2) { "不支持的设置版本" }
        require(fontScale.isFinite() && fontScale in 0.8f..1.6f)
        require(themeColor in 0..18 && followType in 0..4)
        // Native has no access to Flutter's Hive. Migration never overwrites explicit typography.
        return copy(schemaVersion = 2)
    }
}
class SettingsRepository(private val store: JsonStateStore<AppSettings>) {
    val settings = store.data.map { it.validated() }
    suspend fun update(transform: (AppSettings) -> AppSettings) = store.update { transform(it.validated()).validated() }
    companion object {
        fun create(context: Context) = SettingsRepository(PreferenceJsonStore.create(context, "settings", AppSettings.serializer(), AppSettings(), AppSettings::validated))
    }
}
