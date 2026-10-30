// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.calculator

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

private val Context.calculatorDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "calculator",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Lokaler Speicher des Rechners: Winkelmodus, Speicherwert, letztes Ergebnis und Verlauf (höchstens
 * [CalcSession.MAX_HISTORY] Einträge). Die Daten verlassen das Gerät nicht und sind nicht Teil des Launcher-Backups.
 */
class CalculatorStore(context: Context) {
    private val store = context.applicationContext.calculatorDataStore

    suspend fun load(): CalcPersisted {
        val prefs = store.data.catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }.first()
        return CalcCodec.decode(prefs[STATE])
    }

    suspend fun save(state: CalcPersisted) {
        store.edit { it[STATE] = CalcCodec.encode(state) }
    }

    private companion object {
        val STATE = stringPreferencesKey("state")
    }
}
