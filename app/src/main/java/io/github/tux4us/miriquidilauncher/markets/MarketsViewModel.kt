// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.markets

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Zeitreihe für das Diagramm einer Card. [id] und [range] kennzeichnen Wert und Zeitraum, für die sie gilt. */
data class SeriesLoad(
    val id: String = "",
    val range: String = "",
    val series: Series? = null,
    val loading: Boolean = false,
    val error: MarketError? = null,
)

data class MarketsUiState(
    val snapshot: MarketSnapshot = MarketSnapshot(),
    /** `true`, sobald der gespeicherte Stand gelesen ist. */
    val cacheLoaded: Boolean = false,
    val loading: Boolean = false,
    val bitcoinError: MarketError? = null,
    val quotesError: MarketError? = null,
    val cryptoError: MarketError? = null,
    val securitiesError: MarketError? = null,
    val fxError: MarketError? = null,
    /** Eigene Basis-Adresse der Bitcoin-Daten oder `null` (öffentliche Standard-Instanz). */
    val customBaseUrl: String? = null,
    val range: ChartRange = ChartRange.Day,
    val chartCurrency: ChartCurrency = ChartCurrency.Eur,
    /** Bis zu diesem Zeitpunkt (Unix-Millisekunden) wird nach HTTP 429 der Bitcoin-Quelle nichts abgerufen. */
    val blockedUntilMillis: Long = 0L,
    /** Card „Eigene Werte“ aufgeklappt. */
    val holdingsVisible: Boolean = false,
    val yahooEnabled: Boolean = true,
    val stooqEnabled: Boolean = false,
    val stooqKey: String = "",
    val choice: ChartChoice = ChartChoice(),
    val indexSeries: SeriesLoad = SeriesLoad(),
    val commoditySeries: SeriesLoad = SeriesLoad(),
    val cryptoSeries: SeriesLoad = SeriesLoad(),
    /** Eigene Werte (Aktien, Rohstoffe, Krypto). */
    val positions: List<Position> = emptyList(),
    val fxFrom: String = "EUR",
    val fxTo: String = "USD",
    val search: SecuritySearch = SecuritySearch.Idle,
) {
    val baseUrl: String get() = customBaseUrl ?: MarketUrl.DEFAULT

    /** Stooq wird nur mit eingetragenem Schlüssel abgefragt. */
    val stooqActive: Boolean get() = stooqEnabled && stooqKey.isNotBlank()
}

/** Stand der Suche nach einem Wertpapier (WKN/ISIN). */
sealed interface SecuritySearch {
    data object Idle : SecuritySearch
    data object Loading : SecuritySearch
    data object InvalidId : SecuritySearch
    data object NotFound : SecuritySearch
    data class Error(val error: MarketError) : SecuritySearch

    /** Treffer zur Eingabe [ident]; der Nutzer wählt einen aus. */
    data class Found(val ident: String, val hits: List<SecurityHit>) : SecuritySearch
}

/**
 * Zustand der Scope „Kurse“. Abgerufen wird nur, solange die Seite sichtbar ist ([setActive]), und nur, was veraltet
 * ist: Bitcoin-Kurs, Gebühren, Mempool und Block alle zwei Minuten, Krypto-Kurse alle zwei Minuten, Indizes und
 * Rohstoffe alle 15 Minuten, Aktien alle fünf Minuten, Difficulty, Hashrate und Lightning alle zehn Minuten, der
 * Bitcoin-Kursverlauf alle 15 Minuten, Wechselkurse stündlich. Die Schaltfläche „Aktualisieren“ ruft früher ab.
 * Verläufe der Diagramme werden nur für den gewählten Wert und Zeitraum geladen und kurz im Speicher gehalten.
 * Es gibt keinen Hintergrunddienst.
 */
class MarketsViewModel(application: Application) : AndroidViewModel(application) {

    private val store = MarketStore(application)

    private val _state = MutableStateFlow(MarketsUiState())
    val state: StateFlow<MarketsUiState> = _state.asStateFlow()

