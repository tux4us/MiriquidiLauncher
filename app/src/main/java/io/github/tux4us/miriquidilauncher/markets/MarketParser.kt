// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.markets

import io.github.tux4us.miriquidilauncher.weather.MiniJson
import io.github.tux4us.miriquidilauncher.weather.asDouble
import io.github.tux4us.miriquidilauncher.weather.asObject
import io.github.tux4us.miriquidilauncher.weather.asString
import java.time.Instant

/**
 * Liest die Antworten von mempool.space (bzw. einer kompatiblen eigenen Instanz) und Frankfurter.
 * Reine Funktionen ohne Android-Bezug. Ein unlesbarer oder unvollständiger Aufbau führt zu [ParseException];
 * unbekannte zusätzliche Felder werden ignoriert.
 */
object MarketParser {
    class ParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** Höchstzahl der Kurspunkte aus dem Verlauf (stündlich, entspricht etwa 83 Tagen). */
    const val MAX_HISTORY_POINTS = 2000

    fun parsePrices(text: String): BitcoinPrice {
        val o = obj(text)
        val usd = o["USD"].asDouble()
        val eur = o["EUR"].asDouble()
        if (usd == null || eur == null || !valid(usd) || !valid(eur)) throw ParseException("Kurs fehlt")
        return BitcoinPrice(usd, eur, o["time"].asDouble()?.toLong() ?: 0L)
    }

    fun parseFees(text: String): FeeLevels {
        val o = obj(text)
        fun fee(key: String): Double = o[key].asDouble()?.takeIf { it >= 0.0 && it.isFinite() }
            ?: throw ParseException("Gebühr '$key' fehlt")
        return FeeLevels(fee("fastestFee"), fee("halfHourFee"), fee("hourFee"), fee("economyFee"), fee("minimumFee"))
    }

    fun parseMempool(text: String): MempoolStats {
        val o = obj(text)
        fun num(key: String): Long = o[key].asDouble()?.takeIf { it >= 0.0 && it.isFinite() }?.toLong()
            ?: throw ParseException("Mempool-Feld '$key' fehlt")
        return MempoolStats(num("count"), num("vsize"), num("total_fee"))
    }

    /** Antwort von `/api/blocks/tip/height`: eine Zahl als Text. */
    fun parseTipHeight(text: String): Int {
        val height = text.trim().toIntOrNull()
        if (height == null || height < 0) throw ParseException("Blockhöhe unlesbar")
        return height
    }

    /** Antwort von `/api/blocks/tip/hash`: 64 Hexzeichen. */
    fun parseTipHash(text: String): String {
        val hash = text.trim()
        if (hash.length != 64 || !hash.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            throw ParseException("Block-Kennung unlesbar")
        }
        return hash
    }

    /** Antwort von `/api/block/<Kennung>`: Höhe, Zeitstempel und Transaktionszahl. */
    fun parseBlock(text: String): TipBlock {
        val o = obj(text)
        val height = o["height"].asDouble()?.toInt() ?: throw ParseException("Blockhöhe fehlt")
        return TipBlock(
            height = height,
            timeSec = o["timestamp"].asDouble()?.toLong(),
            txCount = o["tx_count"].asDouble()?.toInt(),
        )
    }

    fun parseDifficulty(text: String): DifficultyInfo {
        val o = obj(text)
        fun num(key: String): Double = o[key].asDouble()?.takeIf { it.isFinite() }
            ?: throw ParseException("Difficulty-Feld '$key' fehlt")
        return DifficultyInfo(
            progressPercent = num("progressPercent"),
            expectedChangePercent = num("difficultyChange"),
            estimatedRetargetMillis = num("estimatedRetargetDate").toLong(),
            remainingBlocks = num("remainingBlocks").toInt(),
            nextRetargetHeight = num("nextRetargetHeight").toInt(),
            previousChangePercent = o["previousRetarget"].asDouble()?.takeIf { it.isFinite() },
        )
    }

    fun parseHashrate(text: String): HashrateInfo {
        val o = obj(text)
        val rate = o["currentHashrate"].asDouble()
        val difficulty = o["currentDifficulty"].asDouble()
        if (rate == null || difficulty == null || !valid(rate) || !valid(difficulty)) throw ParseException("Hashrate fehlt")
        return HashrateInfo(rate, difficulty)
    }

    fun parseLightning(text: String): LightningInfo {
        val latest = obj(text)["latest"].asObject() ?: throw ParseException("Lightning-Stand fehlt")
        fun num(key: String): Long = latest[key].asDouble()?.takeIf { it >= 0.0 && it.isFinite() }?.toLong()
            ?: throw ParseException("Lightning-Feld '$key' fehlt")
        return LightningInfo(num("channel_count"), num("node_count"), num("total_capacity"))
    }

    private val HISTORY_OBJECT = Regex("\\{[^{}]*\\}")
    private val HISTORY_TIME = Regex("\"time\"\\s*:\\s*(\\d+)")
    private val HISTORY_USD = Regex("\"USD\"\\s*:\\s*(\\d+(?:\\.\\d+)?)")
    private val HISTORY_EUR = Regex("\"EUR\"\\s*:\\s*(\\d+(?:\\.\\d+)?)")

    /**
     * Liest den stündlichen Kursverlauf aus `/api/v1/historical-price`. Die Antwort kann sehr lang sein und wird
     * beim Abruf nach einer Obergrenze von Bytes abgeschnitten; deshalb wird nicht als ganzes JSON, sondern Objekt für
     * Objekt gelesen, und ein abgeschnittenes letztes Objekt wird ignoriert. Das Ergebnis ist aufsteigend nach Zeit
     * sortiert, ohne doppelte Zeitpunkte und höchstens [MAX_HISTORY_POINTS] Punkte lang (die neuesten).
     */
    fun parseHistory(text: String): List<PricePoint> {
        val byTime = HashMap<Long, PricePoint>()
        for (match in HISTORY_OBJECT.findAll(text)) {
            val chunk = match.value
            val time = HISTORY_TIME.find(chunk)?.groupValues?.get(1)?.toLongOrNull() ?: continue
            val usd = HISTORY_USD.find(chunk)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            val eur = HISTORY_EUR.find(chunk)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            if (!valid(usd) || !valid(eur)) continue
            byTime[time] = PricePoint(time, usd, eur)
            if (byTime.size > MAX_HISTORY_POINTS * 4) break
        }
        val sorted = byTime.values.sortedBy { it.timeSec }
        return if (sorted.size > MAX_HISTORY_POINTS) sorted.takeLast(MAX_HISTORY_POINTS) else sorted
    }

    /** Kurs von 1 Euro in US-Dollar und Datum aus `https://api.frankfurter.dev/v2/rate/eur/usd`. */
    data class EuroRate(val usdPerEur: Double, val date: String?)

    fun parseEuroRate(text: String): EuroRate {
        val o = obj(text)
        val rate = o["rate"].asDouble()
        if (rate == null || !valid(rate)) throw ParseException("Euro-Kurs fehlt")
        return EuroRate(rate, o["date"].asString())
    }

    private fun obj(text: String): Map<String, Any?> {
        val value = try {
            MiniJson.parse(text)
        } catch (e: MiniJson.JsonException) {
            throw ParseException("Antwort unlesbar", e)
        }
        return value.asObject() ?: throw ParseException("Objekt erwartet")
    }

    private fun valid(value: Double) = value.isFinite() && value > 0.0
}
