// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.markets

import io.github.tux4us.miriquidilauncher.weather.MiniJson
import io.github.tux4us.miriquidilauncher.weather.asArray
import io.github.tux4us.miriquidilauncher.weather.asDouble
import io.github.tux4us.miriquidilauncher.weather.asObject
import io.github.tux4us.miriquidilauncher.weather.asString

/** Art einer eingegebenen Wertpapier-Kennung. */
enum class SecurityIdKind { Isin, Wkn }

/** Geprüfte Eingabe: [value] in Großbuchstaben. */
data class SecurityId(val kind: SecurityIdKind, val value: String) {
    /**
     * Suchbegriffe für die Suche, in der Reihenfolge der Wahrscheinlichkeit. Bei einer WKN folgt auf die WKN selbst die
     * daraus gebildete deutsche ISIN („DE000“ + WKN + Prüfziffer), die bei deutschen Wertpapieren passt.
     */
    fun searchTerms(): List<String> = when (kind) {
        SecurityIdKind.Isin -> listOf(value)
        SecurityIdKind.Wkn -> listOfNotNull(value, SecurityIds.germanIsinFromWkn(value))
    }
}

object SecurityIds {
    /** ISIN: zwei Buchstaben, neun Zeichen, Prüfziffer. WKN: sechs Zeichen aus Ziffern und Buchstaben. */
    fun parse(input: String): SecurityId? {
        val text = input.trim().uppercase().replace(" ", "").replace("-", "")
        if (text.length == 12 && text.take(2).all { it in 'A'..'Z' } && text.all { it in 'A'..'Z' || it in '0'..'9' }) {
            return if (checkDigit(text.take(11)) == text.last()) SecurityId(SecurityIdKind.Isin, text) else null
        }
        if (text.length == 6 && text.all { it in 'A'..'Z' || it in '0'..'9' }) return SecurityId(SecurityIdKind.Wkn, text)
        return null
    }

    fun germanIsinFromWkn(wkn: String): String? {
        if (wkn.length != 6) return null
        val body = "DE000$wkn"
        return body + (checkDigit(body) ?: return null)
    }

    /** Prüfziffer einer ISIN (Luhn über die in Ziffern umgesetzten Zeichen). */
    internal fun checkDigit(body: String): Char? {
        if (body.length != 11) return null
        val digits = StringBuilder()
        for (c in body) {
            when (c) {
                in '0'..'9' -> digits.append(c)
                in 'A'..'Z' -> digits.append(c - 'A' + 10)
                else -> return null
            }
        }
        var sum = 0
        var double = true
        for (i in digits.indices.reversed()) {
            var d = digits[i] - '0'
            if (double) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            double = !double
        }
        return '0' + (10 - sum % 10) % 10
    }
}

/** Ein Treffer der Wertpapiersuche. */
data class SecurityHit(val symbol: String, val name: String, val exchange: String?, val type: String?)

/** Aktueller Kurs eines Wertpapiers in seiner Handelswährung. */
data class SecurityQuote(val symbol: String, val price: Double, val currency: String, val name: String?)

/** Art eines Werts im Bestand des Nutzers. */
enum class PositionKind(val key: String) {
    Stock("s"),
    Commodity("c"),
    Crypto("x"),
    ;

    companion object {
        fun fromKey(key: String?): PositionKind = entries.firstOrNull { it.key == key } ?: Stock
    }
}

/**
 * Ein Wert im Bestand des Nutzers. [symbol] ist bei Aktien das Yahoo-Symbol, bei Rohstoffen die Kennung aus dem
 * [QuoteCatalog] (z. B. „gold“), bei Krypto die Kennung aus dem [CryptoCatalog] (z. B. „bitcoin“). [ident] ist bei Aktien
 * die eingegebene WKN/ISIN. [quantity] ist die Stückzahl, bei Rohstoffen die Menge in der Handelseinheit
 * (z. B. Unzen), bei Krypto die Menge der Einheit.
 */
data class Position(
    val symbol: String,
    val name: String,
    val ident: String,
    val quantity: Double,
    val kind: PositionKind = PositionKind.Stock,
)

/** Zuletzt abgerufener Kurs eines Bestands-Wertpapiers, bereits in Hauptwährung ([currency], z. B. GBP statt GBp). */
data class PositionQuote(val symbol: String, val price: Double, val currency: String)

