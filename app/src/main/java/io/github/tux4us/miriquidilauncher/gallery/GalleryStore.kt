// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.gallery

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first

private val Context.galleryDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "gallery",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

data class GallerySettings(
    /** Favoriten, als Dateipfade. */
    val favorites: Set<String> = emptySet(),
    val hiddenBuckets: Set<Long> = emptySet(),
    val ascending: Boolean = false,
    val showHidden: Boolean = false,
)

/** Lokale Einstellungen der Galerie: Favoriten, ausgeblendete Alben, Sortierung. Alles bleibt auf dem Gerät. */
class GalleryStore(context: Context) {
    private val store = context.applicationContext.galleryDataStore

    suspend fun load(): GallerySettings {
        val prefs = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.first()
        return GallerySettings(
            favorites = prefs[FAVORITES].orEmpty(),
            hiddenBuckets = prefs[HIDDEN].orEmpty().mapNotNull { it.toLongOrNull() }.toSet(),
            ascending = prefs[ASCENDING] ?: false,
            showHidden = prefs[SHOW_HIDDEN] ?: false,
        )
    }

    suspend fun saveFavorites(value: Set<String>) {
        store.edit { it[FAVORITES] = value }
    }

    suspend fun saveHidden(value: Set<Long>) {
        store.edit { it[HIDDEN] = value.map { id -> id.toString() }.toSet() }
    }

    suspend fun saveAscending(value: Boolean) {
        store.edit { it[ASCENDING] = value }
    }

    suspend fun saveShowHidden(value: Boolean) {
        store.edit { it[SHOW_HIDDEN] = value }
    }

    private companion object {
        val FAVORITES = stringSetPreferencesKey("favorites")
        val HIDDEN = stringSetPreferencesKey("hidden_buckets")
        val ASCENDING = booleanPreferencesKey("ascending")
        val SHOW_HIDDEN = booleanPreferencesKey("show_hidden")
    }
}
