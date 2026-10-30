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

private val Context.contactsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "favorite_contacts",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Speichert die Favoriten-Kontakte (Reihenfolge = Reihenfolge im Schnellmenü). */
class FavoriteContactsStore(context: Context) {
    private val store = context.applicationContext.contactsDataStore

    val contacts: Flow<List<FavoriteContact>> = store.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { prefs -> FavoriteContactCodec.decode(prefs[KEY]) }

    suspend fun add(contact: FavoriteContact) {
        store.edit { prefs ->
            val updated = FavoriteContactCodec.add(FavoriteContactCodec.decode(prefs[KEY]), contact)
            prefs[KEY] = FavoriteContactCodec.encode(updated)
        }
    }

    suspend fun remove(contact: FavoriteContact) {
        store.edit { prefs ->
            val updated = FavoriteContactCodec.remove(FavoriteContactCodec.decode(prefs[KEY]), contact)
            prefs[KEY] = FavoriteContactCodec.encode(updated)
        }
    }

    suspend fun move(contact: FavoriteContact, delta: Int) {
        store.edit { prefs ->
            val updated = ListOrder.moveBy(FavoriteContactCodec.decode(prefs[KEY]), delta) { it.key == contact.key }
            prefs[KEY] = FavoriteContactCodec.encode(updated)
        }
    }

    suspend fun current(): List<FavoriteContact> = contacts.first()

    /** Ersetzt die gesamte Liste (Import eines Backups). */
    suspend fun replaceAll(list: List<FavoriteContact>) {
        val unique = list.fold(emptyList<FavoriteContact>()) { acc, c -> FavoriteContactCodec.add(acc, c) }
        store.edit { it[KEY] = FavoriteContactCodec.encode(unique) }
    }

    private companion object {
        val KEY = stringPreferencesKey("contacts")
    }
}
