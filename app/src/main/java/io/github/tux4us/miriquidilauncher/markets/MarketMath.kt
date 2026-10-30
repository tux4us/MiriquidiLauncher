// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.markets

import kotlin.math.abs

/** Feste Größen und abgeleitete Werte des Bitcoin-Protokolls. Alles wird lokal berechnet, kein Netzwerkzugriff. */
object BitcoinMath {
    const val SATS_PER_BTC = 100_000_000L
    const val HALVING_INTERVAL = 210_000
    const val INITIAL_SUBSIDY_SATS = 5_000_000_000L
    const val SECONDS_PER_BLOCK = 600L

    /** Typische Größe einer Transaktion (1 Eingang, 2 Ausgänge, SegWit) in vB für die Gebührenschätzung. */
    const val TYPICAL_TX_VBYTES = 140

    /** Blockbelohnung in Satoshi für einen Block der Höhe [height] (ohne Gebühren). */
    fun subsidySats(height: Int): Long {
        if (height < 0) return 0L
        val epoch = height / HALVING_INTERVAL
        return if (epoch >= 64) 0L else INITIAL_SUBSIDY_SATS shr epoch
    }

    /** Höhe des nächsten Blocks mit halbierter Belohnung, ausgehend von der Höhe [height] des letzten Blocks. */
    fun nextHalvingHeight(height: Int): Int = (height / HALVING_INTERVAL + 1) * HALVING_INTERVAL

    /** Anzahl der noch zu findenden Blöcke bis zum Block der nächsten Halbierung. */
    fun blocksUntilHalving(height: Int): Int = nextHalvingHeight(height) - height

    /** Fortschritt (0..1) innerhalb der aktuellen Halving-Periode. */
    fun epochProgress(height: Int): Double = (height % HALVING_INTERVAL) / HALVING_INTERVAL.toDouble()

    /** Geschätzter Zeitpunkt der nächsten Halbierung (Unix-Millisekunden) bei zehn Minuten je Block. */
    fun estimatedHalvingMillis(height: Int, nowMillis: Long): Long =
        nowMillis + blocksUntilHalving(height).toLong() * SECONDS_PER_BLOCK * 1000L

    /**
     * Theoretisch geschaffene Menge in Satoshi bis einschließlich Block [height] (Summe der Blockbelohnungen
     * einschließlich Genesis-Block). Tatsächlich nicht beanspruchte Belohnungen und verlorene Coins sind nicht
     * berücksichtigt.
     */
    fun supplySats(height: Int): Long {
        if (height < 0) return 0L
        var total = 0L
        var epoch = 0
        while (epoch < 64) {
            val start = epoch.toLong() * HALVING_INTERVAL
            if (start > height) break
            val count = minOf(HALVING_INTERVAL.toLong(), height - start + 1L)
            total += count * (INITIAL_SUBSIDY_SATS shr epoch)
            epoch++
        }
        return total
    }

    /** Satoshi je Einheit der Währung bei einem Kurs von [pricePerBtc]. */
    fun satsPerUnit(pricePerBtc: Double): Double = SATS_PER_BTC / pricePerBtc

    /** Wert in Währung für [sats] Satoshi bei einem Kurs von [pricePerBtc]. */
    fun fiatValue(sats: Double, pricePerBtc: Double): Double = sats / SATS_PER_BTC * pricePerBtc

    /** Gebühr in Währung für eine Transaktion von [vbytes] bei [satPerVbyte] sat/vB. */
    fun feeFiat(satPerVbyte: Double, vbytes: Int, pricePerBtc: Double): Double =
        fiatValue(satPerVbyte * vbytes, pricePerBtc)

    /** Marktkapitalisierung aus theoretischer Menge und Kurs. */
    fun marketCap(supplySats: Long, pricePerBtc: Double): Double = fiatValue(supplySats.toDouble(), pricePerBtc)
}

