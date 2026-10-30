// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.files

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first

private val Context.filesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "files",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

data class FilesSettings(val sort: SortOrder = SortOrder(), val showHidden: Boolean = false, val lastPath: String? = null)

/** Einstellungen des Dateimanagers (Sortierung, versteckte Dateien, zuletzt geöffneter Ordner) im privaten DataStore. */
class FilesStore(context: Context) {
    private val store = context.applicationContext.filesDataStore

    suspend fun load(): FilesSettings {
        val prefs = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.first()
        val field = SortField.entries.firstOrNull { it.name == prefs[SORT_FIELD] } ?: SortField.NAME
        return FilesSettings(
            sort = SortOrder(field, prefs[SORT_ASC] ?: true),
            showHidden = prefs[SHOW_HIDDEN] ?: false,
            lastPath = prefs[LAST_PATH],
        )
    }

    suspend fun saveSort(order: SortOrder) {
        store.edit {
            it[SORT_FIELD] = order.field.name
            it[SORT_ASC] = order.ascending
        }
    }

    suspend fun saveShowHidden(value: Boolean) {
        store.edit { it[SHOW_HIDDEN] = value }
    }

    suspend fun saveLastPath(path: String) {
        store.edit { it[LAST_PATH] = path }
    }

    private companion object {
        val SORT_FIELD = stringPreferencesKey("sort_field")
        val SORT_ASC = booleanPreferencesKey("sort_asc")
        val SHOW_HIDDEN = booleanPreferencesKey("show_hidden")
        val LAST_PATH = stringPreferencesKey("last_path")
    }
}
