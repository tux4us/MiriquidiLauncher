// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.markets

import io.github.tux4us.miriquidilauncher.weather.MiniJson
import io.github.tux4us.miriquidilauncher.weather.asArray
import io.github.tux4us.miriquidilauncher.weather.asDouble
import io.github.tux4us.miriquidilauncher.weather.asObject
import io.github.tux4us.miriquidilauncher.weather.asString

/**
 * Serialisiert den [MarketSnapshot] für den Offline-Zwischenspeicher als JSON-Text und liest ihn wieder. Ein
 * beschädigter oder fremder Text ergibt `null` statt eines Fehlers; der Stand wird dann neu geladen.
 */
object MarketCodec {
    private const val VERSION = 2

    fun encode(snapshot: MarketSnapshot): String {
        val sb = StringBuilder(2048)
        sb.append("{\"v\":").append(VERSION)
        snapshot.fast?.let { f ->
            sb.append(",\"fast\":{\"at\":").append(f.atMillis)
            f.price?.let { sb.append(",\"price\":[").append(num(it.usd)).append(',').append(num(it.eur)).append(',').append(it.timeSec).append(']') }
            f.tip?.let { t ->
                sb.append(",\"tip\":{\"h\":").append(t.height)
                t.timeSec?.let { sb.append(",\"t\":").append(it) }
                t.txCount?.let { sb.append(",\"tx\":").append(it) }
                sb.append('}')
            }
            f.fees?.let {
                sb.append(",\"fees\":[").append(num(it.fastest)).append(',').append(num(it.halfHour)).append(',')
                    .append(num(it.hour)).append(',').append(num(it.economy)).append(',').append(num(it.minimum)).append(']')
            }
            f.mempool?.let { sb.append(",\"mempool\":[").append(it.txCount).append(',').append(it.vsize).append(',').append(it.totalFeeSats).append(']') }
            sb.append('}')
        }
        snapshot.slow?.let { s ->
            sb.append(",\"slow\":{\"at\":").append(s.atMillis)
            s.difficulty?.let { d ->
                sb.append(",\"diff\":[").append(num(d.progressPercent)).append(',').append(num(d.expectedChangePercent)).append(',')
                    .append(d.estimatedRetargetMillis).append(',').append(d.remainingBlocks).append(',').append(d.nextRetargetHeight).append(',')
                    .append(d.previousChangePercent?.let { num(it) } ?: "null").append(']')
            }
            s.hashrate?.let { sb.append(",\"hash\":[").append(num(it.hashrate)).append(',').append(num(it.difficulty)).append(']') }
            s.lightning?.let { sb.append(",\"ln\":[").append(it.channels).append(',').append(it.nodes).append(',').append(it.capacitySats).append(']') }
            sb.append('}')
        }
        snapshot.history?.let { h ->
            sb.append(",\"hist\":{\"at\":").append(h.atMillis).append(",\"p\":[")
            h.points.forEachIndexed { i, p ->
                if (i > 0) sb.append(',')
                sb.append('[').append(p.timeSec).append(',').append(num(p.usd)).append(',').append(num(p.eur)).append(']')
            }
            sb.append("]}")
        }
        snapshot.quotes?.let { q ->
            sb.append(",\"quotes\":{\"at\":").append(q.atMillis).append(",\"q\":[")
            q.quotes.forEachIndexed { i, x ->
                if (i > 0) sb.append(',')
                sb.append("[\"").append(jsonText(x.id)).append("\",").append(num(x.price)).append(',')
                    .append(x.prevClose?.let { num(it) } ?: "null").append(",\"").append(jsonText(x.currency.orEmpty())).append("\",\"")
                    .append(jsonText(x.source)).append("\",[")
                x.closes.forEachIndexed { j, c ->
                    if (j > 0) sb.append(',')
                    sb.append(num(c))
                }
                sb.append("]]")
            }
            sb.append("]}")
        }
        snapshot.crypto?.let { c ->
            sb.append(",\"crypto\":{\"at\":").append(c.atMillis).append(",\"q\":[")
            c.quotes.forEachIndexed { i, x ->
                if (i > 0) sb.append(',')
                sb.append("[\"").append(jsonText(x.id)).append("\",").append(num(x.eur)).append(',').append(num(x.usd)).append(',')
                    .append(x.change24h?.let { num(it) } ?: "null").append(',')
                    .append(x.marketCapEur?.let { num(it) } ?: "null").append(']')
            }
            sb.append("]}")
        }
        snapshot.securities?.let { s ->
            sb.append(",\"sec\":{\"at\":").append(s.atMillis).append(",\"q\":[")
            s.quotes.forEachIndexed { i, x ->
                if (i > 0) sb.append(',')
                sb.append("[\"").append(jsonText(x.symbol)).append("\",").append(num(x.price)).append(",\"").append(jsonText(x.currency)).append("\"]")
            }
            sb.append("]}")
        }
        snapshot.fx?.let { f ->
            sb.append(",\"fx\":{\"at\":").append(f.atMillis).append(",\"r\":{")
            f.perEur.entries.forEachIndexed { i, e ->
                if (i > 0) sb.append(',')
                sb.append('"').append(jsonText(e.key)).append("\":").append(num(e.value))
            }
            sb.append("}}")
        }
        sb.append('}')
        return sb.toString()
    }

