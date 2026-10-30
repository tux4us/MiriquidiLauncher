// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.weather

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.tux4us.miriquidilauncher.feed.SecretBox
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.weatherDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "weather",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Gespeicherter letzter Abruf zusammen mit dem Ort, zu dem er gehört. */
data class CachedReport(val place: GeoPlace, val raw: RawReport)

/** Favoriten-Orte, gewählter Ort und der zuletzt abgerufene Bericht (für Offline-Anzeige). */
class WeatherStore(context: Context) {
    private val store = context.applicationContext.weatherDataStore

    private val box = SecretBox(KEY_ALIAS)

    private val safeData: Flow<Preferences> = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }

    val favorites: Flow<List<GeoPlace>> = safeData.map { PlaceCodec.decode(it[FAVORITES]) }

    val selectedPlace: Flow<GeoPlace?> = safeData.map { PlaceCodec.decodeOne(it[SELECTED]) }

    /** Höhen für die Schattenberechnung in Metern: Person und frei wählbarer Gegenstand. */
    val shadowHeights: Flow<ShadowHeights> = safeData.map {
        ShadowHeights(
            personM = it[HEIGHT_PERSON] ?: ShadowHeights.DEFAULT_PERSON_M,
            objectM = it[HEIGHT_OBJECT] ?: ShadowHeights.DEFAULT_OBJECT_M,
        )
    }

    suspend fun saveShadowHeights(heights: ShadowHeights) {
        store.edit {
            it[HEIGHT_PERSON] = heights.personM
            it[HEIGHT_OBJECT] = heights.objectM
        }
    }

    suspend fun loadCache(): CachedReport? {
        val prefs = safeData.first()
        val place = PlaceCodec.decodeOne(prefs[CACHE_PLACE]) ?: return null
        val forecast = prefs[CACHE_FORECAST] ?: return null
        val time = prefs[CACHE_TIME] ?: return null
        return CachedReport(place, RawReport(forecast, prefs[CACHE_AIR], time))
    }

    suspend fun saveCache(place: GeoPlace, raw: RawReport) {
        store.edit { prefs ->
            prefs[CACHE_PLACE] = PlaceCodec.encodeOne(place)
            prefs[CACHE_FORECAST] = raw.forecastJson
            if (raw.airJson != null) prefs[CACHE_AIR] = raw.airJson else prefs.remove(CACHE_AIR)
            prefs[CACHE_TIME] = raw.fetchedAtMillis
        }
    }

    /**
     * Eigener Wetterserver oder `null` (öffentliche Adressen). Der Schlüssel liegt verschlüsselt im Android-Keystore
     * (wie das Nextcloud-Passwort) und ist nicht Teil des Backups.
     */
    suspend fun loadServer(): WeatherServer? = withContext(Dispatchers.IO) {
        val prefs = safeData.first()
        val forecast = prefs[SERVER_FORECAST]?.takeIf { it.isNotBlank() } ?: return@withContext null
        val key = prefs[SERVER_SECRET]?.let(box::decrypt)
        WeatherServer(forecast, prefs[SERVER_AIR]?.takeIf { it.isNotBlank() }, key)
    }

    /**
     * Speichert den eigenen Server (`null` entfernt ihn) und verwirft den zwischengespeicherten Bericht, weil er von
     * einer anderen Quelle stammen kann.
     * @throws java.security.GeneralSecurityException wenn der Keystore nicht verfügbar ist
     */
    suspend fun saveServer(server: WeatherServer?) {
        val secret = server?.apiKey?.takeIf { it.isNotEmpty() }?.let { key -> withContext(Dispatchers.IO) { box.encrypt(key) } }
        store.edit { prefs ->
            if (server == null) {
                prefs.remove(SERVER_FORECAST); prefs.remove(SERVER_AIR); prefs.remove(SERVER_SECRET)
            } else {
                prefs[SERVER_FORECAST] = server.forecastBase
                if (server.airBase != null) prefs[SERVER_AIR] = server.airBase else prefs.remove(SERVER_AIR)
                if (secret != null) prefs[SERVER_SECRET] = secret else prefs.remove(SERVER_SECRET)
            }
            prefs.remove(CACHE_PLACE); prefs.remove(CACHE_FORECAST); prefs.remove(CACHE_AIR); prefs.remove(CACHE_TIME)
        }
    }

    suspend fun currentFavorites(): List<GeoPlace> = favorites.first()

    suspend fun currentSelected(): GeoPlace? = selectedPlace.first()

    suspend fun currentShadowHeights(): ShadowHeights = shadowHeights.first()

    /** Ersetzt Favoriten und (falls angegeben) gewählten Ort; löscht dann den zwischengespeicherten Bericht. */
    suspend fun replaceAll(favorites: List<GeoPlace>, selected: GeoPlace?) {
        store.edit { prefs ->
            prefs[FAVORITES] = PlaceCodec.encode(favorites.fold(emptyList<GeoPlace>()) { acc, p -> PlaceCodec.add(acc, p) })
            if (selected != null) {
                prefs[SELECTED] = PlaceCodec.encodeOne(selected)
                prefs.remove(CACHE_PLACE); prefs.remove(CACHE_FORECAST); prefs.remove(CACHE_AIR); prefs.remove(CACHE_TIME)
            }
        }
    }

    suspend fun select(place: GeoPlace) {
        store.edit { it[SELECTED] = PlaceCodec.encodeOne(place) }
    }

    suspend fun addFavorite(place: GeoPlace) {
        store.edit { it[FAVORITES] = PlaceCodec.encode(PlaceCodec.add(PlaceCodec.decode(it[FAVORITES]), place)) }
    }

    suspend fun removeFavorite(place: GeoPlace) {
        store.edit { it[FAVORITES] = PlaceCodec.encode(PlaceCodec.remove(PlaceCodec.decode(it[FAVORITES]), place)) }
    }

    private companion object {
        val FAVORITES = stringPreferencesKey("favorites")
        val SELECTED = stringPreferencesKey("selected")
        val CACHE_PLACE = stringPreferencesKey("cache_place")
        val CACHE_FORECAST = stringPreferencesKey("cache_forecast")
        val CACHE_AIR = stringPreferencesKey("cache_air")
        val CACHE_TIME = longPreferencesKey("cache_time")
        val HEIGHT_PERSON = doublePreferencesKey("height_person")
        val HEIGHT_OBJECT = doublePreferencesKey("height_object")
        val SERVER_FORECAST = stringPreferencesKey("server_forecast")
        val SERVER_AIR = stringPreferencesKey("server_air")
        val SERVER_SECRET = stringPreferencesKey("server_secret")
        const val KEY_ALIAS = "miriquidilauncher_weather"
    }
}
