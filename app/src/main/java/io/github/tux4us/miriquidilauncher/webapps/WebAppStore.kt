// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.webapps

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

private val Context.webAppsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "webapps",
    // Beschädigte Datei: mit leerer Liste neu beginnen statt abzustürzen.
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Speichert die Liste der Webapps (Name, Adresse, Öffnungsart, Reihenfolge). Die Symbole liegen in [WebAppIcons]. */
class WebAppStore(context: Context) {
    private val store = context.applicationContext.webAppsDataStore

    val apps: Flow<List<WebApp>> = store.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { prefs -> WebAppLogic.decode(prefs[KEY]) }

    /** Wendet [change] auf die gespeicherte Liste an; die Änderung ist atomar. */
    suspend fun update(change: (List<WebApp>) -> List<WebApp>) {
        store.edit { prefs ->
            prefs[KEY] = WebAppLogic.encode(change(WebAppLogic.decode(prefs[KEY])))
        }
    }

    suspend fun current(): List<WebApp> = apps.first()

    /** Ersetzt die gesamte Liste (Import eines Backups). */
    suspend fun replaceAll(list: List<WebApp>) {
        store.edit { it[KEY] = WebAppLogic.encode(WebAppLogic.sanitize(list)) }
    }

    private companion object {
        val KEY = stringPreferencesKey("apps")
    }
}
