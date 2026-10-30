// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.markets

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.marketsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "markets",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Zwischenspeicher der Scope „Kurse“ (letzter Stand für die Offline-Anzeige) und
 * ihre Einstellungen: eigene Basis-Adresse der Bitcoin-Daten, Zeitraum und Währung des Diagramms.
 */
class MarketStore(context: Context) {
    private val store = context.applicationContext.marketsDataStore

    private val safeData: Flow<Preferences> = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }

    /** Eigene Basis-Adresse oder `null` für die öffentliche Standard-Instanz. */
    val customBaseUrl: Flow<String?> = safeData.map { prefs ->
        prefs[BASE_URL]?.let { (MarketUrl.check(it) as? BaseUrlCheck.Ok)?.url }
    }

    val range: Flow<ChartRange> = safeData.map { ChartRange.fromKey(it[RANGE]) }

    val chartCurrency: Flow<ChartCurrency> = safeData.map { ChartCurrency.fromKey(it[CHART_CURRENCY]) }

    /** Frühere Bestände (BTC, Gold, Silber); werden beim ersten Start in „Eigene Werte“ übernommen und dann gelöscht. */
    val legacyHoldings: Flow<Holdings> = safeData.map { HoldingsLogic.decode(it[HOLDINGS]) }

    /** `true`, wenn die Card „Eigene Werte“ aufgeklappt ist. Standard: eingeklappt. */
    val holdingsVisible: Flow<Boolean> = safeData.map { it[HOLDINGS_VISIBLE] ?: false }

    /** Quelle Stooq eingeschaltet (wirkt nur mit eingetragenem Schlüssel). Standard: aus. */
    val stooqEnabled: Flow<Boolean> = safeData.map { it[STOOQ_ENABLED] ?: false }

    /** Eigener API-Schlüssel für Stooq oder leer. Nur auf dem Gerät gespeichert, nicht Teil der Sicherung. */
    val stooqKey: Flow<String> = safeData.map { it[STOOQ_KEY].orEmpty() }

    /** Quelle Yahoo Finance (Indizes, Rohstoffe, Aktien) eingeschaltet. Standard: ja. */
    val yahooEnabled: Flow<Boolean> = safeData.map { it[YAHOO_ENABLED] ?: true }

    /** Auswahl und Zeitraum der Diagramme von Indizes, Rohstoffen und Ethereum. */
    val chartChoice: Flow<ChartChoice> = safeData.map { prefs ->
        ChartChoice(
            indexId = prefs[SEL_INDEX]?.takeIf { id -> QuoteCatalog.indices.any { it.id == id } } ?: "dax",
            indexRange = SeriesRange.fromKey(prefs[RANGE_INDEX]),
            commodityId = prefs[SEL_COMMODITY]?.takeIf { id -> QuoteCatalog.commodities.any { it.id == id } } ?: "gold",
            commodityRange = SeriesRange.fromKey(prefs[RANGE_COMMODITY]),
            cryptoRange = CryptoRange.fromKey(prefs[RANGE_CRYPTO]),
        )
    }

    /** Wertpapiere im Bestand (nur auf dem Gerät gespeichert). */
    val positions: Flow<List<Position>> = safeData.map { PositionsLogic.decode(it[POSITIONS]) }

    /** Ausgangs- und Zielwährung des Währungsrechners. */
    val fxPair: Flow<Pair<String, String>> = safeData.map { prefs ->
        val from = prefs[FX_FROM]?.takeIf { FxLogic.isSupported(it) } ?: "EUR"
        val to = prefs[FX_TO]?.takeIf { FxLogic.isSupported(it) } ?: "USD"
        from to to
    }

    suspend fun setStooqEnabled(enabled: Boolean) {
        store.edit { it[STOOQ_ENABLED] = enabled }
    }

    suspend fun setStooqKey(key: String) {
        store.edit { prefs -> if (key.isBlank()) prefs.remove(STOOQ_KEY) else prefs[STOOQ_KEY] = key.trim() }
    }

    suspend fun setYahooEnabled(enabled: Boolean) {
        store.edit { it[YAHOO_ENABLED] = enabled }
    }

    suspend fun setChartChoice(choice: ChartChoice) {
        store.edit {
            it[SEL_INDEX] = choice.indexId
            it[RANGE_INDEX] = choice.indexRange.key
            it[SEL_COMMODITY] = choice.commodityId
            it[RANGE_COMMODITY] = choice.commodityRange.key
            it[RANGE_CRYPTO] = choice.cryptoRange.key
        }
    }

    suspend fun setPositions(positions: List<Position>) {
        store.edit { prefs ->
            if (positions.isEmpty()) prefs.remove(POSITIONS) else prefs[POSITIONS] = PositionsLogic.encode(positions)
        }
    }

    suspend fun setFxPair(from: String, to: String) {
        store.edit {
            it[FX_FROM] = from
            it[FX_TO] = to
        }
    }

    /** Löscht die früheren Bestände nach der Übernahme in „Eigene Werte“. */
    suspend fun clearLegacyHoldings() {
        store.edit { it.remove(HOLDINGS) }
    }

    suspend fun setHoldingsVisible(visible: Boolean) {
        store.edit { it[HOLDINGS_VISIBLE] = visible }
    }

    suspend fun loadSnapshot(): MarketSnapshot? = MarketCodec.decode(safeData.first()[SNAPSHOT])

    suspend fun saveSnapshot(snapshot: MarketSnapshot) {
        store.edit { it[SNAPSHOT] = MarketCodec.encode(snapshot) }
    }

    suspend fun clearBitcoinSnapshot(snapshot: MarketSnapshot) {
        // Beim Wechsel der Datenquelle gelten die alten Bitcoin-Werte nicht mehr; die übrigen Kurse bleiben.
        store.edit { it[SNAPSHOT] = MarketCodec.encode(snapshot.copy(fast = null, slow = null, history = null)) }
    }

    suspend fun setCustomBaseUrl(url: String?) {
        store.edit { prefs ->
            prefs.remove(BASE_URL)
            if (url != null) prefs[BASE_URL] = url
        }
    }

    suspend fun setRange(range: ChartRange) {
        store.edit { it[RANGE] = range.key }
    }

    suspend fun setChartCurrency(currency: ChartCurrency) {
        store.edit { it[CHART_CURRENCY] = currency.key }
    }

    private companion object {
        val SNAPSHOT = stringPreferencesKey("snapshot")
        val BASE_URL = stringPreferencesKey("base_url")
        val RANGE = stringPreferencesKey("range")
        val CHART_CURRENCY = stringPreferencesKey("chart_currency")
        val HOLDINGS = stringPreferencesKey("holdings")
        val STOOQ_ENABLED = booleanPreferencesKey("stooq_on")
        val STOOQ_KEY = stringPreferencesKey("stooq_key")
        val SEL_INDEX = stringPreferencesKey("sel_index")
        val RANGE_INDEX = stringPreferencesKey("range_index")
        val SEL_COMMODITY = stringPreferencesKey("sel_commodity")
        val RANGE_COMMODITY = stringPreferencesKey("range_commodity")
        val RANGE_CRYPTO = stringPreferencesKey("range_crypto")
        val YAHOO_ENABLED = booleanPreferencesKey("yahoo_enabled")
        val POSITIONS = stringPreferencesKey("positions")
        val FX_FROM = stringPreferencesKey("fx_from")
        val FX_TO = stringPreferencesKey("fx_to")
        val HOLDINGS_VISIBLE = booleanPreferencesKey("holdings_visible")
    }
}
