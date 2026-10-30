// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.groupsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app_groups",
    // Beschädigte Datei: mit leerer Gruppenliste neu beginnen statt abzustürzen.
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Speichert die App-Gruppen (Reihenfolge der Gruppen und der Apps darin). */
class AppGroupsStore(context: Context) {
    private val store = context.applicationContext.groupsDataStore

    val groups: Flow<List<AppGroup>> = store.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { prefs -> AppGroupCodec.decode(prefs[KEY]) }

    /** Wendet [change] auf die gespeicherte Liste an; die Änderung ist atomar. */
    suspend fun update(change: (List<AppGroup>) -> List<AppGroup>) {
        store.edit { prefs ->
            prefs[KEY] = AppGroupCodec.encode(change(AppGroupCodec.decode(prefs[KEY])))
        }
    }

    suspend fun current(): List<AppGroup> = groups.first()

    /** Ersetzt die gesamte Liste (Import eines Backups). */
    suspend fun replaceAll(list: List<AppGroup>) {
        store.edit { it[KEY] = AppGroupCodec.encode(AppGroupCodec.sanitize(list)) }
    }

    private companion object {
        val KEY = stringPreferencesKey("groups")
    }
}
