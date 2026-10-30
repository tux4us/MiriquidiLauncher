// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.weather

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.tux4us.miriquidilauncher.feed.ServerUrl
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class WeatherError { NETWORK, LOCATION_UNAVAILABLE, LOCATION_PERMISSION, DATA }

/** Ergebnis der Eingabe des eigenen Wetterservers. */
enum class ServerCheck { OK, FORECAST_NOT_HTTPS, FORECAST_INVALID, AIR_NOT_HTTPS, AIR_INVALID, KEY_INVALID, STORE_FAILED }

data class WeatherUiState(
    val place: GeoPlace? = null,
    val report: WeatherReport? = null,
    val loading: Boolean = false,
    val error: WeatherError? = null,
    val favorites: List<GeoPlace> = emptyList(),
    val searchQuery: String = "",
    val searchResults: List<GeoPlace> = emptyList(),
    val searching: Boolean = false,
    val searchFailed: Boolean = false,
    val shadowHeights: ShadowHeights = ShadowHeights(ShadowHeights.DEFAULT_PERSON_M, ShadowHeights.DEFAULT_OBJECT_M),
    /** `true`, wenn ein eigener Wetterserver eingetragen ist. */
    val customServer: Boolean = false,
) {
    /** `true`, wenn der angezeigte Bericht nicht aus dem aktuellen Abruf stammt (Fehler beim Aktualisieren). */
    val showsStaleData: Boolean get() = report != null && error != null
    val isFavorite: Boolean get() = place != null && favorites.any { it.key == place.key }
}

class WeatherViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = WeatherRepository()
    private val store = WeatherStore(application)
    private val locationHelper = LocationHelper(application)

    private val _state = MutableStateFlow(WeatherUiState())
    val state: StateFlow<WeatherUiState> = _state.asStateFlow()

    private var fetchJob: Job? = null
    private var searchJob: Job? = null
    private var initialized = false

    private val languageTag: String get() = Locale.getDefault().toLanguageTag()

    init {
        viewModelScope.launch {
            store.favorites.collect { favorites -> _state.update { it.copy(favorites = favorites) } }
        }
        viewModelScope.launch {
            _state.update { it.copy(customServer = runCatching { store.loadServer() }.getOrNull() != null) }
        }
        viewModelScope.launch {
            store.shadowHeights.collect { heights -> _state.update { it.copy(shadowHeights = heights) } }
        }
    }

    /** Nach dem Einspielen eines Backups: gewählten Ort und Cache neu aus dem Speicher laden. */
    fun reloadFromStore() {
        initialized = false
        fetchJob?.cancel()
        _state.update { it.copy(place = null, report = null, error = null, loading = false) }
        viewModelScope.launch {
            store.selectedPlace.first()?.let { place -> _state.update { it.copy(place = place) } }
        }
    }

    /** Beim Öffnen des Wetterscreens: erstmalig Cache laden, danach nur bei veralteten Daten neu abrufen. */
    fun onScreenShown() {
        if (!initialized) {
            initialized = true
            viewModelScope.launch {
                val selected = store.selectedPlace.first()
                val cache = store.loadCache()
                if (selected != null) {
                    _state.update { it.copy(place = selected) }
                    if (cache != null && cache.place.key == selected.key) {
                        runCatching { repository.parse(cache.raw, selected) }.getOrNull()
                            ?.let { report -> _state.update { it.copy(report = report) } }
                    }
                    refreshIfStale()
                }
            }
        } else {
            refreshIfStale()
        }
    }

    fun refreshIfStale() {
        val s = _state.value
        val report = s.report
        val stale = report == null || System.currentTimeMillis() - report.fetchedAtMillis > STALE_AFTER_MS
        if (s.place != null && stale && !s.loading) refresh()
    }

    fun refresh() {
        val place = _state.value.place ?: return
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            try {
                val raw = repository.fetch(place, loadServerOrNull())
                val report = repository.parse(raw, place)
                store.saveCache(place, raw)
                _state.update { it.copy(report = report, loading = false, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OpenMeteoParser.ParseException) {
                _state.update { it.copy(loading = false, error = WeatherError.DATA) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = WeatherError.NETWORK) }
            }
        }
    }

    private suspend fun loadServerOrNull(): WeatherServer? = runCatching { store.loadServer() }.getOrNull()

    /** Liefert den eingetragenen eigenen Server (oder `null`) an [onLoaded], z. B. zum Füllen des Dialogs. */
    fun loadServer(onLoaded: (WeatherServer?) -> Unit) {
        viewModelScope.launch { onLoaded(loadServerOrNull()) }
    }

    /**
     * Prüft und speichert den eigenen Wetterserver. Alle Felder leer = zurück zu den öffentlichen Open-Meteo-Adressen.
     * [onResult] wird nach dem Speichern aufgerufen.
     */
    fun saveServer(forecastInput: String, airInput: String, keyInput: String, onResult: (ServerCheck) -> Unit) {
        val key = keyInput.trim()
        val forecast = WeatherEndpoints.parseBase(forecastInput)
        val air = WeatherEndpoints.parseBase(airInput)
        val failure = when {
            forecast == ServerUrl.NotHttps -> ServerCheck.FORECAST_NOT_HTTPS
            forecast == ServerUrl.Invalid -> ServerCheck.FORECAST_INVALID
            air == ServerUrl.NotHttps -> ServerCheck.AIR_NOT_HTTPS
            air == ServerUrl.Invalid -> ServerCheck.AIR_INVALID
            !WeatherEndpoints.isValidKey(key) -> ServerCheck.KEY_INVALID
            // Ohne Adresse gibt es keinen Server, an den ein Schlüssel oder eine Luftqualitäts-Adresse gehen könnte.
            forecast == ServerUrl.Empty && (air != ServerUrl.Empty || key.isNotEmpty()) -> ServerCheck.FORECAST_INVALID
            else -> null
        }
        if (failure != null) {
            onResult(failure)
            return
        }
        val server = (forecast as? ServerUrl.Ok)?.let {
            WeatherServer(it.baseUrl, (air as? ServerUrl.Ok)?.baseUrl, key.ifEmpty { null })
        }
        viewModelScope.launch {
            val saved = runCatching { store.saveServer(server) }.isSuccess
            if (saved) {
                fetchJob?.cancel()
                _state.update { it.copy(customServer = server != null, report = null, error = null, loading = false) }
                if (_state.value.place != null) refresh()
            }
            onResult(if (saved) ServerCheck.OK else ServerCheck.STORE_FAILED)
        }
    }

    fun selectPlace(place: GeoPlace) {
        clearSearch()
        _state.update { it.copy(place = place, report = null, error = null) }
        viewModelScope.launch { store.select(place) }
        refresh()
    }

    /** Muss aufgerufen werden, nachdem die Standortberechtigung erteilt wurde (oder bereits vorlag). */
    fun useCurrentLocation() {
        if (!locationHelper.hasPermission()) {
            _state.update { it.copy(error = WeatherError.LOCATION_PERMISSION) }
            return
        }
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val location = locationHelper.currentLocation()
            if (location == null) {
                _state.update { it.copy(loading = false, error = WeatherError.LOCATION_UNAVAILABLE) }
                return@launch
            }
            val name = repository.reverseGeocode(location.latitude, location.longitude, languageTag)
                ?: String.format(Locale.ROOT, "%.2f, %.2f", location.latitude, location.longitude)
            val place = GeoPlace(name, location.latitude, location.longitude)
            clearSearch()
            _state.update { it.copy(place = place, report = null) }
            store.select(place)
            // Der Abruf läuft in diesem Job weiter, damit ein erneutes Tippen den vorigen abbricht.
            try {
                val raw = repository.fetch(place, loadServerOrNull())
                val report = repository.parse(raw, place)
                store.saveCache(place, raw)
                _state.update { it.copy(report = report, loading = false, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OpenMeteoParser.ParseException) {
                _state.update { it.copy(loading = false, error = WeatherError.DATA) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = WeatherError.NETWORK) }
            }
        }
    }

    fun onLocationPermissionDenied() {
        _state.update { it.copy(error = WeatherError.LOCATION_PERMISSION) }
    }

    fun setSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query, searchFailed = false) }
        searchJob?.cancel()
        if (query.trim().length < MIN_QUERY_LENGTH) {
            _state.update { it.copy(searchResults = emptyList(), searching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            _state.update { it.copy(searching = true) }
            try {
                val results = repository.search(query, languageTag)
                _state.update { it.copy(searchResults = results, searching = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(searchResults = emptyList(), searching = false, searchFailed = true) }
            }
        }
    }

    /** Speichert die Höhen, sofern sie in den erlaubten Bereichen liegen; gibt `false` bei ungültigen Werten zurück. */
    fun setShadowHeights(personM: Double, objectM: Double): Boolean {
        if (personM !in ShadowHeights.PERSON_RANGE || objectM !in ShadowHeights.OBJECT_RANGE) return false
        viewModelScope.launch { store.saveShadowHeights(ShadowHeights(personM, objectM)) }
        return true
    }

    fun clearSearch() {
        searchJob?.cancel()
        _state.update { it.copy(searchQuery = "", searchResults = emptyList(), searching = false, searchFailed = false) }
    }

    fun toggleFavorite(place: GeoPlace) {
        viewModelScope.launch {
            if (_state.value.favorites.any { it.key == place.key }) store.removeFavorite(place) else store.addFavorite(place)
        }
    }

    private companion object {
        const val STALE_AFTER_MS = 30 * 60 * 1000L
        const val SEARCH_DEBOUNCE_MS = 400L
        const val MIN_QUERY_LENGTH = 2
    }
}
