// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.markets

import io.github.tux4us.miriquidilauncher.weather.MiniJson
import io.github.tux4us.miriquidilauncher.weather.asArray
import io.github.tux4us.miriquidilauncher.weather.asDouble
import io.github.tux4us.miriquidilauncher.weather.asObject

/** Art eines Krypto-Werts. */
enum class CryptoKind { Coin, Stablecoin }

/**
 * Ein Krypto-Wert. [id] ist die Kennung bei CoinGecko. Bei Stablecoins ist [peg] die Währung, an die der Wert
 * gebunden ist („USD“ oder „EUR“). Bitcoin wird von der Bitcoin-Quelle (mempool.space) geliefert, nicht von CoinGecko.
 */
data class CryptoDef(val id: String, val symbol: String, val name: String, val kind: CryptoKind, val peg: String? = null)

object CryptoCatalog {
    val ethereum = CryptoDef("ethereum", "ETH", "Ethereum", CryptoKind.Coin)

    val stablecoins: List<CryptoDef> = listOf(
        CryptoDef("tether", "USDT", "Tether", CryptoKind.Stablecoin, "USD"),
        CryptoDef("usd-coin", "USDC", "USD Coin", CryptoKind.Stablecoin, "USD"),
        CryptoDef("dai", "DAI", "Dai", CryptoKind.Stablecoin, "USD"),
        CryptoDef("ethena-usde", "USDe", "Ethena USDe", CryptoKind.Stablecoin, "USD"),
        CryptoDef("paypal-usd", "PYUSD", "PayPal USD", CryptoKind.Stablecoin, "USD"),
        CryptoDef("first-digital-usd", "FDUSD", "First Digital USD", CryptoKind.Stablecoin, "USD"),
        CryptoDef("euro-coin", "EURC", "Euro Coin", CryptoKind.Stablecoin, "EUR"),
    )

    /** Werte, die von CoinGecko abgerufen werden. */
    val fetched: List<CryptoDef> = listOf(ethereum) + stablecoins

    /** Werte, die als Eigene Werte eingetragen werden können (Bitcoin zusätzlich). */
    val bitcoin = CryptoDef("bitcoin", "BTC", "Bitcoin", CryptoKind.Coin)
    val selectable: List<CryptoDef> = listOf(bitcoin) + fetched

    fun byId(id: String): CryptoDef? = selectable.firstOrNull { it.id == id }
}

/** Kurs eines Krypto-Werts in Euro und US-Dollar. [change24h] in Prozent (Euro-Kurs), [marketCapEur] kann fehlen. */
data class CryptoQuote(
    val id: String,
    val eur: Double,
    val usd: Double,
    val change24h: Double?,
    val marketCapEur: Double?,
) {
    /**
     * Abweichung von der Bindung in Prozent: Kurs der Bindungswährung gegen 1. Positiv = über der Bindung. `null`
     * für Werte ohne Bindung.
     */
    fun pegDeviationPercent(def: CryptoDef): Double? {
        val price = when (def.peg) {
            "USD" -> usd
            "EUR" -> eur
            else -> return null
        }
        return (price - 1.0) * 100.0
    }
}

data class CryptoData(val quotes: List<CryptoQuote>, val atMillis: Long) {
    fun byId(id: String): CryptoQuote? = quotes.firstOrNull { it.id == id }

    fun filledFrom(old: CryptoData?): CryptoData {
        if (old == null) return this
        val fresh = quotes.map { it.id }.toSet()
        return copy(quotes = quotes + old.quotes.filter { it.id !in fresh })
    }
}

/** Darstellungszeitraum des Krypto-Diagramms (CoinGecko ohne Schlüssel: höchstens 365 Tage). */
enum class CryptoRange(val key: String, val days: Int) {
    Day("1d", 1),
    Week("1w", 7),
    Month("1m", 30),
    Year("1y", 365),
    ;

    companion object {
        fun fromKey(key: String?): CryptoRange = entries.firstOrNull { it.key == key } ?: Week
    }
}

/** Antworten von CoinGecko (öffentliche Schnittstelle ohne Schlüssel). */
object CoinGeckoParser {
    class ParseException(message: String) : Exception(message)

    const val BASE = "https://api.coingecko.com/api/v3"

    fun pricesUrl(ids: List<String>): String =
        "$BASE/simple/price?ids=" + ids.joinToString(",") +
            "&vs_currencies=eur,usd&include_market_cap=true&include_24hr_change=true"

    fun chartUrl(id: String, days: Int): String =
        "$BASE/coins/" + java.net.URLEncoder.encode(id, "UTF-8") + "/market_chart?vs_currency=eur&days=$days"

    /** Kurse aus `/simple/price`; Einträge ohne gültigen Euro- und Dollarkurs entfallen. */
    fun parsePrices(text: String): List<CryptoQuote> {
        val root = obj(text)
        // Bei Ratenbegrenzung kann der Dienst ein Objekt mit Statusmeldung statt der Kurse liefern.
        if (root["status"].asObject() != null) throw ParseException("Statusmeldung")
        return root.mapNotNull { (id, value) ->
            val o = value.asObject() ?: return@mapNotNull null
            val eur = o["eur"].asDouble()?.takeIf { it.isFinite() && it > 0.0 } ?: return@mapNotNull null
            val usd = o["usd"].asDouble()?.takeIf { it.isFinite() && it > 0.0 } ?: return@mapNotNull null
            CryptoQuote(
                id = id,
                eur = eur,
                usd = usd,
                change24h = o["eur_24h_change"].asDouble()?.takeIf { it.isFinite() },
                marketCapEur = o["eur_market_cap"].asDouble()?.takeIf { it.isFinite() && it > 0.0 },
            )
        }
    }

    /** Verlauf aus `/coins/<id>/market_chart`: Paare `[Millisekunden, Preis]`. */
    fun parseChart(text: String, nowMillis: Long = 0L): Series {
        val prices = obj(text)["prices"].asArray() ?: throw ParseException("Kein Verlauf")
        val t = ArrayList<Long>()
        val v = ArrayList<Double>()
        for (entry in prices) {
            val a = entry.asArray() ?: continue
            val ms = a.getOrNull(0).asDouble() ?: continue
            val price = a.getOrNull(1).asDouble()?.takeIf { it.isFinite() && it > 0.0 } ?: continue
            t += (ms / 1000.0).toLong()
            v += price
        }
        if (v.size < 2) throw ParseException("Zu wenige Kurse")
        val (tt, vv) = YahooParser.thin(t, v, YahooParser.MAX_SERIES_POINTS)
        return Series(tt, vv, null, "EUR", nowMillis)
    }

    private fun obj(text: String): Map<String, Any?> = try {
        MiniJson.parse(text).asObject() ?: throw ParseException("Objekt erwartet")
    } catch (e: MiniJson.JsonException) {
        throw ParseException("Antwort unlesbar")
    }
}
