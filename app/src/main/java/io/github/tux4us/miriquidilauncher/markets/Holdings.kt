// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.markets

/**
 * Frühere Bestände (Bitcoin in BTC, Gold und Silber in Feinunze). Die Struktur wird nur noch gelesen, um alte
 * Einträge beim ersten Start in die Liste „Eigene Werte“ zu übernehmen.
 */
data class Holdings(val btc: Double? = null, val goldOz: Double? = null, val silverOz: Double? = null) {
    val isEmpty: Boolean get() = btc == null && goldOz == null && silverOz == null
}

/** Eingabe von Mengen und Lesen des alten Speicherformats; plattformunabhängig und auf der JVM testbar. */
object HoldingsLogic {
    /** Obergrenze einer Eingabe; schützt vor Überlauf bei der Rechnung und vor Tippfehlern. */
    const val MAX_AMOUNT = 1_000_000_000.0

    sealed interface Parsed {
        data class Ok(val value: Double) : Parsed
        data object Empty : Parsed
        data object Invalid : Parsed
    }

    /**
     * Liest eine Mengenangabe. Dezimaltrenner sind Komma und Punkt; Tausendertrenner werden nicht unterstützt,
     * um Eingaben wie „1.500“ nicht falsch zu deuten (gilt als 1,5). Null ist erlaubt, negative Werte nicht.
     */
    fun parseAmount(input: String): Parsed {
        val text = input.trim().replace(',', '.')
        if (text.isEmpty()) return Parsed.Empty
        if (!text.all { it.isDigit() || it == '.' } || text.count { it == '.' } > 1 || text == ".") return Parsed.Invalid
        val value = text.toDoubleOrNull() ?: return Parsed.Invalid
        if (value.isNaN() || value.isInfinite() || value > MAX_AMOUNT) return Parsed.Invalid
        return Parsed.Ok(value)
    }

    private const val SEPARATOR = '|'

    /** Altes Speicherformat `btc|gold|silber`; leere Teile bedeuten „nicht eingetragen“. */
    fun decode(text: String?): Holdings {
        if (text.isNullOrBlank()) return Holdings()
        val parts = text.split(SEPARATOR)
        if (parts.size != 3) return Holdings()
        fun part(index: Int): Double? =
            parts[index].toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() && it >= 0.0 && it <= MAX_AMOUNT }
        return Holdings(part(0), part(1), part(2))
    }
}
