// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.markets

/** Bitcoin-Kurs in US-Dollar und Euro; [timeSec] ist der Zeitstempel der Quelle (Unix-Sekunden). */
data class BitcoinPrice(val usd: Double, val eur: Double, val timeSec: Long)

/** Empfohlene Gebühren in sat/vB für die vier Bestätigungsziele. */
data class FeeLevels(
    val fastest: Double,
    val halfHour: Double,
    val hour: Double,
    val economy: Double,
    val minimum: Double,
)

/** Stand des Mempools: Anzahl Transaktionen, virtuelle Größe in vB, Summe der Gebühren in Satoshi. */
data class MempoolStats(val txCount: Long, val vsize: Long, val totalFeeSats: Long)

/** Letzter bekannter Block. [timeSec] und [txCount] fehlen, wenn nur die Höhe abrufbar war. */
data class TipBlock(val height: Int, val timeSec: Long?, val txCount: Int?)

/** Stand der Difficulty-Anpassung (Zeitraum von 2016 Blöcken). */
data class DifficultyInfo(
    val progressPercent: Double,
    val expectedChangePercent: Double,
    val estimatedRetargetMillis: Long,
    val remainingBlocks: Int,
    val nextRetargetHeight: Int,
    val previousChangePercent: Double?,
)

/** Hashrate in Hash pro Sekunde und aktuelle Difficulty. */
data class HashrateInfo(val hashrate: Double, val difficulty: Double)

/** Lightning-Netzwerk: Kanäle, Knoten und Gesamtkapazität in Satoshi. */
data class LightningInfo(val channels: Long, val nodes: Long, val capacitySats: Long)

/** Ein Kurspunkt aus dem Verlauf (stündlich). */
data class PricePoint(val timeSec: Long, val usd: Double, val eur: Double)

/** Bitcoin-Daten, die bei jedem Abruf aktualisiert werden. Einzelne Teile können fehlen. */
data class BitcoinFast(
    val price: BitcoinPrice?,
    val tip: TipBlock?,
    val fees: FeeLevels?,
    val mempool: MempoolStats?,
    val atMillis: Long,
) {
    /** Übernimmt für fehlende Teile die Werte des früheren Standes [old]. */
    fun filledFrom(old: BitcoinFast?): BitcoinFast = if (old == null) this else copy(
        price = price ?: old.price,
        tip = tip ?: old.tip,
        fees = fees ?: old.fees,
        mempool = mempool ?: old.mempool,
    )
}

/** Langsam veränderliche Bitcoin-Daten (Difficulty, Hashrate, Lightning); werden seltener abgerufen. */
data class BitcoinSlow(
    val difficulty: DifficultyInfo?,
    val hashrate: HashrateInfo?,
    val lightning: LightningInfo?,
    val atMillis: Long,
) {
    fun filledFrom(old: BitcoinSlow?): BitcoinSlow = if (old == null) this else copy(
        difficulty = difficulty ?: old.difficulty,
        hashrate = hashrate ?: old.hashrate,
        lightning = lightning ?: old.lightning,
    )
}

/** Stündlicher Kursverlauf, aufsteigend nach Zeit. */
data class BitcoinHistory(val points: List<PricePoint>, val atMillis: Long)

/** Gesamter zwischengespeicherter Stand der Scope „Kurse“. */
data class MarketSnapshot(
    val fast: BitcoinFast? = null,
    val slow: BitcoinSlow? = null,
    val history: BitcoinHistory? = null,
    val quotes: QuotesData? = null,
    val crypto: CryptoData? = null,
    val securities: SecuritiesData? = null,
    val fx: FxData? = null,
) {
    /** Euro-Kurs in US-Dollar (1 Euro in USD): aus den Wechselkursen, sonst aus dem Bitcoin-Preis in beiden Währungen. */
    fun eurUsd(): Double? = fx?.perEur?.get("USD")?.takeIf { it > 0.0 }
        ?: fast?.price?.let { if (it.eur > 0.0) it.usd / it.eur else null }

    /** Kurse für die Bewertung von „Eigene Werte“. Bitcoin kommt von der Bitcoin-Quelle, die übrigen Werte von CoinGecko. */
    fun assetPrices(): AssetPrices {
        val rates = fx?.perEur.orEmpty().toMutableMap()
        if ("USD" !in rates) eurUsd()?.let { rates["USD"] = it }
        val crypto = HashMap<String, Double>()
        this.crypto?.quotes?.forEach { crypto[it.id] = it.eur }
        fast?.price?.eur?.takeIf { it > 0.0 }?.let { crypto["bitcoin"] = it }
        return AssetPrices(rates, securities?.quotes.orEmpty(), quotes, crypto)
    }
}

/** Darstellungszeitraum des Kursdiagramms. */
enum class ChartRange(val seconds: Long, val key: String) {
    Day(24L * 3600L, "24h"),
    Week(7L * 24L * 3600L, "7d"),
    Month(30L * 24L * 3600L, "30d"),
    ;

    companion object {
        fun fromKey(key: String?): ChartRange = entries.firstOrNull { it.key == key } ?: Day
    }
}

/** Währung des Kursdiagramms. */
enum class ChartCurrency(val key: String, val code: String) {
    Usd("usd", "USD"),
    Eur("eur", "EUR"),
    ;

    companion object {
        fun fromKey(key: String?): ChartCurrency = entries.firstOrNull { it.key == key } ?: Eur
    }
}

/** Auswahl und Zeitraum der Diagramme von Indizes, Rohstoffen und Ethereum. */
data class ChartChoice(
    val indexId: String = "dax",
    val indexRange: SeriesRange = SeriesRange.Month,
    val commodityId: String = "gold",
    val commodityRange: SeriesRange = SeriesRange.Month,
    val cryptoRange: CryptoRange = CryptoRange.Week,
)

/** Fehlerart beim Abruf. [KEY] = Stooq lehnt den eingetragenen Schlüssel ab. */
enum class MarketError { NETWORK, RATE_LIMIT, DATA, KEY }