    private val sourceBlockedUntil = HashMap<String, Long>()
    private val seriesCache = HashMap<String, Pair<Long, Series>>()
    private val seriesJobs = HashMap<Slot, Job>()
    private var refreshJob: Job? = null
    private var activeJob: Job? = null
    private val settingsLoaded = CompletableDeferred<Unit>()

    private enum class Slot { Index, Commodity, Crypto }

    init {
        reloadFromStore()
    }

    /** Liest Einstellungen, Bestand und Zwischenspeicher neu; beim Start und nach dem Einspielen eines Backups. */
    fun reloadFromStore() {
        viewModelScope.launch {
            val snapshot = store.loadSnapshot() ?: MarketSnapshot()
            val url = store.customBaseUrl.first()
            val range = store.range.first()
            val currency = store.chartCurrency.first()
            val legacy = store.legacyHoldings.first()
            var positions = store.positions.first()
            if (!legacy.isEmpty) {
                // Bestände aus früheren Versionen (BTC, Gold, Silber) gehen einmalig in „Eigene Werte“ über.
                positions = PositionsLogic.migrate(positions, legacy)
                store.setPositions(positions)
                store.clearLegacyHoldings()
            }
            val holdingsVisible = store.holdingsVisible.first()
            val stooq = store.stooqEnabled.first()
            val stooqKey = store.stooqKey.first()
            val yahoo = store.yahooEnabled.first()
            val fxPair = store.fxPair.first()
            val choice = store.chartChoice.first()
            _state.update {
                it.copy(
                    snapshot = snapshot,
                    customBaseUrl = url,
                    range = range,
                    chartCurrency = currency,
                    holdingsVisible = holdingsVisible,
                    stooqEnabled = stooq,
                    stooqKey = stooqKey,
                    yahooEnabled = yahoo,
                    choice = choice,
                    positions = positions,
                    fxFrom = fxPair.first,
                    fxTo = fxPair.second,
                    cacheLoaded = true,
                )
            }
            settingsLoaded.complete(Unit)
        }
    }

    /** Wird mit `true` aufgerufen, wenn die Seite sichtbar wird, und mit `false`, wenn sie verlassen wird. */
    fun setActive(active: Boolean) {
        activeJob?.cancel()
        activeJob = null
        if (!active) return
        activeJob = viewModelScope.launch {
            settingsLoaded.await()
            loadAllSeries()
            while (isActive) {
                startRefresh(manual = false)
                delay(AUTO_CHECK_MS)
            }
        }
    }

    /** Manuelles Aktualisieren. */
    fun refresh() {
        seriesCache.clear()
        startRefresh(manual = true)
        loadAllSeries()
    }

    fun setRange(range: ChartRange) {
        _state.update { it.copy(range = range) }
        viewModelScope.launch { store.setRange(range) }
    }

    fun setChartCurrency(currency: ChartCurrency) {
        _state.update { it.copy(chartCurrency = currency) }
        viewModelScope.launch { store.setChartCurrency(currency) }
    }

    // region Diagramme von Indizes, Rohstoffen und Ethereum

    fun selectIndex(id: String) = updateChoice(Slot.Index) { it.copy(indexId = id) }

    fun setIndexRange(range: SeriesRange) = updateChoice(Slot.Index) { it.copy(indexRange = range) }

    fun selectCommodity(id: String) = updateChoice(Slot.Commodity) { it.copy(commodityId = id) }

    fun setCommodityRange(range: SeriesRange) = updateChoice(Slot.Commodity) { it.copy(commodityRange = range) }

    fun setCryptoRange(range: CryptoRange) = updateChoice(Slot.Crypto) { it.copy(cryptoRange = range) }

    private fun updateChoice(slot: Slot, change: (ChartChoice) -> ChartChoice) {
        val updated = change(_state.value.choice)
        _state.update { it.copy(choice = updated) }
        viewModelScope.launch { store.setChartChoice(updated) }
        loadSeries(slot)
    }

    private fun loadAllSeries() {
        Slot.entries.forEach { loadSeries(it) }
    }

