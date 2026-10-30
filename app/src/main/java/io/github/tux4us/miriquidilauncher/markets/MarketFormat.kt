// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.markets

import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import kotlin.math.abs

/** Zahlenformatierung der Scope „Kurse“, abhängig von der Sprache. Reine Funktionen. */
object MarketFormat {

    /** Betrag in [currencyCode] (z. B. "USD", "EUR") mit [decimals] Nachkommastellen und Währungszeichen. */
    fun money(value: Double, currencyCode: String, locale: Locale, decimals: Int = 2): String {
        val format = NumberFormat.getCurrencyInstance(locale)
        format.currency = Currency.getInstance(currencyCode)
        format.minimumFractionDigits = decimals
        format.maximumFractionDigits = decimals
        format.roundingMode = RoundingMode.HALF_UP
        return format.format(value)
    }

    /**
     * Preis mit Währung. Ist [currencyCode] keine bekannte Währung oder fehlt er, steht die Zahl allein bzw. mit dem
     * Kürzel dahinter. Die Nachkommastellen richten sich nach der Größe des Preises, wenn [decimals] fehlt.
     */
    fun price(value: Double, currencyCode: String?, locale: Locale, decimals: Int? = null): String {
        val d = decimals ?: autoDecimals(value)
        if (currencyCode == null) return number(value, locale, d)
        return try {
            money(value, currencyCode, locale, d)
        } catch (e: IllegalArgumentException) {
            number(value, locale, d) + " " + currencyCode
        }
    }

    /** Nachkommastellen nach Größe: kleine Preise brauchen mehr Stellen. */
    fun autoDecimals(value: Double): Int = when {
        abs(value) >= 1000.0 -> 0
        abs(value) >= 10.0 -> 2
        abs(value) >= 0.1 -> 3
        else -> 4
    }

    /** Zahl mit Tausendertrennung und genau [decimals] Nachkommastellen. */
    fun number(value: Double, locale: Locale, decimals: Int = 0): String = decimal(locale, decimals, decimals).format(value)

    /** Zahl mit höchstens [maxDecimals] Nachkommastellen, ohne überflüssige Nullen am Ende. */
    fun trimmed(value: Double, locale: Locale, maxDecimals: Int): String = decimal(locale, 0, maxDecimals).format(value)

    /** Prozentwert mit Vorzeichen, z. B. "+1,2 %" bzw. "−0,4 %". */
    fun signedPercent(value: Double, locale: Locale): String {
        val sign = if (value > 0.05) "+" else if (value < -0.05) "−" else ""
        return sign + decimal(locale, 1, 1).format(abs(value)) + " %"
    }

    /** Zahlenformat der Sprache mit kaufmännischer Rundung (0,5 rundet auf). */
    private fun decimal(locale: Locale, minDecimals: Int, maxDecimals: Int): NumberFormat {
        val format = NumberFormat.getNumberInstance(locale)
        format.minimumFractionDigits = minDecimals
        format.maximumFractionDigits = maxDecimals
        format.roundingMode = RoundingMode.HALF_UP
        return format
    }

    /**
     * Große Zahl gekürzt, z. B. "1,64 Bio.". [suffixes] sind die Bezeichnungen für Tausend, Million, Milliarde und
     * Billion in dieser Reihenfolge (4 Einträge); Werte unter 1000 werden ungekürzt ausgegeben.
     */
    fun compact(value: Double, locale: Locale, suffixes: List<String>): String {
        require(suffixes.size >= 4) { "Vier Bezeichnungen erforderlich" }
        val a = abs(value)
        val (scaled, suffix) = when {
            a >= 1e12 -> value / 1e12 to suffixes[3]
            a >= 1e9 -> value / 1e9 to suffixes[2]
            a >= 1e6 -> value / 1e6 to suffixes[1]
            a >= 1e3 -> value / 1e3 to suffixes[0]
            else -> return number(value, locale, 0)
        }
        return number(scaled, locale, 2) + " " + suffix
    }

    private val HASH_UNITS = listOf("H/s", "kH/s", "MH/s", "GH/s", "TH/s", "PH/s", "EH/s", "ZH/s")

    /** Hashrate in der passenden Einheit, z. B. "943,2 EH/s". */
    fun hashrate(hashesPerSecond: Double, locale: Locale): String {
        var value = hashesPerSecond
        var unit = 0
        while (value >= 1000.0 && unit < HASH_UNITS.lastIndex) {
            value /= 1000.0
            unit++
        }
        return number(value, locale, if (unit == 0) 0 else 1) + " " + HASH_UNITS[unit]
    }

    private val SIZE_UNITS = listOf("B", "kB", "MB", "GB")

    /** Größe in Byte (Dezimalpräfixe) in passender Einheit, z. B. "39,5 MB". */
    fun bytes(bytes: Long, locale: Locale): String {
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1000.0 && unit < SIZE_UNITS.lastIndex) {
            value /= 1000.0
            unit++
        }
        return number(value, locale, if (unit == 0) 0 else 1) + " " + SIZE_UNITS[unit]
    }

    /** Menge in Bitcoin aus Satoshi mit [decimals] Nachkommastellen, z. B. "20.100.115,63 BTC". */
    fun btc(sats: Long, locale: Locale, decimals: Int = 2): String =
        number(sats.toDouble() / BitcoinMath.SATS_PER_BTC, locale, decimals) + " BTC"
}
