// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.recents

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.tux4us.miriquidilauncher.system.AppStorageRepository
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.recentsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "recents",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Liest die Nutzungsereignisse des Systems (`UsageStatsManager`, Spezialzugriff „Nutzungszugriff“). Gelesen werden nur
 * Wechsel einer App in den Vordergrund oder Hintergrund; sie werden im Arbeitsspeicher zur Liste verdichtet und nicht
 * gespeichert.
 */
class RecentsRepository(private val context: Context) {

    fun hasUsageAccess(): Boolean = AppStorageRepository(context).hasUsageAccess()

    /** Vordergrund- und Hintergrundwechsel der letzten [RecentsLogic.WINDOW_MILLIS] bis [now]. Blockierend. */
    @Suppress("DEPRECATION")
    fun loadSamples(now: Long): List<RecentsLogic.UsageSample> {
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return emptyList()
        val events = try {
            manager.queryEvents(now - RecentsLogic.WINDOW_MILLIS, now)
        } catch (ignored: SecurityException) {
            null
        } ?: return emptyList()
        val event = UsageEvents.Event()
        val result = ArrayList<RecentsLogic.UsageSample>()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            // MOVE_TO_FOREGROUND/-BACKGROUND haben seit Android 10 die Nachfolger ACTIVITY_RESUMED/-PAUSED mit
            // denselben Werten und gelten auf allen unterstützten Versionen.
            val type = event.eventType
            if (type != UsageEvents.Event.MOVE_TO_FOREGROUND && type != UsageEvents.Event.MOVE_TO_BACKGROUND) continue
            val pkg = event.packageName ?: continue
            result += RecentsLogic.UsageSample(pkg, event.timeStamp)
        }
        return result
    }
}

/** Gespeicherte Entfernungsmarken der Übersicht (Paket → Zeitpunkt), siehe [RecentsLogic.recentApps]. */
class RecentsStore(context: Context) {
    private val store = context.applicationContext.recentsDataStore

    val dismissed: Flow<Map<String, Long>> = store.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { RecentsCodec.decode(it[DISMISSED]) }

    suspend fun setDismissed(marks: Map<String, Long>) {
        store.edit { prefs ->
            if (marks.isEmpty()) prefs.remove(DISMISSED) else prefs[DISMISSED] = RecentsCodec.encode(marks)
        }
    }

    private companion object {
        val DISMISSED = stringPreferencesKey("dismissed")
    }
}
