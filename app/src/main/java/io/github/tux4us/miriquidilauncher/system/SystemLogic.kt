// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.system

import kotlin.math.abs

/** Speicherwerte aus `/proc/meminfo` in Kilobyte (1 kB = 1024 Byte). */
data class MemInfo(
    val totalKb: Long,
    val availableKb: Long,
    val freeKb: Long,
    val cachedKb: Long,
    val buffersKb: Long,
    val swapTotalKb: Long,
    val swapFreeKb: Long,
) {
    val usedKb: Long get() = (totalKb - availableKb).coerceAtLeast(0)
    val swapUsedKb: Long get() = (swapTotalKb - swapFreeKb).coerceAtLeast(0)
    val usedFraction: Double get() = if (totalKb > 0) usedKb.toDouble() / totalKb else 0.0
}

object MemInfoParser {
    /** Liest den Text von `/proc/meminfo`; `null`, wenn `MemTotal` fehlt. Fehlt `MemAvailable`, wird es geschätzt. */
    fun parse(text: String): MemInfo? {
        val values = HashMap<String, Long>()
        for (line in text.lineSequence()) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val number = line.substring(colon + 1).trim().substringBefore(' ').toLongOrNull() ?: continue
            values[line.substring(0, colon).trim()] = number
        }
        val total = values["MemTotal"]?.takeIf { it > 0 } ?: return null
        val free = values["MemFree"] ?: 0L
        val buffers = values["Buffers"] ?: 0L
        val cached = values["Cached"] ?: 0L
        val available = values["MemAvailable"] ?: (free + buffers + cached)
        return MemInfo(
            totalKb = total,
            availableKb = available.coerceIn(0, total),
            freeKb = free,
            cachedKb = cached,
            buffersKb = buffers,
            swapTotalKb = values["SwapTotal"] ?: 0L,
            swapFreeKb = values["SwapFree"] ?: 0L,
        )
    }
}

/** Anzeige von Mengen und Verlaufsdaten. */
object SystemFormat {
    private val UNITS = listOf("B", "KB", "MB", "GB", "TB")

    /**
     * Bytes mit passender Einheit. [base] ist 1000 für Speicherplatz (wie in den Android-Einstellungen) oder 1024 für
     * Arbeitsspeicher. Eine Nachkommastelle ab MB, bei GB und mehr zwei unter 10.
     */
    fun bytes(value: Long, base: Int = 1000, decimalSeparator: Char = ','): String {
        var v = value.coerceAtLeast(0).toDouble()
        var unit = 0
        while (v >= base && unit < UNITS.lastIndex) {
            v /= base
            unit++
        }
        val text = when {
            unit == 0 -> v.toLong().toString()
            unit >= 3 && v < 10 -> String.format(java.util.Locale.ROOT, "%.2f", v)
            unit >= 2 -> String.format(java.util.Locale.ROOT, "%.1f", v)
            else -> String.format(java.util.Locale.ROOT, "%.0f", v)
        }
        return text.replace('.', decimalSeparator) + " " + UNITS[unit]
    }

    /** Neuer Verlauf mit [value] am Ende, auf höchstens [max] Werte gekürzt (älteste fallen weg). */
    fun <T> appendCapped(history: List<T>, value: T, max: Int): List<T> {
        val result = ArrayList<T>(history.size + 1)
        result.addAll(history)
        result.add(value)
        return if (result.size > max) result.subList(result.size - max, result.size).toList() else result
    }
}

/** Ein Messpunkt der Akku-Aufzeichnung. [tempTenthsC] ist die Akkutemperatur in Zehntelgrad, `null` wenn unbekannt. */
data class BatterySample(val timeMillis: Long, val level: Int, val tempTenthsC: Int?, val charging: Boolean)

/** Auswertung der Akku-Aufzeichnung. Raten sind Prozentpunkte des Akkustands pro Stunde. */
data class BatteryStatsSummary(
    val sampleCount: Int,
    val spanMillis: Long,
    val dischargePctPerHour: Double?,
    val chargePctPerHour: Double?,
    val chargeSessions: Int,
    val avgTempC: Double?,
    val maxTempC: Double?,
    val minLevel: Int?,
    val hoursRemaining: Double?,
)

object BatteryStatsLogic {
    const val MIN_INTERVAL_MS = 5 * 60_000L
    const val MAX_AGE_MS = 7 * 24 * 3_600_000L
    const val MAX_SAMPLES = 3000

