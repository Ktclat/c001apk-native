// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.ktclat.c001apk.nativeapp.local.PreferenceJsonStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FloatingNavigationPersistenceTest {
    @Test fun navigationSettingSurvivesRecreationWithoutAnUnpublishedScrollMode() = runBlocking {
        val directory = Files.createTempDirectory("dock-default-test").toFile()
        val file = File(directory, "settings.preferences_pb")
        var job = SupervisorJob()
        var store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file })
        val key = stringPreferencesKey("settings")
        try {
            store.edit { it[key] = """{"floatingNavigation":true,"floatingDockScrollBehavior":"ALWAYS_VISIBLE","fontScale":1.4}""" }
            val repository = SettingsRepository(PreferenceJsonStore(store, "settings", AppSettings.serializer(), AppSettings(), AppSettings::validated))
            repository.update { it.copy(theme = ThemeMode.DARK) }
            assertFalse(store.data.first()[key]!!.contains("floatingDockScrollBehavior"))
            job.cancelAndJoin()
            job = SupervisorJob()
            store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file })
            val restored = SettingsRepository(PreferenceJsonStore(store, "settings", AppSettings.serializer(), AppSettings(), AppSettings::validated)).settings.first()
            assertTrue(restored.floatingNavigation)
            assertEquals(1.4f, restored.fontScale)
            assertEquals(ThemeMode.DARK, restored.theme)
        } finally { job.cancelAndJoin(); directory.deleteRecursively() }
    }
    @Test fun unrelatedCorruptionCannotBeOverwrittenByAnUpdate() = runBlocking {
        val directory = Files.createTempDirectory("dock-invalid-test").toFile()
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job), produceFile = { File(directory, "settings.preferences_pb") })
        val key = stringPreferencesKey("settings")
        try {
            for (bad in listOf("not-json", """{"schemaVersion":999}""")) {
                store.edit { it[key] = bad }
                val repository = SettingsRepository(PreferenceJsonStore(store, "settings", AppSettings.serializer(), AppSettings(), AppSettings::validated))
                try { repository.settings.first(); fail("Corrupt settings silently reset") } catch (_: IllegalArgumentException) { }
                try { repository.update { AppSettings() }; fail("Corrupt settings overwritten") } catch (_: IllegalArgumentException) { }
                assertEquals(bad, store.data.first()[key])
            }
        } finally { job.cancelAndJoin(); directory.deleteRecursively() }
    }
}
