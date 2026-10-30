// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.markets

/**
 * Wechselkurse. Gespeichert wird je Währung der Kurs für 1 Euro ([perEur]); jede Umrechnung zwischen zwei Währungen
 * läuft über den Euro. [atMillis] ist der Zeitpunkt des letzten erfolgreichen Abrufs.
 */
data class FxData(val perEur: Map<String, Double>, val atMillis: Long) {
    fun filledFrom(old: FxData?): FxData = if (old == null) this else copy(perEur = old.perEur + perEur)
}

object FxLogic {
    /** Währungen der Europäischen Zentralbank, die Frankfurter anbietet (Stand der Entwicklung). */
    val currencies: List<String> = listOf(
        "EUR", "USD", "GBP", "CHF", "JPY", "CAD", "AUD", "NZD", "SEK", "NOK", "DKK", "PLN", "CZK", "HUF", "RON", "BGN",
        "TRY", "CNY", "HKD", "SGD", "KRW", "INR", "IDR", "MYR", "PHP", "THB", "ILS", "ISK", "BRL", "MXN", "ZAR",
    )

    fun isSupported(code: String): Boolean = code in currencies

    /** Abruf-Adresse für den Kurs von 1 Euro in [code]. */
    fun rateUrl(code: String): String = "https://api.frankfurter.dev/v2/rate/eur/" + code.lowercase()

    /** [amount] von [from] nach [to]; `null`, wenn ein Kurs fehlt. */
    fun convert(amount: Double, from: String, to: String, perEur: Map<String, Double>): Double? {
        if (from == to) return amount
        val f = rate(from, perEur) ?: return null
        val t = rate(to, perEur) ?: return null
        return amount * t / f
    }

    /** Kurs für 1 Euro in [code]; Euro selbst ist 1. */
    fun rate(code: String, perEur: Map<String, Double>): Double? =
        if (code == "EUR") 1.0 else perEur[code]?.takeIf { it.isFinite() && it > 0.0 }

    /** Hauptwährung zu einer Notierung in Untereinheiten (z. B. GBp → GBP, USX → USD). */
    fun mainCurrency(code: String): String = when (code) {
        "GBp", "GBX" -> "GBP"
        "ZAc" -> "ZAR"
        "ILA" -> "ILS"
        "USX", "USc" -> "USD"
        else -> code.uppercase()
    }

    /** Teiler, mit dem ein Preis in Untereinheiten in die Hauptwährung umgerechnet wird (100 bei Pence/Cent). */
    fun divisor(code: String): Double = when (code) {
        "GBp", "GBX", "ZAc", "ILA", "USX", "USc" -> 100.0
        else -> 1.0
    }

    /** Hauptwährung und der umgerechnete Preis. */
    fun normalize(currency: String, price: Double): Pair<String, Double> = mainCurrency(currency) to price / divisor(currency)

    /** Eingabe eines Betrags: Komma oder Punkt als Dezimaltrenner, nicht negativ. */
    fun parseAmount(input: String): Double? = (HoldingsLogic.parseAmount(input) as? HoldingsLogic.Parsed.Ok)?.value
}