data class SecuritiesData(val quotes: List<PositionQuote>, val atMillis: Long) {
    fun filledFrom(old: SecuritiesData?): SecuritiesData {
        if (old == null) return this
        val fresh = quotes.map { it.symbol }.toSet()
        return copy(quotes = quotes + old.quotes.filter { it.symbol !in fresh })
    }
}

/** Antworten von Yahoo Finance (inoffizielle Schnittstelle) lesen. */
object YahooParser {
    class ParseException(message: String) : Exception(message)

    private val TYPES = setOf("EQUITY", "ETF", "MUTUALFUND")

    fun searchUrl(term: String): String =
        "https://query2.finance.yahoo.com/v1/finance/search?quotesCount=8&newsCount=0&listsCount=0&q=" +
            java.net.URLEncoder.encode(term, "UTF-8")

    fun chartUrl(symbol: String, range: String = "1d", interval: String = "1d"): String =
        "https://query1.finance.yahoo.com/v8/finance/chart/" + java.net.URLEncoder.encode(symbol, "UTF-8") +
            "?range=" + range + "&interval=" + interval

    fun parseSearch(text: String): List<SecurityHit> {
        val root = obj(text)
        return root["quotes"].asArray().orEmpty().mapNotNull { entry ->
            val o = entry.asObject() ?: return@mapNotNull null
            val symbol = o["symbol"].asString()?.takeIf { it.isNotBlank() && it.length <= 24 } ?: return@mapNotNull null
            val type = o["quoteType"].asString()
            if (type != null && type !in TYPES) return@mapNotNull null
            val name = (o["longname"].asString() ?: o["shortname"].asString() ?: symbol).take(80)
            SecurityHit(symbol, name, o["exchDisp"].asString() ?: o["exchange"].asString(), type)
        }
    }

    fun parseChart(text: String): SecurityQuote {
        val root = obj(text)
        val result = root["chart"].asObject()?.get("result").asArray()?.firstOrNull().asObject()
            ?: throw ParseException("Kein Ergebnis")
        val meta = result["meta"].asObject() ?: throw ParseException("Keine Meta-Daten")
        val price = meta["regularMarketPrice"].asDouble()?.takeIf { it.isFinite() && it > 0.0 }
            ?: throw ParseException("Kein Kurs")
        val currency = meta["currency"].asString()?.takeIf { it.length in 3..4 } ?: throw ParseException("Keine Währung")
        val symbol = meta["symbol"].asString() ?: throw ParseException("Kein Symbol")
        return SecurityQuote(symbol, price, currency, meta["longName"].asString() ?: meta["shortName"].asString())
    }

    /** Höchstzahl der Punkte einer Zeitreihe; darüber wird gleichmäßig verdünnt (der letzte Punkt bleibt). */
    const val MAX_SERIES_POINTS = 200

    /**
     * Zeitreihe aus einer Chart-Antwort. Einträge ohne Kurs (Yahoo liefert `null` für Zeiten ohne Handel) entfallen.
     * [Series.prevClose] ist der Schlusskurs vor dem Zeitraum (`chartPreviousClose`), sofern angegeben.
     */
    fun parseSeries(text: String, nowMillis: Long = 0L): Series {
        val result = firstResult(text)
        val meta = result["meta"].asObject() ?: throw ParseException("Keine Meta-Daten")
        val times = result["timestamp"].asArray().orEmpty()
        val closes = result["indicators"].asObject()?.get("quote").asArray()?.firstOrNull().asObject()?.get("close").asArray()
            ?: throw ParseException("Keine Kurse")
        val t = ArrayList<Long>()
        val v = ArrayList<Double>()
        for (i in 0 until minOf(times.size, closes.size)) {
            val time = times[i].asDouble()?.toLong() ?: continue
            val close = closes[i].asDouble()?.takeIf { it.isFinite() && it > 0.0 } ?: continue
            t += time
            v += close
        }
        if (v.size < 2) throw ParseException("Zu wenige Kurse")
        val (tt, vv) = thin(t, v, MAX_SERIES_POINTS)
        val rawCurrency = meta["currency"].asString()?.takeIf { it.length in 3..4 }
        val div = rawCurrency?.let { FxLogic.divisor(it) } ?: 1.0
        val prev = meta["chartPreviousClose"].asDouble()?.takeIf { it.isFinite() && it > 0.0 }?.div(div)
        return Series(tt, vv.map { it / div }, prev, rawCurrency?.let { FxLogic.mainCurrency(it) }, nowMillis)
    }