    fun decode(text: String?): MarketSnapshot? {
        if (text.isNullOrBlank()) return null
        return try {
            val root = MiniJson.parse(text).asObject() ?: return null
            if (root["v"].asDouble()?.toInt() != VERSION) return null
            MarketSnapshot(
                fast = root["fast"].asObject()?.let(::decodeFast),
                slow = root["slow"].asObject()?.let(::decodeSlow),
                history = root["hist"].asObject()?.let(::decodeHistory),
                quotes = root["quotes"].asObject()?.let(::decodeQuotes),
                crypto = root["crypto"].asObject()?.let(::decodeCrypto),
                securities = root["sec"].asObject()?.let(::decodeSecurities),
                fx = root["fx"].asObject()?.let(::decodeFx),
            )
        } catch (e: MiniJson.JsonException) {
            null
        } catch (e: RuntimeException) {
            null
        }
    }

    private fun decodeFast(o: Map<String, Any?>): BitcoinFast {
        val price = o["price"].asArray()?.let { a ->
            val usd = a.getOrNull(0).asDouble()
            val eur = a.getOrNull(1).asDouble()
            if (usd != null && eur != null) BitcoinPrice(usd, eur, a.getOrNull(2).asDouble()?.toLong() ?: 0L) else null
        }
        val tip = o["tip"].asObject()?.let { t ->
            t["h"].asDouble()?.let { TipBlock(it.toInt(), t["t"].asDouble()?.toLong(), t["tx"].asDouble()?.toInt()) }
        }
        val fees = o["fees"].asArray()?.let { a ->
            val v = a.map { it.asDouble() }
            if (v.size == 5 && v.all { it != null }) FeeLevels(v[0]!!, v[1]!!, v[2]!!, v[3]!!, v[4]!!) else null
        }
        val mempool = o["mempool"].asArray()?.let { a ->
            val v = a.map { it.asDouble() }
            if (v.size == 3 && v.all { it != null }) MempoolStats(v[0]!!.toLong(), v[1]!!.toLong(), v[2]!!.toLong()) else null
        }
        return BitcoinFast(price, tip, fees, mempool, o["at"].asDouble()?.toLong() ?: 0L)
    }

    private fun decodeSlow(o: Map<String, Any?>): BitcoinSlow {
        val difficulty = o["diff"].asArray()?.let { a ->
            if (a.size == 6 && a.take(5).all { it.asDouble() != null }) {
                DifficultyInfo(
                    progressPercent = a[0].asDouble()!!,
                    expectedChangePercent = a[1].asDouble()!!,
                    estimatedRetargetMillis = a[2].asDouble()!!.toLong(),
                    remainingBlocks = a[3].asDouble()!!.toInt(),
                    nextRetargetHeight = a[4].asDouble()!!.toInt(),
                    previousChangePercent = a[5].asDouble(),
                )
            } else {
                null
            }
        }
        val hashrate = o["hash"].asArray()?.let { a ->
            val rate = a.getOrNull(0).asDouble()
            val diff = a.getOrNull(1).asDouble()
            if (rate != null && diff != null) HashrateInfo(rate, diff) else null
        }
        val lightning = o["ln"].asArray()?.let { a ->
            val v = a.map { it.asDouble() }
            if (v.size == 3 && v.all { it != null }) LightningInfo(v[0]!!.toLong(), v[1]!!.toLong(), v[2]!!.toLong()) else null
        }
        return BitcoinSlow(difficulty, hashrate, lightning, o["at"].asDouble()?.toLong() ?: 0L)
    }

