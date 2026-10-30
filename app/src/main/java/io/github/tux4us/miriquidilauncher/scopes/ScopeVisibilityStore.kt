// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.scopes

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

private val Context.scopesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "scopes",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Speichert, welche Scopes ausgeblendet sind. Nicht Teil des Launcher-Backups. */
class ScopeVisibilityStore(context: Context) {
    private val store = context.applicationContext.scopesDataStore

    suspend fun loadHidden(known: Set<String>): Set<String> {
        val prefs = store.data.catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }.first()
        return ScopeVisibility.decode(prefs[HIDDEN], known)
    }

    suspend fun saveHidden(hidden: Set<String>) {
        store.edit { it[HIDDEN] = ScopeVisibility.encode(hidden) }
    }

    private companion object {
        val HIDDEN = stringPreferencesKey("hidden")
    }
}
