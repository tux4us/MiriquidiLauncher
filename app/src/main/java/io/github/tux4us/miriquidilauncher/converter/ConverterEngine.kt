// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.converter

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Tastaturart der Eingabefelder einer Kategorie. */
enum class FieldInput { Decimal, Text }

/**
 * Ein Eingabefeld einer Kategorie. [id] ist stabil und ASCII; [symbol] steht im Feld. [nameDe] und [nameEn] sind
 * Langnamen, soweit das Kürzel allein nicht reicht.
 */
class ConverterUnit(val id: String, val symbol: String, val nameDe: String? = null, val nameEn: String? = nameDe) {
    /** Beschriftung des Feldes: „Name (Kürzel)“ oder nur das Kürzel. */
    fun label(german: Boolean): String {
        val name = if (german) nameDe else nameEn
        return when {
            name == null || name == symbol -> symbol
            symbol.isBlank() -> name
            else -> "$name ($symbol)"
        }
    }
}

/**
 * Eine Kategorie des Converters mit ihren Feldern. Gibt der Nutzer in einem Feld einen Wert ein, liefert [convert] die
 * Texte aller anderen Felder. Reine Logik ohne Android-Bezug.
 */
sealed class ConverterCategory(val id: String, val units: List<ConverterUnit>) {
    open val input: FieldInput get() = FieldInput.Decimal

    /** `true`, wenn negative Werte sinnvoll sind (Temperatur, Winkel); nur dann bietet die Oberfläche das Vorzeichen an. */
    open val allowNegative: Boolean get() = false

    /** Bereinigt den Text im Feld [unitId] (unzulässige Zeichen entfallen, Länge begrenzt). */
    abstract fun sanitize(unitId: String, text: String): String

    /**
     * Texte aller Felder außer [sourceId] zur Eingabe [text] in [sourceId]. Ist die Eingabe leer oder (noch) nicht
     * auswertbar, sind alle Texte leer.
     */
    abstract fun convert(sourceId: String, text: String, decimalSeparator: Char): Map<String, String>

    protected fun blank(sourceId: String): Map<String, String> = units.filter { it.id != sourceId }.associate { it.id to "" }
}

/** Lesen, Bereinigen und Darstellen von Dezimalzahlen. */
object ConverterNumbers {
    const val MAX_INPUT_LENGTH = 40
    private val NUMBER = Regex("[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?")

    /** Zahl aus [text]; Komma und Punkt gelten als Dezimaltrenner. `null` bei leerer, unvollständiger oder nicht endlicher Eingabe. */
    fun parse(text: String): Double? {
        val t = text.trim().replace(',', '.')
        if (!NUMBER.matches(t)) return null
        val v = t.toDoubleOrNull() ?: return null
        return if (v.isFinite()) v else null
    }

    /**
     * Erlaubt Ziffern, einen Dezimaltrenner, einen Exponenten („E“) und das Vorzeichen: ein Minus am Anfang nur bei
     * [allowNegative], im Exponenten immer. Höchstens [MAX_INPUT_LENGTH] Zeichen.
     */
    fun sanitize(text: String, allowNegative: Boolean): String {
        val out = StringBuilder()
        var separator = false
        var exponent = false
        for (c in text) {
            when {
                c in '0'..'9' -> out.append(c)
                (c == '.' || c == ',') && !separator && !exponent -> {
                    separator = true
                    out.append(c)
                }
                (c == 'e' || c == 'E') && !exponent && out.any { it in '0'..'9' } -> {
                    exponent = true
                    out.append('E')
                }
                c == '-' && out.isEmpty() && allowNegative -> out.append(c)
                (c == '-' || c == '+') && exponent && out.last() == 'E' -> out.append(c)
            }
            if (out.length >= MAX_INPUT_LENGTH) break
        }
        return out.toString()
    }

    /** Schaltet das Vorzeichen am Anfang von [text] um. Ein leerer Text wird zu „-“. */
    fun toggleMinus(text: String): String = if (text.startsWith("-")) text.removePrefix("-") else "-$text"

