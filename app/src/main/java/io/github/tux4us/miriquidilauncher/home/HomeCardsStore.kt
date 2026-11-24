// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.home

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.homeCardsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "home_cards",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Wahlmöglichkeiten für die Startseite; alles standardmäßig aus, außer dem Nachrichtentext der Nachrichten-Card.
 *
 * @property tomorrow Termin-Card zeigt zusätzlich zu heute auch die Termine von morgen
 * @property messages Card mit ungelesenen Nachrichten des Messengers
 * @property messagesText Card zeigt Absender und Textvorschau (sonst nur Gespräch und Anzahl)
 * @property weather Card mit dem aktuellen Wetter und animiertem Hintergrund
 * @property menuOpacity Deckkraft der Fläche des Scope-Menüs (0,1 bis 1,0)
 */
data class HomeCardsSettings(
    val tomorrow: Boolean = false,
    val messages: Boolean = false,
    val messagesText: Boolean = true,
    val weather: Boolean = false,
    val menuOpacity: Float = DEFAULT_MENU_OPACITY,
) {
    companion object {
        /** Deckkraft des Scope-Menüs, die dem bisherigen Aussehen entspricht. */
        const val DEFAULT_MENU_OPACITY = 0.55f
        const val MIN_MENU_OPACITY = 0.10f
        const val MAX_MENU_OPACITY = 1.0f
    }
}

class HomeCardsStore(context: Context) {
    private val store = context.applicationContext.homeCardsDataStore

    val settings: Flow<HomeCardsSettings> = store.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { p ->
            HomeCardsSettings(
                tomorrow = p[TOMORROW] ?: false,
                messages = p[MESSAGES] ?: false,
                messagesText = p[MESSAGES_TEXT] ?: true,
                weather = p[WEATHER] ?: false,
                menuOpacity = (p[MENU_OPACITY] ?: HomeCardsSettings.DEFAULT_MENU_OPACITY)
                    .coerceIn(HomeCardsSettings.MIN_MENU_OPACITY, HomeCardsSettings.MAX_MENU_OPACITY),
            )
        }

    suspend fun load(): HomeCardsSettings = settings.first()

    suspend fun save(value: HomeCardsSettings) {
        store.edit {
            it[TOMORROW] = value.tomorrow
            it[MESSAGES] = value.messages
            it[MESSAGES_TEXT] = value.messagesText
            it[WEATHER] = value.weather
            it[MENU_OPACITY] = value.menuOpacity
        }
    }

    private companion object {
        val TOMORROW = booleanPreferencesKey("tomorrow")
        val MESSAGES = booleanPreferencesKey("messages")
        val MESSAGES_TEXT = booleanPreferencesKey("messages_text")
        val WEATHER = booleanPreferencesKey("weather")
        val MENU_OPACITY = floatPreferencesKey("menu_opacity")
    }
}