    private fun decodeHistory(o: Map<String, Any?>): BitcoinHistory {
        val points = o["p"].asArray().orEmpty().mapNotNull { entry ->
            val a = entry.asArray() ?: return@mapNotNull null
            val t = a.getOrNull(0).asDouble() ?: return@mapNotNull null
            val usd = a.getOrNull(1).asDouble() ?: return@mapNotNull null
            val eur = a.getOrNull(2).asDouble() ?: return@mapNotNull null
            PricePoint(t.toLong(), usd, eur)
        }
        return BitcoinHistory(points, o["at"].asDouble()?.toLong() ?: 0L)
    }

    private fun decodeQuotes(o: Map<String, Any?>): QuotesData {
        val list = o["q"].asArray().orEmpty().mapNotNull { entry ->
            val a = entry.asArray() ?: return@mapNotNull null
            val id = a.getOrNull(0).asString() ?: return@mapNotNull null
            val price = a.getOrNull(1).asDouble()?.takeIf { it > 0.0 } ?: return@mapNotNull null
            val closes = a.getOrNull(5).asArray().orEmpty().mapNotNull { it.asDouble()?.takeIf { v -> v > 0.0 && v.isFinite() } }
            Quote(
                id = id,
                price = price,
                prevClose = a.getOrNull(2).asDouble()?.takeIf { it > 0.0 },
                currency = a.getOrNull(3).asString()?.takeIf { it.isNotEmpty() },
                closes = closes,
                source = a.getOrNull(4).asString() ?: QuoteSource.YAHOO,
            )
        }
        return QuotesData(list, o["at"].asDouble()?.toLong() ?: 0L)
    }

    private fun decodeCrypto(o: Map<String, Any?>): CryptoData {
        val list = o["q"].asArray().orEmpty().mapNotNull { entry ->
            val a = entry.asArray() ?: return@mapNotNull null
            val id = a.getOrNull(0).asString() ?: return@mapNotNull null
            val eur = a.getOrNull(1).asDouble()?.takeIf { it > 0.0 } ?: return@mapNotNull null
            val usd = a.getOrNull(2).asDouble()?.takeIf { it > 0.0 } ?: return@mapNotNull null
            CryptoQuote(id, eur, usd, a.getOrNull(3).asDouble(), a.getOrNull(4).asDouble()?.takeIf { it > 0.0 })
        }
        return CryptoData(list, o["at"].asDouble()?.toLong() ?: 0L)
    }

    private fun decodeSecurities(o: Map<String, Any?>): SecuritiesData {
        val list = o["q"].asArray().orEmpty().mapNotNull { entry ->
            val a = entry.asArray() ?: return@mapNotNull null
            val symbol = a.getOrNull(0).asString() ?: return@mapNotNull null
            val price = a.getOrNull(1).asDouble()?.takeIf { it > 0.0 } ?: return@mapNotNull null
            val currency = a.getOrNull(2).asString() ?: return@mapNotNull null
            PositionQuote(symbol, price, currency)
        }
        return SecuritiesData(list, o["at"].asDouble()?.toLong() ?: 0L)
    }

    private fun decodeFx(o: Map<String, Any?>): FxData {
        val rates = HashMap<String, Double>()
        o["r"].asObject()?.forEach { (code, value) ->
            value.asDouble()?.takeIf { it > 0.0 && it.isFinite() }?.let { rates[code] = it }
        }
        return FxData(rates, o["at"].asDouble()?.toLong() ?: 0L)
    }

    /** Text für einen JSON-String: Anführungszeichen, Rückstriche und Steuerzeichen werden ersetzt. */
    private fun jsonText(text: String): String = text.map { c -> if (c == '"' || c == '\\' || c < ' ') ' ' else c }.joinToString("")

    /** Zahl als JSON-Text; nicht endliche Werte (nicht darstellbar) werden zu 0. */
    private fun num(value: Double): String = if (value.isFinite()) value.toString() else "0"
}