    /**
     * Darstellung von [value] mit höchstens [significant] gültigen Stellen. Sehr große und sehr kleine Beträge erscheinen in
     * Exponentialschreibweise („1,5E-9“), die übrigen ohne Exponent. Nicht endliche Werte ergeben einen leeren Text.
     */
    fun format(value: Double, decimalSeparator: Char, significant: Int = 12): String {
        if (!value.isFinite()) return ""
        if (value == 0.0) return "0"
        val rounded = BigDecimal(value).round(MathContext(significant, RoundingMode.HALF_EVEN)).stripTrailingZeros()
        val magnitude = abs(value)
        val text = if (magnitude >= 1e15 || magnitude < 1e-6) scientific(rounded) else rounded.toPlainString()
        return if (decimalSeparator == '.') text else text.replace('.', decimalSeparator)
    }

    private fun scientific(value: BigDecimal): String {
        val digits = value.unscaledValue().abs().toString()
        val exponent = digits.length - 1 - value.scale()
        val mantissa = digits[0] + if (digits.length > 1) "." + digits.substring(1) else ""
        return (if (value.signum() < 0) "-" else "") + mantissa + "E" + exponent
    }
}

/** Ein Feld mit Umrechnung über eine Basiseinheit. [step] rundet den angezeigten Wert auf Vielfache (Größentabellen). */
class NumericUnit(
    val unit: ConverterUnit,
    val toBase: (Double) -> Double,
    val fromBase: (Double) -> Double,
    val step: Double? = null,
)

/** Kategorie, in der sich alle Felder über Zahlenwerte und eine Basiseinheit ineinander umrechnen lassen. */
class NumericCategory(id: String, private val defs: List<NumericUnit>, override val allowNegative: Boolean = false) :
    ConverterCategory(id, defs.map { it.unit }) {

    override fun sanitize(unitId: String, text: String): String = ConverterNumbers.sanitize(text, allowNegative)

    override fun convert(sourceId: String, text: String, decimalSeparator: Char): Map<String, String> {
        val source = defs.firstOrNull { it.unit.id == sourceId } ?: return blank(sourceId)
        val value = ConverterNumbers.parse(text) ?: return blank(sourceId)
        if (!allowNegative && value < 0.0) return blank(sourceId)
        val base = source.toBase(value)
        if (!base.isFinite()) return blank(sourceId)
        return defs.filter { it.unit.id != sourceId }.associate { def ->
            var v = def.fromBase(base)
            def.step?.let { v = (v / it).roundToLong() * it }
            def.unit.id to ConverterNumbers.format(v, decimalSeparator)
        }
    }
}

/** Zahlensysteme: Binär, Oktal, Dezimal, Hexadezimal und römische Zahlen für ganze, nicht negative Zahlen. */
class RadixCategory(id: String) : ConverterCategory(id, FIELDS.map { it.unit }) {
    override val input: FieldInput get() = FieldInput.Text

    private class Field(val unit: ConverterUnit, val radix: Int, val maxLength: Int)

    override fun sanitize(unitId: String, text: String): String {
        val field = FIELDS.firstOrNull { it.unit.id == unitId } ?: return ""
        val upper = text.uppercase(Locale.ROOT)
        val kept = if (unitId == ROMAN) upper.filter { it in "IVXLCDM" } else upper.filter { Character.digit(it, field.radix) >= 0 }
        return kept.take(field.maxLength)
    }

    override fun convert(sourceId: String, text: String, decimalSeparator: Char): Map<String, String> {
        val field = FIELDS.firstOrNull { it.unit.id == sourceId } ?: return blank(sourceId)
        val number = (if (sourceId == ROMAN) fromRoman(text) else text.takeIf { it.isNotEmpty() }?.let { BigInteger(it, field.radix) })
            ?: return blank(sourceId)
        return FIELDS.filter { it.unit.id != sourceId }.associate { other ->
            other.unit.id to if (other.unit.id == ROMAN) toRoman(number).orEmpty() else number.toString(other.radix).uppercase(Locale.ROOT)
        }
    }

