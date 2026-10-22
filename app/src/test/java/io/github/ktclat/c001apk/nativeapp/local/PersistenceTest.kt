// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.toList
import io.github.ktclat.c001apk.nativeapp.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PersistenceTest {
    @Test fun datastoreSurvivesRecreationAndKeysAreIsolated() = runBlocking {
        val dir = Files.createTempDirectory("native-local-test").toFile()
        val file = File(dir,"test.preferences_pb")
        var job = SupervisorJob()
        var store = PreferenceDataStoreFactory.create(scope=CoroutineScope(Dispatchers.IO+job), produceFile={file})
        val local = LocalRepository(PreferenceJsonStore(store,"library",LocalState.serializer(),LocalState(),LocalRepository::validateState))
        val settings = SettingsRepository(PreferenceJsonStore(store,"settings",AppSettings.serializer(),AppSettings(),AppSettings::validated))
        settings.update { it.copy(fontScale=1.2f,recordHistory=false) }; local.follow(LocalFollowUser("42","合成用户")); local.addBlockedTopic("测试")
        job.cancelAndJoin()
        job = SupervisorJob(); store = PreferenceDataStoreFactory.create(scope=CoroutineScope(Dispatchers.IO+job), produceFile={file})
        try {
            val restored = LocalRepository(PreferenceJsonStore(store,"library",LocalState.serializer(),LocalState(),LocalRepository::validateState))
            val restoredSettings = SettingsRepository(PreferenceJsonStore(store,"settings",AppSettings.serializer(),AppSettings(),AppSettings::validated))
            assertEquals("42",restored.state.first().follows.single().uid)
            assertEquals(1.2f,restoredSettings.settings.first().fontScale)
            restored.clear(LibraryKind.HISTORY); restored.clear(LibraryKind.FAVORITES)
            assertEquals(listOf("测试"),restored.state.first().blacklist.topicBlackList)
            assertFalse(restoredSettings.settings.first().recordHistory)
        } finally { job.cancelAndJoin(); dir.deleteRecursively() }
    }
    @Test fun malformedAndFutureSettingsAreVisibleFailuresNotWiped() = runBlocking {
        val dir = Files.createTempDirectory("native-invalid-test").toFile()
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope=CoroutineScope(Dispatchers.IO+job), produceFile={File(dir,"invalid.preferences_pb")})
        val key = stringPreferencesKey("settings")
        try {
            for (bad in listOf("not-json", "{\"schemaVersion\":999}")) {
                store.edit { it[key] = bad }
                val repo = SettingsRepository(PreferenceJsonStore(store,"settings",AppSettings.serializer(),AppSettings(),AppSettings::validated))
                assertEquals(SettingsLoadState.Failed,repo.loadStates().first())
                try { repo.update { AppSettings() }; fail("damaged record overwritten") } catch (_: IllegalArgumentException) { }
                assertEquals(bad,store.data.first()[key])
            }
        } finally { job.cancelAndJoin(); dir.deleteRecursively() }
    }
}
