// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.search

/** Ergebnis einer Einheitenumrechnung: Eingabewert und -einheit, Zielwert und -einheit (Anzeigenamen). */
data class Conversion(val value: Double, val fromLabel: String, val result: Double, val toLabel: String)

/**
 * Einheitenumrechnung für das Suchfeld, z. B. "5 km in mi", "20 c nach f", "3 lb kg". Unterstützt Länge, Masse,
 * Volumen, Temperatur, Geschwindigkeit, Fläche und Zeit. Reine Funktionen ohne Android-Bezug.
 */
object UnitConverter {
    private enum class Category { LENGTH, MASS, VOLUME, TEMPERATURE, SPEED, AREA, TIME }

    /** @property factor Faktor zur Basiseinheit der Kategorie (Meter, Gramm, Liter, m/s, m², Sekunde). */
    private class Unit(val label: String, val category: Category, val factor: Double)

    private val units: Map<String, Unit> = buildMap {
        fun add(unit: Unit, vararg names: String) = names.forEach { put(it, unit) }
        // Länge
        add(Unit("mm", Category.LENGTH, 0.001), "mm", "millimeter")
        add(Unit("cm", Category.LENGTH, 0.01), "cm", "zentimeter", "centimeter")
        add(Unit("m", Category.LENGTH, 1.0), "m", "meter", "metre")
        add(Unit("km", Category.LENGTH, 1000.0), "km", "kilometer")
        add(Unit("in", Category.LENGTH, 0.0254), "in", "inch", "inches", "zoll", "\"")
        add(Unit("ft", Category.LENGTH, 0.3048), "ft", "foot", "feet", "fuss", "fuß")
        add(Unit("yd", Category.LENGTH, 0.9144), "yd", "yard", "yards")
        add(Unit("mi", Category.LENGTH, 1609.344), "mi", "mile", "miles", "meile", "meilen")
        add(Unit("nmi", Category.LENGTH, 1852.0), "nm", "nmi", "seemeile", "seemeilen")
        // Masse
        add(Unit("mg", Category.MASS, 0.001), "mg", "milligramm")
        add(Unit("g", Category.MASS, 1.0), "g", "gramm", "gram")
        add(Unit("kg", Category.MASS, 1000.0), "kg", "kilogramm", "kilo")
        add(Unit("t", Category.MASS, 1_000_000.0), "t", "tonne", "tonnen")
        add(Unit("oz", Category.MASS, 28.349523125), "oz", "unze", "unzen", "ounce", "ounces")
        add(Unit("lb", Category.MASS, 453.59237), "lb", "lbs", "pfund", "pound", "pounds")
        // Volumen
        add(Unit("ml", Category.VOLUME, 0.001), "ml", "milliliter")
        add(Unit("cl", Category.VOLUME, 0.01), "cl", "zentiliter")
        add(Unit("l", Category.VOLUME, 1.0), "l", "liter", "litre")
        add(Unit("gal (US)", Category.VOLUME, 3.785411784), "gal", "gallone", "gallonen", "gallon")
        add(Unit("fl oz (US)", Category.VOLUME, 0.0295735295625), "floz", "fl.oz", "flozus")
        // Temperatur (Faktor unbenutzt)
        add(Unit("°C", Category.TEMPERATURE, 1.0), "c", "°c", "grad", "celsius")
        add(Unit("°F", Category.TEMPERATURE, 1.0), "f", "°f", "fahrenheit")
        add(Unit("K", Category.TEMPERATURE, 1.0), "k", "kelvin")
        // Geschwindigkeit
        add(Unit("m/s", Category.SPEED, 1.0), "m/s", "ms")
        add(Unit("km/h", Category.SPEED, 1000.0 / 3600.0), "km/h", "kmh", "kph")
        add(Unit("mph", Category.SPEED, 0.44704), "mph")
        add(Unit("kn", Category.SPEED, 1852.0 / 3600.0), "kn", "kt", "knoten", "knot", "knots")
        // Fläche
        add(Unit("m²", Category.AREA, 1.0), "m2", "m²", "qm")
        add(Unit("km²", Category.AREA, 1_000_000.0), "km2", "km²", "qkm")
        add(Unit("ha", Category.AREA, 10_000.0), "ha", "hektar")
        add(Unit("acre", Category.AREA, 4046.8564224), "acre", "acres")
        // Zeit
        add(Unit("s", Category.TIME, 1.0), "s", "sek", "sekunde", "sekunden", "sec")
        add(Unit("min", Category.TIME, 60.0), "min", "minute", "minuten")
        add(Unit("h", Category.TIME, 3600.0), "h", "std", "stunde", "stunden", "hour", "hours")
        add(Unit("d", Category.TIME, 86400.0), "d", "tag", "tage", "tagen", "day", "days")
    }

    private val PATTERN = Regex(
        "^([-+]?\\d+(?:[.,]\\d+)?)\\s*([^\\s\\d][^\\s]*)\\s+(?:(?:in|to|nach|zu|=>|->|=)\\s+)?([^\\s]+)$",
        RegexOption.IGNORE_CASE,
    )

    /** Umrechnung oder `null`, wenn die Eingabe keine bekannte Umrechnung ist. */
    fun convert(input: String): Conversion? {
        val text = input.trim()
        if (text.length > 60) return null
        val match = PATTERN.matchEntire(text) ?: return null
        val value = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val from = units[normalize(match.groupValues[2])] ?: return null
        val to = units[normalize(match.groupValues[3])] ?: return null
        if (from.category != to.category) return null
        val result = if (from.category == Category.TEMPERATURE) {
            fromCelsius(toCelsius(value, from.label), to.label)
        } else {
            value * from.factor / to.factor
        }
        return Conversion(value, from.label, result, to.label)
    }

    private fun normalize(unit: String): String = unit.lowercase().trimEnd('.').replace(".", "")

    private fun toCelsius(value: Double, label: String): Double = when (label) {
        "°F" -> (value - 32.0) * 5.0 / 9.0
        "K" -> value - 273.15
        else -> value
    }

    private fun fromCelsius(celsius: Double, label: String): Double = when (label) {
        "°F" -> celsius * 9.0 / 5.0 + 32.0
        "K" -> celsius + 273.15
        else -> celsius
    }
}
