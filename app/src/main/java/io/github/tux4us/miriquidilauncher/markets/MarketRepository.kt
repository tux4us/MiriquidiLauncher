// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.markets

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** Der Dienst meldet HTTP 429 (zu viele Anfragen). [retryAfterSeconds] ist die verlangte Wartezeit oder 0. */
class RateLimitException(val retryAfterSeconds: Long) : IOException("HTTP 429")

/**
 * Netzwerkzugriff für die Scope „Kurse“.
 *
 * - Bitcoin-Daten: [baseUrl] (Standard `https://mempool.space` oder eine eigene mempool-kompatible Instanz).
 * - Wechselkurse: `https://api.frankfurter.dev/v2/rate/eur/<Währung>`.
 * - Indizes, Rohstoffe, Wertpapiersuche und -kurse: Yahoo Finance (inoffizielle Schnittstelle, ohne Schlüssel);
 *   alternativ oder ergänzend Stooq (CSV, nur mit eigenem API-Schlüssel des Nutzers).
 * - Ethereum und Stablecoins: CoinGecko (öffentliche Schnittstelle ohne Schlüssel).
 *
 * Es werden nur HTTPS-Adressen abgerufen. Übertragen werden die Anfrageadresse und die technisch bedingte
 * IP-Adresse, keine Kennungen und keine Nutzerdaten. Die Anfragen an einen Dienst laufen nacheinander, um dessen
 * Ratenbegrenzung zu schonen. Bei HTTP 429 wird nichts weiter angefragt ([RateLimitException]).
 */
class MarketRepository(baseUrl: String = MarketUrl.DEFAULT) {
    private val base = baseUrl.trimEnd('/')

    init {
        require(base.startsWith("https://")) { "Nur HTTPS" }
    }

    /** Kurs, Gebühren, Mempool und letzter Block. Einzelne Fehler lassen nur den jeweiligen Teil leer. */
    suspend fun fetchFast(): BitcoinFast {
        val price = part { MarketParser.parsePrices(get("$base/api/v1/prices")) }
        val fees = part { MarketParser.parseFees(get("$base/api/v1/fees/recommended")) }
        val mempool = part { MarketParser.parseMempool(get("$base/api/mempool")) }
        val tip = part {
            val height = MarketParser.parseTipHeight(get("$base/api/blocks/tip/height"))
            // Zeit und Transaktionszahl des letzten Blocks sind zusätzlich; fehlen sie, bleibt die Höhe.
            val block = part {
                val hash = MarketParser.parseTipHash(get("$base/api/blocks/tip/hash"))
                MarketParser.parseBlock(get("$base/api/block/$hash"))
            }
            if (block != null && block.height >= height) block else TipBlock(height, null, null)
        }
        if (price == null && fees == null && mempool == null && tip == null) throw IOException("Keine Bitcoin-Daten")
        return BitcoinFast(price, tip, fees, mempool, System.currentTimeMillis())
    }

    /** Difficulty-Anpassung, Hashrate und Lightning-Statistik. */
    suspend fun fetchSlow(): BitcoinSlow {
        val difficulty = part { MarketParser.parseDifficulty(get("$base/api/v1/difficulty-adjustment")) }
        val hashrate = part { MarketParser.parseHashrate(get("$base/api/v1/mining/hashrate/3d")) }
        val lightning = part { MarketParser.parseLightning(get("$base/api/v1/lightning/statistics/latest")) }
        if (difficulty == null && hashrate == null && lightning == null) throw IOException("Keine Netzwerkdaten")
        return BitcoinSlow(difficulty, hashrate, lightning, System.currentTimeMillis())
    }

    /**
     * Stündlicher Kursverlauf. Die Antwort enthält den gesamten Verlauf, neueste Einträge zuerst; gelesen werden nur
     * die ersten [HISTORY_BYTES] Bytes (etwa 80 Tage), der Rest wird nicht übertragen bzw. verworfen.
     */
    suspend fun fetchHistory(): BitcoinHistory {
        val text = get("$base/api/v1/historical-price?currency=EUR", maxBytes = HISTORY_BYTES, truncate = true)
        // Das Diagramm zeigt höchstens 30 Tage; mehr wird nicht zwischengespeichert.
        val points = PriceSeries.keepLast(MarketParser.parseHistory(text), KEEP_HISTORY_SECONDS)
        if (points.isEmpty()) throw IOException("Kein Kursverlauf")
        return BitcoinHistory(points, System.currentTimeMillis())
    }

    /** Ergebnis eines Abrufs der Indizes und Rohstoffe. */
    data class QuotesResult(
        val data: QuotesData,
        /** Wartezeit in Sekunden, wenn Yahoo während des Abrufs HTTP 429 meldete; sonst `null`. */
        val yahooRateLimitSeconds: Long?,
        /** Stooq lehnte den Schlüssel ab (fehlend, ungültig oder Kontingent ausgeschöpft). */
        val stooqKeyRejected: Boolean,
    )