    /** Zwischen zwei Messpunkten mit größerem Abstand ist der Verlauf unbekannt; solche Paare zählen nicht für Raten. */
    const val MAX_GAP_MS = 3 * 3_600_000L

    /** Soll [now] aufgezeichnet werden? Bei neuem Stand, geändertem Ladezustand oder nach [MIN_INTERVAL_MS]. */
    fun shouldRecord(last: BatterySample?, now: BatterySample): Boolean {
        if (last == null) return true
        if (now.timeMillis < last.timeMillis) return true
        return now.level != last.level || now.charging != last.charging || now.timeMillis - last.timeMillis >= MIN_INTERVAL_MS
    }

    /** Entfernt zu alte und überzählige Messpunkte. */
    fun prune(samples: List<BatterySample>, nowMillis: Long): List<BatterySample> {
        val recent = samples.filter { nowMillis - it.timeMillis <= MAX_AGE_MS }
        return if (recent.size > MAX_SAMPLES) recent.subList(recent.size - MAX_SAMPLES, recent.size) else recent
    }

    fun summarize(samples: List<BatterySample>): BatteryStatsSummary {
        if (samples.isEmpty()) return BatteryStatsSummary(0, 0, null, null, 0, null, null, null, null)
        val sorted = samples.sortedBy { it.timeMillis }
        var dischargePct = 0.0
        var dischargeMs = 0L
        var chargePct = 0.0
        var chargeMs = 0L
        var sessions = 0
        for (i in 1 until sorted.size) {
            val a = sorted[i - 1]
            val b = sorted[i]
            if (!a.charging && b.charging) sessions++
            val gap = b.timeMillis - a.timeMillis
            if (gap <= 0 || gap > MAX_GAP_MS) continue
            if (!a.charging && !b.charging && b.level <= a.level) {
                dischargePct += (a.level - b.level).toDouble()
                dischargeMs += gap
            } else if (a.charging && b.charging && b.level >= a.level) {
                chargePct += (b.level - a.level).toDouble()
                chargeMs += gap
            }
        }
        if (sorted.first().charging) sessions++
        val temps = sorted.mapNotNull { it.tempTenthsC }.map { it / 10.0 }
        val discharge = if (dischargeMs >= 30 * 60_000L) dischargePct / (dischargeMs / 3_600_000.0) else null
        val charge = if (chargeMs >= 10 * 60_000L) chargePct / (chargeMs / 3_600_000.0) else null
        val last = sorted.last()
        val remaining = if (discharge != null && discharge > 0.05 && !last.charging) last.level / discharge else null
        return BatteryStatsSummary(
            sampleCount = sorted.size,
            spanMillis = last.timeMillis - sorted.first().timeMillis,
            dischargePctPerHour = discharge,
            chargePctPerHour = charge,
            chargeSessions = sessions,
            avgTempC = if (temps.isEmpty()) null else temps.average(),
            maxTempC = temps.maxOrNull(),
            minLevel = sorted.minOf { it.level },
            hoursRemaining = remaining,
        )
    }
}

/** Textform der Akku-Aufzeichnung: eine Zeile je Messpunkt `Zeit,Stand,Temperatur,Laden` (Temperatur leer = unbekannt). */
object BatteryHistoryCodec {
    fun encode(samples: List<BatterySample>): String = buildString {
        for (s in samples) {
            append(s.timeMillis).append(',').append(s.level).append(',')
            append(s.tempTenthsC?.toString().orEmpty()).append(',').append(if (s.charging) 1 else 0).append('\n')
        }
    }

    fun decode(text: String?): List<BatterySample> {
        if (text.isNullOrEmpty()) return emptyList()
        val result = ArrayList<BatterySample>()
        for (line in text.lineSequence()) {
            val parts = line.split(',')
            if (parts.size != 4) continue
            val time = parts[0].toLongOrNull() ?: continue
            val level = parts[1].toIntOrNull()?.takeIf { it in 0..100 } ?: continue
            val temp = parts[2].toIntOrNull()?.takeIf { abs(it) < 2000 }
            val charging = when (parts[3]) {
                "1" -> true
                "0" -> false
                else -> continue
            }
            result += BatterySample(time, level, temp, charging)
            if (result.size >= BatteryStatsLogic.MAX_SAMPLES * 2) break
        }
        return result
    }
}
