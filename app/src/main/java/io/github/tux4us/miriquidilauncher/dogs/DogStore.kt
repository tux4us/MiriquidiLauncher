// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.dogs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first

private val Context.dogDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "dogs",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Lokaler Speicher der Hundeprofile und Fälligkeiten (JSON, siehe [DogCodec]). Die Daten verlassen das Gerät nicht;
 * sie stehen nur in der Backup-Datei des Launchers, nie in QR-Codes der Sicherung.
 */
class DogStore(context: Context) {
    private val store = context.applicationContext.dogDataStore

    suspend fun load(): DogData {
        val prefs = store.data.catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }.first()
        return DogCodec.decode(prefs[DATA])
    }

    suspend fun save(data: DogData) {
        store.edit { it[DATA] = DogCodec.encode(data) }
    }

    private companion object {
        val DATA = stringPreferencesKey("data")
    }
}
