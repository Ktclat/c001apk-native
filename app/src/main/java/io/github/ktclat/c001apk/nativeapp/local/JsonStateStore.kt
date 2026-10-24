// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/** One application-scoped DataStore, unrelated to account credentials and Android cache. */
private val Context.localPreferences by preferencesDataStore("native_local_v1")
interface JsonStateStore<T> {
    val data: Flow<T>
    suspend fun update(transform: (T) -> T)
}
class PreferenceJsonStore<T>(
    private val preferences: DataStore<Preferences>,
    key: String,
    private val serializer: KSerializer<T>,
    private val initial: T,
    private val validate: (T) -> T = { it },
) : JsonStateStore<T> {
    private val preferenceKey = stringPreferencesKey(key)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun decode(value: String?): T = validate(value?.let { json.decodeFromString(serializer, it) } ?: initial)
    override val data = preferences.data.map { it[preferenceKey] }.distinctUntilChanged().map(::decode).flowOn(Dispatchers.IO)
    override suspend fun update(transform: (T) -> T) = withContext(Dispatchers.IO) {
        preferences.edit { values ->
            // Decode failure propagates; never replace a damaged or newer record with an empty one.
            values[preferenceKey] = json.encodeToString(serializer, validate(transform(decode(values[preferenceKey]))))
        }
        Unit
    }
    companion object {
        fun <T> create(context: Context, key: String, serializer: KSerializer<T>, initial: T, validate: (T) -> T = { it }): PreferenceJsonStore<T> =
            PreferenceJsonStore(context.applicationContext.localPreferences, key, serializer, initial, validate)
    }
}
