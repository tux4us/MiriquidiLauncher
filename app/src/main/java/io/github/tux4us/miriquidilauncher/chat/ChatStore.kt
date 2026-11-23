// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.chat

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.tux4us.miriquidilauncher.feed.SecretBox
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private val Context.chatDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "chat",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Einstellungen der Chat-Benachrichtigungen. */
data class ChatNotifySettings(val enabled: Boolean, val showText: Boolean)

/**
 * Lokaler Zustand des Messenger-Scopes: Zugangsdaten (Passwort über den Keystore verschlüsselt) und der Zwischenspeicher
 * (Raumliste und je Gespräch die letzten Nachrichten). Auch der Zwischenspeicher ist mit AES-256-GCM und einem Schlüssel im
 * Android-Keystore verschlüsselt; ohne den Schlüssel sind die Dateien unbrauchbar. Alles liegt im privaten App-Speicher und
 * gehört nicht zum Launcher-Backup.
 */
class ChatStore(context: Context) {
    private val appContext = context.applicationContext
    private val store = appContext.chatDataStore
    private val box = SecretBox(KEY_ALIAS)
    private val directory = File(appContext.filesDir, "chat")

    private suspend fun prefs(): Preferences = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.first()

    suspend fun loadCredentials(): ChatCredentials? = withContext(Dispatchers.IO) {
        val p = prefs()
        val base = p[SERVER] ?: return@withContext null
        val user = p[USER] ?: return@withContext null
        val password = p[SECRET]?.let(box::decrypt) ?: return@withContext null
        ChatCredentials(base, user, password, p[UID] ?: user)
    }

    /** @throws GeneralSecurityException wenn der Keystore nicht verfügbar ist */
    suspend fun saveCredentials(credentials: ChatCredentials) {
        val secret = withContext(Dispatchers.IO) { box.encrypt(credentials.password) }
        store.edit {
            it[SERVER] = credentials.baseUrl
            it[USER] = credentials.user
            it[UID] = credentials.userId
            it[SECRET] = secret
        }
    }

    /** Einstellungen der Benachrichtigungen; standardmäßig aus, mit Absender und Text. */
    suspend fun loadNotify(): ChatNotifySettings {
        val p = prefs()
        return ChatNotifySettings(p[NOTIFY_ENABLED] ?: false, p[NOTIFY_TEXT] ?: true)
    }

    suspend fun saveNotifyShowText(showText: Boolean) {
        store.edit { it[NOTIFY_TEXT] = showText }
    }

    /** Schaltet ein und legt die Grundlinie fest, oder schaltet aus und verwirft sie. */
    suspend fun saveNotifyEnabled(enabled: Boolean, baseline: Map<String, Long> = emptyMap()) {
        store.edit {
            it[NOTIFY_ENABLED] = enabled
            if (enabled) it[NOTIFIED] = ChatNotifyLogic.encodeBaseline(baseline) else it.remove(NOTIFIED)
        }
    }

    /** `null`, wenn noch keine Grundlinie gespeichert ist. */
    suspend fun loadBaseline(): Map<String, Long>? = prefs()[NOTIFIED]?.let(ChatNotifyLogic::decodeBaseline)

    suspend fun saveBaseline(baseline: Map<String, Long>) {
        store.edit { it[NOTIFIED] = ChatNotifyLogic.encodeBaseline(baseline) }
    }

    /** Entfernt Zugangsdaten, Schlüssel und den gesamten Zwischenspeicher. */
    suspend fun clear() {
        store.edit {
            it.remove(NOTIFY_ENABLED)
            it.remove(NOTIFY_TEXT)
            it.remove(NOTIFIED)
            it.remove(SERVER)
            it.remove(USER)
            it.remove(UID)
            it.remove(SECRET)
        }
        withContext(Dispatchers.IO) {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
            box.deleteKey()
        }
    }

    suspend fun loadRooms(): List<ChatRoom> = readText(File(directory, ROOMS_FILE))?.let(ChatCacheCodec::decodeRooms).orEmpty()

    suspend fun saveRooms(rooms: List<ChatRoom>) = writeText(File(directory, ROOMS_FILE), ChatCacheCodec.encodeRooms(rooms))

    suspend fun loadMessages(token: String): List<ChatMessage> {
        if (!ChatApi.isSafeToken(token)) return emptyList()
        return readText(File(directory, messagesFile(token)))?.let(ChatCacheCodec::decodeMessages).orEmpty()
    }

    suspend fun saveMessages(token: String, messages: List<ChatMessage>) {
        if (!ChatApi.isSafeToken(token)) return
        writeText(File(directory, messagesFile(token)), ChatCacheCodec.encodeMessages(messages))
    }

    /** Entfernt Nachrichtendateien von Gesprächen, die nicht mehr in [tokens] stehen (z. B. verlassene Gespräche). */
    suspend fun prune(tokens: Set<String>) {
        withContext(Dispatchers.IO) {
            val keep = tokens.map(::messagesFile).toSet() + ROOMS_FILE
            directory.listFiles()?.filter { it.isFile && it.name !in keep }?.forEach { it.delete() }
        }
    }

    private suspend fun readText(file: File): String? = withContext(Dispatchers.IO) {
        try {
            if (file.isFile) box.decrypt(file.readText(Charsets.UTF_8)) else null
        } catch (ignored: IOException) {
            null
        }
    }

    private suspend fun writeText(file: File, plain: String) {
        withContext(Dispatchers.IO) {
            try {
                directory.mkdirs()
                val temporary = File(directory, file.name + ".tmp")
                temporary.writeText(box.encrypt(plain), Charsets.UTF_8)
                if (!temporary.renameTo(file)) temporary.delete()
            } catch (ignored: IOException) {
                // Ohne Zwischenspeicher fehlt nur die Offline-Ansicht.
            } catch (ignored: GeneralSecurityException) {
                // Keystore nicht erreichbar: dasselbe.
            } catch (ignored: java.security.ProviderException) {
                // Der Keystore meldet manche Störungen als ProviderException: dasselbe.
            }
        }
    }

    private fun messagesFile(token: String) = "room_$token.enc"

    private companion object {
        const val KEY_ALIAS = "miriquidilauncher_chat"
        const val ROOMS_FILE = "rooms.enc"
        val SERVER = stringPreferencesKey("server")
        val USER = stringPreferencesKey("user")
        val UID = stringPreferencesKey("uid")
        val SECRET = stringPreferencesKey("secret")
        val NOTIFY_ENABLED = booleanPreferencesKey("notify_enabled")
        val NOTIFY_TEXT = booleanPreferencesKey("notify_text")
        val NOTIFIED = stringPreferencesKey("notified")
    }
}