    /**
     * Kurs aus einer Tagesreihe (Zeitraum ein Monat): aktueller Kurs aus den Meta-Daten, Vortagesschluss als
     * vorletzter Schlusskurs der Reihe. Fehlt die Reihe, dienen `previousClose` bzw. `chartPreviousClose`.
     */
    fun parseQuote(id: String, text: String): Quote {
        val result = firstResult(text)
        val meta = result["meta"].asObject() ?: throw ParseException("Keine Meta-Daten")
        val closes = result["indicators"].asObject()?.get("quote").asArray()?.firstOrNull().asObject()?.get("close").asArray()
            .orEmpty().mapNotNull { it.asDouble()?.takeIf { v -> v.isFinite() && v > 0.0 } }
        val price = meta["regularMarketPrice"].asDouble()?.takeIf { it.isFinite() && it > 0.0 }
            ?: closes.lastOrNull() ?: throw ParseException("Kein Kurs")
        // Der letzte Eintrag der Reihe ist die laufende Sitzung; der Vortagesschluss ist der davor.
        val prev = closes.dropLast(1).lastOrNull()
            ?: meta["previousClose"].asDouble()?.takeIf { it > 0.0 }
            ?: meta["chartPreviousClose"].asDouble()?.takeIf { it > 0.0 }
        val tail = closes.takeLast(StooqParser.CLOSES_KEPT).toMutableList()
        if (tail.isEmpty() || tail.last() != price) tail += price
        val rawCurrency = meta["currency"].asString()?.takeIf { it.length in 3..4 }
        val div = rawCurrency?.let { FxLogic.divisor(it) } ?: 1.0
        return Quote(
            id = id,
            price = price / div,
            prevClose = prev?.div(div),
            currency = rawCurrency?.let { FxLogic.mainCurrency(it) },
            closes = tail.map { it / div },
            source = QuoteSource.YAHOO,
        )
    }

    /** Verdünnt gleichmäßig auf höchstens [max] Punkte; der letzte bleibt erhalten. */
    internal fun thin(times: List<Long>, values: List<Double>, max: Int): Pair<List<Long>, List<Double>> {
        if (values.size <= max) return times to values
        val stride = (values.size + max - 1) / max
        val t = ArrayList<Long>()
        val v = ArrayList<Double>()
        for (i in values.indices) {
            if (i % stride == 0 || i == values.size - 1) {
                t += times[i]
                v += values[i]
            }
        }
        return t to v
    }

    private fun firstResult(text: String): Map<String, Any?> {
        val root = obj(text)
        val chart = root["chart"].asObject() ?: throw ParseException("Kein Chart")
        return chart["result"].asArray()?.firstOrNull().asObject() ?: throw ParseException("Kein Ergebnis")
    }

    private fun obj(text: String): Map<String, Any?> = try {
        MiniJson.parse(text).asObject() ?: throw ParseException("Objekt erwartet")
    } catch (e: MiniJson.JsonException) {
        throw ParseException("Antwort unlesbar")
    }
}

/** Wert eines Bestandseintrags; Preis und Wert fehlen, solange kein Kurs bzw. Wechselkurs vorliegt. */
data class PositionValue(val position: Position, val price: Double?, val currency: String?, val valueEur: Double?)

data class PositionsValuation(val items: List<PositionValue>, val totalEur: Double?, val complete: Boolean)

/**
 * Kurse, mit denen der Bestand bewertet wird. [perEur] sind die Wechselkurse (1 Euro in Währung), [stocks] die Kurse
 * der Aktien, [commodities] die Rohstoffkurse, [cryptoEur] die Krypto-Preise in Euro je Kennung.
 */
data class AssetPrices(
    val perEur: Map<String, Double>,
    val stocks: List<PositionQuote>,
    val commodities: QuotesData?,
    val cryptoEur: Map<String, Double>,
)

/** Eingabe, Speicherformat und Bewertung des Bestands; reine Funktionen. */
object PositionsLogic {
    const val MAX_POSITIONS = 100

    fun encode(positions: List<Position>): String {
        val sb = StringBuilder("[")
        positions.forEachIndexed { i, p ->
            if (i > 0) sb.append(',')
            sb.append("{\"s\":").append(quote(p.symbol)).append(",\"n\":").append(quote(p.name))
                .append(",\"i\":").append(quote(p.ident)).append(",\"q\":").append(p.quantity)
                .append(",\"k\":").append(quote(p.kind.key)).append('}')
        }
        return sb.append(']').toString()
    }