/** Auswertung des Kursverlaufs. */
object PriceSeries {
    /** Abstand (Sekunden), bis zu dem ein Verlaufspunkt noch als Wert für einen Zeitpunkt gilt. */
    const val MAX_GAP_SECONDS = 3L * 3600L

    /** Prozentuale Änderung von [past] auf [current]; `null`, wenn [past] nicht größer als 0 ist. */
    fun changePercent(current: Double, past: Double): Double? =
        if (past > 0.0 && current.isFinite()) (current - past) / past * 100.0 else null

    /** Verlaufspunkt, der [targetSec] am nächsten liegt, sofern er höchstens [maxGapSec] entfernt ist. */
    fun closest(points: List<PricePoint>, targetSec: Long, maxGapSec: Long = MAX_GAP_SECONDS): PricePoint? {
        val best = points.minByOrNull { abs(it.timeSec - targetSec) } ?: return null
        return if (abs(best.timeSec - targetSec) <= maxGapSec) best else null
    }

    /**
     * Änderung des Kurses in [currency] über [rangeSec] Sekunden bis zum aktuellen Kurs [current]
     * (Zeitpunkt [nowSec]); `null`, wenn der Verlauf keinen passenden Punkt enthält.
     */
    fun changeOver(
        points: List<PricePoint>,
        current: Double,
        nowSec: Long,
        rangeSec: Long,
        currency: ChartCurrency,
    ): Double? {
        val past = closest(points, nowSec - rangeSec) ?: return null
        return changePercent(current, value(past, currency))
    }

    /** Verlaufspunkte der letzten [seconds] Sekunden vor dem neuesten Punkt (für den Zwischenspeicher). */
    fun keepLast(points: List<PricePoint>, seconds: Long): List<PricePoint> {
        val newest = points.maxOfOrNull { it.timeSec } ?: return points
        return points.filter { it.timeSec >= newest - seconds }
    }

    fun value(point: PricePoint, currency: ChartCurrency): Double = when (currency) {
        ChartCurrency.Usd -> point.usd
        ChartCurrency.Eur -> point.eur
    }

    /**
     * Punkte der letzten [range] bis [nowSec] für das Diagramm, auf höchstens [maxPoints] Punkte verdünnt
     * (jeder n-te Punkt; der letzte bleibt erhalten).
     */
    fun window(points: List<PricePoint>, range: ChartRange, nowSec: Long, maxPoints: Int = 160): List<PricePoint> {
        val inRange = points.filter { it.timeSec >= nowSec - range.seconds && it.timeSec <= nowSec + MAX_GAP_SECONDS }
        if (inRange.size <= maxPoints || maxPoints < 2) return inRange
        val stride = (inRange.size + maxPoints - 1) / maxPoints
        val thinned = inRange.filterIndexed { index, _ -> index % stride == 0 }.toMutableList()
        val last = inRange.last()
        if (thinned.last() != last) thinned += last
        return thinned
    }
}

/** Umrechnungen für Edelmetalle. */
object MetalMath {
    const val GRAMS_PER_OUNCE = 31.1034768

    fun perGram(perOunce: Double): Double = perOunce / GRAMS_PER_OUNCE

    /** Preis in Euro aus Dollar und dem Kurs von 1 Euro in Dollar. */
    fun usdToEur(usd: Double, eurUsd: Double): Double = usd / eurUsd

    /** Wie viele Unzen Silber eine Unze Gold kostet; `null` bei ungültigen Werten. */
    fun goldSilverRatio(goldUsd: Double, silverUsd: Double): Double? =
        if (goldUsd > 0.0 && silverUsd > 0.0) goldUsd / silverUsd else null

    /** Wie viele Unzen Gold ein Bitcoin kostet. */
    fun bitcoinInGoldOunces(btcUsd: Double, goldUsd: Double): Double? =
        if (btcUsd > 0.0 && goldUsd > 0.0) btcUsd / goldUsd else null
}