    /**
     * Indizes und Rohstoffe. Je Eintrag wird zuerst Yahoo abgefragt (wenn [yahoo]), bei Fehler oder Ratenbegrenzung
     * danach Stooq (wenn ein Schlüssel eingetragen ist und das Kürzel dort bekannt ist). Höchstens [QUOTES_PARALLEL]
     * Anfragen gleichzeitig. Einträge ohne Daten fehlen im Ergebnis. Liefert keine Quelle etwas, wird eine Ausnahme
     * geworfen: [RateLimitException], [ApiKeyException] oder [IOException].
     */
    suspend fun fetchQuotes(yahoo: Boolean, stooqKey: String?): QuotesResult = coroutineScope {
        val gate = Semaphore(QUOTES_PARALLEL)
        val key = stooqKey?.trim()?.takeIf { it.isNotEmpty() }
        var yahooLimit: Long? = null
        var keyRejected = false
        val found = QuoteCatalog.all.map { def ->
            async {
                gate.withPermit {
                    var result: Quote? = null
                    if (yahoo && yahooLimit == null) {
                        try {
                            result = part { YahooParser.parseQuote(def.id, get(YahooParser.chartUrl(def.yahoo, "1mo", "1d"), userAgent = YAHOO_USER_AGENT)) }
                        } catch (e: RateLimitException) {
                            yahooLimit = e.retryAfterSeconds
                        }
                    }
                    if (result == null && key != null && def.stooq != null && !keyRejected) {
                        try {
                            result = part { fetchStooqQuote(def, key) }
                        } catch (e: ApiKeyException) {
                            keyRejected = true
                        } catch (e: RateLimitException) {
                            // Stooq begrenzt: dieser Eintrag bleibt leer.
                        }
                    }
                    result
                }
            }
        }.awaitAll().filterNotNull()
        if (found.isEmpty()) {
            yahooLimit?.let { throw RateLimitException(it) }
            if (keyRejected) throw ApiKeyException("Stooq")
            throw IOException("Keine Kurse")
        }
        QuotesResult(QuotesData(found, System.currentTimeMillis()), yahooLimit, keyRejected)
    }

    private suspend fun fetchStooqQuote(def: QuoteDef, key: String): Quote {
        val symbol = def.stooq ?: throw IOException("Kein Kürzel")
        val today = LocalDate.now(ZoneOffset.UTC)
        val csv = get(StooqParser.historyUrl(symbol, STOOQ_DATE.format(today.minusDays(45)), STOOQ_DATE.format(today), "d", key))
        val quote = StooqParser.toQuote(def.id, StooqParser.parseSeries(csv))
        // Stooq nennt keine Währung: Rohstoffe notieren in US-Dollar, bei Indizes sind es Punkte.
        return if (def.group == QuoteGroup.Commodity) quote.copy(currency = "USD") else quote
    }

