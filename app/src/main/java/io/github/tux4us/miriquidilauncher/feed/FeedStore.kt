// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.feed

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.feedDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "feed",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

data class FeedCredentials(val baseUrl: String, val user: String, val password: String)

data class FeedSettings(val unreadOnly: Boolean = true, val loadImages: Boolean = true)

/**
 * Lokale Daten des FeedReaders: Zugangsdaten (das Passwort verschlüsselt über den Keystore), Einstellungen und
 * der Zwischenspeicher der Artikel für die Offline-Anzeige. Alles liegt im privaten App-Speicher; die App
 * ist vom Android-Backup ausgenommen. Im Backup des Launchers stehen die Einstellungen und die lokalen Abos, die
 * Zugangsdaten nicht.
 */
class FeedStore(context: Context) {
    private val appContext = context.applicationContext
    private val store = appContext.feedDataStore
    private val box = SecretBox(KEY_ALIAS)
    private val directory = File(appContext.filesDir, "feed")
    private val cacheFile = File(directory, "cache.json")
    private val localFile = File(directory, "local.json")

    private val safeData: Flow<Preferences> = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }

    val settings: Flow<FeedSettings> = safeData.map {
        FeedSettings(unreadOnly = it[UNREAD_ONLY] ?: true, loadImages = it[LOAD_IMAGES] ?: true)
    }

    suspend fun setUnreadOnly(value: Boolean) {
        store.edit { it[UNREAD_ONLY] = value }
    }

    suspend fun setLoadImages(value: Boolean) {
        store.edit { it[LOAD_IMAGES] = value }
    }

    /** Gespeicherte Zugangsdaten oder `null`, wenn keine vorhanden oder nicht mehr entschlüsselbar sind. */
    suspend fun loadCredentials(): FeedCredentials? = withContext(Dispatchers.IO) {
        val prefs = safeData.first()
        val base = prefs[SERVER] ?: return@withContext null
        val user = prefs[USER] ?: return@withContext null
        val password = prefs[SECRET]?.let(box::decrypt) ?: return@withContext null
        FeedCredentials(base, user, password)
    }

    /** @throws java.security.GeneralSecurityException wenn der Keystore nicht verfügbar ist */
    suspend fun saveCredentials(credentials: FeedCredentials) {
        val secret = withContext(Dispatchers.IO) { box.encrypt(credentials.password) }
        store.edit {
            it[SERVER] = credentials.baseUrl
            it[USER] = credentials.user
            it[SECRET] = secret
        }
    }

    /** Entfernt Zugangsdaten, Schlüssel und Zwischenspeicher. Die Einstellungen bleiben erhalten. */
    suspend fun clear() {
        store.edit {
            it.remove(SERVER)
            it.remove(USER)
            it.remove(SECRET)
        }
        withContext(Dispatchers.IO) {
            box.deleteKey()
            cacheFile.delete()
        }
    }

    suspend fun loadCache(): FeedSnapshot? = read(cacheFile)

    suspend fun saveCache(snapshot: FeedSnapshot) = write(cacheFile, snapshot)

    /**
     * Lokale Abos (ohne Nextcloud): Feed-Liste (Adresse in [FeedInfo.link]), Artikel und Gelesen-Markierungen
     * (in [FeedSnapshot.pendingRead]). Sie bleiben bei An- und Abmeldung bei Nextcloud erhalten.
     */
    suspend fun loadLocal(): FeedSnapshot? = read(localFile)

    suspend fun saveLocal(snapshot: FeedSnapshot) = write(localFile, snapshot)

    suspend fun deleteLocal() {
        withContext(Dispatchers.IO) { localFile.delete() }
    }

    private suspend fun read(file: File): FeedSnapshot? = withContext(Dispatchers.IO) {
        try {
            if (file.isFile) FeedCacheCodec.decode(file.readText(Charsets.UTF_8)) else null
        } catch (ignored: IOException) {
            null
        }
    }

    private suspend fun write(file: File, snapshot: FeedSnapshot) {
        withContext(Dispatchers.IO) {
            try {
                directory.mkdirs()
                val temporary = File(directory, file.name + ".tmp")
                temporary.writeText(FeedCacheCodec.encode(snapshot), Charsets.UTF_8)
                if (!temporary.renameTo(file)) temporary.delete()
            } catch (ignored: IOException) {
                // Nur Zwischenspeicher: ohne ihn wird beim nächsten Öffnen neu geladen.
            }
        }
    }

    private companion object {
        const val KEY_ALIAS = "miriquidilauncher_feed"
        val SERVER = stringPreferencesKey("server")
        val USER = stringPreferencesKey("user")
        val SECRET = stringPreferencesKey("secret")
        val UNREAD_ONLY = booleanPreferencesKey("unread_only")
        val LOAD_IMAGES = booleanPreferencesKey("load_images")
    }
}
