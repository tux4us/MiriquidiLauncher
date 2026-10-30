// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.notes

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.tux4us.miriquidilauncher.feed.SecretBox
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private val Context.notesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "notes",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

data class NotesCredentials(val baseUrl: String, val user: String, val password: String)

data class NotesSettings(
    val sort: NoteSort = NoteSort.MODIFIED,
    val ascending: Boolean = false,
    val lastSyncMillis: Long = 0L,
)

/**
 * Lokaler Zustand der Notizen außerhalb der Notiz-Dateien: Zugangsdaten (Passwort verschlüsselt über den Keystore),
 * Einstellungen sowie Favoriten und Abgleich-Zuordnung in `files/notes/state.json`. Zugangsdaten sind nicht Teil
 * des Launcher-Backups.
 */
class NotesStore(context: Context) {
    private val appContext = context.applicationContext
    private val store = appContext.notesDataStore
    private val box = SecretBox(KEY_ALIAS)
    private val directory = File(appContext.filesDir, "notes")
    private val stateFile = File(directory, "state.json")

    private suspend fun prefs(): Preferences = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.first()

    suspend fun settings(): NotesSettings {
        val p = prefs()
        return NotesSettings(
            sort = if (p[SORT] == "title") NoteSort.TITLE else NoteSort.MODIFIED,
            ascending = p[ASCENDING] ?: false,
            lastSyncMillis = p[LAST_SYNC] ?: 0L,
        )
    }

    suspend fun saveSort(sort: NoteSort, ascending: Boolean) {
        store.edit {
            it[SORT] = if (sort == NoteSort.TITLE) "title" else "modified"
            it[ASCENDING] = ascending
        }
    }

    suspend fun saveLastSync(millis: Long) {
        store.edit { it[LAST_SYNC] = millis }
    }

    suspend fun loadCredentials(): NotesCredentials? = withContext(Dispatchers.IO) {
        val p = prefs()
        val base = p[SERVER] ?: return@withContext null
        val user = p[USER] ?: return@withContext null
        val password = p[SECRET]?.let(box::decrypt) ?: return@withContext null
        NotesCredentials(base, user, password)
    }

    /** @throws java.security.GeneralSecurityException wenn der Keystore nicht verfügbar ist */
    suspend fun saveCredentials(credentials: NotesCredentials) {
        val secret = withContext(Dispatchers.IO) { box.encrypt(credentials.password) }
        store.edit {
            it[SERVER] = credentials.baseUrl
            it[USER] = credentials.user
            it[SECRET] = secret
        }
    }

    /** Entfernt Zugangsdaten, Schlüssel und die Zuordnung zu Server-Notizen. Die lokalen Dateien bleiben. */
    suspend fun clearAccount() {
        store.edit {
            it.remove(SERVER)
            it.remove(USER)
            it.remove(SECRET)
            it.remove(LAST_SYNC)
        }
        withContext(Dispatchers.IO) { box.deleteKey() }
        val state = loadState()
        saveState(state.copy(index = emptyList()))
    }

    suspend fun loadState(): NotesState = withContext(Dispatchers.IO) {
        try {
            if (stateFile.isFile) NotesStateCodec.decode(stateFile.readText(Charsets.UTF_8)) else NotesState()
        } catch (ignored: IOException) {
            NotesState()
        }
    }

    suspend fun saveState(state: NotesState) {
        withContext(Dispatchers.IO) {
            try {
                directory.mkdirs()
                val temporary = File(directory, "state.json.tmp")
                temporary.writeText(NotesStateCodec.encode(state), Charsets.UTF_8)
                if (!temporary.renameTo(stateFile)) temporary.delete()
            } catch (ignored: IOException) {
                // Ohne Zustand gehen nur Favoriten und Zuordnung verloren; die Notizen selbst liegen in Dateien.
            }
        }
    }

    private companion object {
        const val KEY_ALIAS = "miriquidilauncher_notes"
        val SERVER = stringPreferencesKey("server")
        val USER = stringPreferencesKey("user")
        val SECRET = stringPreferencesKey("secret")
        val SORT = stringPreferencesKey("sort")
        val ASCENDING = booleanPreferencesKey("ascending")
        val LAST_SYNC = longPreferencesKey("last_sync")
    }
}
