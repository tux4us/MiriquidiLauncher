// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.calendar

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.agendaDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "agenda",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Einstellung "Termine auf der Startseite anzeigen" (standardmäßig aus). */
class AgendaStore(context: Context) {
    private val store = context.applicationContext.agendaDataStore

    val enabled: Flow<Boolean> = store.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { it[ENABLED] ?: false }

    suspend fun setEnabled(value: Boolean) {
        store.edit { it[ENABLED] = value }
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("enabled")
    }
}
