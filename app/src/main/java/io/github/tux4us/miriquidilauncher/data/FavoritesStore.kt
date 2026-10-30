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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.favoritesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "favorites",
    // Beschädigte Datei: mit leerer Favoritenliste neu beginnen statt abzustürzen.
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Speichert die Reihenfolge der Favoriten als Liste von [AppEntry.key].
 *
 * Schlüssel nicht (mehr) installierter Apps bleiben gespeichert. Dadurch tauchen Apps, die nur
 * vorübergehend fehlen (SD-Karte, pausiertes Arbeitsprofil), nach der Rückkehr wieder auf.
 */
class FavoritesStore(context: Context) {
    private val store = context.applicationContext.favoritesDataStore

    val favoriteKeys: Flow<List<String>> = store.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { prefs -> decode(prefs[KEY]) }

    suspend fun toggle(key: String) {
        store.edit { prefs ->
            val current = decode(prefs[KEY])
            val updated = if (key in current) current - key else current + key
            prefs[KEY] = encode(updated)
        }
    }

    /** Verschiebt den Favoriten [key] um [delta] Positionen (negativ = nach vorn, also im Menü nach unten). */
    suspend fun move(key: String, delta: Int) {
        store.edit { prefs ->
            prefs[KEY] = encode(ListOrder.moveBy(decode(prefs[KEY]), delta) { it == key })
        }
    }

    suspend fun current(): List<String> = favoriteKeys.first()

    /** Ersetzt die gesamte Liste (Import eines Backups). */
    suspend fun replaceAll(keys: List<String>) {
        store.edit { it[KEY] = encode(keys.distinct()) }
    }

    private fun decode(raw: String?): List<String> =
        raw?.split(SEPARATOR)?.filter { it.isNotEmpty() }.orEmpty()

    private fun encode(keys: List<String>): String = keys.joinToString(SEPARATOR)

    private companion object {
        val KEY = stringPreferencesKey("favorite_keys")
        const val SEPARATOR = "\n"
    }
}
