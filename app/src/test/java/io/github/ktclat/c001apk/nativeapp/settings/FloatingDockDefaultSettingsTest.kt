// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.settings

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class FloatingDockDefaultSettingsTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun scrollHidingHasNoSeparateSavedPreference() {
        val saved = json.encodeToString(AppSettings.serializer(), AppSettings(floatingNavigation = true))
        assertFalse(saved.contains("floatingDockScrollBehavior"))
        assertFalse(AppSettings::class.java.declaredFields.any { it.name == "floatingDockScrollBehavior" })
    }
    @Test fun navigationModeDefaultIsUnchanged() {
        assertFalse(AppSettings().floatingNavigation)
        assertTrue(AppSettings(floatingNavigation = true).validated().floatingNavigation)
    }
    @Test fun legacySchemaWithoutDraftPreferencePreservesItsValues() {
        for (schema in 1..2) {
            val saved = """{"schemaVersion":$schema,"floatingNavigation":true,"fontScale":1.4,"readingPreset":"ORIGINAL"}"""
            val restored = json.decodeFromString(AppSettings.serializer(), saved).validated()
            assertEquals(2, restored.schemaVersion)
            assertTrue(restored.floatingNavigation)
            assertEquals(1.4f, restored.fontScale)
            assertEquals(ReadingPreset.ORIGINAL, restored.readingPreset)
        }
    }
    @Test fun unpublishedModeIsIgnoredAndNotWrittenBack() {
        for (value in listOf("\"AUTO\"", "\"HIDE_WHILE_SCROLLING\"", "\"ALWAYS_VISIBLE\"", "\"FUTURE_CHOICE\"", "null")) {
            val old = """{"floatingNavigation":true,"floatingDockScrollBehavior":$value,"fontScale":1.4}"""
            val restored = json.decodeFromString(AppSettings.serializer(), old).validated()
            assertTrue(restored.floatingNavigation)
            assertEquals(1.4f, restored.fontScale)
            assertFalse(json.encodeToString(AppSettings.serializer(), restored).contains("floatingDockScrollBehavior"))
        }
    }
    @Test fun ordinarySettingsChangesKeepNavigationAndTypography() {
        val before = AppSettings(floatingNavigation = true, fontScale = 1.2f, themeColor = 18, secretFollow = true)
        val restored = json.decodeFromString(AppSettings.serializer(), json.encodeToString(AppSettings.serializer(), before)).validated()
        assertEquals(before, restored)
        assertTrue(restored.copy(theme = ThemeMode.DARK).validated().floatingNavigation)
    }
    @Test fun removingTheDraftPreferenceDoesNotRelaxExistingValidation() {
        for (bad in listOf(AppSettings(schemaVersion = 3), AppSettings(fontScale = Float.NaN), AppSettings(fontScale = 2f), AppSettings(themeColor = 19), AppSettings(followType = 5))) {
            try { bad.validated(); fail("Invalid settings accepted") } catch (_: IllegalArgumentException) { }
        }
    }
}
