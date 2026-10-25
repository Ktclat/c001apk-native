// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.ktclat.c001apk.nativeapp.local.PreferenceJsonStore
import io.github.ktclat.c001apk.nativeapp.domain.FollowType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SettingsParityPersistenceTest {
    @Test fun queuedFieldIntentsPreserveOtherFieldsAndExplicitReselections() = runBlocking {
        withStore { repo ->
            val before = repo.settings.first()
            repo.update { it.copy(checkAppUpdates = false) }
            repo.update { it.copy(theme = ThemeMode.DARK) }
            repo.selectFollow(FollowType.PRODUCTS)
            val updated = repo.settings.first()
            assertFalse(updated.checkAppUpdates)
            assertEquals(ThemeMode.DARK, updated.theme)
            assertEquals(FollowType.PRODUCTS.ordinal, updated.followType)
            assertEquals(before.fontScale, updated.fontScale)
            repo.update { it.copy(fontScale = 1.2f) }
            repo.update { it.copy(fontScale = 1f) } // Explicit reset cannot be dropped as an equal-to-old snapshot.
            repo.update { it.copy(theme = ThemeMode.SYSTEM) }
            assertEquals(1f, repo.settings.first().fontScale)
            assertEquals(ThemeMode.SYSTEM, repo.settings.first().theme)
            assertFalse(repo.settings.first().checkAppUpdates)
        }
    }
    @Test fun rapidExplicitFollowSelectionsAreAtomicEvenBeforeUiReceivesChanges() = runBlocking {
        withStore { repo ->
            repo.selectFollow(FollowType.TOPICS)
            repo.selectFollow(null)
            repo.selectFollow(FollowType.TOPICS) // Explicitly reselect unchanged earlier server type.
            assertFalse(repo.settings.first().secretFollow)
            assertEquals(2, repo.settings.first().followType)
            repo.selectFollow(null)
            repo.selectFollow(FollowType.PRODUCTS)
            assertFalse(repo.settings.first().secretFollow)
            assertEquals(3, repo.settings.first().followType)
        }
    }
    @Test fun allFollowChoicesUseExactExistingApiTypeAndLocalKeepsPreviousServerChoice() {
        var settings = AppSettings(fontScale = 1.4f, recordHistory = false)
        FollowType.entries.forEach { type ->
            settings = settings.selectFollow(type)
            assertFalse(settings.secretFollow); assertEquals(type.ordinal, settings.followType)
            assertEquals(type.title, settings.followLabel())
        }
        val local = settings.selectFollow(null)
        assertTrue(local.secretFollow); assertEquals(FollowType.APPS.ordinal, local.followType)
        assertFalse(local.recordHistory); assertEquals(1.4f, local.fontScale)
    }
    @Test fun settingsSurviveDiskRecreationAndRetainExplicitLegacyChoice() = runBlocking {
        val directory = Files.createTempDirectory("settings-parity").toFile()
        val file = File(directory, "settings.preferences_pb")
        var job = SupervisorJob()
        fun create() = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file })
        var store = create()
        try {
            store.edit { it[stringPreferencesKey("settings")] = """{"schemaVersion":1,"secretFollow":true,"followType":4,"fontScale":1.4,"theme":"DARK"}""" }
            val repo = SettingsRepository(PreferenceJsonStore(store, "settings", AppSettings.serializer(), AppSettings(), AppSettings::validated))
            val before = repo.settings.first()
            repo.update { it.copy(checkAppUpdates = false, recordHistory = false) }
            job.cancelAndJoin(); job = SupervisorJob(); store = create()
            val restored = SettingsRepository(PreferenceJsonStore(store, "settings", AppSettings.serializer(), AppSettings(), AppSettings::validated)).settings.first()
            assertEquals(2, restored.schemaVersion); assertTrue(restored.secretFollow)
            assertEquals(4, restored.followType); assertEquals(1.4f, restored.fontScale)
            assertEquals(ThemeMode.DARK, restored.theme); assertFalse(restored.checkAppUpdates); assertFalse(restored.recordHistory)
        } finally { job.cancelAndJoin(); directory.deleteRecursively() }
    }
    @Test fun invalidEditedValueCannotReplaceStoredSettings() = runBlocking {
        withStore { repo ->
            val before = repo.settings.first()
            try { repo.update { it.copy(followType = 5) }; fail("invalid follow type accepted") }
            catch (_: IllegalArgumentException) { }
            assertEquals(before, repo.settings.first())
        }
    }
    private suspend fun withStore(block: suspend (SettingsRepository) -> Unit) {
        val directory = Files.createTempDirectory("settings-merge").toFile()
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job), produceFile = { File(directory, "settings.preferences_pb") })
        try { block(SettingsRepository(PreferenceJsonStore(store, "settings", AppSettings.serializer(), AppSettings(), AppSettings::validated))) }
        finally { job.cancelAndJoin(); directory.deleteRecursively() }
    }
}