    private fun idOf(slot: Slot, choice: ChartChoice): String = when (slot) {
        Slot.Index -> choice.indexId
        Slot.Commodity -> choice.commodityId
        Slot.Crypto -> CryptoCatalog.ethereum.id
    }

    private fun rangeOf(slot: Slot, choice: ChartChoice): String = when (slot) {
        Slot.Index -> choice.indexRange.key
        Slot.Commodity -> choice.commodityRange.key
        Slot.Crypto -> choice.cryptoRange.key
    }

    private fun load(slot: Slot, choice: ChartChoice, series: Series? = null, loading: Boolean = false, error: MarketError? = null) =
        SeriesLoad(idOf(slot, choice), rangeOf(slot, choice), series, loading, error)

    /** Schlüssel des Zwischenspeichers für Wert und Zeitraum des Bereichs [slot]. */
    private fun keyOf(slot: Slot, choice: ChartChoice): String = when (slot) {
        Slot.Index -> "i:${choice.indexId}:${choice.indexRange.key}"
        Slot.Commodity -> "c:${choice.commodityId}:${choice.commodityRange.key}"
        Slot.Crypto -> "x:${CryptoCatalog.ethereum.id}:${choice.cryptoRange.key}"
    }

    private fun setSeries(slot: Slot, load: SeriesLoad) = _state.update {
        when (slot) {
            Slot.Index -> it.copy(indexSeries = load)
            Slot.Commodity -> it.copy(commoditySeries = load)
            Slot.Crypto -> it.copy(cryptoSeries = load)
        }
    }

    /**
     * Lädt den Verlauf für die aktuelle Auswahl des Bereichs. Der Monatsverlauf von Indizes und Rohstoffen kommt aus
     * dem Kurs-Abruf selbst und braucht keine eigene Anfrage.
     */
    private fun loadSeries(slot: Slot) {
        val s = _state.value
        if (!s.cacheLoaded) return
        val choice = s.choice
        val key = keyOf(slot, choice)
        seriesJobs.remove(slot)?.cancel()
        if (slot != Slot.Crypto) {
            val range = if (slot == Slot.Index) choice.indexRange else choice.commodityRange
            if (range == SeriesRange.Month) {
                setSeries(slot, load(slot, choice))
                return
            }
        }
        val cached = seriesCache[key]
        if (cached != null && System.currentTimeMillis() - cached.first < seriesTtl(key)) {
            setSeries(slot, load(slot, choice, series = cached.second))
            return
        }
        val now = System.currentTimeMillis()
        val source = if (slot == Slot.Crypto) SOURCE_COINGECKO else SOURCE_YAHOO
        if (sourceBlocked(source, now) && !(slot != Slot.Crypto && s.stooqActive)) {
            setSeries(slot, load(slot, choice, error = MarketError.RATE_LIMIT))
            return
        }
        setSeries(slot, load(slot, choice, loading = true))
        seriesJobs[slot] = viewModelScope.launch {
            val repository = MarketRepository(s.baseUrl)
            val result: SeriesLoad = try {
                val series = when (slot) {
                    Slot.Index -> repository.fetchSeries(requireNotNull(QuoteCatalog.byId(choice.indexId)), choice.indexRange, s.yahooEnabled && !sourceBlocked(SOURCE_YAHOO, now), s.stooqKey.takeIf { s.stooqActive })
                    Slot.Commodity -> repository.fetchSeries(requireNotNull(QuoteCatalog.byId(choice.commodityId)), choice.commodityRange, s.yahooEnabled && !sourceBlocked(SOURCE_YAHOO, now), s.stooqKey.takeIf { s.stooqActive })
                    Slot.Crypto -> repository.fetchCryptoSeries(CryptoCatalog.ethereum.id, choice.cryptoRange)
                }
                seriesCache[key] = System.currentTimeMillis() to series
                load(slot, choice, series = series)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RateLimitException) {
                blockSource(source, e.retryAfterSeconds)
                load(slot, choice, error = MarketError.RATE_LIMIT)
            } catch (e: ApiKeyException) {
                load(slot, choice, error = MarketError.KEY)
            } catch (e: IOException) {
                load(slot, choice, error = MarketError.NETWORK)
            } catch (e: Exception) {
                load(slot, choice, error = MarketError.DATA)
            }
            // Nur übernehmen, wenn die Auswahl inzwischen nicht gewechselt hat.
            if (keyOf(slot, _state.value.choice) == key) setSeries(slot, result)
        }
    }

