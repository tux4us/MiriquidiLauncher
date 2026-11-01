// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.camera

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

// Eigener Name: "camera" gehört bereits dem Kamera-Button neben dem Suchfeld.
private val Context.cameraScopeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "camera_scope",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Lokaler Speicher der Kamera-Scope: Einstellungen und Adresse der zuletzt aufgenommenen Datei. Die Daten verlassen
 * das Gerät nicht. Die Einstellungen (ohne Kamerakennung und letzte Datei) sind Teil der Backup-Datei des Launchers. Die Aufnahmen selbst liegen in der Mediathek.
 */
class CameraSettingsStore(context: Context) {
    private val store = context.applicationContext.cameraScopeDataStore

    suspend fun load(): CameraSettings {
        val prefs = store.data.catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }.first()
        return CameraSettingsCodec.decode(prefs[SETTINGS])
    }

    suspend fun save(settings: CameraSettings) {
        store.edit { it[SETTINGS] = CameraSettingsCodec.encode(settings) }
    }

    private companion object {
        val SETTINGS = stringPreferencesKey("settings")
    }
}