    /**
     * Zeitreihe zu [def] für [range]. Yahoo zuerst, danach Stooq (Tages- und Wochenwerte, nur mit Schlüssel). Wirft
     * die Ausnahme der zuletzt versuchten Quelle, wenn keine Daten kommen.
     */
    suspend fun fetchSeries(def: QuoteDef, range: SeriesRange, yahoo: Boolean, stooqKey: String?): Series {
        val key = stooqKey?.trim()?.takeIf { it.isNotEmpty() }
        var failure: Exception = IOException("Keine Quelle")
        if (yahoo) {
            try {
                return YahooParser.parseSeries(
                    get(YahooParser.chartUrl(def.yahoo, range.yahooRange, range.yahooInterval), userAgent = YAHOO_USER_AGENT),
                    System.currentTimeMillis(),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
            }
        }
        if (key != null && def.stooq != null && range.stooqSupported) {
            try {
                val today = LocalDate.now(ZoneOffset.UTC)
                val url = StooqParser.historyUrl(def.stooq, STOOQ_DATE.format(today.minusDays(range.days.toLong())), STOOQ_DATE.format(today), range.stooqInterval, key)
                val s = StooqParser.parseSeries(get(url))
                val (t, v) = YahooParser.thin(s.times, s.values, YahooParser.MAX_SERIES_POINTS)
                return Series(t, v, null, if (def.group == QuoteGroup.Commodity) "USD" else null, System.currentTimeMillis())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
            }
        }
        throw failure
    }

    /** Ethereum und die Stablecoins von CoinGecko. */
    suspend fun fetchCrypto(): CryptoData {
        val quotes = try {
            CoinGeckoParser.parsePrices(get(CoinGeckoParser.pricesUrl(CryptoCatalog.fetched.map { it.id })))
        } catch (e: CoinGeckoParser.ParseException) {
            throw IOException("Krypto-Daten unlesbar")
        }
        if (quotes.isEmpty()) throw IOException("Keine Krypto-Kurse")
        return CryptoData(quotes, System.currentTimeMillis())
    }

    /** Verlauf eines Krypto-Werts in Euro. */
    suspend fun fetchCryptoSeries(id: String, range: CryptoRange): Series = try {
        CoinGeckoParser.parseChart(get(CoinGeckoParser.chartUrl(id, range.days)), System.currentTimeMillis())
    } catch (e: CoinGeckoParser.ParseException) {
        throw IOException("Krypto-Verlauf unlesbar")
    }

    /**
     * Sucht Wertpapiere über Yahoo Finance zu einer WKN oder ISIN. Bei einer WKN wird zusätzlich die daraus gebildete
     * deutsche ISIN versucht. Leere Liste = nichts gefunden; [IOException], wenn kein Suchbegriff abrufbar war.
     */
    suspend fun searchSecurities(id: SecurityId): List<SecurityHit> {
        var failed = 0
        val terms = id.searchTerms()
        for (term in terms) {
            val hits = try {
                YahooParser.parseSearch(get(YahooParser.searchUrl(term), userAgent = YAHOO_USER_AGENT))
            } catch (e: CancellationException) {
                throw e
            } catch (e: RateLimitException) {
                throw e
            } catch (e: IOException) {
                failed++
                continue
            } catch (e: YahooParser.ParseException) {
                failed++
                continue
            }
            if (hits.isNotEmpty()) return hits
        }
        if (failed == terms.size) throw IOException("Suche fehlgeschlagen")
        return emptyList()
    }

    /** Aktueller Kurs eines Wertpapiers (Währung bereits in Hauptwährung umgerechnet). */
    suspend fun fetchSecurity(symbol: String): PositionQuote {
        val q = YahooParser.parseChart(get(YahooParser.chartUrl(symbol), userAgent = YAHOO_USER_AGENT))
        val (currency, price) = FxLogic.normalize(q.currency, q.price)
        return PositionQuote(symbol, price, currency)
    }

    suspend fun fetchSecurities(symbols: List<String>): SecuritiesData = coroutineScope {
        val gate = Semaphore(YAHOO_PARALLEL)
        val found = symbols.map { symbol -> async { gate.withPermit { part { fetchSecurity(symbol) } } } }.awaitAll().filterNotNull()
        if (found.isEmpty() && symbols.isNotEmpty()) throw IOException("Keine Wertpapierkurse")
        SecuritiesData(found, System.currentTimeMillis())
    }

    /** Kurse für 1 Euro in den Währungen [codes] (Frankfurter, EZB-Referenzkurse). */
    suspend fun fetchEurRates(codes: Set<String>): FxData = coroutineScope {
        val wanted = codes.filter { it != "EUR" && FxLogic.isSupported(it) }
        val found = wanted.map { code ->
            async { part { MarketParser.parseEuroRate(get(FxLogic.rateUrl(code))).usdPerEur }?.let { code to it } }
        }.awaitAll().filterNotNull().toMap()
        if (found.isEmpty() && wanted.isNotEmpty()) throw IOException("Keine Wechselkurse")
        FxData(found, System.currentTimeMillis())
    }

    /** Führt [block] aus; Lese- und Netzwerkfehler ergeben `null`, Abbruch und Ratenbegrenzung werden weitergereicht. */
    private suspend fun <T> part(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: RateLimitException) {
        throw e
    } catch (e: IOException) {
        null
    } catch (e: MarketParser.ParseException) {
        null
    } catch (e: StooqParser.ParseException) {
        null
    } catch (e: YahooParser.ParseException) {
        null
    } catch (e: CoinGeckoParser.ParseException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    private suspend fun get(url: String, maxBytes: Int = MAX_BYTES, truncate: Boolean = false, userAgent: String = USER_AGENT): String =
        withContext(Dispatchers.IO) {
            if (!url.startsWith("https://")) throw IOException("Nur HTTPS")
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("User-Agent", userAgent)
                connection.setRequestProperty("Accept", "application/json, text/csv, text/plain")
                val code = connection.responseCode
                if (code == 429) {
                    val wait = connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull() ?: 0L
                    throw RateLimitException(wait.coerceIn(0L, MAX_RETRY_AFTER_SECONDS))
                }
                if (code !in 200..299) throw IOException("HTTP $code")
                connection.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        if (out.size() > maxBytes) {
                            if (truncate) break
                            throw IOException("Antwort zu groß")
                        }
                    }
                    out.toString(Charsets.UTF_8.name())
                }
            } finally {
                connection.disconnect()
            }
        }

    companion object {
        const val HISTORY_BYTES = 96 * 1024
        const val KEEP_HISTORY_SECONDS = 31L * 24L * 3600L
        const val MAX_RETRY_AFTER_SECONDS = 900L
        private const val QUOTES_PARALLEL = 3
        private val STOOQ_DATE = DateTimeFormatter.BASIC_ISO_DATE
        private const val YAHOO_PARALLEL = 3
        private const val YAHOO_USER_AGENT = "Mozilla/5.0 (Linux; Android 14) MiriquidiLauncher/1.0"
        private const val MAX_BYTES = 1024 * 1024
        private const val TIMEOUT_MS = 12_000
        private const val USER_AGENT = "MiriquidiLauncher/1.0 (FOSS Android launcher; https://github.com/tux4us/MiriquidiLauncher)"
    }
}