    private fun seriesTtl(key: String): Long =
        if (key.endsWith(":1d") || key.endsWith(":1w")) SERIES_TTL_SHORT else SERIES_TTL_LONG

    // endregion

    // region Einstellungen

    fun setHoldingsVisible(visible: Boolean) {
        _state.update { it.copy(holdingsVisible = visible) }
        viewModelScope.launch { store.setHoldingsVisible(visible) }
    }

    fun setStooqEnabled(enabled: Boolean) {
        _state.update { it.copy(stooqEnabled = enabled, quotesError = null) }
        viewModelScope.launch { store.setStooqEnabled(enabled) }
        if (enabled) refreshAfterSourceChange()
    }

    /** Trägt den Stooq-Schlüssel ein (leer = entfernen). Nur auf dem Gerät gespeichert. */
    fun setStooqKey(key: String) {
        val trimmed = key.trim().take(MAX_KEY_LENGTH)
        _state.update { it.copy(stooqKey = trimmed, quotesError = null) }
        viewModelScope.launch { store.setStooqKey(trimmed) }
        if (trimmed.isNotEmpty() && _state.value.stooqEnabled) refreshAfterSourceChange()
    }

    fun setYahooEnabled(enabled: Boolean) {
        _state.update { it.copy(yahooEnabled = enabled, quotesError = null, securitiesError = null, search = SecuritySearch.Idle) }
        viewModelScope.launch { store.setYahooEnabled(enabled) }
        if (enabled) refreshAfterSourceChange()
    }

    private fun refreshAfterSourceChange() {
        seriesCache.clear()
        startRefresh(manual = true)
        loadAllSeries()
    }

    /** Währungspaar des Rechners; fehlende Kurse werden nachgeladen. */
    fun setFxPair(from: String, to: String) {
        if (!FxLogic.isSupported(from) || !FxLogic.isSupported(to)) return
        _state.update { it.copy(fxFrom = from, fxTo = to) }
        viewModelScope.launch { store.setFxPair(from, to) }
        startRefresh(manual = false)
    }

    fun swapFx() = setFxPair(_state.value.fxTo, _state.value.fxFrom)

    // endregion

    // region Eigene Werte

    fun clearSearch() = _state.update { it.copy(search = SecuritySearch.Idle) }

    /** Sucht ein Wertpapier zu einer WKN oder ISIN (Yahoo Finance). */
    fun searchSecurity(input: String) {
        val s = _state.value
        if (!s.yahooEnabled || s.search == SecuritySearch.Loading) return
        val id = SecurityIds.parse(input)
        if (id == null) {
            _state.update { it.copy(search = SecuritySearch.InvalidId) }
            return
        }
        _state.update { it.copy(search = SecuritySearch.Loading) }
        viewModelScope.launch {
            val result = try {
                val hits = MarketRepository().searchSecurities(id)
                if (hits.isEmpty()) SecuritySearch.NotFound else SecuritySearch.Found(id.value, hits)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RateLimitException) {
                blockSource(SOURCE_YAHOO, e.retryAfterSeconds)
                SecuritySearch.Error(MarketError.RATE_LIMIT)
            } catch (e: IOException) {
                SecuritySearch.Error(MarketError.NETWORK)
            } catch (e: Exception) {
                SecuritySearch.Error(MarketError.DATA)
            }
            _state.update { it.copy(search = result) }
        }
    }

