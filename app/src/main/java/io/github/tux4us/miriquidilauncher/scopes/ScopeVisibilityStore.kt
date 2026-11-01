// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.scopes

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

private val Context.scopesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "scopes",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Speichert, welche Scopes ausgeblendet sind und in welcher Reihenfolge sie stehen. Beides ist Teil des Launcher-Backups. */
class ScopeVisibilityStore(context: Context) {
    private val store = context.applicationContext.scopesDataStore

    private suspend fun prefs(): Preferences = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.first()

    /**
     * Wirksam ausgeblendete Scopes: der gespeicherte Wert (ohne ihn [defaults]) plus jede Netzwerk-Scope ([network]) ohne
     * Zustimmung. Damit kann auch ein eingespieltes Backup keine Netzwerk-Scope ohne Zustimmung einblenden.
     */
    suspend fun loadHidden(known: Set<String>, defaults: Set<String>, network: Set<String>): Set<String> {
        val saved = ScopeVisibility.initialHidden(prefs()[HIDDEN], defaults, known)
        return ScopeVisibility.effectiveHidden(saved, network, loadConsented(network, known))
    }

    /** Scopes mit Netzwerkzugriff, denen zugestimmt wurde; ein abgeleiteter Wert wird dabei dauerhaft gespeichert. */
    suspend fun loadConsented(network: Set<String>, known: Set<String>): Set<String> {
        val p = prefs()
        val consented = ScopeVisibility.consentOf(p[CONSENT], p[HIDDEN], network, known)
        if (p[CONSENT] == null) store.edit { it[CONSENT] = ScopeVisibility.encode(consented) }
        return consented
    }

    /** Hält die Zustimmung des Nutzers zu [id] fest. */
    suspend fun grantConsent(id: String, network: Set<String>, known: Set<String>) {
        val consented = loadConsented(network, known) + id
        store.edit { it[CONSENT] = ScopeVisibility.encode(consented.filter { c -> c in network }.toSet()) }
    }

    suspend fun saveHidden(hidden: Set<String>) {
        store.edit { it[HIDDEN] = ScopeVisibility.encode(hidden) }
    }

    /** Gespeicherte Reihenfolge aller Scopes; [known] ist die Standardreihenfolge. */
    suspend fun loadOrder(known: List<String>): List<String> = ScopeVisibility.decodeOrder(prefs()[ORDER], known)

    suspend fun saveOrder(order: List<String>) {
        store.edit { it[ORDER] = ScopeVisibility.encodeOrder(order) }
    }

    /** Ersetzt Sichtbarkeit und Reihenfolge in einem Schritt (Backup-Import). */
    suspend fun replaceAll(hidden: Set<String>, order: List<String>) {
        store.edit {
            it[HIDDEN] = ScopeVisibility.encode(hidden)
            it[ORDER] = ScopeVisibility.encodeOrder(order)
        }
    }

    private companion object {
        val HIDDEN = stringPreferencesKey("hidden")
        val ORDER = stringPreferencesKey("order")
        val CONSENT = stringPreferencesKey("networkConsent")
    }
}