    fun decode(text: String?): List<Position> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            MiniJson.parse(text).asArray().orEmpty().mapNotNull { entry ->
                val o = entry.asObject() ?: return@mapNotNull null
                val symbol = o["s"].asString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val quantity = o["q"].asDouble()?.takeIf { it.isFinite() && it > 0.0 && it <= HoldingsLogic.MAX_AMOUNT }
                    ?: return@mapNotNull null
                // Einträge ohne Art stammen aus früheren Ständen und sind Aktien.
                Position(symbol, o["n"].asString() ?: symbol, o["i"].asString().orEmpty(), quantity, PositionKind.fromKey(o["k"].asString()))
            }.take(MAX_POSITIONS)
        } catch (e: MiniJson.JsonException) {
            emptyList()
        }
    }

    /**
     * Übernimmt die früheren Bestände (BTC, Gold, Silber) als Einträge, soweit dafür noch kein Eintrag besteht.
     * Die Beträge werden addiert, nicht ersetzt, falls die Kennung bereits vorhanden ist.
     */
    fun migrate(positions: List<Position>, legacy: Holdings): List<Position> {
        var result = positions
        fun put(symbol: String, name: String, kind: PositionKind, amount: Double?) {
            if (amount == null || amount <= 0.0) return
            result = add(result, Position(symbol, name, "", amount, kind)) ?: result
        }
        put("bitcoin", "Bitcoin", PositionKind.Crypto, legacy.btc)
        put("gold", "Gold", PositionKind.Commodity, legacy.goldOz)
        put("silver", "Silver", PositionKind.Commodity, legacy.silverOz)
        return result
    }

    /** Fügt hinzu; dieselbe Kennung der gleichen Art erhöht die Menge. `null`, wenn die Obergrenze erreicht ist. */
    fun add(positions: List<Position>, added: Position): List<Position>? {
        val existing = positions.indexOfFirst { it.symbol == added.symbol && it.kind == added.kind }
        if (existing >= 0) {
            val sum = (positions[existing].quantity + added.quantity).coerceAtMost(HoldingsLogic.MAX_AMOUNT)
            return positions.toMutableList().also { it[existing] = it[existing].copy(quantity = sum) }
        }
        if (positions.size >= MAX_POSITIONS) return null
        return positions + added
    }

    fun remove(positions: List<Position>, kind: PositionKind, symbol: String): List<Position> =
        positions.filterNot { it.symbol == symbol && it.kind == kind }

    /** Setzt die Menge; 0 entfernt den Eintrag. */
    fun setQuantity(positions: List<Position>, kind: PositionKind, symbol: String, quantity: Double): List<Position> =
        if (quantity <= 0.0) {
            remove(positions, kind, symbol)
        } else {
            positions.map { if (it.symbol == symbol && it.kind == kind) it.copy(quantity = quantity.coerceAtMost(HoldingsLogic.MAX_AMOUNT)) else it }
        }

    /** Kurs und Währung eines Eintrags; `null`, wenn (noch) kein Kurs vorliegt. */
    fun priceOf(p: Position, prices: AssetPrices): Pair<Double, String>? = when (p.kind) {
        PositionKind.Stock -> prices.stocks.firstOrNull { it.symbol == p.symbol }?.let { it.price to it.currency }
        PositionKind.Commodity -> prices.commodities?.byId(p.symbol)?.let { q ->
            val (cur, price) = FxLogic.normalize(q.currency ?: "USD", q.price)
            price to cur
        }
        PositionKind.Crypto -> prices.cryptoEur[p.symbol]?.takeIf { it > 0.0 }?.let { it to "EUR" }
    }

    fun value(positions: List<Position>, prices: AssetPrices): PositionsValuation {
        val items = positions.map { p ->
            val priced = priceOf(p, prices)
            val eur = priced?.let { (price, currency) -> FxLogic.convert(p.quantity * price, currency, "EUR", prices.perEur) }
            PositionValue(p, priced?.first, priced?.second, eur)
        }
        val complete = items.all { it.valueEur != null }
        val total = if (items.isEmpty() || !complete) null else items.sumOf { it.valueEur!! }
        return PositionsValuation(items, total, complete)
    }

    /** Währungen, in denen die Kurse der Einträge vorliegen und für die ein Wechselkurs nötig ist. */
    fun currenciesNeeded(positions: List<Position>, prices: AssetPrices): Set<String> =
        positions.mapNotNull { priceOf(it, prices)?.second }.filter { it != "EUR" }.toSet()

    private fun quote(text: String): String {
        val sb = StringBuilder("\"")
        for (c in text) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c < ' ' -> sb.append(' ')
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