    /** Nimmt einen Treffer mit [quantity] Stück als Aktie auf, wenn Yahoo einen Kurs dazu liefert. */
    fun addSecurity(hit: SecurityHit, ident: String, quantity: Double) {
        if (quantity <= 0.0) return
        _state.update { it.copy(search = SecuritySearch.Loading) }
        viewModelScope.launch {
            try {
                val quote = MarketRepository().fetchSecurity(hit.symbol)
                val updated = PositionsLogic.add(_state.value.positions, Position(hit.symbol, hit.name, ident, quantity, PositionKind.Stock))
                if (updated == null) {
                    _state.update { it.copy(search = SecuritySearch.Idle) }
                    return@launch
                }
                val snap = _state.value.snapshot
                val old = snap.securities
                val merged = SecuritiesData(listOf(quote), System.currentTimeMillis()).filledFrom(old)
                val snapshot = snap.copy(securities = merged.copy(atMillis = old?.atMillis ?: merged.atMillis))
                _state.update { it.copy(positions = updated, snapshot = snapshot, search = SecuritySearch.Idle) }
                store.setPositions(updated)
                store.saveSnapshot(snapshot)
                startRefresh(manual = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RateLimitException) {
                blockSource(SOURCE_YAHOO, e.retryAfterSeconds)
                _state.update { it.copy(search = SecuritySearch.Error(MarketError.RATE_LIMIT)) }
            } catch (e: IOException) {
                _state.update { it.copy(search = SecuritySearch.Error(MarketError.NETWORK)) }
            } catch (e: Exception) {
                _state.update { it.copy(search = SecuritySearch.Error(MarketError.DATA)) }
            }
        }
    }

    /** Nimmt einen Rohstoff aus dem Katalog mit [quantity] in seiner Handelseinheit auf. */
    fun addCommodity(id: String, quantity: Double) {
        val def = QuoteCatalog.commodities.firstOrNull { it.id == id } ?: return
        addPosition(Position(def.id, def.name, "", quantity, PositionKind.Commodity))
    }

    /** Nimmt einen Krypto-Wert aus dem Katalog mit [quantity] Einheiten auf. */
    fun addCrypto(id: String, quantity: Double) {
        val def = CryptoCatalog.byId(id) ?: return
        addPosition(Position(def.id, def.name, def.symbol, quantity, PositionKind.Crypto))
    }

    private fun addPosition(position: Position) {
        if (position.quantity <= 0.0 || position.quantity > HoldingsLogic.MAX_AMOUNT) return
        val updated = PositionsLogic.add(_state.value.positions, position) ?: return
        _state.update { it.copy(positions = updated) }
        viewModelScope.launch { store.setPositions(updated) }
        startRefresh(manual = false)
    }

    fun setPositionQuantity(kind: PositionKind, symbol: String, quantity: Double) {
        val updated = PositionsLogic.setQuantity(_state.value.positions, kind, symbol, quantity)
        _state.update { it.copy(positions = updated) }
        viewModelScope.launch { store.setPositions(updated) }
    }

    fun removePosition(kind: PositionKind, symbol: String) {
        val updated = PositionsLogic.remove(_state.value.positions, kind, symbol)
        _state.update { it.copy(positions = updated) }
        viewModelScope.launch { store.setPositions(updated) }
    }

    // endregion

    private fun blockSource(source: String, retryAfterSeconds: Long) {
        sourceBlockedUntil[source] = System.currentTimeMillis() + retryAfterSeconds.coerceAtLeast(MIN_BLOCK_SECONDS) * 1000L
    }

    private fun sourceBlocked(source: String, now: Long): Boolean = (sourceBlockedUntil[source] ?: 0L) > now

    /**
     * Wählt die Datenquelle für Bitcoin: [url] ist eine mit [MarketUrl.check] bereinigte HTTPS-Adresse einer eigenen
     * Instanz oder `null` für die öffentliche Instanz. Die bisherigen Bitcoin-Werte werden verworfen und neu geladen.
     */
    fun setCustomBaseUrl(url: String?) {
        val normalized = url?.takeIf { !MarketUrl.isDefault(it) }
        viewModelScope.launch {
            refreshJob?.cancelAndJoin()
            store.setCustomBaseUrl(normalized)
            val cleared = _state.value.snapshot.copy(fast = null, slow = null, history = null)
            store.clearBitcoinSnapshot(cleared)
            _state.update {
                it.copy(
                    customBaseUrl = normalized,
                    snapshot = cleared,
                    loading = false,
                    bitcoinError = null,
                    blockedUntilMillis = 0L,
                )
            }
            startRefresh(manual = true)
        }
    }

    private fun startRefresh(manual: Boolean) {
        val now = System.currentTimeMillis()
        val s = _state.value
        if (s.loading || !s.cacheLoaded) return
        val limits = if (manual) MANUAL_AGES else AUTO_AGES
        val snap = s.snapshot
        fun due(atMillis: Long?, maxAge: Long) = atMillis == null || now - atMillis >= maxAge
        val mempoolOpen = now >= s.blockedUntilMillis
        val needFast = mempoolOpen && due(snap.fast?.atMillis, limits.fast)
        val needSlow = mempoolOpen && due(snap.slow?.atMillis, limits.slow)
        val needHistory = mempoolOpen && due(snap.history?.atMillis, limits.history)
        val yahooOpen = s.yahooEnabled && !sourceBlocked(SOURCE_YAHOO, now)
        val stooqKey = s.stooqKey.takeIf { s.stooqActive && !sourceBlocked(SOURCE_STOOQ, now) }
        val needQuotes = (yahooOpen || stooqKey != null) && due(snap.quotes?.atMillis, limits.quotes)
        val needCrypto = !sourceBlocked(SOURCE_COINGECKO, now) && due(snap.crypto?.atMillis, limits.crypto)
        val symbols = s.positions.filter { it.kind == PositionKind.Stock }.map { it.symbol }
        val needSecurities = yahooOpen && symbols.isNotEmpty() &&
            (due(snap.securities?.atMillis, limits.securities) || symbols.any { sym -> snap.securities?.quotes?.none { it.symbol == sym } != false })
        val neededCurrencies = buildSet {
            add("USD")
            add(s.fxFrom)
            add(s.fxTo)
            addAll(PositionsLogic.currenciesNeeded(s.positions, snap.assetPrices()))
        }.filter { it != "EUR" && FxLogic.isSupported(it) }.toSet()
        val needFx = neededCurrencies.isNotEmpty() && !sourceBlocked(SOURCE_FX, now) &&
            (due(snap.fx?.atMillis, limits.fx) || neededCurrencies.any { snap.fx?.perEur?.containsKey(it) != true })
        if (!needFast && !needSlow && !needHistory && !needQuotes && !needCrypto && !needSecurities && !needFx) return

        val repository = MarketRepository(s.baseUrl)
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            var snapshot = _state.value.snapshot
            var bitcoinError: MarketError? = null
            var blockedFor = 0L
            var quotesError: MarketError? = null
            var cryptoError: MarketError? = null
            var securitiesError: MarketError? = null
            var fxError: MarketError? = null
            /** Führt den Abruf einer Quelle aus; Fehler werden der Quelle zugeordnet, nicht allen Cards. */
            suspend fun <T> guarded(source: String, onError: (MarketError) -> Unit, block: suspend () -> T): T? = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: RateLimitException) {
                blockSource(source, e.retryAfterSeconds)
                onError(MarketError.RATE_LIMIT)
                null
            } catch (e: ApiKeyException) {
                onError(MarketError.KEY)
                null
            } catch (e: IOException) {
                onError(MarketError.NETWORK)
                null
            } catch (e: Exception) {
                onError(MarketError.DATA)
                null
            }
            try {
                if (needFast || needSlow || needHistory) {
                    try {
                        if (needFast) snapshot = snapshot.copy(fast = repository.fetchFast().filledFrom(snapshot.fast))
                        if (needSlow) snapshot = snapshot.copy(slow = repository.fetchSlow().filledFrom(snapshot.slow))
                        if (needHistory) snapshot = snapshot.copy(history = repository.fetchHistory())
                    } catch (e: RateLimitException) {
                        bitcoinError = MarketError.RATE_LIMIT
                        blockedFor = maxOf(blockedFor, e.retryAfterSeconds.coerceAtLeast(MIN_BLOCK_SECONDS))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: IOException) {
                        bitcoinError = MarketError.NETWORK
                    } catch (e: Exception) {
                        bitcoinError = MarketError.DATA
                    }
                }
                if (needQuotes) {
                    guarded(SOURCE_YAHOO, { quotesError = it }) { repository.fetchQuotes(yahooOpen, stooqKey) }?.let { result ->
                        snapshot = snapshot.copy(quotes = result.data.filledFrom(snapshot.quotes))
                        result.yahooRateLimitSeconds?.let { blockSource(SOURCE_YAHOO, it) }
                        if (result.stooqKeyRejected) quotesError = MarketError.KEY
                    }
                }
                if (needCrypto) {
                    guarded(SOURCE_COINGECKO, { cryptoError = it }) { repository.fetchCrypto() }?.let { fresh ->
                        snapshot = snapshot.copy(crypto = fresh.filledFrom(snapshot.crypto))
                    }
                }
                if (needSecurities) {
                    guarded(SOURCE_YAHOO, { securitiesError = it }) { repository.fetchSecurities(symbols) }?.let { fresh ->
                        snapshot = snapshot.copy(securities = fresh.filledFrom(snapshot.securities))
                    }
                }
                if (needFx) {
                    // Nach den Kursen, damit deren Währungen schon bekannt sind.
                    val currencies = buildSet {
                        addAll(neededCurrencies)
                        addAll(PositionsLogic.currenciesNeeded(_state.value.positions, snapshot.assetPrices()))
                    }.filter { it != "EUR" && FxLogic.isSupported(it) }.toSet()
                    guarded(SOURCE_FX, { fxError = it }) { repository.fetchEurRates(currencies) }?.let { fresh ->
                        snapshot = snapshot.copy(fx = fresh.filledFrom(snapshot.fx))
                    }
                }
                store.saveSnapshot(snapshot)
            } finally {
                // Auch beim Abbruch den Ladezustand zurücksetzen; neuere Werte stehen nur bei normalem Ende fest.
                val finished = snapshot
                _state.update {
                    it.copy(
                        snapshot = finished,
                        loading = false,
                        bitcoinError = if (needFast || needSlow || needHistory) bitcoinError else it.bitcoinError,
                        quotesError = if (needQuotes) quotesError else it.quotesError,
                        cryptoError = if (needCrypto) cryptoError else it.cryptoError,
                        securitiesError = if (needSecurities) securitiesError else it.securitiesError,
                        fxError = if (needFx) fxError else it.fxError,
                        blockedUntilMillis = if (blockedFor > 0L) System.currentTimeMillis() + blockedFor * 1000L else it.blockedUntilMillis,
                    )
                }
            }
        }
    }

    private class Ages(
        val fast: Long,
        val slow: Long,
        val history: Long,
        val quotes: Long,
        val crypto: Long,
        val securities: Long,
        val fx: Long,
    )

    private companion object {
        const val AUTO_CHECK_MS = 30_000L
        const val MIN_BLOCK_SECONDS = 60L
        const val MAX_KEY_LENGTH = 200
        const val SERIES_TTL_SHORT = 5L * 60_000L
        const val SERIES_TTL_LONG = 30L * 60_000L
        const val SOURCE_STOOQ = "stooq"
        const val SOURCE_YAHOO = "yahoo"
        const val SOURCE_COINGECKO = "coingecko"
        const val SOURCE_FX = "fx"
        val AUTO_AGES = Ages(fast = 120_000L, slow = 600_000L, history = 900_000L, quotes = 900_000L, crypto = 120_000L, securities = 300_000L, fx = 3_600_000L)
        val MANUAL_AGES = Ages(fast = 0L, slow = 120_000L, history = 300_000L, quotes = 120_000L, crypto = 60_000L, securities = 60_000L, fx = 600_000L)
    }
}