    companion object {
        const val ROMAN = "roman"
        private val FIELDS = listOf(
            Field(ConverterUnit("bin", "2", "Binär", "Binary"), 2, 160),
            Field(ConverterUnit("oct", "8", "Oktal", "Octal"), 8, 54),
            Field(ConverterUnit("dec", "10", "Dezimal", "Decimal"), 10, 48),
            Field(ConverterUnit("hex", "16", "Hexadezimal", "Hexadecimal"), 16, 40),
            Field(ConverterUnit(ROMAN, "", "Römisch", "Roman"), 0, 20),
        )

        private val ROMAN_STEPS = listOf(
            1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC",
            50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I",
        )

        /** Römische Schreibweise für 1 bis 3999; sonst `null`. */
        fun toRoman(number: BigInteger): String? {
            if (number < BigInteger.ONE || number > BigInteger.valueOf(3999)) return null
            var rest = number.toInt()
            val sb = StringBuilder()
            for ((value, symbol) in ROMAN_STEPS) {
                while (rest >= value) {
                    sb.append(symbol)
                    rest -= value
                }
            }
            return sb.toString()
        }

        private val ROMAN_VALUES = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)

        /** Wert einer römischen Zahl; `null`, wenn die Schreibweise nicht der Normalform entspricht (z. B. „IIII“, „IC“). */
        fun fromRoman(text: String): BigInteger? {
            if (text.isEmpty()) return null
            var total = 0
            for (i in text.indices) {
                val value = ROMAN_VALUES[text[i]] ?: return null
                val next = text.getOrNull(i + 1)?.let { ROMAN_VALUES[it] }
                total += if (next != null && value < next) -value else value
            }
            val number = BigInteger.valueOf(total.toLong())
            return if (toRoman(number) == text) number else null
        }
    }
}

/**
 * Kategorie mit einer Tabelle zusammengehöriger Werte (Kleidergrößen). Jede Zeile enthält je Feld einen Text; die Eingabe
 * muss einem Eintrag einer Spalte entsprechen. Bei mehrfach vorkommenden Werten gilt die erste Zeile.
 */
class TableCategory(id: String, columns: List<ConverterUnit>, private val rows: List<List<String>>) :
    ConverterCategory(id, columns) {
    override val input: FieldInput get() = FieldInput.Text

    init {
        require(rows.all { it.size == columns.size }) { "Jede Zeile braucht einen Wert je Spalte" }
    }

    override fun sanitize(unitId: String, text: String): String =
        text.uppercase(Locale.ROOT).filter { it.isLetterOrDigit() || it == '/' || it == '.' || it == ',' || it == '-' }.take(8)

    override fun convert(sourceId: String, text: String, decimalSeparator: Char): Map<String, String> {
        val column = units.indexOfFirst { it.id == sourceId }
        if (column < 0) return blank(sourceId)
        val key = normalize(text)
        if (key.isEmpty()) return blank(sourceId)
        val row = rows.firstOrNull { normalize(it[column]) == key } ?: return blank(sourceId)
        return units.withIndex().filter { it.index != column }.associate { (i, unit) -> unit.id to row[i] }
    }

    private fun normalize(text: String): String = text.trim().uppercase(Locale.ROOT).replace(',', '.').replace(" ", "")
}

/**
 * Texte aller Felder einer Kategorie, wenn im Feld [sourceId] der Text [text] steht. Das Quellfeld behält seinen Text
 * unverändert, die übrigen Felder werden berechnet. Ohne Quelle sind alle Felder leer.
 */
fun ConverterCategory.display(sourceId: String?, text: String, decimalSeparator: Char): Map<String, String> {
    if (sourceId == null || units.none { it.id == sourceId }) return units.associate { it.id to "" }
    return convert(sourceId, text, decimalSeparator) + (sourceId to text)
}
