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

/** Speichert, welche Scopes ausgeblendet sind und in welcher Reihenfolge sie stehen. Beides ist Teil des Launcher-Backups. */
class ScopeVisibilityStore(context: Context) {
    private val store = context.applicationContext.scopesDataStore

    private suspend fun prefs(): Preferences = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.first()

    suspend fun loadHidden(known: Set<String>): Set<String> = ScopeVisibility.decode(prefs()[HIDDEN], known)

    suspend fun saveHidden(hidden: Set<String>) {
        store.edit { it[HIDDEN] = ScopeVisibility.encode(hidden) }
    }

    /** Gespeicherte Reihenfolge aller Scopes; [known] ist die Standardreihenfolge. */
    suspend fun loadOrder(known: List<String>): List<String> = ScopeVisibility.decodeOrder(prefs()[ORDER], known)

    suspend fun saveOrder(order: List<String>) {
        store.edit { it[ORDER] = ScopeVisibility.encodeOrder(order) }
    }

    /** Ersetzt Sichtbarkeit und Reihenfolge in einem Schritt (Backup-Import). */
    suspend fun replaceAll(hidden: Set<String>, order: List<String>) {
        store.edit {
            it[HIDDEN] = ScopeVisibility.encode(hidden)
            it[ORDER] = ScopeVisibility.encodeOrder(order)
        }
    }

    private companion object {
        val HIDDEN = stringPreferencesKey("hidden")
        val ORDER = stringPreferencesKey("order")
    }
}
